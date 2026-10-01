package com.crowja.damiupos.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keputusan murni (tanpa Android/jaringan) untuk guard "satu nomor HP = satu pelanggan aktif" di
 * form pelanggan: nomor mana yang perlu dicek, bentuk permintaan {@code POST /api/customers/phone-check},
 * pembacaan jawaban server, dan teks dialog pemblokir. Dipisah dari {@code CustomerFormActivity}
 * supaya bisa diuji di JVM — Gson (bukan org.json, yang di-stub pada unit test) seperti paket
 * {@code pengisian}.
 *
 * <p>Aturan (selaras server): nomor tanpa digit / kanonik &lt; {@link PhoneUtils#MIN_CANONICAL_DIGITS}
 * (placeholder) tak pernah memblokir. Mode EDIT hanya memeriksa nomor BARU atau BERUBAH, supaya
 * pelanggan lama yang sudah terlanjur berbagi nomor tetap bisa disunting untuk hal lain.
 *
 * <p>Keterbatasan yang DITERIMA (tanpa perubahan perilaku): (1) HP tak mengenal {@code split_key}
 * ("Pisahkan Pelanggan" — kolom itu tak disinkronkan ke HP), jadi pengecualian server untuk dua orang
 * yang sengaja dipisah tak berlaku di HP: cek lokal maupun server memblokir nomor yang di web masih
 * boleh. (2) Cek lokal berjalan DULU tanpa konfirmasi server, jadi salinan lokal yang basi (pelanggan
 * sudah dihapus/nomor sudah dilepas di dashboard tetapi HP belum menarik) bisa memblokir sampai sinkron
 * berikutnya. Keduanya sempit dan hilang setelah sinkron; mengatasinya butuh kolom {@code split_key}
 * di skema HP / alur "tetap simpan" — di luar lingkup guard ini.
 */
public final class PhoneConflictPolicy {

    private PhoneConflictPolicy() {
    }

    /** Batas kontrak server: 1..15 nomor, tiap nomor &le; 40 karakter. */
    public static final int MAX_PHONES = 15;
    public static final int MAX_PHONE_LENGTH = 40;

    /** Satu bentrokan: nomor yang diketik, dan siapa pemegang aktifnya. */
    public static final class Conflict {
        public final String phone;       // nomor yang diketik/diperiksa
        public final String canonical;   // bagian nasional (dari server; bisa kosong untuk cek lokal)
        public final String holderName;
        public final String holderUuid;
        public final String holderPhone;
        /** "primary" | "secondary" | "" (tak diketahui — cek lokal). */
        public final String holderRole;
        /** true = dilaporkan SERVER; false = cek lokal. */
        public final boolean atServer;

        public Conflict(String phone, String canonical, String holderName, String holderUuid,
                        String holderPhone, String holderRole, boolean atServer) {
            this.phone = phone == null ? "" : phone;
            this.canonical = canonical == null ? "" : canonical;
            this.holderName = holderName;
            this.holderUuid = holderUuid == null ? "" : holderUuid;
            this.holderPhone = holderPhone == null ? "" : holderPhone;
            this.holderRole = holderRole == null ? "" : holderRole;
            this.atServer = atServer;
        }
    }

    /** Hasil pertanyaan ke server. */
    public enum Verdict {
        /** Server menjawab: tak ada pemegang aktif → lanjut simpan. */
        FREE,
        /** Server menjawab: ada pemegang aktif → blokir. */
        BLOCKED,
        /** Server tak bisa menjawab (offline/timeout/401/429/5xx/respons rusak) → lanjut dengan cek
         *  lokal saja (best-effort; HP di lapangan tak boleh terkunci jaringan). */
        UNAVAILABLE
    }

    /** Hasil penguraian jawaban server. */
    public static final class ServerResult {
        public final Verdict verdict;
        public final List<Conflict> conflicts;

        ServerResult(Verdict verdict, List<Conflict> conflicts) {
            this.verdict = verdict;
            this.conflicts = conflicts;
        }

        public Conflict first() {
            return conflicts.isEmpty() ? null : conflicts.get(0);
        }
    }

    // ------------------------------------------------------------------ nomor mana yang dicek

    /**
     * Nomor yang HARUS dicek terhadap pelanggan lain.
     *
     * @param before nomor tersimpan pelanggan ini (kosong/null untuk pelanggan baru)
     * @param after  nomor yang akan disimpan (utama dulu, lalu "Nomor lain")
     * @param create true = pelanggan baru → semua nomor; false = edit → hanya yang BARU atau
     *               BERUBAH (dibandingkan kanonik). Nomor lama yang dijadikan nomor UTAMA dihitung
     *               berubah (menukar urutan juga mengubah identitas utama).
     * @return nomor (apa adanya dari {@code after}), unik per kanonik, maks {@link #MAX_PHONES}
     */
    public static List<String> numbersToCheck(List<String> before, List<String> after, boolean create) {
        List<String> out = new ArrayList<>();
        if (after == null) return out;
        Set<String> oldSet = new HashSet<>();
        String oldPrimary = "";
        if (!create && before != null) {
            for (int i = 0; i < before.size(); i++) {
                String c = PhoneUtils.canonical(before.get(i));
                if (c.isEmpty()) continue;
                oldSet.add(c);
                if (oldPrimary.isEmpty()) oldPrimary = c;
            }
        }
        Set<String> seen = new HashSet<>();
        boolean first = true;
        for (String p : after) {
            if (p == null) continue;
            String t = p.trim();
            String c = PhoneUtils.canonical(t);
            boolean isPrimarySlot = first && !c.isEmpty();
            if (!c.isEmpty()) first = false;
            if (c.length() < PhoneUtils.MIN_CANONICAL_DIGITS) continue;   // placeholder: tak pernah blokir
            if (!seen.add(c)) continue;
            if (!create) {
                boolean isNew = !oldSet.contains(c);
                boolean becamePrimary = isPrimarySlot && !c.equals(oldPrimary);
                if (!isNew && !becamePrimary) continue;
            }
            if (t.length() > MAX_PHONE_LENGTH) t = t.substring(0, MAX_PHONE_LENGTH);
            out.add(t);
            if (out.size() >= MAX_PHONES) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ kontrak phone-check

    /** Badan JSON {@code {"phones":[…],"exclude_uuid":"…"}}; {@code exclude_uuid} dihilangkan bila kosong. */
    public static String buildRequestJson(List<String> phones, String excludeUuid) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        if (phones != null) {
            for (String p : phones) arr.add(p);
        }
        root.add("phones", arr);
        if (excludeUuid != null && !excludeUuid.trim().isEmpty()) {
            root.addProperty("exclude_uuid", excludeUuid.trim());
        }
        return root.toString();
    }

    /**
     * Urai jawaban 200 server {@code {"ok":true,"conflicts":[{phone,canonical,customer:{uuid,name,phone,role}}]}}.
     * Toleran: field hilang/null tak membuat crash. Badan rusak / bukan JSON / {@code ok} bukan true /
     * {@code conflicts} bukan array → {@link Verdict#UNAVAILABLE} (jangan memblokir atas jawaban yang
     * tak dipercaya, dan jangan menyatakan "bebas" atas jawaban yang tak dipahami).
     *
     * @param excludeUuid pelanggan yang sedang diedit; bentrokan dengan dirinya sendiri diabaikan
     *                    (pagar ganda bila server tak mengecualikannya)
     */
    public static ServerResult parseResponse(String json, String excludeUuid) {
        List<Conflict> none = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return new ServerResult(Verdict.UNAVAILABLE, none);
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return new ServerResult(Verdict.UNAVAILABLE, none);
            JsonObject o = root.getAsJsonObject();
            if (!bool(o, "ok")) return new ServerResult(Verdict.UNAVAILABLE, none);
            JsonElement cs = o.get("conflicts");
            if (cs == null || !cs.isJsonArray()) return new ServerResult(Verdict.UNAVAILABLE, none);
            List<Conflict> out = new ArrayList<>();
            for (JsonElement e : cs.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject co = e.getAsJsonObject();
                JsonObject cust = co.has("customer") && co.get("customer").isJsonObject()
                        ? co.getAsJsonObject("customer") : new JsonObject();
                String uuid = str(cust, "uuid");
                if (excludeUuid != null && !excludeUuid.isEmpty() && excludeUuid.equalsIgnoreCase(uuid)) {
                    continue;   // dirinya sendiri bukan bentrokan
                }
                out.add(new Conflict(str(co, "phone"), str(co, "canonical"), str(cust, "name"), uuid,
                        str(cust, "phone"), str(cust, "role"), true));
            }
            return new ServerResult(out.isEmpty() ? Verdict.FREE : Verdict.BLOCKED, out);
        } catch (RuntimeException e) {   // JsonSyntaxException, IllegalStateException, dsb.
            return new ServerResult(Verdict.UNAVAILABLE, none);
        }
    }

    /** Server menjawab non-2xx / jaringan putus → tak bisa dipakai memutuskan: lanjut cek lokal saja. */
    public static ServerResult unavailable() {
        return new ServerResult(Verdict.UNAVAILABLE, new ArrayList<Conflict>());
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ teks dialog

    /** Teks dialog pemblokir untuk pemegang aktif (lokal maupun server). Tanpa nama → "(tanpa nama)". */
    public static String blockedMessage(Conflict c, boolean editing) {
        String name = c.holderName == null || c.holderName.trim().isEmpty() ? "(tanpa nama)" : c.holderName.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("Nomor ").append(c.phone).append(" sudah terdaftar atas nama:\n\n• ").append(name);
        if ("primary".equals(c.holderRole)) sb.append(" (nomor utama)");
        else if ("secondary".equals(c.holderRole)) sb.append(" (nomor tambahan)");
        if (c.atServer) {
            sb.append("\n\nTercatat di server — pelanggan itu mungkin belum muncul di HP ini.");
        }
        if (editing) {
            sb.append("\n\nPerubahan diblokir — hapus atau ganti nomor ini pada pelanggan yang sedang diedit, "
                    + "atau gunakan pelanggan yang sudah ada (cari di daftar Pelanggan).");
        } else {
            sb.append("\n\nPenambahan diblokir — gunakan pelanggan yang sudah ada "
                    + "(cari di daftar Pelanggan), atau perbaiki nomornya bila memang orang berbeda.");
        }
        return sb.toString();
    }
}
