package com.crowja.damiupos.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "⏸ Antrean Tertunda" se-cabang — keputusan murni (tanpa Android/jaringan): urai jawaban
 * {@code GET /api/delivery/tertunda} dan gabungkan dengan baris TERTUNDA lokal
 * ({@code TransactionDao.getTertundaQueue}), plus pilihan jalur tulis tiap aksi kartu. Dipisah dari
 * {@code DeliveryQueueActivity} supaya bisa diuji di JVM — Gson (bukan org.json, yang di-stub pada
 * unit test) seperti {@link PhoneConflictPolicy}. Kontraknya dipatok fixture balasan ASLI server di
 * {@code src/test/resources/tertunda/} (TertundaContractFixtureTest).
 *
 * <p>Latar: pull transaksi per-perangkat, jadi tunda milik HP lain (asal HP lain, belum dirutekan,
 * bukan Pesanan Terbuka) tak pernah ada di DB lokal — layar lama yang membaca DB lokal saja
 * menampilkan 1 order sementara web menampilkan 13.
 *
 * <p>Aturan gabung ({@link #merge}):
 * <ul>
 *   <li>Server menjawab → server OTORITATIF: semua baris server (urutan server), masing-masing
 *       dipasangkan dengan salinan lokalnya bila uuid itu ada di HP ini; DITAMBAH baris lokal yang
 *       tak dikenal server HANYA bila belum terdorong (synced=0 — mis. ditunda luring). Baris lokal
 *       lain disembunyikan: salinan basi dari order yang sudah dilanjutkan/diubah di tempat lain
 *       (pull menyusul async) tak boleh muncul lagi.</li>
 *   <li>Server gagal / jawaban tak dipahami → baris lokal saja (pemanggil menandainya luring) —
 *       daftar tak pernah dikosongkan karena jaringan — KECUALI order yang di sesi ini sudah
 *       dinyatakan server lepas dari TERTUNDA ({@code released}): salinan lokalnya masih TERTUNDA
 *       sampai pull tiba dan tak boleh ditawarkan lagi.</li>
 * </ul>
 *
 * <p>Baris server TAK PERNAH diberi _id lokal: {@code id} di JSON adalah id SERVER, dan setiap
 * jalur ber-_id (resume/reschedule DAO, struk, alokasi, ubah/void) akan mengenai baris lokal LAIN
 * yang kebetulan ber-_id sama. Karena itu baris server dimodelkan terpisah ({@link Row}); salinan
 * lokal hanya terpasang lewat uuid.
 */
public final class TertundaRows {

    private TertundaRows() {
    }

    /** Satu baris server — bentuk kartu {@code Reports::shapeQueueRow} + kolom tambahan endpoint ini. */
    public static final class Row {
        /** JSON mentah baris ini (dipakai ulang Activity lewat org.json, mis. teks detail). */
        public final String json;
        public final String uuid;
        public final String name;
        public final String receiptNo;
        public final String customerUuid;
        public final String phone;
        public final String address;
        public final String destName;
        public final String note;
        public final String items;
        public final String orderedAt;        // mentah (ISO-UTC dari server) — tampilkan lewat Ts
        public final String sourceWa;
        public final String type;             // JUAL | KEMBALI | …
        public final String paymentMethod;
        /** Perangkat penanggung jawab efektif (rute ?: asal) — "web" untuk order web tanpa rute,
         *  "__none__" bila belum ditugaskan. */
        public final String deviceGroupUuid;
        public final String deviceGroupLabel;
        /** delivery_device_uuid mentah ("" = belum dirutekan, tetap di HP asal). */
        public final String routedUuid;
        /** Jadwal lanjut / saat ditunda / saat benar-benar kembali ke antrean (jam buka cabang pada
         *  hari jadwal) — semuanya waktu LOKAL "yyyy-MM-dd HH:mm:ss", "" bila tak ada. */
        public final String resumeAtLocal;
        public final String tertundaAtLocal;
        public final String dueAtLocal;
        public final String tertundaReason;
        public final String tertundaPhotoUrl;
        public final int galon;
        public final int checkoutSeq;
        public final int checkoutSize;
        public final double total;
        public final double ongkir;
        /** 0,0 = tak ada koordinat. */
        public final double latitude;
        public final double longitude;
        /** Milik antrean perangkat PEMANGGIL (device_group_uuid == uuid HP ini, dihitung server). */
        public final boolean mine;
        /** 🎲 Pesanan Terbuka yang sedang dijeda (stempel terisi DAN belum diklaim — predikat server). */
        public final boolean openDispatch;
        public final boolean inProgress;
        public final boolean voidPending;
        public final boolean chatSession;
        public final boolean pickupOnly;

        Row(JsonObject o) {
            this.json = o.toString();
            this.uuid = str(o, "uuid");
            String n = str(o, "name");
            this.name = n.isEmpty() ? "Umum" : n;
            this.receiptNo = str(o, "receipt_no");
            this.customerUuid = str(o, "customer_uuid");
            this.phone = str(o, "phone");
            this.address = str(o, "address");
            this.destName = str(o, "dest_name");
            this.note = str(o, "note");
            this.items = str(o, "items");
            this.orderedAt = str(o, "ordered_at");
            this.sourceWa = str(o, "source_wa");
            this.type = str(o, "type");
            this.paymentMethod = str(o, "payment_method");
            this.deviceGroupUuid = str(o, "device_group_uuid");
            this.deviceGroupLabel = str(o, "device_group_label");
            this.routedUuid = str(o, "routed_uuid");
            this.resumeAtLocal = localOr(str(o, "resume_at_local"), str(o, "resume_at"));
            this.tertundaAtLocal = localOr(str(o, "tertunda_at_local"), str(o, "tertunda_at"));
            this.dueAtLocal = localOr(str(o, "due_at_local"), "");
            this.tertundaReason = str(o, "tertunda_reason");
            this.tertundaPhotoUrl = str(o, "tertunda_photo_url");
            this.galon = (int) num(o, "galon");
            JsonObject co = o.has("checkout") && o.get("checkout").isJsonObject()
                    ? o.getAsJsonObject("checkout") : null;
            this.checkoutSeq = (int) (co != null ? num(co, "seq") : num(o, "checkout_seq"));
            this.checkoutSize = (int) (co != null ? num(co, "size") : num(o, "checkout_size"));
            this.total = num(o, "total");
            this.ongkir = num(o, "ongkir");
            this.latitude = num(o, "latitude");
            this.longitude = num(o, "longitude");
            this.mine = bool(o, "mine");
            this.openDispatch = bool(o, "open_dispatch");
            this.inProgress = bool(o, "in_progress");
            this.voidPending = bool(o, "void_pending");
            this.chatSession = bool(o, "chat_session");
            this.pickupOnly = bool(o, "pickup_only") || "KEMBALI".equals(this.type);
        }

        /** Sudah di antrean perangkat {@code deviceUuid} DAN dirutekan ke sana — "📥 Lanjutkan &amp;
         *  Ambil" tak mengubah apa pun, cukup "▶ Lanjutkan". */
        public boolean mineAndRoutedTo(String deviceUuid) {
            return mine && deviceUuid != null && !deviceUuid.isEmpty() && deviceUuid.equals(routedUuid);
        }

        /** Tak ada perangkat BERNAMA yang memegangnya: order web tanpa rute ("web") atau belum
         *  ditugaskan ("__none__"). Saat dilanjutkan server memilihkan tujuan (wilayah / terbuka). */
        public boolean unassigned() {
            return deviceGroupUuid.isEmpty() || "web".equals(deviceGroupUuid) || "__none__".equals(deviceGroupUuid);
        }

        /** Label pemegang di kartu ("" = tak ada yang perlu ditampilkan). */
        public String ownerLabel() {
            if (openDispatch) return "🎲 Pesanan Terbuka";
            if (mine) return "📱 HP ini";
            if (unassigned()) return "🌐 " + (deviceGroupLabel.isEmpty() ? "Belum Ditugaskan" : deviceGroupLabel);
            return deviceGroupLabel.isEmpty() ? "" : "📱 " + deviceGroupLabel;
        }
    }

    /** Hasil penguraian {@code GET /api/delivery/tertunda}. */
    public static final class Result {
        /** false = server tak bisa dipakai (gagal jaringan / non-2xx / badan tak dipahami). */
        public final boolean ok;
        public final List<Row> rows;
        public final int count;
        public final String serverTime;

        Result(boolean ok, List<Row> rows, int count, String serverTime) {
            this.ok = ok;
            this.rows = Collections.unmodifiableList(rows);
            this.count = count;
            this.serverTime = serverTime == null ? "" : serverTime;
        }

        /** uuid semua baris server (kosong bila !ok). */
        public Set<String> uuids() {
            Set<String> out = new HashSet<>();
            for (Row r : rows) if (!r.uuid.isEmpty()) out.add(r.uuid);
            return out;
        }
    }

    /** Server tak menjawab / gagal → pemanggil jatuh ke baris lokal saja. */
    public static Result unavailable() {
        return new Result(false, new ArrayList<Row>(), 0, "");
    }

    /**
     * Urai badan 200 {@code {"tertunda":[row…],"count":N,"server_time":"…"}}. Toleran: kunci yang
     * hilang/null/"null" jadi ""/0/false, angka boleh berupa string. Badan rusak / bukan objek /
     * {@code tertunda} bukan array → {@link #unavailable()} (jangan menyatakan "kosong" atas jawaban
     * yang tak dipahami — itu akan menyembunyikan baris lokal). Uuid ganda dibuang (yang pertama menang).
     */
    public static Result parse(String body) {
        if (body == null || body.trim().isEmpty()) return unavailable();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return unavailable();
            JsonObject o = root.getAsJsonObject();
            JsonElement arr = o.get("tertunda");
            if (arr == null || !arr.isJsonArray()) return unavailable();
            List<Row> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement e : arr.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                Row r = new Row(e.getAsJsonObject());
                if (!r.uuid.isEmpty() && !seen.add(r.uuid)) continue;
                rows.add(r);
            }
            JsonElement c = o.get("count");
            int count = c != null && c.isJsonPrimitive() ? (int) num(o, "count") : rows.size();
            return new Result(true, rows, count, str(o, "server_time"));
        } catch (RuntimeException e) {   // JsonSyntaxException, IllegalStateException, dsb.
            return unavailable();
        }
    }

    // ------------------------------------------------------------------ gabung lokal + server

    /** Satu baris TERTUNDA lokal, dibungkus untuk {@link #merge}. */
    public static final class Local<T> {
        public final T item;
        public final String uuid;
        /** false = ada perubahan yang belum terdorong ke server (kolom synced=0). */
        public final boolean synced;

        public Local(T item, String uuid, boolean synced) {
            this.item = item;
            this.uuid = uuid == null || "null".equals(uuid) ? "" : uuid.trim();
            this.synced = synced;
        }
    }

    /** Pencari salinan lokal untuk uuid baris server yang TAK ada di daftar tertunda lokal (mis.
     *  salinan lokalnya belum ikut berstatus TERTUNDA karena pull belum tiba). null = tak ada. */
    public interface LocalLookup<T> {
        T find(String uuid);
    }

    /** Satu kartu daftar gabungan. Minimal salah satu dari {@code server}/{@code local} terisi. */
    public static final class Merged<T> {
        /** Baris server; null = hanya ada di HP ini (luring, atau belum terdorong). */
        public final Row server;
        /** Salinan lokal (punya _id lokal ASLI); null = hanya di server — jangan pakai jalur ber-_id. */
        public final T local;
        private final String localUuid;

        Merged(Row server, T local, String localUuid) {
            this.server = server;
            this.local = local;
            this.localUuid = localUuid == null ? "" : localUuid;
        }

        /** uuid transaksi: dari server bila ada, else sync_uuid lokal ("" = belum punya identitas). */
        public String uuid() {
            return server != null ? server.uuid : localUuid;
        }

        public boolean isRemoteOnly() {
            return local == null;
        }
    }

    /** {@link #merge(Result, List, LocalLookup, Collection)} tanpa daftar "sudah dilepas". */
    public static <T> List<Merged<T>> merge(Result server, List<Local<T>> locals, LocalLookup<T> lookup) {
        return merge(server, locals, lookup, null);
    }

    /**
     * Gabungkan daftar server dengan baris TERTUNDA lokal (lihat aturan di dokumen kelas).
     *
     * @param server   hasil {@link #parse}; null/!ok = luring → baris lokal saja
     * @param locals   baris TERTUNDA lokal (urutan dipertahankan; uuid ganda dibuang)
     * @param lookup   opsional — dipanggil hanya untuk uuid server yang tak ada di {@code locals}
     * @param released opsional — uuid yang di sesi ini dinyatakan server SUDAH lepas dari TERTUNDA
     *                 (dilanjutkan / tak ada lagi); disembunyikan dari daftar LURING saja — saat
     *                 server menjawab, daftarnya yang berlaku
     */
    public static <T> List<Merged<T>> merge(Result server, List<Local<T>> locals, LocalLookup<T> lookup,
                                            Collection<String> released) {
        List<Merged<T>> out = new ArrayList<>();
        List<Local<T>> uniq = new ArrayList<>();
        Map<String, Local<T>> byUuid = new HashMap<>();
        if (locals != null) {
            for (Local<T> l : locals) {
                if (l == null || l.item == null) continue;
                if (!l.uuid.isEmpty()) {
                    if (byUuid.containsKey(l.uuid)) continue;
                    byUuid.put(l.uuid, l);
                }
                uniq.add(l);
            }
        }

        if (server == null || !server.ok) {
            for (Local<T> l : uniq) {
                if (released != null && !l.uuid.isEmpty() && released.contains(l.uuid)) continue;
                out.add(new Merged<T>(null, l.item, l.uuid));
            }
            return out;
        }

        Set<String> serverUuids = new HashSet<>();
        for (Row r : server.rows) {
            T loc = null;
            if (!r.uuid.isEmpty()) {
                serverUuids.add(r.uuid);
                Local<T> l = byUuid.get(r.uuid);
                if (l != null) {
                    loc = l.item;
                } else if (lookup != null) {
                    loc = lookup.find(r.uuid);
                }
            }
            out.add(new Merged<T>(r, loc, r.uuid));
        }
        for (Local<T> l : uniq) {
            if (!l.uuid.isEmpty() && serverUuids.contains(l.uuid)) continue;   // sudah terpasang
            if (l.synced) continue;   // server tak lagi mencantumkannya → salinan basi, sembunyikan
            out.add(new Merged<T>(null, l.item, l.uuid));
        }
        return out;
    }

    /** Daftar server terbaru mencantumkan uuid itu lagi (ditunda ulang di tempat lain) → bukan lagi
     *  "sudah dilepas". Panggil hanya dengan jawaban server TERBARU. */
    public static void forgetReleasedListedBy(Set<String> released, Result server) {
        if (released == null || server == null || !server.ok || released.isEmpty()) return;
        released.removeAll(server.uuids());
    }

    // ------------------------------------------------------------------ jalur tulis aksi kartu

    /** Ke mana "▶ Lanjutkan" / "🕒 Jadwalkan Ulang" satu kartu dikirim. */
    public enum WritePath {
        /** Endpoint server ber-prasyarat status (satu jalur tulis, efek samping sama dengan web). */
        SERVER,
        /** DAO lokal lama (syncUpdate, terdorong lewat sinkron biasa) — hanya luring/belum terdorong. */
        LOCAL,
        /** Tak bisa: baris server-saja sementara perangkat tak terhubung. */
        NONE
    }

    /**
     * Pilih jalur tulis satu kartu.
     *
     * @param enrolled         perangkat terhubung server (punya token + URL)
     * @param offline          daftar yang tampil adalah fallback LURING (server gagal dijangkau)
     * @param uuid             uuid transaksi ("" = baris lokal yang belum punya identitas server)
     * @param serverRow        kartu punya baris server
     * @param localRow         kartu punya salinan lokal ber-_id
     * @param localSyncPending salinan lokal punya perubahan yang belum terdorong (synced=0)
     * @param serverTouched    server SUDAH mengubah order ini di sesi ini (aksi 2xx / galat basi) —
     *                         salinan lokalnya basi sampai pull tiba; DAO lokal akan mendorong seluruh
     *                         baris itu dengan edited_at=sekarang dan menang last-write-wins di server
     *                         (mis. membalikkan order yang baru dilanjutkan jadi TERTUNDA lagi)
     */
    public static WritePath writePath(boolean enrolled, boolean offline, String uuid, boolean serverRow,
                                      boolean localRow, boolean localSyncPending, boolean serverTouched) {
        boolean hasUuid = uuid != null && !uuid.trim().isEmpty();
        if (enrolled && hasUuid && serverTouched) return WritePath.SERVER;   // luring → gagal jaringan, aman
        if (enrolled && hasUuid && !offline && (serverRow || (localRow && !localSyncPending))) {
            return WritePath.SERVER;
        }
        return localRow ? WritePath.LOCAL : WritePath.NONE;
    }

    /** "📥 Lanjutkan &amp; Ambil": hanya perangkat delivery (server menolak klaim dari HP lain), bukan
     *  order yang sudah di antrean HP ini DAN dirutekan ke sini (cukup ▶ Lanjutkan), bukan yang
     *  sedang diantar. */
    public static boolean canClaim(Row s, boolean enrolled, boolean deliveryDevice, String deviceUuid) {
        if (s == null || s.uuid.isEmpty() || s.inProgress) return false;
        return enrolled && deliveryDevice && !s.mineAndRoutedTo(deviceUuid);
    }

    /** 404/409/422 = order sudah berubah di tempat lain (dilanjutkan/diambil/pindah/hilang) — tutup
     *  &amp; segarkan, bukan galat yang layak diulang. */
    public static boolean isStaleCode(int httpCode) {
        return httpCode == 404 || httpCode == 409 || httpCode == 422;
    }

    /**
     * Kalimat konfirmasi "▶ Lanjutkan" tentang KE MANA order pergi — selaras dengan perutean server
     * ({@code TertundaRelease::applyResumeRouting}, berlaku untuk SEMUA baris termasuk milik HP ini):
     * Pesanan Terbuka yang dijeda tetap terbuka; order tanpa perangkat bernama (web/belum ditugaskan)
     * ke perangkat wilayah yang berawak atau dibuka; selain itu ke pemegangnya, dibuka bila di
     * perangkat itu belum ada staf absen. Kepastiannya ada di pesan balasan server.
     */
    public static String resumeDestinationHint(Row s) {
        if (s == null) {
            return "Server menentukan tujuannya: antrean pemegang order, atau 🎲 Pesanan Terbuka bila belum ada staf absen di perangkat itu.";
        }
        if (s.openDispatch) {
            return "Order kembali sebagai 🎲 Pesanan Terbuka — bisa diambil perangkat mana pun.";
        }
        if (s.unassigned()) {
            return "Order ini belum punya perangkat tujuan: server menugaskannya ke perangkat wilayah pelanggan yang sedang ada staf absen, atau membukanya sebagai 🎲 Pesanan Terbuka.";
        }
        String where = s.mine ? "HP ini" : "perangkat itu";
        String queue = s.mine ? "antrean HP ini" : "antrean \"" + s.deviceGroupLabel + "\"";
        return "Order kembali ke " + queue + ". Bila belum ada staf absen di " + where
                + ", server membukanya sebagai 🎲 Pesanan Terbuka.";
    }

    // ------------------------------------------------------------------ "📥 Lanjutkan & Ambil" (2 langkah)

    /** Balasan {@code POST /api/delivery/tertunda/resume} 200. */
    public static final class ResumeOutcome {
        public final String message;
        /** Perangkat tujuan efektif setelah dilanjutkan ("" = tak ada / Pesanan Terbuka). */
        public final String routedUuid;
        public final String routedName;
        public final boolean openDispatch;

        ResumeOutcome(String message, String routedUuid, String routedName, boolean openDispatch) {
            this.message = message;
            this.routedUuid = routedUuid;
            this.routedName = routedName;
            this.openDispatch = openDispatch;
        }
    }

    /** Urai balasan 200 resume; badan tak dipahami → hasil kosong (pesan "", tanpa tujuan) — order
     *  TETAP sudah dilanjutkan (HTTP 2xx), hanya tujuannya tak diketahui. */
    public static ResumeOutcome parseResume(String body) {
        try {
            JsonElement root = body == null ? null : JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return new ResumeOutcome("", "", "", false);
            JsonObject o = root.getAsJsonObject();
            JsonElement rd = o.get("routed_device");
            JsonObject dev = rd != null && rd.isJsonObject() ? rd.getAsJsonObject() : null;
            return new ResumeOutcome(str(o, "message"), dev != null ? str(dev, "uuid") : "",
                    dev != null ? str(dev, "name") : "", bool(o, "open_dispatch"));
        } catch (RuntimeException e) {
            return new ResumeOutcome("", "", "", false);
        }
    }

    /**
     * "📥 Lanjutkan &amp; Ambil" = {@code /tertunda/resume} (efek samping web: struk email, agregat
     * pelanggan, notifikasi — jalur claim resume=true tak menjalankannya) LALU klaim biasa atas order
     * yang kini PENDING. Ini nilai {@code expected_device_uuid} untuk langkah klaim, dari tujuan yang
     * DILAPORKAN resume: Pesanan Terbuka / tanpa tujuan → "" (belum dirutekan); perangkat lain → uuid-nya
     * (server menyamakan "rute kosong + asal = expected" dengan belum dirutekan). null = LEWATI klaim:
     * order sudah kembali ke antrean HP ini sendiri (klaim akan ditolak "sudah di antrian Anda").
     */
    public static String claimExpectedAfterResume(ResumeOutcome r, String myDeviceUuid) {
        if (r == null || r.openDispatch || r.routedUuid.isEmpty()) return "";
        if (myDeviceUuid != null && !myDeviceUuid.isEmpty() && myDeviceUuid.equals(r.routedUuid)) return null;
        return r.routedUuid;
    }

    // ------------------------------------------------------------------ pembantu Gson

    /** "" untuk kunci hilang / null / bukan primitif / literal "null". */
    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        String v = e.getAsString().trim();
        return "null".equals(v) ? "" : v;
    }

    /** 0 untuk kunci hilang / null / bukan angka; string angka ("-7.12") diterima. */
    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            double v = e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.parseDouble(e.getAsString().trim());
            return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** true / 1 / "1" / "true" → true; selain itu false. */
    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if (e.getAsJsonPrimitive().isNumber()) return num(o, k) != 0;
        String v = e.getAsString().trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /** Nilai lokal dari server bila ada; else normalkan fallback mentah (ISO-UTC) lewat {@link Ts}. */
    private static String localOr(String local, String raw) {
        if (!local.isEmpty()) {
            String v = Ts.local(local);
            return v.isEmpty() ? local : v;
        }
        return raw.isEmpty() ? "" : Ts.local(raw);
    }
}
