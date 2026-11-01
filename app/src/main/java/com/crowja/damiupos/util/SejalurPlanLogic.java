package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 🛣️ Kirim Sejalur — logika MURNI (tanpa Android & org.json; diuji JUnit di src/test).
 *
 * <p>Owner 2026-11-01: kurir memilih SATU order antrean (dari daftar atau peta) sebagai titik
 * pengiriman TERJAUH, mengisi berapa galon yang dibawa (bawaan = kapasitas perangkat), lalu server
 * ({@code POST /api/delivery/sejalur/plan|start}) memilih antrean lain yang SATU JALAN dari depot ke
 * titik itu sampai muatan penuh. Rit dimulai dengan urutan koridor server: dekat → jauh, titik
 * terjauh PALING AKHIR.
 *
 * <p>Isi kelas ini:
 * <ul>
 *   <li>umur pesanan (menit, label teks, tingkat warna) — termasuk Pesanan Terjadwal (negatif);</li>
 *   <li>baris pemilih titik terjauh ({@link Target}): gabung sumber unik per uuid, jarak dari depot,
 *       cari & urutkan (📍 Terjauh / ⏱ Terlama);</li>
 *   <li>pratinjau rencana ({@link PlanRow}): muatan tercentang &amp; urutan uuid yang dikirim ke start;</li>
 *   <li>titipan rencana yang menunggu baris tersinkron ({@link Pending}, TTL {@link #PENDING_TTL_MS})
 *       dan urutan rit aktif ({@link Order}) beserta penyerapannya ({@link #absorb});</li>
 *   <li>{@link #orderStops}: urutan pemberhentian rit berjalan mengikuti rencana, titik terjauh terakhir.</li>
 * </ul>
 */
public final class SejalurPlanLogic {

    private SejalurPlanLogic() {}

    public static final String SRC_MINE = "mine";
    public static final String SRC_OTHER = "other";
    public static final String SRC_OPEN = "open";

    /** Rencana yang menunggu baris order tersinkron ke HP — lewat dari ini dianggap basi & dibuang. */
    public static final long PENDING_TTL_MS = 15L * 60 * 1000;
    /** Umur tak diketahui (tanpa age_minutes dan queued_at tak terurai). */
    public static final long AGE_UNKNOWN = Long.MIN_VALUE;
    /** Toleransi selisih jam HP–server sebelum queued_at masa depan dibaca sebagai Pesanan Terjadwal. */
    public static final long FUTURE_SLACK_MS = 2L * 60 * 1000;
    /** Batas pemberhentian satu rit Kirim Sejalur (cermin server). */
    public static final int MAX_STOPS = 25;

    // ================================================================== umur pesanan

    /**
     * Umur antrean dalam menit.
     *
     * @param serverAgeMin {@code age_minutes} dari server (&lt; 0 = tak dikirim) — dipakai lebih dulu
     *                     supaya jam HP yang meleset tak mengubah umur
     * @param queuedMs     {@code Ts.millis(queued_at)} ({@link Long#MAX_VALUE} = tak terurai)
     * @return menit (&ge; 0); NEGATIF = Pesanan Terjadwal (menit sampai jadwalnya); {@link #AGE_UNKNOWN}
     */
    public static long ageMinutes(long serverAgeMin, long queuedMs, long nowMs) {
        boolean hasQueued = queuedMs != Long.MAX_VALUE && queuedMs != Long.MIN_VALUE;
        // age_minutes > 0 = menurut jam SERVER order sudah mengantre — jam HP yang terlambat tak boleh
        // membuatnya tampak "terjadwal" (queued_at lalu terbaca di masa depan).
        if (serverAgeMin > 0L) return serverAgeMin;
        // Server mengirim age_minutes = 0 untuk jadwal masa depan — tanda terjadwal hanya ada di queued_at.
        if (hasQueued && queuedMs - nowMs > FUTURE_SLACK_MS) {
            return -Math.max(1L, (queuedMs - nowMs + 59999L) / 60000L);
        }
        if (serverAgeMin == 0L) return 0L;
        if (hasQueued) return Math.max(0L, (nowMs - queuedMs) / 60000L);
        return AGE_UNKNOWN;
    }

    /** "45 mnt" · "1 jam 5 mnt" · "2 jam" · "3 hari 4 jam" (menit &ge; 0). */
    public static String durationText(long minutes) {
        long m = Math.max(0L, minutes);
        if (m < 1L) return "< 1 mnt";
        if (m < 60L) return m + " mnt";
        if (m < 1440L) {
            long h = m / 60L, r = m % 60L;
            return h + " jam" + (r > 0L ? " " + r + " mnt" : "");
        }
        long d = m / 1440L, h = m % 1440L / 60L;
        return d + " hari" + (h > 0L ? " " + h + " jam" : "");
    }

    /** Label umur untuk baris/pratinjau: "1 jam 5 mnt", "terjadwal · 2 jam lagi", "" bila tak diketahui. */
    public static String ageText(long ageMin) {
        if (ageMin == AGE_UNKNOWN) return "";
        if (ageMin < 0L) return "terjadwal · " + durationText(-ageMin) + " lagi";
        return durationText(ageMin);
    }

    /** Baris umur di popup pin peta: "⏱ menunggu 1 jam 5 mnt" / "🗓️ Terjadwal — 2 jam lagi" / "". */
    public static String mapAgeLine(long ageMin) {
        if (ageMin == AGE_UNKNOWN) return "";
        if (ageMin < 0L) return "🗓️ Terjadwal — " + durationText(-ageMin) + " lagi";
        return "⏱ menunggu " + durationText(ageMin);
    }

    /** Tingkat warna umur — sama dengan badge antrean: 0 biasa, 1 &ge; 1 jam (kuning), 2 &ge; 2 jam (merah), -1 terjadwal/tak diketahui. */
    public static int ageLevel(long ageMin) {
        if (ageMin == AGE_UNKNOWN || ageMin < 0L) return -1;
        if (ageMin >= 120L) return 2;
        if (ageMin >= 60L) return 1;
        return 0;
    }

    // ================================================================== geometri

    public static boolean isGeo(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && (lat != 0.0 || lng != 0.0);
    }

    /** Haversine (km). */
    public static double km(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 12742.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Titik asal jarak "dari depot": depot cabang; tanpa depot → posisi HP; keduanya tak ada → null. */
    public static double[] origin(double depotLat, double depotLng, double myLat, double myLng) {
        if (isGeo(depotLat, depotLng)) return new double[]{depotLat, depotLng};
        if (isGeo(myLat, myLng)) return new double[]{myLat, myLng};
        return null;
    }

    /** "850 m" / "12,3 km" (koma desimal Indonesia); "" bila tak diketahui. */
    public static String kmText(double km) {
        if (Double.isNaN(km) || km == Double.MAX_VALUE || km < 0) return "";
        if (km < 1.0) return Math.round(km * 1000.0) + " m";
        return String.format(Locale.US, "%.1f km", km).replace('.', ',');
    }

    // ================================================================== pemilih titik terjauh

    /** Satu baris pemilih titik terjauh — order Antrian Saya, Pesanan Terbuka, atau perangkat lain. */
    public static final class Target {
        public String uuid = "";
        public String name = "";
        public String area = "";
        public String items = "";
        public int galon;
        public boolean pickupOnly;
        public boolean priority;
        public String source = SRC_MINE;
        public String deviceLabel = "";
        public double lat;
        public double lng;
        /** Umur antrean (ms); NEGATIF = Pesanan Terjadwal. */
        public long elapsedMs;
        /** Jarak garis lurus dari depot (km); {@link Double#MAX_VALUE} = tak diketahui. */
        public double distKm = Double.MAX_VALUE;
        public long localId = -1L;

        public boolean hasGeo() {
            return isGeo(lat, lng);
        }

        public boolean scheduled() {
            return elapsedMs < -FUTURE_SLACK_MS;
        }

        /** Bisa dijadikan titik terjauh: punya koordinat & bukan Pesanan Terjadwal. */
        public boolean selectable() {
            return hasGeo() && !scheduled();
        }

        /** Alasan baris tak bisa dipilih ("" = bisa). */
        public String blockReason() {
            if (!hasGeo()) return REASON_NO_GEO;
            if (scheduled()) return REASON_SCHEDULED;
            return "";
        }
    }

    public static final String REASON_NO_GEO = "tanpa lokasi — tidak bisa dipilih";
    public static final String REASON_SCHEDULED = "pesanan terjadwal — belum boleh diantar";
    public static final String REASON_CLAIM_PENDING = "permintaan Ambil Alih-nya belum tuntas";

    /**
     * Alasan pin Peta Delivery TIDAK boleh jadi titik terjauh ("" = boleh) — aturan yang sama dengan
     * pemilih daftar ({@link Target#blockReason}; antrean perangkat lain yang terkunci permintaan
     * Ambil Alih tak ikut daftar), supaya tombol "Kirim Sejalur ke sini" tak ditawarkan lalu ditolak.
     *
     * @param ageMin       {@link #ageMinutes} (negatif = Pesanan Terjadwal; {@link #AGE_UNKNOWN} boleh)
     * @param claimBlocked order perangkat lain dengan permintaan Ambil Alih yang masih mengunci
     */
    public static String mapBlockReason(long ageMin, double lat, double lng, boolean claimBlocked) {
        if (!isGeo(lat, lng)) return REASON_NO_GEO;
        if (ageMin != AGE_UNKNOWN && ageMin < 0L) return REASON_SCHEDULED;
        if (claimBlocked) return REASON_CLAIM_PENDING;
        return "";
    }

    /** Gabung sumber berurutan (Antrian Saya → Pesanan Terbuka → perangkat lain), unik per uuid; tanpa uuid dibuang. */
    @SafeVarargs
    public static List<Target> merge(List<Target>... sources) {
        List<Target> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Target> src : sources) {
            if (src == null) continue;
            for (Target t : src) {
                if (t == null || t.uuid == null || t.uuid.trim().isEmpty()) continue;
                if (seen.add(t.uuid.trim())) out.add(t);
            }
        }
        return out;
    }

    /** Isi {@link Target#distKm} dari titik asal (null = tak diketahui → MAX_VALUE). */
    public static void fillDistances(List<Target> rows, double[] origin) {
        for (Target t : rows) {
            t.distKm = origin != null && t.hasGeo() ? km(origin[0], origin[1], t.lat, t.lng) : Double.MAX_VALUE;
        }
    }

    /**
     * Saring (nama/area/produk/perangkat, tak peka huruf) lalu urutkan: baris yang BISA dipilih dulu;
     * {@code byAge} = ⏱ Terlama (umur turun), selainnya 📍 Terjauh (jarak dari depot turun, tak
     * diketahui paling belakang). Urutan asal dipertahankan untuk nilai yang sama.
     */
    public static List<Target> filterSort(List<Target> rows, String query, boolean byAge) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Target> out = new ArrayList<>();
        for (Target t : rows) {
            if (q.isEmpty() || contains(t.name, q) || contains(t.area, q) || contains(t.items, q) || contains(t.deviceLabel, q)) {
                out.add(t);
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.selectable() != b.selectable()) return a.selectable() ? -1 : 1;
            if (byAge) return Long.compare(b.elapsedMs, a.elapsedMs);
            double da = a.distKm == Double.MAX_VALUE ? -1.0 : a.distKm;
            double db = b.distKm == Double.MAX_VALUE ? -1.0 : b.distKm;
            return Double.compare(db, da);
        });
        return out;
    }

    private static boolean contains(String hay, String q) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(q);
    }

    // ================================================================== pratinjau rencana

    /** Satu baris rencana dari server (orders[] atau alternates[]). */
    public static final class PlanRow {
        public String uuid = "";
        public String name = "";
        public String source = SRC_MINE;
        public String deviceLabel = "";
        public String items = "";
        public int galon;
        public boolean pickupOnly;
        public boolean priority;
        public boolean isTarget;
        public boolean alternate;
        public double kmFromOrigin;
        public double detourKm;
        public long ageMin = AGE_UNKNOWN;

        /** Muatan: AMBIL GALON SAJA tak memakan kapasitas. */
        public int load() {
            return pickupOnly ? 0 : Math.max(0, galon);
        }
    }

    /** Total galon baris yang dicentang (titik terjauh selalu dihitung). */
    public static int checkedGalon(Collection<PlanRow> rows, Set<String> checked) {
        int g = 0;
        for (PlanRow r : rows) {
            if (r != null && (r.isTarget || checked.contains(r.uuid))) g += r.load();
        }
        return g;
    }

    /** Jumlah order tercentang (titik terjauh selalu dihitung, uuid ganda sekali). */
    public static int checkedCount(Collection<PlanRow> rows, Set<String> checked) {
        Set<String> seen = new HashSet<>();
        for (PlanRow r : rows) {
            if (r != null && (r.isTarget || checked.contains(r.uuid))) seen.add(r.uuid);
        }
        return seen.size();
    }

    /**
     * Masih boleh mencentang satu order lagi? Satu Kirim Sejalur maksimal {@link #MAX_STOPS} order
     * (titik terjauh termasuk) — pratinjau mengunci kotak yang belum dicentang begitu batas tercapai,
     * jadi tak ada order tercentang yang diam-diam terpotong saat start.
     */
    public static boolean canCheckMore(Collection<PlanRow> rows, Set<String> checked) {
        return checkedCount(rows, checked) < MAX_STOPS;
    }

    /**
     * uuid untuk {@code /sejalur/start}, urutan rute: baris rencana tercentang sesuai urutan tampil,
     * alternatif tercentang DISISIPKAN menurut {@code km_from_origin} (sebelum baris pertama yang lebih
     * jauh), titik terjauh SELALU terakhir. Dibatasi {@link #MAX_STOPS} (titik terjauh tak pernah terpotong)
     * — yang terpotong dilaporkan {@link #startOverflow} dan pratinjau menolak start selama masih ada.
     */
    public static List<String> startUuids(List<PlanRow> main, List<PlanRow> alternates, Set<String> checked, String targetUuid) {
        String target = targetUuid == null ? "" : targetUuid;
        List<String> route = routeUuids(main, alternates, checked, target);
        List<String> out = new ArrayList<>(route.subList(0, Math.min(route.size(), MAX_STOPS - 1)));
        if (!target.isEmpty()) out.add(target);
        return out;
    }

    /** uuid tercentang (bukan titik terjauh) yang TAK muat dalam {@link #startUuids} karena batas {@link #MAX_STOPS}. */
    public static List<String> startOverflow(List<PlanRow> main, List<PlanRow> alternates, Set<String> checked, String targetUuid) {
        List<String> route = routeUuids(main, alternates, checked, targetUuid == null ? "" : targetUuid);
        return route.size() <= MAX_STOPS - 1 ? new ArrayList<>() : new ArrayList<>(route.subList(MAX_STOPS - 1, route.size()));
    }

    /** Baris tercentang selain titik terjauh, urutan rute (tanpa batas). */
    private static List<String> routeUuids(List<PlanRow> main, List<PlanRow> alternates, Set<String> checked, String target) {
        List<PlanRow> base = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(target);
        if (main != null) {
            for (PlanRow r : main) {
                if (r == null || r.isTarget || !checked.contains(r.uuid) || !seen.add(r.uuid)) continue;
                base.add(r);
            }
        }
        List<PlanRow> extra = new ArrayList<>();
        if (alternates != null) {
            for (PlanRow r : alternates) {
                if (r == null || r.isTarget || !checked.contains(r.uuid) || !seen.add(r.uuid)) continue;
                extra.add(r);
            }
        }
        Collections.sort(extra, (a, b) -> Double.compare(a.kmFromOrigin, b.kmFromOrigin));
        for (PlanRow a : extra) {
            int pos = base.size();
            for (int i = 0; i < base.size(); i++) {
                if (base.get(i).kmFromOrigin > a.kmFromOrigin) {
                    pos = i;
                    break;
                }
            }
            base.add(pos, a);
        }
        List<String> out = new ArrayList<>();
        for (PlanRow r : base) out.add(r.uuid);
        return out;
    }

    /** "8 order · 38/40 galon · rute ≈ 12,3 km (jalan)" — ringkasan pratinjau. */
    public static String summaryLine(int orders, int galon, int capacity, double routeKm, String method) {
        StringBuilder sb = new StringBuilder();
        sb.append(orders).append(" order · ").append(galon);
        if (capacity > 0) sb.append('/').append(capacity);
        sb.append(" galon");
        String k = kmText(routeKm);
        if (!k.isEmpty() && routeKm > 0) {
            sb.append(" · rute ≈ ").append(k).append(" (")
                    .append("driving".equals(method) ? "jalan" : "garis lurus").append(')');
        }
        return sb.toString();
    }

    // ================================================================== titipan rencana & urutan rit

    /** Rencana yang sudah di-start di server, menunggu barisnya ada di HP untuk dijalankan. */
    public static final class Pending {
        public final long at;
        /** uuid titik terjauh; "" bila server gagal memegangnya (target_held=false). */
        public final String target;
        /** uuid rit, URUTAN rute server (titik terjauh terakhir bila dipegang). */
        public final List<String> uuids;

        public Pending(long at, String target, List<String> uuids) {
            this.at = at;
            this.target = target == null ? "" : target;
            this.uuids = uuids == null ? new ArrayList<>() : new ArrayList<>(uuids);
        }

        public boolean expired(long nowMs) {
            return nowMs - this.at > PENDING_TTL_MS;
        }
    }

    /**
     * Rencana yang masih menunggu (belum kedaluwarsa) — Kirim Sejalur baru ditolak selama ini ada,
     * supaya order rencana lama yang sudah diambil server tak kehilangan rit-nya. null = tidak ada.
     */
    public static Pending waiting(String raw, long nowMs) {
        Pending p = decodePending(raw);
        return p != null && !p.expired(nowMs) ? p : null;
    }

    private static boolean token(String s) {
        return s != null && !s.isEmpty() && s.indexOf('|') < 0 && s.indexOf(',') < 0 && s.indexOf('=') < 0;
    }

    /** "epochMs|target|u1,u2,…" */
    public static String encodePending(Pending p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(p.at).append('|').append(token(p.target) ? p.target : "").append('|');
        boolean first = true;
        for (String u : p.uuids) {
            if (!token(u)) continue;
            if (!first) sb.append(',');
            sb.append(u);
            first = false;
        }
        return sb.toString();
    }

    /** null bila kosong/rusak/tanpa uuid. */
    public static Pending decodePending(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] parts = raw.trim().split("\\|", -1);
        if (parts.length != 3) return null;
        long at;
        try {
            at = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        List<String> uuids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String u : parts[2].split(",")) {
            String v = u.trim();
            if (!v.isEmpty() && seen.add(v)) uuids.add(v);
        }
        if (uuids.isEmpty()) return null;
        return new Pending(at, parts[1].trim(), uuids);
    }

    /** Urutan rit Kirim Sejalur yang sedang berjalan: uuid → id lokal, URUTAN rute. */
    public static final class Order {
        /** {@link Pending#at} rencana asal — identitas rencana (target yang sama bisa dipakai lagi nanti). */
        public final long planAt;
        public final String target;
        public final LinkedHashMap<String, Long> ids;

        public Order(long planAt, String target, Map<String, Long> ids) {
            this.planAt = planAt;
            this.target = target == null ? "" : target;
            this.ids = ids == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ids);
        }

        /** id lokal titik terjauh; -1 bila tak dipegang / belum tersinkron. */
        public long targetId() {
            Long v = this.target.isEmpty() ? null : this.ids.get(this.target);
            return v != null ? v : -1L;
        }

        public List<Long> idList() {
            return new ArrayList<>(this.ids.values());
        }
    }

    /** Urutan rit {@code o} memang hasil rencana {@code p} (bukan sisa rencana lain dengan target sama). */
    public static boolean samePlan(Pending p, Order o) {
        return p != null && o != null && o.planAt == p.at && o.target.equals(p.target);
    }

    /** "planAt|target|u1=12,u2=15" */
    public static String encodeOrder(Order o) {
        if (o == null || o.ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(o.planAt).append('|').append(token(o.target) ? o.target : "").append('|');
        boolean first = true;
        for (Map.Entry<String, Long> e : o.ids.entrySet()) {
            if (!token(e.getKey()) || e.getValue() == null || e.getValue() <= 0) continue;
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    public static Order decodeOrder(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] parts = raw.trim().split("[|]", -1);
        if (parts.length != 3) return null;
        long planAt;
        try {
            planAt = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        for (String pair : parts[2].split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                long id = Long.parseLong(pair.substring(eq + 1).trim());
                String u = pair.substring(0, eq).trim();
                if (id > 0 && !u.isEmpty() && !ids.containsKey(u)) ids.put(u, id);
            } catch (NumberFormatException ignored) {
            }
        }
        if (ids.isEmpty()) return null;
        return new Order(planAt, parts[1].trim(), ids);
    }

    /** Rencana tak diteruskan: kedaluwarsa sebelum rit-nya sempat jalan (tak ada rit berjalan). */
    public static final int DROP_EXPIRED = 1;
    /** Rencana tak diteruskan: rit Kirim Sejalur-nya sudah habis (Selesai / dikeluarkan semua). */
    public static final int DROP_RIT_ENDED = 2;

    /** Hasil satu kali penyerapan rencana ke rit (dipanggil tiap daftar Antrian Saya dimuat). */
    public static final class Absorb {
        /** id lokal yang harus MULAI diantar sekarang (urutan rute). */
        public final List<Long> add = new ArrayList<>();
        /** uuid dari {@link #add} (untuk dilepas dari titipan "masukkan ke rit"). */
        public final List<String> addUuids = new ArrayList<>();
        /** uuid rencana yang barisnya belum ada di HP (menunggu sinkron). */
        public final List<String> missing = new ArrayList<>();
        /** Urutan rit terbaru (uuid → id), urutan rute. */
        public Order order;
        /** Rencana tuntas (semua sudah ada), kedaluwarsa, atau dibuang → titipan dihapus. */
        public boolean finished;
        /** Kedaluwarsa saat rit berjalan padahal masih ada order yang belum tiba → diteruskan ke titipan "masukkan ke rit"; beri tahu kurir sekali. */
        public boolean expiredMissing;
        /**
         * 0, {@link #DROP_EXPIRED} atau {@link #DROP_RIT_ENDED}: rencana DIBUANG tanpa menjalankan apa pun
         * (tak ada rit berjalan) — {@link #add} kosong, order-nya tetap/akan ada di Antrian Saya.
         */
        public int dropped;
        /** Saat {@link #dropped}: uuid rencana yang tak pernah ikut rit (sudah ada di HP atau belum). */
        public final List<String> left = new ArrayList<>();
    }

    /**
     * Serap rencana: tiap uuid rencana yang barisnya SUDAH ada di Antrian Saya ({@code localIds}) dan
     * belum pernah diserap masuk {@link Absorb#add} bila belum berjalan; yang sudah diserap sebelumnya
     * ({@code prev} dari rencana yang SAMA, {@link #samePlan}) tak pernah ditambahkan ulang (mungkin
     * sudah Selesai / sengaja dikeluarkan kurir).
     *
     * <p>Rit hanya DIMULAI dari rencana yang masih segar dan belum pernah berjalan. Tanpa rit berjalan,
     * rencana yang lewat {@link #PENDING_TTL_MS} atau yang rit-nya sudah habis dibuang ({@link Absorb#dropped})
     * — HP tak diam-diam memulai rit (stempel "sedang diantar") dari rencana basi / dari order yang telat
     * tersinkron setelah rit-nya selesai. Rit masih berjalan → order yang tiba tetap diserap ke rit itu.
     */
    public static Absorb absorb(Pending p, Order prev, Map<String, Long> localIds, Collection<Long> running, long nowMs) {
        Absorb a = new Absorb();
        boolean same = samePlan(p, prev);
        boolean idle = running == null || running.isEmpty();
        boolean expired = p.expired(nowMs);
        boolean ended = same && !prev.ids.isEmpty();
        if (idle && (ended || expired)) {
            a.dropped = ended ? DROP_RIT_ENDED : DROP_EXPIRED;
            for (String u : p.uuids) {
                if (same && prev.ids.containsKey(u)) continue;
                a.left.add(u);
                Long id = localIds != null ? localIds.get(u) : null;
                if (id == null || id <= 0L) a.missing.add(u);
            }
            a.order = new Order(p.at, p.target, null);
            a.finished = true;
            return a;
        }
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        for (String u : p.uuids) {
            Long known = same ? prev.ids.get(u) : null;
            if (known != null) {
                ids.put(u, known);
                continue;
            }
            Long id = localIds != null ? localIds.get(u) : null;
            if (id == null || id <= 0L) {
                a.missing.add(u);
                continue;
            }
            ids.put(u, id);
            if (running == null || !running.contains(id)) {
                a.add.add(id);
                a.addUuids.add(u);
            }
        }
        a.order = new Order(p.at, p.target, ids);
        a.finished = a.missing.isEmpty() || expired;
        a.expiredMissing = expired && !a.missing.isEmpty();
        return a;
    }

    /**
     * Urutan rit Kirim Sejalur DILEPAS dengan sengaja (🔄 Urutkan Rute Terpendek sukses, ▶ rit baru):
     * rencana yang menunggu ikut dibuang — kalau tidak, penyerapan berikutnya (tanpa {@code prev})
     * membangun & memaksakan urutan koridor lagi. uuid rencana yang BELUM pernah ikut rit dikembalikan
     * untuk titipan "masukkan ke rit" bila rit masih berjalan (order perangkat lain yang telat tetap
     * ikut rit ini); tanpa rit berjalan → kosong (order-nya tinggal di Antrian Saya).
     */
    public static List<String> releaseCarry(Pending p, Order current, boolean running) {
        List<String> out = new ArrayList<>();
        if (p == null || !running) return out;
        boolean same = samePlan(p, current);
        for (String u : p.uuids) {
            if (same && current.ids.containsKey(u)) continue;
            out.add(u);
        }
        return out;
    }

    /**
     * Urutan pemberhentian rit berjalan: order rencana menurut urutan rute server, order lain yang
     * ditambahkan belakangan (➕ Tambah ke Rit) sesudahnya, dan titik terjauh SELALU terakhir.
     *
     * @param current id rit berjalan dalam urutan rencana lokal (adapter / DeliveryPlanner)
     */
    public static List<Long> orderStops(List<Long> current, Order o) {
        List<Long> out = new ArrayList<>();
        if (current == null) return out;
        if (o == null || o.ids.isEmpty() || current.size() < 2) {
            out.addAll(current);
            return out;
        }
        final Map<Long, Integer> rank = new HashMap<>();
        int i = 0;
        for (Long id : o.ids.values()) {
            if (id != null && !rank.containsKey(id)) rank.put(id, i++);
        }
        long targetId = o.targetId();
        List<Long> ranked = new ArrayList<>();
        List<Long> extra = new ArrayList<>();
        Long tgt = null;
        for (Long id : current) {
            if (id == null) continue;
            if (targetId > 0L && id == targetId) {
                tgt = id;
            } else if (rank.containsKey(id)) {
                ranked.add(id);
            } else {
                extra.add(id);
            }
        }
        Collections.sort(ranked, (x, y) -> Integer.compare(rank.get(x), rank.get(y)));
        out.addAll(ranked);
        out.addAll(extra);
        if (tgt != null) out.add(tgt);
        return out;
    }
}
