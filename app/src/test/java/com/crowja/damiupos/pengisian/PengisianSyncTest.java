package com.crowja.damiupos.pengisian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.sync.SyncApi;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Putaran sinkron Pengisian: nasib operasi yang gagal diputuskan dari KODE di badan JSON (bukan status
 * HTTP), ketukan staf lain tak dikirim & tak ikut overlay, kedaluwarsa 24 jam, dan {@code age_seconds}
 * dihitung saat kirim. Jalur jaringan & penyimpanan diganti palsu (JVM murni).
 */
public class PengisianSyncTest {

    private static final long T0 = 1_780_000_000_000L;

    /** Transport palsu: skrip jawaban per jenis operasi, dan catat apa yang dikirim. */
    private static final class FakeTransport implements PengisianSync.Transport {
        final List<String> sent = new ArrayList<>();
        final List<Integer> ages = new ArrayList<>();
        final List<String> staffs = new ArrayList<>();
        SyncApi.SyncException writeError;       // dilempar pada setiap tulis bila diisi
        Exception writeFailure;                 // galat jaringan umum
        String writeBody = "{\"ok\":true,\"plan\":{\"rev\":\"r2\",\"products\":[]}}";
        SyncApi.SyncException planError;
        String planBody = "{\"ok\":true,\"rev\":\"r2\",\"unchanged\":true}";
        String lastPlanRev;

        @Override public String plan(String rev, String staffUuid) throws Exception {
            lastPlanRev = rev;
            if (planError != null) throw planError;
            return planBody;
        }

        private String write(String tag, String staffUuid, int age) throws Exception {
            sent.add(tag);
            ages.add(age);
            staffs.add(staffUuid);
            if (writeError != null) throw writeError;
            if (writeFailure != null) throw writeFailure;
            return writeBody;
        }

        @Override public String fill(String staffUuid, String uuid, String productUuid, int qty, int ageSeconds) throws Exception {
            return write("FILL:" + uuid, staffUuid, ageSeconds);
        }

        @Override public String count(String staffUuid, String batchUuid, List<FillOutbox.Count> counts, int ageSeconds) throws Exception {
            return write("COUNT:" + batchUuid, staffUuid, ageSeconds);
        }

        @Override public String voidOf(String staffUuid, String targetUuid) throws Exception {
            return write("VOID:" + targetUuid, staffUuid, -1);
        }
    }

    private static final class MemBacking implements PengisianStore.Backing {
        final Map<String, String> m = new HashMap<>();
        @Override public String get(String key, String def) { return m.containsKey(key) ? m.get(key) : def; }
        @Override public void set(String key, String value) { m.put(key, value); }
    }

    private static final class FakeClock implements PengisianSync.Clock {
        long wall = T0, elapsed = 50_000L;
        @Override public long wallMs() { return wall; }
        @Override public long elapsedMs() { return elapsed; }
        void advance(long ms) { wall += ms; elapsed += ms; }
    }

    private PengisianStore store;
    private FakeTransport net;
    private FakeClock clock;

    @Before public void setUp() {
        store = new PengisianStore(new MemBacking());
        net = new FakeTransport();
        clock = new FakeClock();
    }

    private FillOutbox.Op enqueueFill(String staff, int qty) {
        FillOutbox.Op op = FillOutbox.Op.fill(staff, "p-1", qty, clock.wall, clock.elapsed);
        store.enqueue(op);
        return op;
    }

    private static SyncApi.SyncException err(int http, String body) {
        return new SyncApi.SyncException(http, body);
    }

    private PengisianSync.Result run(String staff) {
        return PengisianSync.run(net, store, staff, "r1", clock);
    }

    // ------------------------------------------------------------------ happy path

    @Test public void successfulFlushRemovesOpAndTakesPlanFromReply() {
        enqueueFill("s-1", 5);
        PengisianSync.Result r = run("s-1");
        assertEquals(1, net.sent.size());
        assertTrue(store.pending().isEmpty());
        assertFalse(r.outboxLeft);
        assertNotNull(r.planJson);
        assertEquals("r2", net.lastPlanRev);          // rev dari balasan tulis dipakai untuk poll berikutnya
        assertTrue(r.unchanged);
        assertFalse(r.offline);
    }

    // ------------------------------------------------------------------ #3 #23 klasifikasi

    @Test public void htmlOr404OrUnknown4xxKeepsOpAndRetries() {
        FillOutbox.Op a = enqueueFill("s-1", 5);
        FillOutbox.Op b = enqueueFill("s-1", 7);
        int[] codes = {403, 404, 405, 410, 422};
        String[] bodies = {"<html>Cloudflare</html>", "{\"message\":\"The route api/fill/log could not be found.\"}",
                "", "<html>retired</html>", "{\"message\":\"The given data was invalid.\"}"};
        for (int i = 0; i < codes.length; i++) {
            net.writeError = err(codes[i], bodies[i]);
            PengisianSync.Result r = run("s-1");
            assertEquals("kode " + codes[i], 2, store.pending().size());     // tak ada yang dibuang
            assertTrue(r.rejections.isEmpty());
            assertNull(r.blockedMessage);
            assertTrue(r.outboxLeft);
        }
        // pulih: kirim sukses berurutan, FIFO
        net.writeError = null;
        net.sent.clear();
        run("s-1");
        assertEquals("FILL:" + a.uuid, net.sent.get(0));
        assertEquals("FILL:" + b.uuid, net.sent.get(1));
        assertTrue(store.pending().isEmpty());
    }

    @Test public void serverTransientErrorsStopTheQueueInOrder() {
        enqueueFill("s-1", 5);
        enqueueFill("s-1", 7);
        net.writeError = err(503, "<html>down</html>");
        run("s-1");
        assertEquals(1, net.sent.size());            // berhenti di yang pertama (urutan penting)
        assertEquals(2, store.pending().size());
        net.sent.clear();
        net.writeError = err(429, "{\"ok\":false,\"code\":\"rate_limited\"}");
        run("s-1");
        assertEquals(1, net.sent.size());
        assertEquals(2, store.pending().size());
    }

    @Test public void captivePortal200WithoutOkTrueKeepsOp() {
        enqueueFill("s-1", 5);
        net.writeBody = "<html>login wifi</html>";
        PengisianSync.Result r = run("s-1");
        assertEquals(1, store.pending().size());
        assertTrue(r.outboxLeft);
        net.writeBody = "";
        run("s-1");
        assertEquals(1, store.pending().size());
        net.writeBody = "{\"ok\":false}";
        run("s-1");
        assertEquals(1, store.pending().size());
    }

    @Test public void knownPermanentCodeDropsOpAndReportsMessageAndContinues() {
        FillOutbox.Op bad = enqueueFill("s-1", 5);
        FillOutbox.Op good = enqueueFill("s-1", 7);
        net.writeError = err(422, "{\"ok\":false,\"code\":\"product_invalid\",\"message\":\"Produk tidak dikenal.\"}");
        // hanya yang pertama ditolak; skrip: setelah itu sukses
        PengisianSync.Transport scripted = new PengisianSync.Transport() {
            boolean first = true;
            @Override public String plan(String rev, String s) throws Exception { return net.plan(rev, s); }
            @Override public String fill(String s, String uuid, String p, int q, int age) throws Exception {
                if (first) { first = false; throw net.writeError; }
                net.writeError = null;
                return net.fill(s, uuid, p, q, age);
            }
            @Override public String count(String s, String b, List<FillOutbox.Count> c, int age) throws Exception { return net.count(s, b, c, age); }
            @Override public String voidOf(String s, String t) throws Exception { return net.voidOf(s, t); }
        };
        PengisianSync.Result r = PengisianSync.run(scripted, store, "s-1", "r1", clock);
        assertEquals(1, r.rejections.size());
        assertEquals("Produk tidak dikenal.", r.rejections.get(0));
        assertNull(r.blockedMessage);
        assertTrue(store.pending().isEmpty());       // yang buruk dibuang, yang baik terkirim
        assertEquals(1, net.sent.size());
        assertEquals("FILL:" + good.uuid, net.sent.get(0));
        assertFalse(bad.uuid.equals(good.uuid));
    }

    // ------------------------------------------------------------------ #24 identitas pada tulis

    @Test public void identityRejectionOnWriteKeepsOpStopsFlushingAndSurfacesMessage() {
        for (String code : new String[]{"staff_not_found", "staff_uuid_missing", "forbidden_role", "staff_inactive"}) {
            setUp();
            enqueueFill("s-1", 5);
            enqueueFill("s-1", 7);
            String msg = "Pesan server untuk " + code;
            net.writeError = err(code.equals("staff_not_found") ? 404 : code.equals("staff_uuid_missing") ? 422 : 403,
                    "{\"ok\":false,\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}");
            PengisianSync.Result r = run("s-1");
            assertEquals(code, msg, r.blockedMessage);
            assertEquals(code, 2, store.pending().size());           // TIDAK ada yang dihapus
            assertEquals(code, 1, net.sent.size());                   // berhenti setelah yang pertama
            assertTrue(code, r.rejections.isEmpty());                // bukan toast "ditolak", tapi banner
            assertTrue(r.outboxLeft);
        }
    }

    @Test public void identityBlockRecoversOnNextTickWhenServerAcceptsAgain() {
        FillOutbox.Op a = enqueueFill("s-1", 5);
        net.writeError = err(404, "{\"ok\":false,\"code\":\"staff_not_found\",\"message\":\"sync dulu\"}");
        assertNotNull(run("s-1").blockedMessage);
        net.writeError = null;
        PengisianSync.Result r = run("s-1");
        assertNull(r.blockedMessage);
        assertTrue(store.pending().isEmpty());
        assertEquals("FILL:" + a.uuid, net.sent.get(net.sent.size() - 1));
    }

    // ------------------------------------------------------------------ #14 #17 #25 poll

    @Test public void pollStaffInactiveIsBlockedNotOffline() {
        net.planError = err(403, "{\"ok\":false,\"code\":\"staff_inactive\",\"message\":\"Akun staf ini nonaktif di server.\"}");
        PengisianSync.Result r = run("s-1");
        assertEquals("Akun staf ini nonaktif di server.", r.blockedMessage);
        assertFalse(r.offline);
    }

    @Test public void pollGenericFailuresAreOffline() {
        net.planError = err(403, "<html>waf</html>");
        assertTrue(run("s-1").offline);
        net.planError = null;
        net.planBody = "<html>captive</html>";
        assertTrue(run("s-1").offline);
    }

    // ------------------------------------------------------------------ #4 #30 staf lain

    @Test public void foreignOpsAreNeverSentAndNotCountedForCurrentWorker() {
        FillOutbox.Op foreign = enqueueFill("s-A", 10);
        FillOutbox.Op mine = enqueueFill("s-B", 3);
        PengisianSync.Result r = run("s-B");
        assertEquals(1, net.sent.size());
        assertEquals("FILL:" + mine.uuid, net.sent.get(0));
        assertEquals("s-B", net.staffs.get(0));
        assertFalse(r.outboxLeft);                                      // milik B habis, milik A tak dihitung
        assertEquals(1, store.pending().size());                        // milik A tetap tersimpan
        assertEquals(foreign.uuid, store.pending().get(0).uuid);

        assertTrue(store.pendingFor("s-B").isEmpty());                  // overlay/penghitung B bersih
        assertEquals(1, store.foreignCount("s-B"));
        assertEquals(1, store.pendingFor("s-A").size());
        assertEquals(0, store.foreignCount("s-A"));
    }

    @Test public void foreignOpsSentWhenOwnerLogsBackIn() {
        FillOutbox.Op foreign = enqueueFill("s-A", 10);
        run("s-B");
        assertTrue(net.sent.isEmpty());
        run("s-A");
        assertEquals("FILL:" + foreign.uuid, net.sent.get(0));
        assertEquals("s-A", net.staffs.get(0));
        assertTrue(store.pending().isEmpty());
    }

    @Test public void opsExpireAfter24hButNotBefore() {
        FillOutbox.Op foreign = enqueueFill("s-A", 10);
        clock.advance(23L * 3600_000L);
        PengisianSync.Result r = run("s-B");
        assertEquals(0, r.expired);
        assertEquals(1, store.pending().size());
        clock.advance(2L * 3600_000L);                                  // total 25 jam
        r = run("s-B");
        assertEquals(1, r.expired);
        assertTrue(store.pending().isEmpty());
        assertFalse(foreign.uuid.isEmpty());
    }

    // ------------------------------------------------------------------ #20 #26 age_seconds

    @Test public void ageSecondsIsElapsedSinceTapAtFlushTime() {
        enqueueFill("s-1", 5);
        clock.advance(95_000L);                                         // 95 dtk offline
        net.writeError = err(503, "x");
        run("s-1");
        assertEquals(95, (int) net.ages.get(0));
        clock.advance(25_000L);
        net.writeError = null;
        run("s-1");
        assertEquals(120, (int) net.ages.get(net.ages.size() - 1));     // dihitung ulang tiap kirim
    }

    @Test public void countAgeAlsoSentAndVoidCarriesNone() {
        List<FillOutbox.Count> cs = new ArrayList<>();
        cs.add(new FillOutbox.Count("p-1", 25));
        store.enqueue(FillOutbox.Op.count("s-1", cs, clock.wall, clock.elapsed));
        store.enqueue(FillOutbox.Op.voidOf("s-1", "row-1", clock.wall, clock.elapsed));
        clock.advance(40_000L);
        run("s-1");
        assertEquals(40, (int) net.ages.get(0));
        assertEquals(-1, (int) net.ages.get(1));                        // batal tak punya age_seconds
    }
}
