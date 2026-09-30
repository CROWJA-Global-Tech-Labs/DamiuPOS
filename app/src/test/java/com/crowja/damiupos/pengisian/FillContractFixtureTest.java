package com.crowja.damiupos.pengisian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crowja.damiupos.sync.SyncApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KONTRAK dengan server: fixture di {@code src/test/resources/fill/} adalah balasan ASLI endpoint
 * {@code /api/fill/*} yang ditulis uji backend (tests/Feature/FillPlanContractFixtureTest.php, repo
 * DAMIUPOS-Online, folder tests/Fixtures/fill) saat menjalankan satu hari kerja lengkap. Di sini tiap
 * fixture di-parse dengan kelas APK yang sungguhan — {@link FillPlan}, {@link FillText},
 * {@link FillOverlay}, {@link PengisianSync} — dan KEPUTUSAN buang / ulang / berhenti per kode galat
 * dipatok. Bila server mengubah bentuk/kode, salin ulang fixture (lihat README di bawah) dan uji ini
 * memberi tahu apa yang berubah di sisi APK.
 *
 * <p>Pembaruan: jalankan {@code php artisan test --filter=FillPlanContractFixtureTest} di backend lalu
 * salin {@code tests/Fixtures/fill/*.json} ke {@code app/src/test/resources/fill/}.
 *
 * <p>Yang TIDAK terjangkau JVM murni: pembentukan isi permintaan di {@code SyncApi.fillLog/fillCount/
 * fillVoid} (org.json stub di uji unit). Bentuknya dijaga lewat tinjauan: uuid, product_uuid, qty,
 * age_seconds (hanya bila &gt; 0), counts[{product_uuid, qty}], header X-Staff-Uuid — identik dengan
 * bagian {@code request} tiap fixture.
 */
public class FillContractFixtureTest {

    // ------------------------------------------------------------------ pemuat fixture

    private static final class Fx {
        String name, category, method, path, code;
        int http;
        JsonObject request, body;
        String bodyText;
    }

    private static String read(String resource) throws IOException {
        InputStream in = FillContractFixtureTest.class.getResourceAsStream(resource);
        assertNotNull("fixture tak ditemukan: " + resource, in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static List<Fx> all() throws IOException {
        JsonArray index = JsonParser.parseString(read("/fill/_index.json")).getAsJsonArray();
        List<Fx> out = new ArrayList<>();
        for (JsonElement e : index) {
            JsonObject row = e.getAsJsonObject();
            String name = row.get("name").getAsString();
            JsonObject doc = JsonParser.parseString(read("/fill/" + name + ".json")).getAsJsonObject();
            Fx f = new Fx();
            f.name = name;
            f.category = row.get("category").getAsString();
            f.method = row.get("method").getAsString();
            f.path = row.get("path").getAsString();
            f.code = row.has("code") ? row.get("code").getAsString() : "";
            f.http = doc.get("http").getAsInt();
            f.body = doc.get("body").getAsJsonObject();
            f.bodyText = f.body.toString();
            f.request = doc.has("request") && doc.get("request").isJsonObject() ? doc.get("request").getAsJsonObject() : null;
            assertEquals(name, doc.get("name").getAsString());
            assertEquals("indeks vs berkas: http " + name, row.get("http").getAsInt(), f.http);
            out.add(f);
        }
        return out;
    }

    private static Fx fx(String name) throws IOException {
        for (Fx f : all()) if (f.name.equals(name)) return f;
        fail("fixture tak ada di indeks: " + name);
        return null;
    }

    private static List<Fx> ofCategory(String category) throws IOException {
        List<Fx> out = new ArrayList<>();
        for (Fx f : all()) if (f.category.equals(category)) out.add(f);
        return out;
    }

    /** JSON rencana lengkap dalam satu fixture: body itu sendiri (plan) atau body.plan (tulis). */
    private static JsonObject planOf(Fx f) {
        if ("plan".equals(f.category)) return f.body;
        return f.body.getAsJsonObject("plan");
    }

    // ------------------------------------------------------------------ keputusan per kode galat

    /** Nama fixture galat → keputusan APK. Fixture galat baru TANPA baris di sini membuat uji merah. */
    private static final Map<String, FillText.WriteVerdict> VERDICT = new HashMap<>();

    static {
        FillText.WriteVerdict drop = FillText.WriteVerdict.DROP;
        FillText.WriteVerdict block = FillText.WriteVerdict.KEEP_BLOCKED;
        FillText.WriteVerdict retry = FillText.WriteVerdict.RETRY;
        VERDICT.put("err_validation_log", drop);
        VERDICT.put("err_validation_uuid", drop);
        VERDICT.put("err_validation_count", drop);
        VERDICT.put("err_product_invalid_log", drop);
        VERDICT.put("err_product_invalid_count", drop);
        VERDICT.put("err_not_owner", drop);
        VERDICT.put("err_void_window_closed", drop);
        VERDICT.put("err_log_not_found", drop);
        VERDICT.put("err_staff_uuid_missing", block);
        VERDICT.put("err_staff_not_found", block);
        VERDICT.put("err_staff_inactive", block);
        VERDICT.put("err_forbidden_role", block);
        VERDICT.put("err_forbidden_role_write", block);
        VERDICT.put("err_rate_limited", retry);
        VERDICT.put("err_rate_limited_write", retry);
        VERDICT.put("err_unauthenticated", retry);   // 401 Laravel tanpa kode: bukan kata akhir soal ketukan
    }

    // ------------------------------------------------------------------ transport & jam palsu

    private static final long T0 = 1_780_000_000_000L;

    private static final class Clock implements PengisianSync.Clock {
        long wall = T0, elapsed = 50_000L;
        @Override public long wallMs() { return wall; }
        @Override public long elapsedMs() { return elapsed; }
    }

    private static final class Mem implements PengisianStore.Backing {
        final Map<String, String> m = new HashMap<>();
        @Override public String get(String k, String d) { return m.containsKey(k) ? m.get(k) : d; }
        @Override public void set(String k, String v) { m.put(k, v); }
    }

    /** Transport yang membalas dengan badan fixture (atau melempar SyncException sesuai status fixture). */
    private static final class FixtureTransport implements PengisianSync.Transport {
        final List<String> sent = new ArrayList<>();
        String writeBody;                 // balasan sukses untuk tulis
        SyncApi.SyncException writeError; // bila diisi, semua tulis melempar ini
        String planBody = "{\"ok\":true,\"unchanged\":true,\"rev\":\"x\"}";
        SyncApi.SyncException planError;
        String lastPlanRev;

        @Override public String plan(String rev, String staffUuid) throws Exception {
            lastPlanRev = rev;
            if (planError != null) throw planError;
            return planBody;
        }

        private String write(String tag) throws Exception {
            sent.add(tag);
            if (writeError != null) throw writeError;
            return writeBody;
        }

        @Override public String fill(String s, String uuid, String p, int q, int age) throws Exception { return write("FILL:" + uuid); }

        @Override public String count(String s, String batch, List<FillOutbox.Count> c, int age) throws Exception { return write("COUNT:" + batch); }

        @Override public String voidOf(String s, String target) throws Exception { return write("VOID:" + target); }
    }

    private static FillOutbox.Op opFor(Fx f, String staff, Clock c) {
        if (f.path.endsWith("/void")) {
            String target = f.path.substring("/api/fill/log/".length(), f.path.length() - "/void".length());
            return FillOutbox.Op.voidOf(staff, target, c.wall, c.elapsed);
        }
        if (f.path.equals("/api/fill/count")) {
            List<FillOutbox.Count> counts = new ArrayList<>();
            counts.add(new FillOutbox.Count("p-1", 3));
            return FillOutbox.Op.count(staff, counts, c.wall, c.elapsed);
        }
        return FillOutbox.Op.fill(staff, "p-1", 2, c.wall, c.elapsed);
    }

    // ------------------------------------------------------------------ 1. inventaris

    @Test public void indexAccountsForEveryFixtureAndEveryErrorHasADecision() throws Exception {
        List<Fx> fixtures = all();
        assertTrue("fixture menyusut: " + fixtures.size(), fixtures.size() >= 30);

        Set<String> seen = new HashSet<>();
        for (Fx f : fixtures) {
            assertTrue("nama fixture ganda: " + f.name, seen.add(f.name));
            if ("error".equals(f.category)) {
                assertTrue("galat tanpa keputusan di VERDICT: " + f.name, VERDICT.containsKey(f.name));
            } else {
                assertTrue("non-galat harus 2xx: " + f.name, f.http >= 200 && f.http < 300);
                assertTrue("balasan sukses wajib ok:true (PengisianSync.requireOk): " + f.name,
                        f.body.has("ok") && f.body.get("ok").getAsBoolean());
            }
        }
        for (String name : VERDICT.keySet()) assertTrue("VERDICT menunjuk fixture yang tak ada: " + name, seen.contains(name));

        // setiap kode galat yang dikenal APK benar-benar muncul di fixture server, dan sebaliknya
        Set<String> codesInFixtures = new HashSet<>();
        for (Fx f : ofCategory("error")) {
            String c = FillText.rejectionCode(f.bodyText);
            assertEquals("kode di indeks vs badan: " + f.name, f.code, c);
            if (!c.isEmpty()) codesInFixtures.add(c);
            assertFalse("galat: ok harus false: " + f.name, f.body.has("ok") && f.body.get("ok").getAsBoolean());
        }
        for (String c : Arrays.asList("validation", "product_invalid", "log_not_found", "void_window_closed", "not_owner",
                "forbidden_role", "staff_not_found", "staff_uuid_missing", "staff_inactive", "rate_limited")) {
            assertTrue("server tak lagi menghasilkan kode " + c, codesInFixtures.contains(c));
        }
    }

    // ------------------------------------------------------------------ 2. FillPlan.parse

    @Test public void everyPlanInEveryFixtureParsesLosslessly() throws Exception {
        int plans = 0;
        for (Fx f : all()) {
            if (!("plan".equals(f.category) || "write_ok".equals(f.category))) continue;
            if (f.name.equals("plan_unchanged")) continue;
            JsonObject raw = planOf(f);
            FillPlan p = FillPlan.parse(raw.toString());
            plans++;

            assertEquals(f.name + " rev", raw.get("rev").getAsString(), p.rev);
            assertEquals(f.name + " server_time", raw.get("server_time").getAsString(), p.serverTime);
            assertEquals(f.name + " spare", raw.get("spare").getAsInt(), p.spare);
            assertEquals(f.name + " status", raw.get("status").getAsString(), p.status);
            assertEquals(f.name + " needs_count", raw.get("needs_count").getAsBoolean(), p.needsCount);

            JsonArray rp = raw.getAsJsonArray("products");
            assertEquals(f.name + " products", rp.size(), p.products.size());
            for (int i = 0; i < rp.size(); i++) {
                JsonObject o = rp.get(i).getAsJsonObject();
                FillPlan.Product pr = p.products.get(i);
                assertEquals(o.get("product_uuid").getAsString(), pr.uuid);
                assertEquals(o.get("name").getAsString(), pr.name);
                assertEquals(f.name + " backlog", o.get("backlog").getAsInt(), pr.backlog);
                assertEquals(o.get("orders").getAsInt(), pr.orders);
                assertEquals(o.get("spare").getAsInt(), pr.spare);
                assertEquals(o.get("target").getAsInt(), pr.target);
                assertEquals(f.name + " ready", o.get("ready").getAsInt(), pr.ready);
                assertEquals(f.name + " to_fill", o.get("to_fill").getAsInt(), pr.toFill);
                assertEquals(o.get("surplus").getAsInt(), pr.surplus);
                assertEquals(o.get("filled_today").getAsInt(), pr.filledToday);
                assertEquals(o.get("needs_count").getAsBoolean(), pr.needsCount);
                if (o.get("counted_at").isJsonNull()) assertNull(pr.countedAt);
                else assertEquals(o.get("counted_at").getAsString(), pr.countedAt);
            }
            JsonObject t = raw.getAsJsonObject("totals");
            assertEquals(t.get("to_fill").getAsInt(), p.totals.toFill);
            assertEquals(t.get("backlog").getAsInt(), p.totals.backlog);
            assertEquals(t.get("target").getAsInt(), p.totals.target);
            assertEquals(t.get("ready").getAsInt(), p.totals.ready);
            assertEquals(t.get("orders").getAsInt(), p.totals.orders);
            assertEquals(t.get("filled_today").getAsInt(), p.totals.filledToday);
            assertEquals(raw.getAsJsonObject("in_transit").get("pcs").getAsInt(), p.inTransitPcs);
            assertEquals(raw.getAsJsonObject("unmapped").get("orders").getAsInt(), p.unmappedOrders);

            JsonArray rr = raw.getAsJsonArray("recent");
            assertEquals(f.name + " recent", rr.size(), p.recent.size());
            for (int i = 0; i < rr.size(); i++) {
                JsonObject o = rr.get(i).getAsJsonObject();
                FillPlan.Recent r = p.recent.get(i);
                assertEquals(o.get("uuid").getAsString(), r.uuid);
                assertEquals(o.get("kind").getAsString(), r.kind);
                assertEquals(o.get("qty").getAsInt(), r.qty);
                assertEquals(o.get("voidable").getAsBoolean(), r.voidable);
                assertEquals("batch_uuid " + f.name, o.get("batch_uuid").isJsonNull() ? "" : o.get("batch_uuid").getAsString(), r.batchUuid);
                assertEquals(o.get("staff_name").isJsonNull() ? "" : o.get("staff_name").getAsString(), r.staffName);
                assertEquals(o.get("product_name").getAsString(), r.productName);
            }

            // jumlah lintas-produk sama dengan totals (server sudah menjaganya; APK tak menghitung ulang)
            int sumToFill = 0;
            for (FillPlan.Product pr : p.products) sumToFill += pr.toFill;
            assertEquals(f.name + " Σ to_fill", p.totals.toFill, sumToFill);

            // cache lokal & teks tak boleh melempar untuk bentuk asli
            Object[] cache = FillText.decodeCache(FillText.encodeCache(raw.toString(), T0));
            assertNotNull(cache);
            assertEquals(p.rev, FillPlan.parse((String) cache[1]).rev);
            FillText.heroTitle(p);
            FillText.heroValue(p);
            FillText.heroSub(p);
            FillText.unmappedWarning(p);
            for (FillPlan.Product pr : p.products) {
                FillText.headline(pr);
                FillText.detail(pr);
                FillText.progressPercent(pr);
                FillText.isActive(pr);
            }
            for (FillPlan.Recent r : p.recent) FillText.recentLabel(r);
            FillText.historyRows(p.recent, "whoever", false);
            FillText.historyRows(p.recent, "whoever", true);
        }
        assertTrue("terlalu sedikit rencana yang diuji: " + plans, plans >= 13);
    }

    @Test public void needPlanTextMatchesTheDocumentedNumbers() throws Exception {
        FillPlan p = FillPlan.parse(fx("plan_need").bodyText);
        assertEquals(FillPlan.STATUS_NEED, p.status);
        assertEquals("Perlu diisi sekarang", FillText.heroTitle(p));
        assertEquals("17 galon", FillText.heroValue(p));
        assertEquals("4 pesanan · 14 galon · 2 sudah di jalan", FillText.heroSub(p));
        String w = FillText.unmappedWarning(p);
        assertNotNull(w);
        assertTrue(w, w.contains("3 galon di 1 pesanan"));

        FillPlan.Product min = p.products.get(0);   // to_fill terbesar paling atas
        assertEquals("ISI 12 LAGI", FillText.headline(min));
        assertEquals("Pesanan berjalan 11 (+2 cadangan) = 13 · Siap di rak 1", FillText.detail(min));
        assertEquals(7, FillText.progressPercent(min));   // 1 * 100 / 13

        FillPlan idle = FillPlan.parse(fx("plan_idle").bodyText);
        assertNull(FillText.unmappedWarning(idle));
        assertEquals("Belum ada pesanan berjalan", FillText.heroTitle(idle));
    }

    // ------------------------------------------------------------------ 3. PengisianSync: sukses

    @Test public void unchangedFixtureShortCircuitsAndKeepsTheRevForTheNextPoll() throws Exception {
        Fx u = fx("plan_unchanged");
        FixtureTransport net = new FixtureTransport();
        net.planBody = u.bodyText;
        PengisianSync.Result r = PengisianSync.run(net, new PengisianStore(new Mem()), "s-1", "rev-lama", new Clock());
        assertTrue(r.unchanged);
        assertNull(r.planJson);
        assertFalse(r.offline);
        assertNull(r.blockedMessage);
        assertEquals("rev-lama", net.lastPlanRev);
    }

    @Test public void fullPlanFixtureFromPollIsDeliveredToTheScreen() throws Exception {
        FixtureTransport net = new FixtureTransport();
        net.planBody = fx("plan_need").bodyText;
        PengisianSync.Result r = PengisianSync.run(net, new PengisianStore(new Mem()), "s-1", "", new Clock());
        assertFalse(r.unchanged);
        assertFalse(r.offline);
        assertNotNull(r.planJson);
        assertEquals(fx("plan_need").body.get("rev").getAsString(), FillPlan.parse(r.planJson).rev);
    }

    @Test public void everySuccessfulWriteFixtureAcksTheOpAndHandsBackThePlan() throws Exception {
        int n = 0;
        for (Fx f : ofCategory("write_ok")) {
            Clock clock = new Clock();
            PengisianStore store = new PengisianStore(new Mem());
            store.enqueue(opFor(f, "s-1", clock));
            FixtureTransport net = new FixtureTransport();
            net.writeBody = f.bodyText;
            net.planBody = "{\"ok\":true,\"unchanged\":true,\"rev\":\"x\"}";

            PengisianSync.Result r = PengisianSync.run(net, store, "s-1", "r0", clock);
            n++;

            assertEquals(f.name + " terkirim", 1, net.sent.size());
            assertTrue(f.name + " op harus keluar dari kotak", store.pending().isEmpty());
            assertFalse(r.outboxLeft);
            assertNotNull(f.name + " rencana dari balasan tulis", r.planJson);
            FillPlan plan = FillPlan.parse(r.planJson);
            assertEquals(planOf(f).get("rev").getAsString(), plan.rev);
            // poll sesudah tulis memakai rev dari balasan tulis
            assertEquals(f.name, plan.rev, net.lastPlanRev);
            assertTrue(r.rejections.isEmpty());
            assertNull(r.blockedMessage);
        }
        assertTrue(n >= 10);
    }

    // ------------------------------------------------------------------ 4. PengisianSync: galat

    @Test public void everyErrorFixtureGetsTheDocumentedVerdict() throws Exception {
        for (Fx f : ofCategory("error")) {
            FillText.WriteVerdict want = VERDICT.get(f.name);
            assertEquals("klasifikasi " + f.name, want, FillText.classifyWrite(f.http, f.bodyText));

            boolean identity = want == FillText.WriteVerdict.KEEP_BLOCKED;
            assertEquals("isIdentityRejection " + f.name, identity, FillText.isIdentityRejection(f.bodyText));

            String serverMessage = f.body.has("message") ? f.body.get("message").getAsString() : "";
            String shown = FillText.rejectionMessage(f.bodyText, "cadangan");
            assertEquals("pesan server dipakai apa adanya: " + f.name, serverMessage, shown);

            if (f.method.equals("POST")) assertWriteFlowOutcome(f, want, serverMessage);
            else assertPlanFlowOutcome(f, want, serverMessage);
        }
    }

    /** Dua op antre; galat datang pada yang pertama. */
    private void assertWriteFlowOutcome(Fx f, FillText.WriteVerdict want, String serverMessage) {
        Clock clock = new Clock();
        PengisianStore store = new PengisianStore(new Mem());
        FillOutbox.Op first = opFor(f, "s-1", clock);
        FillOutbox.Op second = FillOutbox.Op.fill("s-1", "p-1", 1, clock.wall, clock.elapsed);
        store.enqueue(first);
        store.enqueue(second);
        FixtureTransport net = new FixtureTransport();
        net.writeError = new SyncApi.SyncException(f.http, f.bodyText);

        PengisianSync.Result r;
        try {
            r = PengisianSync.run(net, store, "s-1", "r0", clock);
        } catch (RuntimeException e) {
            throw new AssertionError(f.name + ": run() tak boleh melempar", e);
        }

        switch (want) {
            case DROP:
                // dibuang + dilaporkan; antrean LANJUT ke op berikutnya (yang juga ditolak sama oleh fake)
                assertEquals(f.name + " kotak keluar kosong", 0, store.pending().size());
                assertEquals(f.name + " kedua op terkirim", 2, net.sent.size());
                assertEquals(2, r.rejections.size());
                assertEquals(serverMessage, r.rejections.get(0));
                assertNull(r.blockedMessage);
                assertFalse(r.outboxLeft);
                break;
            case KEEP_BLOCKED:
                assertEquals(f.name + " op disimpan", 2, store.pending().size());
                assertEquals(f.name + " berhenti di galat identitas", 1, net.sent.size());
                assertEquals(serverMessage, r.blockedMessage);
                assertTrue(r.rejections.isEmpty());
                assertTrue(r.outboxLeft);
                break;
            case RETRY:
                assertEquals(f.name + " op disimpan", 2, store.pending().size());
                assertEquals(f.name + " berhenti, coba lagi nanti", 1, net.sent.size());
                assertNull(r.blockedMessage);
                assertTrue(r.rejections.isEmpty());
                assertTrue(r.outboxLeft);
                break;
            default:
                fail("verdict tak dikenal");
        }
    }

    /** Galat pada GET /plan: identitas → pesan server; selain itu → banner offline. Tak pernah melempar. */
    private void assertPlanFlowOutcome(Fx f, FillText.WriteVerdict want, String serverMessage) {
        FixtureTransport net = new FixtureTransport();
        net.planError = new SyncApi.SyncException(f.http, f.bodyText);
        PengisianSync.Result r = PengisianSync.run(net, new PengisianStore(new Mem()), "s-1", "r0", new Clock());
        assertNull(f.name + " tak ada rencana", r.planJson);
        assertFalse(r.unchanged);
        if (want == FillText.WriteVerdict.KEEP_BLOCKED) {
            assertEquals(f.name, serverMessage, r.blockedMessage);
            assertFalse(f.name + " identitas ditolak ≠ offline", r.offline);
        } else {
            assertNull(f.name, r.blockedMessage);
            assertTrue(f.name + " → banner offline", r.offline);
        }
    }

    @Test public void htmlAndRouteNotFoundResponsesAreNeverTreatedAsServerVerdicts() {
        // bentuk yang BUKAN dari kontrak tapi bisa tiba: CDN/WAF, rute belum dideploy, 5xx
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(403, "<html>Cloudflare</html>"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(404, "{\"message\":\"The route api/fill/log could not be found.\"}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(500, "{\"message\":\"Server Error\"}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(0, ""));
        // kode permanen tetapi status 5xx = bukan jawaban pengontrol → ulang
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(502, "{\"ok\":false,\"code\":\"validation\"}"));
    }

    // ------------------------------------------------------------------ 5. FillOverlay pada rencana asli

    @Test public void overlayAppliesPendingOutboxToARealServerPlan() throws Exception {
        FillPlan base = FillPlan.parse(fx("plan_need").bodyText);
        FillPlan.Product min = base.products.get(0);
        FillPlan.Product alk = base.products.get(1);
        assertEquals(12, min.toFill);
        assertEquals(5, alk.toFill);

        List<FillOutbox.Op> ops = new ArrayList<>();
        ops.add(FillOutbox.Op.fill("s-1", min.uuid, 5, T0));
        FillPlan v = FillOverlay.apply(base, ops);
        assertEquals(6, v.product(min.uuid).ready);        // 1 + 5
        assertEquals(7, v.product(min.uuid).toFill);       // 13 - 6
        assertEquals(5, v.product(min.uuid).filledToday);
        assertEquals(12, v.totals.toFill);                 // 17 - 5
        assertEquals(FillPlan.STATUS_NEED, v.status);
        assertEquals("ISI 7 LAGI", FillText.headline(v.product(min.uuid)));
        assertEquals("server tak diubah", 12, base.product(min.uuid).toFill);

        // COUNT absolut menimpa + membersihkan needs_count; mengisi melebihi target → surplus
        ops.add(FillOutbox.Op.count("s-1", Arrays.asList(new FillOutbox.Count(alk.uuid, 7)), T0));
        ops.add(FillOutbox.Op.fill("s-1", min.uuid, 7, T0));
        v = FillOverlay.apply(base, ops);
        assertEquals(7, v.product(alk.uuid).ready);
        assertEquals(0, v.product(alk.uuid).toFill);
        assertEquals(2, v.product(alk.uuid).surplus);
        assertEquals(13, v.product(min.uuid).ready);       // 1 + 5 + 7 = target
        assertEquals(0, v.product(min.uuid).toFill);
        assertEquals(FillPlan.STATUS_MET, v.status);
        assertEquals(0, v.totals.toFill);
        assertEquals(20, v.totals.ready);
    }

    @Test public void overlayDoesNotDoubleCountAFillTheServerAlreadyHas() throws Exception {
        // balasan kirim hilang di jalan: op masih di kotak, tapi uuid-nya sudah ada di recent server
        FillPlan base = FillPlan.parse(fx("plan_after_fills").bodyText);
        FillPlan.Recent rf = null;
        for (FillPlan.Recent r : base.recent) if (r.uuid.equals("fill-mineral-0001")) rf = r;
        assertNotNull(rf);
        FillOutbox.Op ghost = FillOutbox.Op.fill("s-1", rf.productUuid, rf.qty, T0);
        ghost.uuid = rf.uuid;
        FillPlan v = FillOverlay.apply(base, new ArrayList<>(Arrays.asList(ghost)));
        assertEquals(base.product(rf.productUuid).ready, v.product(rf.productUuid).ready);
        assertEquals(base.product(rf.productUuid).filledToday, v.product(rf.productUuid).filledToday);
    }

    @Test public void overlayVoidOfAFillAndOfAWholeCountBatchUsesTheBatchUuidFromRecent() throws Exception {
        FillPlan base = FillPlan.parse(fx("plan_after_fills").bodyText);
        FillPlan.Product min = null;
        for (FillPlan.Product p : base.products) if (p.name.contains("Mineral")) min = p;
        assertNotNull(min);
        int ready = min.ready;

        // batal baris FILL → ready turun sebesar qty, baris hilang dari riwayat
        FillOutbox.Op v1 = FillOutbox.Op.voidOf("s-1", "fill-mineral-0001", T0);
        FillPlan v = FillOverlay.apply(base, new ArrayList<>(Arrays.asList(v1)));
        assertEquals(ready - 7, v.product(min.uuid).ready);
        assertEquals(base.recent.size() - 1, v.recent.size());

        // batal sesi COUNT via batch_uuid (dari recent[].batch_uuid): semua barisnya hilang
        FillPlan withCount = FillPlan.parse(fx("count_second").body.getAsJsonObject("plan").toString());
        String batch = null;
        int batchRows = 0;
        for (FillPlan.Recent r : withCount.recent) {
            if (FillPlan.KIND_COUNT.equals(r.kind) && "count-midday-001".equals(r.batchUuid)) {
                batch = r.batchUuid;
                batchRows++;
            }
        }
        assertEquals("count-midday-001", batch);
        assertEquals(2, batchRows);
        FillPlan after = FillOverlay.apply(withCount,
                new ArrayList<>(Arrays.asList(FillOutbox.Op.voidOf("s-1", batch, T0))));
        for (FillPlan.Recent r : after.recent) assertFalse("count-midday-001".equals(r.batchUuid));
        assertEquals(withCount.recent.size() - 2, after.recent.size());
    }

    @Test public void historyGroupsACountSessionIntoOneVoidableRowKeyedByItsBatchUuid() throws Exception {
        FillPlan p = FillPlan.parse(fx("count_second").body.getAsJsonObject("plan").toString());
        String worker = "20000000-0000-4000-8000-00000000b001";
        List<FillText.HistoryRow> rows = FillText.historyRows(p.recent, worker, false);

        FillText.HistoryRow count = null;
        for (FillText.HistoryRow r : rows) if ("count-midday-001".equals(r.voidTarget)) count = r;
        assertNotNull("sesi hitung harus satu baris yang batal lewat batch_uuid", count);
        assertEquals("Hitung stok · 2 produk", count.label);

        // pekerja lain tak melihat "Batal" (server akan menolak not_owner); admin melihatnya
        for (FillText.HistoryRow r : FillText.historyRows(p.recent, "orang-lain", false)) assertNull(r.voidTarget);
        boolean adminSees = false;
        for (FillText.HistoryRow r : FillText.historyRows(p.recent, "orang-lain", true)) {
            if ("count-midday-001".equals(r.voidTarget)) adminSees = true;
        }
        assertTrue(adminSees);
    }

    // ------------------------------------------------------------------ 6. request ↔ kontrak

    @Test public void clientRequestsCarryOnlyFieldsTheServerContractDefines() throws Exception {
        // Sisi server memvalidasi persis kolom ini; bagian request fixture = yang APK kirim.
        Set<String> logKeys = new HashSet<>(Arrays.asList("uuid", "product_uuid", "qty", "age_seconds"));
        Set<String> countKeys = new HashSet<>(Arrays.asList("uuid", "counts", "age_seconds"));
        for (Fx f : all()) {
            if (f.request == null) continue;
            Set<String> keys = f.request.keySet();
            if (f.path.equals("/api/fill/log")) assertTrue(f.name + " " + keys, logKeys.containsAll(keys));
            if (f.path.equals("/api/fill/count")) assertTrue(f.name + " " + keys, countKeys.containsAll(keys));
            if (f.request.has("uuid")) {
                String uuid = f.request.get("uuid").getAsString();
                if (f.code.equals("validation") && f.name.equals("err_validation_uuid")) continue;
                // uuid klien APK = UUID.randomUUID() (36 char) — dan kontrak menerima 8..36 [A-Za-z0-9-]
                assertTrue(f.name + " uuid " + uuid, uuid.matches("^[A-Za-z0-9\\-]{8,36}$"));
            }
        }
        // UUID asli APK memenuhi pola server
        String real = FillOutbox.newUuid();
        assertTrue(real, real.matches("^[A-Za-z0-9\\-]{8,36}$"));
    }
}
