package com.crowja.damiupos;

import com.crowja.damiupos.model.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * STRATEGI PENGIRIMAN (sisi HP) — memecah antrean perangkat ini menjadi RIT sesuai "Muatan Max".
 *
 * Alur yang dimodelkan: muat galon sebanyak MUATAN MAX → antar ke beberapa pelanggan → balik ke
 * cabang membawa galon kosong → muat lagi, sampai antrean habis. Satu rit = satu putaran.
 *
 * CERMIN PERSIS {@code App\Support\DeliveryPlan} di web — ubah keduanya bersamaan:
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

    private static final java.text.SimpleDateFormat TS =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);

    /** Lama order menunggu di antrean, dalam JAM. 0 bila stempel tak ada/tak terbaca. */
    public static double waitedHours(Transaction t) {
        String q = t == null ? null : t.getDeliveryQueuedAt();
        if (q == null || q.length() < 19) return 0;
        try {
            java.util.Date d = TS.parse(q.substring(0, 19));
            if (d == null) return 0;
            long ms = System.currentTimeMillis() - d.getTime();
            return ms <= 0 ? 0 : ms / 3600000.0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Potongan jarak (km) yang didapat order karena sudah lama menunggu. */
    public static double ageBonusKm(Transaction t) {
        return Math.min(AGE_BONUS_MAX_KM, waitedHours(t) * AGE_BONUS_KM_PER_HOUR);
    }

    /**
     * Jarak EFEKTIF untuk pengurutan: jarak asli dikurangi potongan umur. Order tanpa koordinat
     * ({@code Double.MAX_VALUE}) dibiarkan apa adanya supaya tetap jatuh ke dasar grupnya.
     */
    public static double costKm(double rawKm, Transaction t) {
        if (rawKm == Double.MAX_VALUE) return rawKm;
        return rawKm - ageBonusKm(t);
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
        List<Trip> trips = new ArrayList<>();
        if (queue == null || queue.isEmpty()) return trips;

        List<Transaction> ordered = sortStops(queue, depotLat, depotLng, mode);

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

        return trips;
    }

    /** AMBIL GALON SAJA paling belakang; lalu prioritas; di dalam grup: manual, lalu mode. */
    private static List<Transaction> sortStops(List<Transaction> queue, double dLat, double dLng, int mode) {
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
        List<Transaction> prioOrdered = orderGroup(prio, dLat, dLng, mode);
        double[] seed = lastGeo(prioOrdered, dLat, dLng);
        List<Transaction> restOrdered = orderGroup(rest, seed[0], seed[1], mode);
        seed = lastGeo(restOrdered, seed[0], seed[1]);

        List<Transaction> out = new ArrayList<>(queue.size());
        out.addAll(prioOrdered);
        out.addAll(restOrdered);
        out.addAll(orderGroup(pickups, seed[0], seed[1], mode));
        return out;
    }

    /** Koordinat pemberhentian terakhir yang punya lokasi; else titik sebelumnya (fallback). */
    private static double[] lastGeo(List<Transaction> stops, double fbLat, double fbLng) {
        for (int i = stops.size() - 1; i >= 0; i--) {
            if (hasGeo(stops.get(i))) return new double[]{lat(stops.get(i)), lng(stops.get(i))};
        }
        return new double[]{fbLat, fbLng};
    }

    private static List<Transaction> orderGroup(List<Transaction> group, double dLat, double dLng, int mode) {
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
            out.addAll(nearestChain(auto, dLat, dLng));
        }
        return out;
    }

    /** Rantai tetangga-terdekat dari cabang — "terdekat" diukur dengan {@link #costKm} (jarak
     *  dikurangi potongan umur), jadi order yang sudah lama menunggu ikut ditarik maju; pemberhentian
     *  tanpa koordinat ditaruh paling belakang. */
    private static List<Transaction> nearestChain(List<Transaction> stops, double dLat, double dLng) {
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
                double d = costKm(km(curLat, curLng, lat(geo.get(i)), lng(geo.get(i))), geo.get(i));
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

    public static boolean isPriority(Transaction t) {
        return t.isOrderPriority() || t.isCustomerPriority();
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
