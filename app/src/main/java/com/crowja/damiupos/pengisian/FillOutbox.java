package com.crowja.damiupos.pengisian;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kotak keluar tulis layar Pengisian: setiap isi/hitung/batal dicatat DULU di sini (uuid klien
 * dibuat sekali per ketukan), baru dikirim. Sinyal depot sering putus — ketukan pekerja tak boleh
 * hilang, dan tak boleh perlu ketuk dua kali. Server idempoten per uuid, jadi kirim ulang aman.
 *
 * <p>Java murni (Gson) — serialisasi ke teks JSON untuk kunci SettingsDao lokal
 * ({@link PengisianStore}) dan bisa diuji di JVM. Urutan = urutan ketukan (FIFO); {@link
 * FillOverlay} memakai urutan itu (hitung stok absolut menimpa isi sebelumnya).
 */
public final class FillOutbox {

    public static final String TYPE_FILL = "FILL";
    public static final String TYPE_COUNT = "COUNT";
    public static final String TYPE_VOID = "VOID";

    /** Batas atas {@code age_seconds} yang diterima server (6 jam); klien ikut memotong ke sini. */
    public static final int MAX_AGE_SECONDS = 6 * 60 * 60;
    /** Operasi tertahan lebih lama dari ini dibuang: catatan stok kemarin tak boleh dicap hari ini. */
    public static final long EXPIRE_AFTER_MS = 24L * 60L * 60L * 1000L;

    public static final class Count {
        public String productUuid = "";
        public int qty;

        public Count() {}

        public Count(String productUuid, int qty) {
            this.productUuid = productUuid;
            this.qty = qty;
        }
    }

    public static final class Op {
        public String type = "";
        /** uuid klien ketukan ini (FILL: uuid baris; COUNT: uuid batch; VOID: uuid operasi void). */
        public String uuid = "";
        public String productUuid = "";      // FILL
        public int qty;                      // FILL
        public List<Count> counts = new ArrayList<>();   // COUNT
        public String targetUuid = "";       // VOID: uuid baris/batch yang dibatalkan
        /** Jam dinding (System.currentTimeMillis) saat diketuk. */
        public long createdAtMs;
        /**
         * {@code SystemClock.elapsedRealtime()} saat diketuk (0 = tak diketahui). Tahan terhadap
         * pengubahan jam HP/NTP, tapi mulai dari nol lagi setelah reboot — makanya umur diambil dari
         * yang TERBESAR dari keduanya ({@link #ageMs}).
         */
        public long createdElapsedMs;
        /** sync_uuid staf yang mengetuk: dikirim HANYA atas identitas ini (atribusi gaji/audit benar). */
        public String staffUuid = "";

        /**
         * Umur ketukan ini (ms) pada saat {@code nowWallMs}/{@code nowElapsedMs}: yang terbesar dari
         * selisih jam dinding (tak pernah negatif) dan selisih elapsedRealtime (hanya bila tak ada
         * reboot sejak diketuk). Memilih yang terbesar sengaja: menaksir umur TERLALU KECIL membuat
         * isi yang sudah ikut terhitung di hitung-stok berikutnya tercap sesudah jangkar → stok hantu;
         * terlalu besar hanya membuat catatan jatuh sebelum jangkar (aman: paling buruk depot isi lebih).
         */
        public long ageMs(long nowWallMs, long nowElapsedMs) {
            long wall = createdAtMs > 0 ? Math.max(0L, nowWallMs - createdAtMs) : 0L;
            long elapsed = (createdElapsedMs > 0 && nowElapsedMs >= createdElapsedMs)
                    ? nowElapsedMs - createdElapsedMs : 0L;
            return Math.max(wall, elapsed);
        }

        /** {@code age_seconds} yang dikirim ke server: umur dibulatkan ke bawah, dipotong 0..{@link #MAX_AGE_SECONDS}. */
        public int ageSeconds(long nowWallMs, long nowElapsedMs) {
            long s = ageMs(nowWallMs, nowElapsedMs) / 1000L;
            return (int) Math.max(0L, Math.min((long) MAX_AGE_SECONDS, s));
        }

        public boolean isExpired(long nowWallMs, long nowElapsedMs) {
            return ageMs(nowWallMs, nowElapsedMs) > EXPIRE_AFTER_MS;
        }

        /** Milik staf ini? Operasi lama tanpa staf dianggap milik siapa pun yang sedang login. */
        public boolean isOwnedBy(String staffUuid) {
            return staffUuid != null && (this.staffUuid.isEmpty() || this.staffUuid.equals(staffUuid));
        }

        public static Op fill(String staffUuid, String productUuid, int qty, long nowMs) {
            return fill(staffUuid, productUuid, qty, nowMs, 0L);
        }

        public static Op count(String staffUuid, List<Count> counts, long nowMs) {
            return count(staffUuid, counts, nowMs, 0L);
        }

        public static Op voidOf(String staffUuid, String targetUuid, long nowMs) {
            return voidOf(staffUuid, targetUuid, nowMs, 0L);
        }

        public static Op fill(String staffUuid, String productUuid, int qty, long nowMs, long elapsedMs) {
            Op o = new Op();
            o.createdElapsedMs = elapsedMs;
            o.staffUuid = staffUuid == null ? "" : staffUuid;
            o.type = TYPE_FILL;
            o.uuid = newUuid();
            o.productUuid = productUuid;
            o.qty = qty;
            o.createdAtMs = nowMs;
            return o;
        }

        public static Op count(String staffUuid, List<Count> counts, long nowMs, long elapsedMs) {
            Op o = new Op();
            o.createdElapsedMs = elapsedMs;
            o.staffUuid = staffUuid == null ? "" : staffUuid;
            o.type = TYPE_COUNT;
            o.uuid = newUuid();
            o.counts = new ArrayList<>(counts);
            o.createdAtMs = nowMs;
            return o;
        }

        public static Op voidOf(String staffUuid, String targetUuid, long nowMs, long elapsedMs) {
            Op o = new Op();
            o.createdElapsedMs = elapsedMs;
            o.staffUuid = staffUuid == null ? "" : staffUuid;
            o.type = TYPE_VOID;
            o.uuid = newUuid();
            o.targetUuid = targetUuid;
            o.createdAtMs = nowMs;
            return o;
        }
    }

    private final List<Op> ops = new ArrayList<>();

    public static String newUuid() {
        return UUID.randomUUID().toString();
    }

    public void add(Op op) {
        ops.add(op);
    }

    /** Buang operasi berdasarkan uuid-nya (dipanggil setelah server menerima / menolak permanen). */
    public boolean remove(String uuid) {
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).uuid.equals(uuid)) {
                ops.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Hanya operasi milik {@code staffUuid} (lihat {@link Op#isOwnedBy}); urutan dipertahankan. */
    public static List<Op> ownedBy(List<Op> all, String staffUuid) {
        List<Op> out = new ArrayList<>();
        for (Op o : all) if (o.isOwnedBy(staffUuid)) out.add(o);
        return out;
    }

    /** Buang operasi yang sudah kedaluwarsa ({@link Op#isExpired}); @return jumlah yang dibuang. */
    public int removeExpired(long nowWallMs, long nowElapsedMs) {
        int n = 0;
        for (int i = ops.size() - 1; i >= 0; i--) {
            if (ops.get(i).isExpired(nowWallMs, nowElapsedMs)) {
                ops.remove(i);
                n++;
            }
        }
        return n;
    }

    /** Salinan daftar (aman dibaca thread lain). */
    public List<Op> ops() {
        return new ArrayList<>(ops);
    }

    public int size() {
        return ops.size();
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    // ------------------------------------------------------------------- serialisasi

    private static final Gson GSON = new Gson();

    public String toJson() {
        JsonArray arr = new JsonArray();
        for (Op o : ops) {
            JsonObject j = new JsonObject();
            j.addProperty("type", o.type);
            j.addProperty("uuid", o.uuid);
            j.addProperty("createdAtMs", o.createdAtMs);
            j.addProperty("createdElapsedMs", o.createdElapsedMs);
            j.addProperty("staffUuid", o.staffUuid);
            if (TYPE_FILL.equals(o.type)) {
                j.addProperty("productUuid", o.productUuid);
                j.addProperty("qty", o.qty);
            } else if (TYPE_COUNT.equals(o.type)) {
                JsonArray cs = new JsonArray();
                for (Count c : o.counts) {
                    JsonObject cj = new JsonObject();
                    cj.addProperty("productUuid", c.productUuid);
                    cj.addProperty("qty", c.qty);
                    cs.add(cj);
                }
                j.add("counts", cs);
            } else if (TYPE_VOID.equals(o.type)) {
                j.addProperty("targetUuid", o.targetUuid);
            }
            arr.add(j);
        }
        return GSON.toJson(arr);
    }

    /** Toleran: teks kosong/rusak → kotak kosong (lebih baik kehilangan antrean rusak daripada crash). */
    public static FillOutbox fromJson(String json) {
        FillOutbox box = new FillOutbox();
        if (json == null || json.trim().isEmpty()) return box;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return box;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject j = e.getAsJsonObject();
                Op o = new Op();
                o.type = FillPlan.str(j, "type", "");
                o.uuid = FillPlan.str(j, "uuid", "");
                o.createdAtMs = FillPlan.lng(j, "createdAtMs", 0L);
                o.createdElapsedMs = FillPlan.lng(j, "createdElapsedMs", 0L);
                o.staffUuid = FillPlan.str(j, "staffUuid", "");
                if (o.uuid.isEmpty()) continue;
                if (TYPE_FILL.equals(o.type)) {
                    o.productUuid = FillPlan.str(j, "productUuid", "");
                    o.qty = FillPlan.num(j, "qty", 0);
                    if (o.productUuid.isEmpty() || o.qty <= 0) continue;
                } else if (TYPE_COUNT.equals(o.type)) {
                    JsonElement cs = j.get("counts");
                    if (cs == null || !cs.isJsonArray()) continue;
                    for (JsonElement ce : cs.getAsJsonArray()) {
                        if (!ce.isJsonObject()) continue;
                        JsonObject cj = ce.getAsJsonObject();
                        String pu = FillPlan.str(cj, "productUuid", "");
                        if (pu.isEmpty()) continue;
                        o.counts.add(new Count(pu, FillPlan.num(cj, "qty", 0)));
                    }
                    if (o.counts.isEmpty()) continue;
                } else if (TYPE_VOID.equals(o.type)) {
                    o.targetUuid = FillPlan.str(j, "targetUuid", "");
                    if (o.targetUuid.isEmpty()) continue;
                } else {
                    continue;
                }
                box.ops.add(o);
            }
        } catch (RuntimeException ignored) {
            // JSON rusak → kotak kosong
        }
        return box;
    }
}
