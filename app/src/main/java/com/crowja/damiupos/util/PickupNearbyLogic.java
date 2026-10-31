package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 📥 Peringatan "Galon Kembali di sekitar rute" — logika MURNI (tanpa Android; diuji JUnit di src/test).
 *
 * <p>Owner 2026-10-31: saat navigasi pesanan, bila di tengah jalan (radius 50 m, diatur per perangkat)
 * ada lokasi yang punya tugas galon kembali (KEMBALI PENDING, bukan kunjungan, tidak sedang dijalankan),
 * kurir diberi peringatan untuk mengambilnya. Kalau dilewati tanpa "Ambil", server mencatatnya di
 * 📜 Jurnal Pesanan.
 *
 * <p>Isi kelas ini: kandidat (lokal + perangkat lain / Pesanan Terbuka), jarak haversine, syarat fix GPS
 * diterima, teks notifikasi, dan mesin keadaan per kandidat ({@link Tracker}):
 * masuk radius → DIPERINGATKAN → menjauh (&gt; {@link #leaveThresholdM}, {@link #LEAVE_CONFIRM_FIXES} fix
 * berturut-turut) tanpa "Ambil" → TERLEWAT (dilaporkan sekali) → jeda {@link #COOLDOWN_MS}.
 * "📥 Ambil" = tak pernah dilaporkan terlewat + jeda; "Nanti" = peringatan ditutup + jeda, tapi TETAP
 * dilaporkan terlewat bila kurir menjauh.
 */
public final class PickupNearbyLogic {

    private PickupNearbyLogic() {}

    /** Antrean Saya (baris lokal milik perangkat ini). */
    public static final String SRC_MINE = "mine";
    /** Antrean perangkat lain (dari /api/devices/queue-all). */
    public static final String SRC_OTHER = "other";
    /** Pesanan Terbuka (belum diklaim siapa pun). */
    public static final String SRC_OPEN = "open";

    public static final int DEFAULT_RADIUS_M = 50;
    public static final int MIN_RADIUS_M = 10;
    public static final int MAX_RADIUS_M = 500;
    /** Fix GPS dipakai bila akurasinya ≤ max(nilai ini, radius). */
    public static final int MIN_ACCEPT_ACCURACY_M = 50;
    /** Bunyi/getar ULANG tiap selang ini selama kurir masih di dalam radius. */
    public static final long REALERT_MS = 45_000L;
    /** Maksimal peringatan ulang (di luar peringatan pertama) per kedatangan. */
    public static final int MAX_REALERTS = 3;
    /** Jeda per kandidat setelah "Ambil", "Nanti", atau terlewat. */
    public static final long COOLDOWN_MS = 20 * 60_000L;
    /**
     * Fix berturut-turut di luar ambang menjauh sebelum dinyatakan TERLEWAT. Satu fix melompat
     * (pantulan GPS di antara rumah) tak boleh menulis "galon kembali tidak diambil" ke jurnal.
     */
    public static final int LEAVE_CONFIRM_FIXES = 2;

    /** Penanda tugas "📞 Kunjungan Follow Up" (KEMBALI 0 galon) — tak pernah diperingatkan. */
    public static final String VISIT_MARKER = com.crowja.damiupos.model.Transaction.VISIT_MARKER;

    /** Teks tambahan BigText notifikasi peringatan. */
    public static final String ALERT_FOOTER = "Kalau dilewati, tercatat di Jurnal Pesanan.";

    // ------------------------------------------------------------------ pengaturan

    /** Radius peringatan (m) dibatasi 10–500, sama dengan validasi server. */
    public static int clampRadius(int r) {
        return Math.max(MIN_RADIUS_M, Math.min(MAX_RADIUS_M, r));
    }

    /**
     * Nilai mentah {@code r.opt("pickup_alert_radius_m")} dari /api/me → radius ter-clamp. Absen / null /
     * tak terurai → {@code current} (nilai tersimpan dipertahankan).
     */
    public static int parseRadius(Object raw, int current) {
        Long v = null;
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d)) v = Math.round(d);
        } else if (raw instanceof String) {
            try {
                v = Math.round(Double.parseDouble(((String) raw).trim()));
            } catch (Exception ignored) {
            }
        }
        if (v == null) return clampRadius(current);
        return clampRadius((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v)));
    }

    /**
     * Nilai mentah boolean dari /api/me ({@code pickup_alert_enabled}) → aktif? Boolean apa adanya;
     * 1/"1"/"true" = aktif, 0/"0"/"false" = mati; absen / null / lainnya → {@code dflt}.
     */
    public static boolean parseBool(Object raw, boolean dflt) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).doubleValue() != 0;
        if (raw instanceof String) {
            String v = ((String) raw).trim();
            if ("1".equals(v) || "true".equalsIgnoreCase(v)) return true;
            if ("0".equals(v) || "false".equalsIgnoreCase(v)) return false;
        }
        return dflt;
    }

    /**
     * Monitor AKTIF (GPS akurasi tinggi menyala) hanya bila sakelar perangkat aktif DAN (rit sedang
     * berjalan di HP ini ATAU panel navigasi melayang hidup). Selain itu GPS-nya mati (hemat baterai).
     */
    public static boolean shouldBeActive(boolean enabled, boolean ritRunning, boolean navLive) {
        return enabled && (ritRunning || navLive);
    }

    // ------------------------------------------------------------------ geometri

    /** Fix dipakai bila punya akurasi dan akurasinya ≤ max(50 m, radius). */
    public static boolean acceptFix(boolean hasAccuracy, float accuracyM, int radiusM) {
        if (!hasAccuracy || Float.isNaN(accuracyM) || accuracyM < 0f) return false;
        return accuracyM <= Math.max(MIN_ACCEPT_ACCURACY_M, clampRadius(radiusM));
    }

    /**
     * Ambang "sudah menjauh" = yang LEBIH BESAR dari 2×radius dan radius+60 m. Histeresis ini harus
     * melebihi derau GPS (fix diterima sampai ±50 m): radius 10 m tak boleh "terlewat" di 20 m.
     */
    public static double leaveThresholdM(int radiusM) {
        int r = clampRadius(radiusM);
        return Math.max(2.0 * r, r + 60.0);
    }

    /** Jarak haversine dalam meter. */
    public static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Titik peta sah (bukan 0,0 / NaN / di luar jangkauan). */
    public static boolean hasGeo(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return false;
        if (lat == 0 && lng == 0) return false;
        return Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
    }

    // ------------------------------------------------------------------ kandidat

    /** Kunci kandidat: uuid server; baris lokal yang belum tersinkron → "local:{_id}". */
    public static String keyFor(String uuid, long localId) {
        String u = uuid == null ? "" : uuid.trim();
        return !u.isEmpty() ? u : "local:" + localId;
    }

    /** Satu tugas galon kembali yang bisa diperingatkan. */
    public static final class Candidate {
        public final String key;
        /** uuid transaksi di server; "" = baris lokal belum tersinkron (tak bisa dilaporkan). */
        public final String uuid;
        /** _id lokal; 0 = hanya ada di server (perangkat lain). */
        public final long localId;
        public final String name;
        public final int galon;
        public final double lat;
        public final double lng;
        /** {@link #SRC_MINE} / {@link #SRC_OTHER} / {@link #SRC_OPEN}. */
        public final String source;
        /** Nama perangkat pemegang (hanya {@link #SRC_OTHER}). */
        public final String holder;
        /** Pemilik menurut server (routed_uuid) — syarat Ambil Alih ({@link #SRC_OTHER}); "" bila tak ada. */
        public final String expectedDevice;

        public Candidate(String uuid, long localId, String name, int galon, double lat, double lng,
                         String source, String holder, String expectedDevice) {
            this.uuid = clean(uuid);
            this.localId = localId;
            this.key = keyFor(this.uuid, localId);
            this.name = displayName(name);
            this.galon = galon;
            this.lat = lat;
            this.lng = lng;
            this.source = source == null ? SRC_MINE : source;
            this.holder = clean(holder);
            this.expectedDevice = clean(expectedDevice);
        }
    }

    /**
     * Baris LOKAL ({@code TransactionDao.getDeliveryQueue}: Antrean Saya + Pesanan Terbuka) → kandidat,
     * atau null: bukan KEMBALI, tugas kunjungan / 0 galon, anggota rit berjalan, atau tanpa titik peta.
     */
    public static Candidate localCandidate(long localId, String uuid, String type, String catatan, int galon,
                                           double lat, double lng, boolean openDispatch, boolean inRunningRit,
                                           String name) {
        if (!"KEMBALI".equals(type)) return null;
        if (isVisit(catatan) || galon <= 0) return null;
        if (inRunningRit) return null;
        if (!hasGeo(lat, lng)) return null;
        return new Candidate(uuid, localId, name, galon, lat, lng, openDispatch ? SRC_OPEN : SRC_MINE, "", "");
    }

    /**
     * Baris {@code /api/devices/queue-all} (perangkat LAIN & Pesanan Terbuka) → kandidat, atau null: tanpa
     * uuid, bukan pickup_only, sedang diantar kurirnya (in_progress), tugas kunjungan / 0 galon, tanpa titik.
     */
    public static Candidate remoteCandidate(String uuid, String name, int galon, Double lat, Double lng,
                                            boolean pickupOnly, boolean inProgress, boolean openDispatch,
                                            String holder, String routedUuid, String note) {
        if (clean(uuid).isEmpty()) return null;
        if (!pickupOnly || inProgress) return null;
        if (isVisit(note) || galon <= 0) return null;
        if (lat == null || lng == null || !hasGeo(lat, lng)) return null;
        return new Candidate(uuid, 0L, name, galon, lat, lng, openDispatch ? SRC_OPEN : SRC_OTHER,
                openDispatch ? "" : holder, openDispatch ? "" : routedUuid);
    }

    /** Gabung kandidat lokal + server: uuid sama → baris LOKAL yang menang (datanya paling segar). */
    public static List<Candidate> merge(List<Candidate> local, List<Candidate> remote) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addUnique(out, seen, local);
        addUnique(out, seen, remote);
        return out;
    }

    private static void addUnique(List<Candidate> out, Set<String> seen, List<Candidate> part) {
        if (part == null) return;
        for (Candidate c : part) {
            if (c != null && seen.add(c.key)) out.add(c);
        }
    }

    /** Kandidat terdekat beserta jaraknya. */
    public static final class Hit {
        public final Candidate cand;
        public final double distanceM;

        Hit(Candidate cand, double distanceM) {
            this.cand = cand;
            this.distanceM = distanceM;
        }
    }

    /** Kandidat TERDEKAT yang berada di dalam radius; null bila tak ada. */
    public static Hit nearest(List<Candidate> cands, double lat, double lng, int radiusM) {
        if (cands == null) return null;
        int r = clampRadius(radiusM);
        Hit best = null;
        for (Candidate c : cands) {
            if (c == null) continue;
            double d = distanceM(lat, lng, c.lat, c.lng);
            if (d <= r && (best == null || d < best.distanceM)) best = new Hit(c, d);
        }
        return best;
    }

    // ------------------------------------------------------------------ teks

    /** "Antrean kamu" | "Antrean {perangkat}" | "Pesanan Terbuka". */
    public static String sourceLabel(String source, String holder) {
        if (SRC_MINE.equals(source)) return "Antrean kamu";
        if (SRC_OPEN.equals(source)) return "Pesanan Terbuka";
        String h = clean(holder);
        return h.isEmpty() ? "Antrean perangkat lain" : "Antrean " + h;
    }

    public static String alertTitle(String name) {
        return "📥 AMBIL GALON — " + displayName(name);
    }

    /** "{n} galon kembali · {d} m dari kamu · {Antrean kamu|Antrean {perangkat}|Pesanan Terbuka}". */
    public static String alertText(int galon, double distanceM, String source, String holder) {
        return galon + " galon kembali · " + meters(distanceM) + " dari kamu · " + sourceLabel(source, holder);
    }

    public static String passedTitle(String name) {
        return "⚠️ Galon kembali " + displayName(name) + " terlewat — tercatat di Jurnal Pesanan";
    }

    /** Pita oranye panel navigasi: "📥 Galon kembali dekat: {nama} · {d} m". */
    public static String bannerText(String name, double distanceM) {
        return "📥 Galon kembali dekat: " + displayName(name) + " · " + meters(distanceM);
    }

    /** Jarak dibulatkan ke meter; tak diketahui → "? m". */
    public static String meters(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return "? m";
        return Math.round(d) + " m";
    }

    /** Jarak yang dikirim ke server ({@code distance_m}): bulat, ≥ 0. */
    public static int reportDistance(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return 0;
        return (int) Math.min(100_000L, Math.round(d));
    }

    static boolean isVisit(String catatan) {
        return catatan != null && catatan.contains(VISIT_MARKER);
    }

    static String clean(String s) {
        String v = s == null ? "" : s.trim();
        return "null".equalsIgnoreCase(v) ? "" : v;
    }

    static String displayName(String s) {
        String v = clean(s);
        return v.isEmpty() ? "Pelanggan" : v;
    }

    // ------------------------------------------------------------------ mesin keadaan

    /** Masuk radius → notifikasi + bunyi/getar. */
    public static final int EV_ALERT = 1;
    /** Masih di dalam radius setelah {@link #REALERT_MS} → bunyi/getar lagi. */
    public static final int EV_REALERT = 2;
    /** Jarak berubah → perbarui teks notifikasi (diam). */
    public static final int EV_UPDATE = 3;
    /** Menjauh tanpa "Ambil" → laporkan ke server + notifikasi "terlewat". */
    public static final int EV_PASSED = 4;
    /** Tutup peringatan TANPA laporan (order hilang dari antrean / monitor berhenti). */
    public static final int EV_CANCEL = 5;

    public static final class Event {
        public final int type;
        public final Candidate cand;
        /** Jarak pada fix terakhir. */
        public final double distanceM;
        /** Jarak TERDEKAT selama kedatangan ini — angka yang dilaporkan sebagai "dalam jarak {d} m". */
        public final double minDistanceM;

        Event(int type, Candidate cand, double distanceM, double minDistanceM) {
            this.type = type;
            this.cand = cand;
            this.distanceM = distanceM;
            this.minDistanceM = minDistanceM;
        }
    }

    /** Peringatan yang sedang tampil (untuk pita panel navigasi). */
    public static final class Alert {
        public final Candidate cand;
        public final double distanceM;

        Alert(Candidate cand, double distanceM) {
            this.cand = cand;
            this.distanceM = distanceM;
        }
    }

    /**
     * Keadaan per kandidat (kunci = uuid). Tidak thread-safe — pemilik (PickupNearbyMonitor) memanggilnya
     * dari satu thread saja.
     */
    public static final class Tracker {
        private static final int ST_ALERTED = 1;
        private static final int ST_SNOOZED = 2;
        private static final int ST_ACTED = 3;
        private static final int ST_COOLDOWN = 4;

        private static final class Entry {
            Candidate cand;
            int state;
            long lastAlertAt;
            int realerts;
            double lastDist = Double.NaN;
            double minDist = Double.POSITIVE_INFINITY;
            boolean inside;
            int outside;
            long until;
        }

        private final Map<String, Entry> entries = new LinkedHashMap<>();

        /** Satu fix GPS yang SUDAH lolos {@link #acceptFix}. */
        public List<Event> onFix(List<Candidate> cands, double lat, double lng, int radiusM, long now) {
            List<Event> out = new ArrayList<>();
            int r = clampRadius(radiusM);
            double leave = leaveThresholdM(r);
            Map<String, Candidate> byKey = index(cands);
            expire(now);
            sweepMissing(byKey, out);
            for (Candidate c : byKey.values()) {
                double d = distanceM(lat, lng, c.lat, c.lng);
                Entry e = entries.get(c.key);
                if (e == null) {
                    if (d <= r) {
                        Entry n = new Entry();
                        n.cand = c;
                        n.state = ST_ALERTED;
                        n.lastAlertAt = now;
                        n.lastDist = d;
                        n.minDist = d;
                        n.inside = true;
                        entries.put(c.key, n);
                        out.add(new Event(EV_ALERT, c, d, d));
                    }
                    continue;
                }
                e.cand = c;   // nama / jumlah / pemegang terbaru
                if (e.state != ST_ALERTED && e.state != ST_SNOOZED) continue;   // Ambil / jeda: diam
                e.lastDist = d;
                e.minDist = Math.min(e.minDist, d);
                e.inside = d <= r;
                if (d > leave) {
                    e.outside++;
                    if (e.outside >= LEAVE_CONFIRM_FIXES) {
                        out.add(new Event(EV_PASSED, c, d, e.minDist));
                        e.state = ST_COOLDOWN;
                        e.until = Math.max(e.until, now + COOLDOWN_MS);
                        e.outside = 0;
                        continue;
                    }
                } else {
                    e.outside = 0;
                }
                if (e.state == ST_ALERTED) {
                    if (dueRealert(e, now)) {
                        e.realerts++;
                        e.lastAlertAt = now;
                        out.add(new Event(EV_REALERT, c, d, e.minDist));
                    } else {
                        out.add(new Event(EV_UPDATE, c, d, e.minDist));
                    }
                }
            }
            return out;
        }

        /**
         * Daftar kandidat diperbarui TANPA fix baru (kurir berhenti): yang hilang ditutup tanpa laporan,
         * data kandidat yang masih ada diperbarui. Jarak / hitungan menjauh tak disentuh.
         */
        public List<Event> sync(List<Candidate> cands, long now) {
            List<Event> out = new ArrayList<>();
            Map<String, Candidate> byKey = index(cands);
            expire(now);
            sweepMissing(byKey, out);
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                Candidate c = byKey.get(me.getKey());
                if (c != null) me.getValue().cand = c;
            }
            return out;
        }

        private static Map<String, Candidate> index(List<Candidate> cands) {
            Map<String, Candidate> byKey = new LinkedHashMap<>();
            if (cands != null) {
                for (Candidate c : cands) {
                    if (c != null && !byKey.containsKey(c.key)) byKey.put(c.key, c);
                }
            }
            return byKey;
        }

        /**
         * Kandidat yang hilang (selesai / diambil kurir lain / dibatalkan) → peringatannya ditutup tanpa
         * laporan. Jeda (Ambil / terlewat) tetap disimpan sampai habis: order yang baru diambil alih
         * sempat hilang dari kedua daftar sebelum tertarik sebagai milik sendiri.
         */
        private void sweepMissing(Map<String, Candidate> byKey, List<Event> out) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> me = it.next();
                if (byKey.containsKey(me.getKey())) continue;
                Entry e = me.getValue();
                if (e.state == ST_ALERTED) {
                    out.add(new Event(EV_CANCEL, e.cand, e.lastDist, e.minDist));
                    it.remove();
                } else if (e.state == ST_SNOOZED) {
                    it.remove();
                }
            }
        }

        /**
         * Detak tanpa fix baru — FusedLocation diam bila kurir berhenti (&lt; 5 m), padahal bunyi ulang
         * tetap harus jalan selama ia masih di dalam radius.
         */
        public List<Event> onTick(long now) {
            List<Event> out = new ArrayList<>();
            for (Entry e : entries.values()) {
                if (e.state == ST_ALERTED && dueRealert(e, now)) {
                    e.realerts++;
                    e.lastAlertAt = now;
                    out.add(new Event(EV_REALERT, e.cand, e.lastDist, e.minDist));
                }
            }
            return out;
        }

        private static boolean dueRealert(Entry e, long now) {
            return e.inside && e.realerts < MAX_REALERTS && now - e.lastAlertAt >= REALERT_MS;
        }

        /**
         * "📥 Ambil": tak pernah dilaporkan terlewat + jeda {@link #COOLDOWN_MS}. Kunci yang belum
         * dilacak tetap dicatat (Ambil dari notifikasi lama). @return true bila peringatannya sedang tampil.
         */
        public boolean markActed(String key, long now) {
            if (key == null || key.isEmpty()) return false;
            Entry e = entries.get(key);
            boolean wasAlerted = e != null && e.state == ST_ALERTED;
            if (e == null) {
                e = new Entry();
                entries.put(key, e);
            }
            e.state = ST_ACTED;
            e.until = now + COOLDOWN_MS;
            e.outside = 0;
            return wasAlerted;
        }

        /**
         * "Nanti": peringatan ditutup + jeda {@link #COOLDOWN_MS}, tapi tetap dilacak — menjauh = TERLEWAT.
         * @return true bila ada peringatan tampil yang ditutup.
         */
        public boolean markSnoozed(String key, long now) {
            Entry e = key != null ? entries.get(key) : null;
            if (e == null || e.state != ST_ALERTED) return false;
            e.state = ST_SNOOZED;
            e.until = now + COOLDOWN_MS;
            return true;
        }

        /**
         * Monitor berhenti (rit / navigasi selesai, sakelar dimatikan): peringatan yang tampil ditutup TANPA
         * laporan; jeda Ambil / terlewat tetap diingat supaya navigasi berikutnya tak langsung berbunyi lagi.
         */
        public List<Event> clear() {
            List<Event> out = new ArrayList<>();
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.state == ST_ALERTED) out.add(new Event(EV_CANCEL, e.cand, e.lastDist, e.minDist));
                if (e.state == ST_ALERTED || e.state == ST_SNOOZED) it.remove();
            }
            return out;
        }

        /** Peringatan tampil yang TERDEKAT (pita panel navigasi); null bila tak ada. */
        public Alert nearestAlerted() {
            Entry best = null;
            for (Entry e : entries.values()) {
                if (e.state != ST_ALERTED || e.cand == null) continue;
                if (best == null || e.lastDist < best.lastDist) best = e;
            }
            return best == null ? null : new Alert(best.cand, best.lastDist);
        }

        /** Kunci yang peringatannya sedang tampil (urutan masuk). */
        public List<String> alertedKeys() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                if (me.getValue().state == ST_ALERTED) out.add(me.getKey());
            }
            return Collections.unmodifiableList(out);
        }

        private void expire(long now) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                // Jeda habis → kandidat boleh diperingatkan lagi pada kedatangan berikutnya. "Nanti" yang
                // jedanya habis saat kurir masih di dalam radius = kedatangan baru (bunyi lagi).
                if ((e.state == ST_ACTED || e.state == ST_COOLDOWN || e.state == ST_SNOOZED) && now >= e.until) {
                    it.remove();
                }
            }
        }
    }
}
