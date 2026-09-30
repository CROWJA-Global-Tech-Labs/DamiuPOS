package com.crowja.damiupos.pengisian;

import com.crowja.damiupos.sync.SyncApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Satu putaran sinkron layar Pengisian (panggil di thread latar): KIRIM dulu semua isi/hitung/batal
 * yang tertahan di kotak keluar, LALU ambil rencana terbaru. Dipanggil seketika setelah ketukan dan
 * pada tiap tick 15 detik.
 *
 * <p>Nasib operasi yang gagal diputuskan dari KODE di badan JSON server, bukan status HTTP telanjang
 * ({@link FillText#classifyWrite}): hanya kode penolakan permanen yang dikenal membuang operasinya
 * (dan dilaporkan di {@link Result#rejections}); penolakan IDENTITAS menyimpan operasinya, menghentikan
 * pengiriman putaran ini, dan menampilkan pesan server ({@link Result#blockedMessage}); semua yang
 * lain — tak ada sinyal, 5xx, 429, 401, halaman HTML CDN/WAF, 404 rute belum dideploy — sementara:
 * simpan & coba lagi. Server idempoten per uuid, jadi mengirim ulang operasi yang balasannya hilang aman.
 *
 * <p>Tiap isi/hitung dikirim dengan {@code age_seconds} = umur ketukan saat dikirim (server mencap
 * {@code logged_at = now() - age_seconds} dengan jamnya sendiri), supaya isi/hitung yang tertahan
 * offline tak tercap "sekarang" dan salah posisi terhadap jangkar hitung-stok.
 */
public final class PengisianSync {

    private PengisianSync() {}

    /** Sumber waktu (bisa disubstitusi pada uji). */
    public interface Clock {
        long wallMs();

        long elapsedMs();
    }

    /** Jalur jaringan; balasan berupa TEKS JSON supaya logika ini bisa diuji di JVM tanpa org.json. */
    public interface Transport {
        String plan(String rev, String staffUuid) throws Exception;

        String fill(String staffUuid, String uuid, String productUuid, int qty, int ageSeconds) throws Exception;

        String count(String staffUuid, String batchUuid, List<FillOutbox.Count> counts, int ageSeconds) throws Exception;

        String voidOf(String staffUuid, String targetUuid) throws Exception;
    }

    public static final class Result {
        /** JSON rencana LENGKAP terbaru (dari poll atau balasan tulis), atau null bila tak ada yang baru. */
        public String planJson;
        /** Server bilang rev sama → tampilan tak perlu dirakit ulang dari server. */
        public boolean unchanged;
        /** Poll gagal (jaringan/server) → tampilkan banner "offline – data lama". */
        public boolean offline;
        /** Server menolak IDENTITAS (peran bukan pengisian/admin, staf tak ada/nonaktif) → tampilkan pesannya. */
        public String blockedMessage;
        /** Pesan penolakan permanen atas ketukan pekerja (untuk toast). */
        public final List<String> rejections = new ArrayList<>();
        /** Masih ada operasi MILIK STAF INI tertahan di kotak keluar setelah putaran ini. */
        public boolean outboxLeft;
        /** Operasi yang dibuang putaran ini karena tertahan lebih dari 24 jam. */
        public int expired;
    }

    public static Transport over(final SyncApi api) {
        return new Transport() {
            @Override
            public String plan(String rev, String staffUuid) throws Exception {
                return api.fillPlan(rev, staffUuid).toString();
            }

            @Override
            public String fill(String staffUuid, String uuid, String productUuid, int qty, int ageSeconds)
                    throws Exception {
                return api.fillLog(staffUuid, uuid, productUuid, qty, ageSeconds).toString();
            }

            @Override
            public String count(String staffUuid, String batchUuid, List<FillOutbox.Count> counts,
                                int ageSeconds) throws Exception {
                JSONArray arr = new JSONArray();
                for (FillOutbox.Count c : counts) {
                    JSONObject o = new JSONObject();
                    o.put("product_uuid", c.productUuid);
                    o.put("qty", c.qty);
                    arr.put(o);
                }
                return api.fillCount(staffUuid, batchUuid, arr, ageSeconds).toString();
            }

            @Override
            public String voidOf(String staffUuid, String targetUuid) throws Exception {
                return api.fillVoid(staffUuid, targetUuid).toString();
            }
        };
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long wallMs() {
            return System.currentTimeMillis();
        }

        @Override
        public long elapsedMs() {
            return android.os.SystemClock.elapsedRealtime();
        }
    };

    public static Result run(SyncApi api, PengisianStore store, String staffUuid, String rev) {
        return run(over(api), store, staffUuid, rev, SYSTEM_CLOCK);
    }

    public static Result run(Transport api, PengisianStore store, String staffUuid, String rev, Clock clock) {
        Result res = new Result();
        String currentRev = rev;

        res.expired = store.pruneExpired(clock.wallMs(), clock.elapsedMs());

        // 1) Kirim kotak keluar (FIFO). Berhenti di kegagalan sementara / identitas pertama: urutan
        //    penting (hitung stok menimpa isi sebelumnya), jangan loncati operasi yang macet.
        for (FillOutbox.Op op : store.pending()) {
            // Ketukan milik staf lain (HP dipakai bergantian, shift lain belum sempat terkirim):
            // jangan dikirim atas nama orang ini — atribusi gaji/audit harus benar. Tetap di kotak
            // keluar (dibuang otomatis setelah 24 jam), dikirim bila pemiliknya login lagi, dan tak
            // menghalangi ketukan sendiri.
            if (!op.isOwnedBy(staffUuid)) continue;
            try {
                JsonObject r = requireOk(send(api, staffUuid, op, clock));
                store.remove(op.uuid);
                JsonElement plan = r.get("plan");
                if (plan != null && plan.isJsonObject()) {
                    res.planJson = plan.toString();
                    JsonElement rv = plan.getAsJsonObject().get("rev");
                    if (rv != null && rv.isJsonPrimitive()) currentRev = rv.getAsString();
                }
            } catch (SyncApi.SyncException se) {
                FillText.WriteVerdict v = FillText.classifyWrite(se.code, se.body);
                if (v == FillText.WriteVerdict.DROP) {
                    store.remove(op.uuid);
                    res.rejections.add(FillText.rejectionMessage(se.body, "Ditolak server (" + se.code + ")"));
                } else if (v == FillText.WriteVerdict.KEEP_BLOCKED) {
                    // Jangan hapus ketukannya: pesan server sendiri bilang "sinkronkan lalu coba lagi",
                    // dan peran/aktif bisa dipulihkan admin. Dicoba lagi di tick berikutnya.
                    res.blockedMessage = FillText.rejectionMessage(se.body, "Akses ditolak server (" + se.code + ")");
                    break;
                } else {
                    break;   // sementara: coba lagi di putaran berikutnya
                }
            } catch (Exception e) {
                break;       // tak ada sinyal, balasan bukan JSON ok (portal captive), dsb.
            }
        }
        for (FillOutbox.Op op : store.pending()) {
            if (op.isOwnedBy(staffUuid)) res.outboxLeft = true;
        }

        // 2) Ambil rencana (rev pendek-sirkuit bila tak berubah).
        try {
            JsonObject r = requireOk(api.plan(currentRev, staffUuid));
            if (r.has("unchanged") && r.get("unchanged").isJsonPrimitive()
                    && r.get("unchanged").getAsBoolean()) {
                res.unchanged = true;
            } else {
                res.planJson = r.toString();
                res.unchanged = false;
            }
            res.offline = false;
        } catch (SyncApi.SyncException se) {
            if (FillText.isIdentityRejection(se.body)) {
                res.blockedMessage = FillText.rejectionMessage(se.body, "Akses ditolak server (" + se.code + ")");
            } else {
                res.offline = true;
            }
        } catch (Exception e) {
            res.offline = true;
        }
        return res;
    }

    /** Balasan 2xx yang bukan JSON {@code ok:true} (halaman portal, badan kosong) BUKAN tanda server menerima. */
    private static JsonObject requireOk(String body) {
        JsonElement root = JsonParser.parseString(body == null ? "" : body);
        if (root == null || !root.isJsonObject()) throw new IllegalStateException("balasan bukan objek JSON");
        JsonObject o = root.getAsJsonObject();
        JsonElement ok = o.get("ok");
        if (ok == null || !ok.isJsonPrimitive() || !ok.getAsBoolean()) {
            throw new IllegalStateException("balasan tanpa ok:true");
        }
        return o;
    }

    private static String send(Transport api, String staffUuid, FillOutbox.Op op, Clock clock) throws Exception {
        switch (op.type) {
            case FillOutbox.TYPE_FILL:
                return api.fill(staffUuid, op.uuid, op.productUuid, op.qty,
                        op.ageSeconds(clock.wallMs(), clock.elapsedMs()));
            case FillOutbox.TYPE_COUNT:
                return api.count(staffUuid, op.uuid, op.counts,
                        op.ageSeconds(clock.wallMs(), clock.elapsedMs()));
            case FillOutbox.TYPE_VOID:
                return api.voidOf(staffUuid, op.targetUuid);
            default:
                // Tak mungkin dari FillOutbox.fromJson (jenis tak dikenal sudah disaring) — tapi bila
                // sampai sini, jangan macetkan antrean selamanya: perlakukan sebagai penolakan permanen.
                throw new SyncApi.SyncException(422,
                        "{\"ok\":false,\"code\":\"validation\",\"message\":\"operasi tak dikenal\"}");
        }
    }
}
