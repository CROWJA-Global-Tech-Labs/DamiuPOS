package com.crowja.damiupos.pengisian;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Teks, pengelompokan, dan aturan kecil layar Pengisian — dipisah dari Activity supaya bisa diuji
 * di JVM dan supaya satu angka tak pernah punya dua kalimat berbeda di dua tempat layar.
 */
public final class FillText {

    private FillText() {}

    /** Produk "aktif" (tampil penuh di atas); sisanya dilipat di bawah "Produk lain". */
    public static boolean isActive(FillPlan.Product p) {
        return p.backlog > 0 || p.ready > 0 || p.filledToday > 0 || p.toFill > 0;
    }

    /** Angka besar di kartu produk. */
    public static String headline(FillPlan.Product p) {
        if (p.toFill > 0) return "ISI " + p.toFill + " LAGI";
        if (p.target > 0) {
            return p.surplus > 0 ? "✓ Terpenuhi · lebih " + p.surplus : "✓ Stok terpenuhi";
        }
        return p.ready > 0 ? "Siap " + p.ready : "Tak ada pesanan";
    }

    /** "Pesanan berjalan 10 (+2 cadangan) = 12 · Siap di rak 5". */
    public static String detail(FillPlan.Product p) {
        StringBuilder sb = new StringBuilder();
        if (p.backlog > 0) {
            sb.append("Pesanan berjalan ").append(p.backlog);
            if (p.spare > 0) sb.append(" (+").append(p.spare).append(" cadangan)");
            sb.append(" = ").append(p.target);
        } else {
            sb.append("Tak ada pesanan berjalan");
        }
        sb.append(" · Siap di rak ").append(p.ready);
        return sb.toString();
    }

    /** Persen isi bar (ready/target), 0..100; tanpa target → 0. */
    public static int progressPercent(FillPlan.Product p) {
        if (p.target <= 0) return 0;
        long pct = (long) p.ready * 100L / p.target;
        return (int) Math.max(0, Math.min(100, pct));
    }

    /** Judul kartu ringkasan atas. */
    public static String heroTitle(FillPlan plan) {
        switch (plan.status) {
            case FillPlan.STATUS_NEED: return "Perlu diisi sekarang";
            case FillPlan.STATUS_MET: return "Semua pesanan sudah tercukupi";
            default: return "Belum ada pesanan berjalan";
        }
    }

    /** Angka besar kartu ringkasan: "12 galon" (perlu diisi) atau tanda centang. */
    public static String heroValue(FillPlan plan) {
        switch (plan.status) {
            case FillPlan.STATUS_NEED: return plan.totals.toFill + " galon";
            case FillPlan.STATUS_MET: return "✓";
            default: return "–";
        }
    }

    /** Baris kecil di bawah angka ringkasan: "5 pesanan · 34 galon · 6 di jalan". */
    public static String heroSub(FillPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.totals.orders).append(" pesanan · ").append(plan.totals.backlog).append(" galon");
        if (plan.inTransitPcs > 0) sb.append(" · ").append(plan.inTransitPcs).append(" sudah di jalan");
        return sb.toString();
    }

    /** Peringatan pesanan yang produknya tak terpetakan, atau null. */
    public static String unmappedWarning(FillPlan plan) {
        if (plan.unmappedPcs <= 0 && plan.unmappedOrders <= 0) return null;
        return "⚠ " + plan.unmappedPcs + " galon di " + plan.unmappedOrders
                + " pesanan tak terbaca jenisnya — lapor admin (belum masuk hitungan di atas).";
    }

    /** "HH:mm" dari "yyyy-MM-dd HH:mm:ss[.uuuuuu]" (waktu server, zona aplikasi); apa adanya bila aneh. */
    public static String timeOf(String loggedAt) {
        if (loggedAt == null) return "";
        int sp = loggedAt.indexOf(' ');
        String t = sp >= 0 ? loggedAt.substring(sp + 1) : loggedAt;
        return t.length() >= 5 ? t.substring(0, 5) : t;
    }

    /** Satu baris riwayat: "FILL · Galon 19L · +10" / "HITUNG · Galon 19L · rak 25". */
    public static String recentLabel(FillPlan.Recent r) {
        if (FillPlan.KIND_COUNT.equals(r.kind)) {
            return "Hitung stok · " + r.productName + " · rak " + r.qty;
        }
        return "Isi · " + r.productName + " · +" + r.qty;
    }

    /** Satu baris di dialog riwayat: satu catatan isi, atau SATU sesi hitung stok (semua produknya). */
    public static final class HistoryRow {
        public final String label;
        public final String meta;
        /** uuid baris FILL, atau batch_uuid sesi hitung; null bila pemegang layar tak boleh membatalkannya. */
        public final String voidTarget;

        HistoryRow(String label, String meta, String voidTarget) {
            this.label = label;
            this.meta = meta;
            this.voidTarget = voidTarget;
        }
    }

    /**
     * Boleh menampilkan "Batal" untuk baris ini? Server tetap yang memutuskan (window 15 menit SEKALIGUS
     * pemilik: `not_owner` untuk non-admin), tapi `voidable` dari rencana sama untuk semua pemanggil —
     * tanpa pemeriksaan pemilik di sini pekerja lain melihat tombol yang pasti ditolak server dan
     * overlay sempat menggeser angkanya.
     */
    public static boolean canVoid(FillPlan.Recent r, String staffUuid, boolean isAdmin) {
        if (!r.voidable) return false;
        return isAdmin || (staffUuid != null && !staffUuid.isEmpty() && staffUuid.equals(r.staffUuid));
    }

    /**
     * Susun riwayat untuk dialog: baris COUNT sesi yang sama (batch_uuid sama) digabung jadi satu baris
     * yang dibatalkan SEKALIGUS lewat batch_uuid-nya (server membatalkan seluruh sesi); baris FILL
     * tetap satu per satu. Urutan = urutan kemunculan pertama (rencana sudah terbaru-dulu).
     */
    public static List<HistoryRow> historyRows(List<FillPlan.Recent> recent, String staffUuid, boolean isAdmin) {
        Map<String, List<FillPlan.Recent>> groups = new LinkedHashMap<>();
        for (FillPlan.Recent r : recent) {
            String key = (FillPlan.KIND_COUNT.equals(r.kind) && !r.batchUuid.isEmpty())
                    ? "b:" + r.batchUuid : "r:" + r.uuid;
            List<FillPlan.Recent> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(r);
        }
        List<HistoryRow> out = new ArrayList<>();
        for (List<FillPlan.Recent> g : groups.values()) {
            FillPlan.Recent first = g.get(0);
            boolean batch = FillPlan.KIND_COUNT.equals(first.kind) && !first.batchUuid.isEmpty();
            boolean voidable = true;
            for (FillPlan.Recent r : g) if (!canVoid(r, staffUuid, isAdmin)) voidable = false;
            String label = g.size() > 1 ? "Hitung stok · " + g.size() + " produk" : recentLabel(first);
            String meta = timeOf(first.loggedAt) + (first.staffName.isEmpty() ? "" : " · " + first.staffName);
            out.add(new HistoryRow(label, meta, voidable ? (batch ? first.batchUuid : first.uuid) : null));
        }
        return out;
    }

    /** Nasib satu operasi tulis yang ditolak server. */
    public enum WriteVerdict {
        /** Ditolak PERMANEN oleh FillPlanController (mengulang tak akan berhasil) → buang + laporkan. */
        DROP,
        /** Identitas pekerja ditolak (peran dicabut, staf nonaktif / belum tersinkron) → SIMPAN operasinya,
         *  hentikan pengiriman putaran ini, tampilkan pesan server. Bisa pulih (sinkron ulang, admin
         *  mengaktifkan lagi), jadi ketukan pekerja tak boleh hilang. */
        KEEP_BLOCKED,
        /** Bukan jawaban pengontrol (CDN/WAF, 404 deploy, halaman galat, 5xx, 429, 401…) → simpan & coba lagi. */
        RETRY
    }

    /** Kode galat PERMANEN yang diberikan FillPlanController untuk satu ketukan (bukan untuk identitas). */
    private static final Set<String> PERMANENT_CODES = new HashSet<>(Arrays.asList(
            "validation", "product_invalid", "log_not_found", "void_window_closed", "not_owner"));

    /** Kode galat IDENTITAS pekerja (FillPlanController::deny). */
    private static final Set<String> IDENTITY_CODES = new HashSet<>(Arrays.asList(
            "forbidden_role", "staff_not_found", "staff_uuid_missing", "staff_inactive"));

    /**
     * Klasifikasi penolakan atas operasi TULIS dari KODE di badan JSON server, bukan status HTTP
     * telanjang: 403/404 juga dijawab CDN/WAF (halaman HTML), 404 oleh rute yang belum dideploy, 405/413
     * oleh proxy — semuanya BUKAN kata akhir server soal ketukan ini, dan membuangnya berarti isi
     * galon pekerja hilang diam-diam (stok siap di rak jadi terlalu rendah → depot kelebihan isi).
     * Hanya kode dikenal dengan status 4xx yang dibuang; identitas ditahan (lihat {@link
     * WriteVerdict#KEEP_BLOCKED}); sisanya sementara.
     */
    public static WriteVerdict classifyWrite(int httpCode, String body) {
        String c = rejectionCode(body);
        if (IDENTITY_CODES.contains(c)) return WriteVerdict.KEEP_BLOCKED;
        if (PERMANENT_CODES.contains(c) && httpCode >= 400 && httpCode < 500) return WriteVerdict.DROP;
        return WriteVerdict.RETRY;
    }

    /** Kode galat server (mis. forbidden_role) atau "" bila tak ada/bukan JSON. */
    public static String rejectionCode(String body) {
        try {
            JsonObject o = JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
            if (o.has("code") && o.get("code").isJsonPrimitive()) return o.get("code").getAsString();
        } catch (RuntimeException ignored) {
            // bukan JSON
        }
        return "";
    }

    /**
     * Penolakan atas IDENTITAS pekerja (peran bukan pengisian/admin, staf tak ada, header hilang) —
     * bukan atas satu ketukan (termasuk {@code staff_inactive}, 403). Layar menampilkan pesannya
     * alih-alih banner "offline". Sengaja dikenali dari
     * KODE, bukan status HTTP: 404 juga dipakai "baris tak ditemukan" saat batal, 403 juga
     * "jendela batal lewat".
     */
    public static boolean isIdentityRejection(String body) {
        return IDENTITY_CODES.contains(rejectionCode(body));
    }

    /** Pesan galat server (JSON {ok:false, code, message}) atau cadangan; tak pernah melempar. */
    public static String rejectionMessage(String body, String fallback) {
        try {
            JsonObject o = JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
            if (o.has("message") && o.get("message").isJsonPrimitive()) {
                String m = o.get("message").getAsString();
                if (!m.trim().isEmpty()) return m;
            }
            if (o.has("code") && o.get("code").isJsonPrimitive()) return o.get("code").getAsString();
        } catch (RuntimeException ignored) {
            // bukan JSON (halaman galat proxy)
        }
        return fallback;
    }

    /** Bungkus cache rencana: {@code {"t":<ms>,"plan":{...}}} supaya "data lama" bisa bilang jam berapa. */
    public static String encodeCache(String planJson, long savedAtMs) {
        return "{\"t\":" + savedAtMs + ",\"plan\":" + planJson + "}";
    }

    /** @return {savedAtMs, planJson} atau null bila rusak/kosong. */
    public static Object[] decodeCache(String cache) {
        if (cache == null || cache.isEmpty()) return null;
        try {
            JsonObject o = JsonParser.parseString(cache).getAsJsonObject();
            if (!o.has("plan") || !o.get("plan").isJsonObject()) return null;
            long t = o.has("t") && o.get("t").isJsonPrimitive() ? o.get("t").getAsLong() : 0L;
            return new Object[]{t, o.get("plan").toString()};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
