package com.crowja.damiupos;

import com.crowja.damiupos.model.Transaction;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * STRATEGI PENGIRIMAN (sisi HP) — memecah antrean perangkat ini menjadi RIT sesuai "Muatan Max".
 *
 * Alur yang dimodelkan: muat galon sebanyak MUATAN MAX → antar ke beberapa pelanggan → balik ke
 * cabang membawa galon kosong → muat lagi, sampai antrean habis. Satu rit = satu putaran.
 *
 * CERMIN PERSIS {@code App\Support\DeliveryPlan} di web — ubah keduanya bersamaan:
 *   0. ⏰ Batas Antar aktif hari ini PALING DEPAN, jam berangkat paling lambat terdekat dulu.
 *   1. Prioritas (⚡ pengiriman / ⭐ pelanggan) selalu di rit paling awal.
 *   2. Urutan MANUAL (delivery_seq, disusun operator di web) dipatuhi & di depan.
 *   3. Sisanya ikut mode: terdekat (default) atau order pertama.
 *   4. Order KEMBALI (ambil galon kosong) TIDAK memakan muatan berangkat.
 *   5. Order yang sendirian melebihi muatan tetap dijadwalkan (ditandai overflow), tak pernah hilang.
 *
 * Dihitung LOKAL dari antrean di perangkat → tetap bekerja tanpa sinyal.
 */
public final class DeliveryPlanner {

    private DeliveryPlanner() {}

    public static final int MODE_NEAREST = 0;
    public static final int MODE_FIRST = 1;

    /**
     * KOMPENSASI UMUR PESANAN — "potongan jarak" untuk tiap JAM sebuah order menunggu di antrean.
     *
     * Kenapa berbentuk potongan jarak, bukan kunci urutan tersendiri: kalau umur dijadikan kunci
     * KETIGA (prioritas → jarak → umur), ia takkan pernah menentukan apa pun, sebab jarak bernilai
     * pecahan dan nyaris tak pernah seri — perbandingan selalu sudah selesai di kunci jarak.
     * Padahal justru inilah masalahnya di lapangan: makin banyak rit yang selesai, makin tua order
     * yang tersisa, tapi pelanggan JAUH terus kalah oleh pelanggan dekat yang baru masuk
     * (starvation). Dengan potongan, order yang sudah lama menunggu perlahan "terasa lebih dekat"
     * sampai akhirnya menang — dan karena rit dihitung ULANG tiap kali daftar berubah, order yang
     * menua selama rit berjalan otomatis naik ke rit berikutnya.
     *
     * Dibatasi {@link #AGE_BONUS_MAX_KM} agar (a) order sangat tua tak menyeret kurir ke ujung kota
     * dalam sekali lompat, dan (b) stempel waktu yang datang dari server dengan zona waktu berbeda
     * tak bisa mengacaukan urutan tanpa batas.
     */
    public static final double AGE_BONUS_KM_PER_HOUR = 1.5;
    public static final double AGE_BONUS_MAX_KM = 8.0;

    /**
     * ⏰ BATAS ANTAR berbasis sisa waktu — konstanta IDENTIK dengan {@code App\Support\DeliveryPlan} di
     * web (owner 2026-10-29, kasus "Ayam Geprek Sawahan"). Server menghitung delivery_depart_by
     * (= batas − perjalanan cabang→tujuan − serah terima − cadangan); HP memakainya untuk urutan,
     * badge, dan perkiraan jam berangkat tiap rit.
     */
    public static final double DL_SPEED_KMH = 20.0;        // kecepatan kurir (= OpsAssistant::DEFAULT_SPEED_KMH)
    public static final double DL_ROAD_FACTOR = 1.3;       // garis lurus (haversine) → jarak jalan
    public static final int DL_SERVICE_MIN = 5;            // serah terima per pemberhentian
    public static final int DL_LOAD_MIN = 10;              // muat di cabang sebelum rit berangkat
    public static final int DL_BUFFER_MIN = 15;            // cadangan aman
    public static final int DL_DEFAULT_TRAVEL_MIN = 15;    // perjalanan bila cabang/tujuan tanpa koordinat
    public static final int DL_MIN_TRAVEL_MIN = 3;         // batas bawah satu kaki cabang→pelanggan

    /** Status risiko satu order ber-batas antar di sebuah rit — lihat {@link #deadlineRisks}. */
    public static final String RISK_OK = "ok";
    public static final String RISK_LAST_SAFE = "last_safe";
    public static final String RISK_LATE = "late";

    /** Lama order menunggu di antrean, dalam JAM. 0 bila stempel tak ada/tak terbaca. */
    public static double waitedHours(Transaction t) {
        return waitedHours(t, System.currentTimeMillis());
    }

    static double waitedHours(Transaction t, long nowMs) {
        String q = t == null ? null : t.getDeliveryQueuedAt();
        // Ts.millis, bukan TS.parse: delivery_queued_at berisi waktu LOKAL bila HP yang menulisnya
        // tapi ISO-UTC bila barisnya ditarik dari server. Mengurainya tanpa zona waktu membuat order
        // asal-web tampak 7 jam lebih tua dan mendapat potongan umur yang tak pernah ia usahakan.
        long ts = com.crowja.damiupos.util.Ts.millis(q);
        if (ts == Long.MAX_VALUE) return 0;
        long ms = nowMs - ts;
        return ms <= 0 ? 0 : ms / 3600000.0;
    }

    /** Potongan jarak (km) yang didapat order karena sudah lama menunggu. */
    public static double ageBonusKm(Transaction t) {
        return ageBonusKm(t, System.currentTimeMillis());
    }

    static double ageBonusKm(Transaction t, long nowMs) {
        return Math.min(AGE_BONUS_MAX_KM, waitedHours(t, nowMs) * AGE_BONUS_KM_PER_HOUR);
    }

    /**
     * Jarak EFEKTIF untuk pengurutan: jarak asli dikurangi potongan umur. Order tanpa koordinat
     * ({@code Double.MAX_VALUE}) dibiarkan apa adanya supaya tetap jatuh ke dasar grupnya.
     */
    public static double costKm(double rawKm, Transaction t) {
        return costKm(rawKm, t, System.currentTimeMillis());
    }

    static double costKm(double rawKm, Transaction t, long nowMs) {
        if (rawKm == Double.MAX_VALUE) return rawKm;
        return rawKm - ageBonusKm(t, nowMs);
    }

    // ---- ⏰ Batas Antar (cermin DeliveryPlan di web) ----

    /** Menit perjalanan satu kaki cabang→pelanggan dari jarak garis lurus (km): dikali faktor jalan,
     *  dibulatkan ke atas, minimal {@link #DL_MIN_TRAVEL_MIN}. Cermin DeliveryPlan::travelMin. */
    public static int travelMin(double km) {
        return Math.max(DL_MIN_TRAVEL_MIN, (int) Math.ceil(km * DL_ROAD_FACTOR / DL_SPEED_KMH * 60.0));
    }

    /**
     * Order ini ber-BATAS ANTAR AKTIF? Semua syarat (identik dengan web & ORDER BY TransactionDao):
     * masih menunggu & bukan AMBIL GALON (KEMBALI tetap "selalu terakhir"); delivery_due_at terisi dan
     * TANGGAL LOKAL-nya = hari ini; level ⚡ > 0 (staf ⚡ Batal / turun ke 0 = keluar, sama aturan TOP).
     */
    public static boolean deadlineActive(Transaction t, long nowMs) {
        if (t == null || isPickupOnly(t) || t.getPriorityLevel() <= 0) return false;
        String st = t.getDeliveryStatus();
        if (st != null && !st.isEmpty() && !Transaction.DELIVERY_PENDING.equals(st)) return false;
        long due = dueMillis(t);
        if (due == Long.MAX_VALUE) return false;
        // Hari via Calendar LOKAL: batas yang dikirim server sebagai ISO-UTC "…Z" (mis. 2026-10-29T17:30Z
        // = 30 Okt 00:30 WIB) harus dinilai menurut tanggal di HP, bukan tanggal teksnya.
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(due);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(nowMs);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** Epoch delivery_due_at; {@link Long#MAX_VALUE} bila kosong/tak terurai. */
    public static long dueMillis(Transaction t) {
        return t == null ? Long.MAX_VALUE : com.crowja.damiupos.util.Ts.millis(t.getDeliveryDueAt());
    }

    /**
     * Peringkat urgensi = epoch jam BERANGKAT paling lambat (delivery_depart_by); belum dihitung server →
     * batas − ({@link #DL_DEFAULT_TRAVEL_MIN} + {@link #DL_SERVICE_MIN} + {@link #DL_BUFFER_MIN}) menit.
     * Kecil = paling mendesak; tanpa batas = {@link Long#MAX_VALUE}.
     */
    public static long deadlineRankMillis(Transaction t) {
        if (t == null) return Long.MAX_VALUE;
        long by = com.crowja.damiupos.util.Ts.millis(t.getDeliveryDepartBy());
        if (by != Long.MAX_VALUE) return by;
        long due = dueMillis(t);
        if (due == Long.MAX_VALUE) return Long.MAX_VALUE;
        return due - (DL_DEFAULT_TRAVEL_MIN + DL_SERVICE_MIN + DL_BUFFER_MIN) * 60000L;
    }

    /**
     * Lama satu rit dalam MENIT (pecahan, TIDAK dibulatkan): kaki cabang→p1→…→pn→cabang (haversine × faktor
     * jalan ÷ kecepatan, tanpa batas bawah per kaki) + serah terima × jumlah pemberhentian. Pemberhentian
     * tanpa koordinat — atau cabang tak diketahui (0,0) — dihitung {@link #DL_DEFAULT_TRAVEL_MIN} dan
     * dilewati dari rantai. Cermin PERSIS DeliveryPlan::tripMinutes (float, menit per kaki dijumlah
     * berurutan): membulatkan di sini membuat jam berangkat rit di HP bergeser ≤1 menit per rit dari web,
     * dan status aman/terakhir/terlambat jadi berlawanan di dekat batas. Bulatkan hanya saat ditampilkan.
     */
    public static double tripMinutes(List<Transaction> stops, double depotLat, double depotLng) {
        if (stops == null || stops.isEmpty()) return 0;
        boolean depot = depotLat != 0 || depotLng != 0;
        double min = 0;
        double curLat = depotLat, curLng = depotLng;
        boolean moved = false;
        for (Transaction s : stops) {
            if (s == null) continue;
            if (!depot || !hasGeo(s)) {
                min += DL_DEFAULT_TRAVEL_MIN;
            } else {
                min += legMin(curLat, curLng, lat(s), lng(s));
                curLat = lat(s);
                curLng = lng(s);
                moved = true;
            }
            min += DL_SERVICE_MIN;
        }
        if (moved) min += legMin(curLat, curLng, depotLat, depotLng);
        return min;
    }

    /** Menit satu kaki garis lurus × faktor jalan ÷ kecepatan (tanpa batas bawah) — DeliveryPlan::legMin. */
    private static double legMin(double lat1, double lng1, double lat2, double lng2) {
        return km(lat1, lng1, lat2, lng2) * DL_ROAD_FACTOR / DL_SPEED_KMH * 60;
    }

    /**
     * Perkiraan rit BERIKUTNYA bisa berangkat bila rit {@code stops} berangkat {@code departMs}: + lama rit +
     * muat ({@link #DL_LOAD_MIN}), dibulatkan ke DETIK — persis {@code DeliveryPlan::deadlineRisks}
     * ({@code (int) round((tripMinutes + DL_LOAD_MIN) * 60)}).
     */
    public static long nextDepartMillis(long departMs, List<Transaction> stops, double depotLat, double depotLng) {
        return departMs + Math.round((tripMinutes(stops, depotLat, depotLng) + DL_LOAD_MIN) * 60.0) * 1000L;
    }

    /** Risiko satu order ber-batas antar di rencana rit — hasil {@link #deadlineRisks}. */
    public static class DeadlineRisk {
        public final Transaction order;
        public final int trip;            // nomor rit (Trip.n)
        public final long departMs;       // perkiraan rit itu berangkat dari cabang
        public final long departByMs;     // jam berangkat paling lambat order ini
        public final long dueMs;          // batas antarnya
        public final int level;           // level ⚡
        public final String status;       // RISK_OK / RISK_LAST_SAFE / RISK_LATE

        DeadlineRisk(Transaction order, int trip, long departMs, long departByMs, long dueMs, int level, String status) {
            this.order = order;
            this.trip = trip;
            this.departMs = departMs;
            this.departByMs = departByMs;
            this.dueMs = dueMs;
            this.level = level;
            this.status = status;
        }
    }

    /**
     * Perkiraan jam berangkat tiap rit & risikonya untuk order ber-batas antar aktif. Rit 1 berangkat
     * {@code firstDepartMs}; rit k+1 = {@link #nextDepartMillis} rit k (rit k+1 hipotetis tetap dihitung
     * untuk rit terakhir). Per order aktif di rit k: {@link #RISK_LATE} bila rit k berangkat setelah jam
     * berangkat paling lambatnya; else {@link #RISK_LAST_SAFE} bila rit k+1 sudah terlambat; else
     * {@link #RISK_OK}. Cermin DeliveryPlan::deadlineRisks.
     */
    public static List<DeadlineRisk> deadlineRisks(List<Trip> trips, double depotLat, double depotLng,
                                                   long firstDepartMs, long nowMs) {
        List<DeadlineRisk> out = new ArrayList<>();
        if (trips == null) return out;
        long depart = firstDepartMs;
        for (Trip trip : trips) {
            long next = nextDepartMillis(depart, trip.stops, depotLat, depotLng);
            for (Transaction s : trip.stops) {
                if (!deadlineActive(s, nowMs)) continue;
                long by = deadlineRankMillis(s);
                String st = depart > by ? RISK_LATE : (next > by ? RISK_LAST_SAFE : RISK_OK);
                out.add(new DeadlineRisk(s, trip.n, depart, by, dueMillis(s), s.getPriorityLevel(), st));
            }
            depart = next;
        }
        return out;
    }

    /** Satu rit: daftar pemberhentian + total galon yang dimuat. */
    public static class Trip {
        public final int n;
        public final List<Transaction> stops = new ArrayList<>();
        public int galon;
        public boolean overflow;

        Trip(int n) { this.n = n; }
    }

    /**
     * Susun rit dari antrean (sudah difilter untuk perangkat ini).
     *
     * @param maxLoad muatan max galon; <= 0 → seluruh antrean jadi satu rit
     * @param depotLat/depotLng titik cabang untuk mode terdekat (0,0 = tak diketahui)
     */
    public static List<Trip> plan(List<Transaction> queue, int maxLoad,
                                  double depotLat, double depotLng, int mode) {
        return plan(queue, maxLoad, depotLat, depotLng, mode, System.currentTimeMillis());
    }

    /** {@link #plan(List, int, double, double, int)} dengan "sekarang" eksplisit (hari batas antar &
     *  potongan umur) — supaya hasilnya deterministik di tes. */
    public static List<Trip> plan(List<Transaction> queue, int maxLoad,
                                  double depotLat, double depotLng, int mode, long nowMs) {
        List<Trip> trips = new ArrayList<>();
        if (queue == null || queue.isEmpty()) return trips;

        List<Transaction> ordered = sortStops(queue, depotLat, depotLng, mode, nowMs);

        Trip cur = new Trip(1);
        for (Transaction t : ordered) {
            int load = isPickupOnly(t) ? 0 : Math.max(0, t.getJumlahGalon());
            boolean overflow = maxLoad > 0 && load > maxLoad;

            // `cur.galon > 0` (BUKAN `!overflow`): order yang sendirian melebihi muatan tetap
            // HARUS menutup rit berjalan dulu, kalau tidak ia MENELAN muatan yang sudah ada.
            if (maxLoad > 0 && !cur.stops.isEmpty() && cur.galon > 0 && cur.galon + load > maxLoad) {
                trips.add(cur);                       // muat penuh → balik ke cabang, muat lagi
                cur = new Trip(trips.size() + 1);
            }
            cur.stops.add(t);
            cur.galon += load;
            if (overflow) {
                cur.overflow = true;
                trips.add(cur);
                cur = new Trip(trips.size() + 1);
            }
        }
        if (!cur.stops.isEmpty()) trips.add(cur);
        if (mode == MODE_NEAREST) nearestFirstWithinTrips(trips, depotLat, depotLng);

        return trips;
    }

    /**
     * TERDEKAT DULU, TERJAUH TERAKHIR di dalam tiap rit. Potongan umur ({@link #costKm}) hanya
     * menentukan order MANA yang naik ke rit lebih awal; begitu muatan sudah di kendaraan, urutan
     * antar memakai jarak MURNI dari cabang — kalau tidak, order tua yang jauh jadi pemberhentian
     * pertama dan muatan penuh "dibawa jalan-jalan". Kelompok tetap (⚡/⭐ dulu, AMBIL GALON terakhir)
     * dan urutan manual (delivery_seq) tetap dipatuhi. CERMIN DeliveryPlan::nearestFirstWithinTrips.
     */
    private static void nearestFirstWithinTrips(List<Trip> trips, double dLat, double dLng) {
        for (Trip trip : trips) {
            List<Transaction> s = trip.stops;
            List<Transaction> out = new ArrayList<>(s.size());
            double[] cur = {dLat, dLng};
            int i = 0;
            while (i < s.size()) {
                int j = i;
                while (j + 1 < s.size() && runKey(s.get(j + 1)) == runKey(s.get(i))) j++;
                if (runKey(s.get(i)) == 0) {
                    // Kelompok prioritas TIDAK diurutkan ulang menurut jarak: urutannya = urutan
                    // antrean web (⏰ batas antar → ⭐ → level ×N → FIFO), sudah disusun sortStops.
                    out.addAll(s.subList(i, j + 1));
                    cur = lastGeo(out, cur[0], cur[1]);
                    i = j + 1;
                    continue;
                }
                List<Transaction> manual = new ArrayList<>();
                List<Transaction> auto = new ArrayList<>();
                for (Transaction t : s.subList(i, j + 1)) (t.getDeliverySeq() > 0 ? manual : auto).add(t);
                out.addAll(manual);
                cur = lastGeo(out, cur[0], cur[1]);
                out.addAll(nearestChain(auto, cur[0], cur[1], false, 0L));   // tanpa potongan umur → "sekarang" tak dipakai
                cur = lastGeo(out, cur[0], cur[1]);
                i = j + 1;
            }
            s.clear();
            s.addAll(out);
        }
    }

    /** Kelompok pemberhentian: 0 = ⏰/⚡/⭐ prioritas, 1 = biasa, 2 = AMBIL GALON SAJA. */
    static int runKey(Transaction t) {
        return isPickupOnly(t) ? 2 : (isPriority(t) ? 0 : 1);
    }

    /** AMBIL GALON SAJA paling belakang; lalu prioritas; di dalam grup: manual, lalu mode. */
    private static List<Transaction> sortStops(List<Transaction> queue, double dLat, double dLng, int mode, long nowMs) {
        // KEMBALI (jemput galon kosong) SELALU di belakang — mengalahkan prioritas & jarak,
        // persis aturan antrean: jangan menyerobot pelanggan yang menunggu air.
        List<Transaction> deliver = new ArrayList<>();
        List<Transaction> pickups = new ArrayList<>();
        for (Transaction t : queue) (isPickupOnly(t) ? pickups : deliver).add(t);

        List<Transaction> prio = new ArrayList<>();
        List<Transaction> rest = new ArrayList<>();
        for (Transaction t : deliver) (isPriority(t) ? prio : rest).add(t);

        // Rantai dilanjutkan dari pemberhentian TERAKHIR grup sebelumnya (kurir sudah di sana),
        // bukan diulang dari cabang — kalau diulang, rutenya zig-zag.
        // Kelompok prioritas = urutan antrean WEB apa adanya (⏰ batas antar aktif dulu, lalu ⭐, lalu
        // level ⚡ ×N terbesar, lalu FIFO) — bukan jarak, bukan urutan manual/Refresh RIT. Owner 2026-10-15: kurir harus
        // mengantar "PRIORITAS ×7" sebelum "×1", persis urutan yang terlihat di web.
        List<Transaction> prioOrdered = webPriorityOrder(prio, nowMs);
        double[] seed = lastGeo(prioOrdered, dLat, dLng);
        List<Transaction> restOrdered = orderGroup(rest, seed[0], seed[1], mode, nowMs);
        seed = lastGeo(restOrdered, seed[0], seed[1]);

        List<Transaction> out = new ArrayList<>(queue.size());
        out.addAll(prioOrdered);
        out.addAll(restOrdered);
        out.addAll(orderGroup(pickups, seed[0], seed[1], mode, nowMs));
        return out;
    }

    /**
     * ⏰ batas antar aktif (jam berangkat paling lambat terdekat, lalu batasnya) › ⭐ pelanggan prioritas ›
     * level ⚡ terbesar (cermin Reports::deliveryList) › di dalam satu tingkat: urutan rute (delivery_seq
     * dari "Urutkan Rute Terpendek"/navigasi) › FIFO. Owner 2026-10-18: titik perjalanan wajib rute
     * terpendek; antar level tetap mutlak. Owner 2026-10-29: batas antar mengalahkan ⭐ & level — order
     * ⚡×1 ber-batas 10:00 tak boleh lagi terkubur di bawah ⚡×3 yang sekadar lama. Peringkat batas antar
     * ditaruh SEBELUM delivery_seq supaya urutan Refresh RIT yang basi tak mengalahkan urgensi.
     * CERMIN DeliveryPlan::webPriorityOrder.
     */
    static List<Transaction> webPriorityOrder(List<Transaction> group, long nowMs) {
        List<Transaction> out = new ArrayList<>(group);
        // Peringkat dihitung SEKALI per order (mengurai stempel di dalam comparator = O(n log n) parse).
        final Map<Transaction, long[]> dl = new IdentityHashMap<>();
        for (Transaction t : out) {
            dl.put(t, deadlineActive(t, nowMs) ? new long[]{deadlineRankMillis(t), dueMillis(t)} : null);
        }
        Collections.sort(out, (a, b) -> {
            long[] da = dl.get(a), db = dl.get(b);
            if ((da != null) != (db != null)) return da != null ? -1 : 1;
            if (da != null) {
                if (da[0] != db[0]) return Long.compare(da[0], db[0]);
                if (da[1] != db[1]) return Long.compare(da[1], db[1]);
            }
            if (a.isCustomerPriority() != b.isCustomerPriority()) return a.isCustomerPriority() ? -1 : 1;
            if (a.getPriorityLevel() != b.getPriorityLevel()) return Integer.compare(b.getPriorityLevel(), a.getPriorityLevel());
            // (bendera ⚡ tanpa level TIDAK jadi kunci: web & tingkat rute server hanya memakai ⭐ + level)
            boolean sa = a.getDeliverySeq() > 0, sb = b.getDeliverySeq() > 0;
            if (sa != sb) return sa ? -1 : 1;
            if (sa && a.getDeliverySeq() != b.getDeliverySeq()) return Integer.compare(a.getDeliverySeq(), b.getDeliverySeq());
            return Long.compare(queuedMillis(a), queuedMillis(b));
        });
        return out;
    }

    private static long queuedMillis(Transaction t) {
        long ms = com.crowja.damiupos.util.Ts.millis(t.getDeliveryQueuedAt());
        return ms == Long.MAX_VALUE ? Long.MIN_VALUE : ms;   // tanpa stempel = paling depan (NULL-first, cermin SQL)
    }

    /** Koordinat pemberhentian terakhir yang punya lokasi; else titik sebelumnya (fallback). */
    private static double[] lastGeo(List<Transaction> stops, double fbLat, double fbLng) {
        for (int i = stops.size() - 1; i >= 0; i--) {
            if (hasGeo(stops.get(i))) return new double[]{lat(stops.get(i)), lng(stops.get(i))};
        }
        return new double[]{fbLat, fbLng};
    }

    private static List<Transaction> orderGroup(List<Transaction> group, double dLat, double dLng, int mode, long nowMs) {
        if (group.size() <= 1) return group;

        List<Transaction> manual = new ArrayList<>();
        List<Transaction> auto = new ArrayList<>();
        for (Transaction t : group) (t.getDeliverySeq() > 0 ? manual : auto).add(t);
        Collections.sort(manual, (a, b) -> Integer.compare(a.getDeliverySeq(), b.getDeliverySeq()));

        List<Transaction> out = new ArrayList<>(group.size());
        out.addAll(manual);
        if (mode == MODE_FIRST) {
            Collections.sort(auto, (a, b) -> safe(a.getDeliveryQueuedAt()).compareTo(safe(b.getDeliveryQueuedAt())));
            out.addAll(auto);
        } else {
            out.addAll(nearestChain(auto, dLat, dLng, true, nowMs));
        }
        return out;
    }

    /** Rantai tetangga-terdekat dari cabang — "terdekat" diukur dengan {@link #costKm} (jarak
     *  dikurangi potongan umur), jadi order yang sudah lama menunggu ikut ditarik maju; pemberhentian
     *  tanpa koordinat ditaruh paling belakang. */
    private static List<Transaction> nearestChain(List<Transaction> stops, double dLat, double dLng, boolean ageBonus, long nowMs) {
        List<Transaction> geo = new ArrayList<>();
        List<Transaction> noGeo = new ArrayList<>();
        for (Transaction t : stops) (hasGeo(t) ? geo : noGeo).add(t);
        if (geo.isEmpty()) return noGeo;

        double curLat = (dLat != 0 || dLng != 0) ? dLat : lat(geo.get(0));
        double curLng = (dLat != 0 || dLng != 0) ? dLng : lng(geo.get(0));

        List<Transaction> route = new ArrayList<>(stops.size());
        while (!geo.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < geo.size(); i++) {
                double raw = km(curLat, curLng, lat(geo.get(i)), lng(geo.get(i)));
                double d = ageBonus ? costKm(raw, geo.get(i), nowMs) : raw;
                if (d < bestD) { bestD = d; best = i; }
            }
            Transaction next = geo.remove(best);
            route.add(next);
            curLat = lat(next);
            curLng = lng(next);
        }
        route.addAll(noGeo);
        return route;
    }

    // ---- helper: dipakai juga oleh kartu strategi di DeliveryQueueActivity ----

    /** ⚡/⭐ prioritas — termasuk SEMUA order ⏰ batas antar aktif (syaratnya level > 0, lihat
     *  {@link #deadlineActive}), jadi runKey 0: tak pernah diacak ulang menurut jarak di dalam rit. */
    public static boolean isPriority(Transaction t) {
        return t.isOrderPriority() || t.isCustomerPriority() || t.getPriorityLevel() > 0;
    }

    public static boolean isPickupOnly(Transaction t) {
        return Transaction.TYPE_KEMBALI.equals(t.getType());
    }

    /** Lat/Lng EFEKTIF tujuan: lokasi "Kirim ke" bila ada, else koordinat pelanggan. */
    public static double lat(Transaction t) {
        return (t.getDeliveryDestLat() != 0 || t.getDeliveryDestLng() != 0)
                ? t.getDeliveryDestLat() : t.getCustomerLat();
    }

    public static double lng(Transaction t) {
        return (t.getDeliveryDestLat() != 0 || t.getDeliveryDestLng() != 0)
                ? t.getDeliveryDestLng() : t.getCustomerLng();
    }

    public static boolean hasGeo(Transaction t) {
        return lat(t) != 0 || lng(t) != 0;
    }

    private static String safe(String s) { return s != null ? s : ""; }

    /** Haversine km — sama dengan DeliveryPlan::km di web. */
    public static double km(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
