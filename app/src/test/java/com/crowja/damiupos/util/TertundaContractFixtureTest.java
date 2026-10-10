package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.TertundaRows.Result;
import com.crowja.damiupos.util.TertundaRows.ResumeOutcome;
import com.crowja.damiupos.util.TertundaRows.Row;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KONTRAK dengan server: fixture di {@code src/test/resources/tertunda/} adalah balasan ASLI endpoint
 * {@code /api/delivery/tertunda}, {@code /tertunda/resume}, {@code /tertunda/reschedule} dan
 * {@code /delivery/claim} yang dihasilkan backend (DAMIUPOS-Online, Laravel —
 * {@code tests/Feature/DeliveryTertundaContractFixtureTest}: cabang dengan HP Saya + HP RAFI, delapan
 * bentuk baris tertunda, lalu alur "📥 Lanjutkan &amp; Ambil" dua langkah sungguhan; uuid, jam &amp; nomor
 * struk dipatok). Tiap fixture diurai dengan kelas APK yang sungguhan ({@link TertundaRows})
 * — bila server mengganti nama / membuang kunci, uji ini yang merah, bukan perilaku HP yang diam-diam
 * menurun (mis. {@code mine} hilang → "📥" muncul untuk order yang sudah di sini).
 *
 * <p>Pembaruan: jalankan uji itu di backend (menulis {@code tests/Fixtures/tertunda/*.json}) lalu salin
 * berkasnya ke {@code app/src/test/resources/tertunda/} (bentuk berkas sama dengan {@code fill/}: name,
 * method, path, http, request, body).
 */
public class TertundaContractFixtureTest {

    /** Kunci yang dibaca HP dari tiap baris daftar — semuanya WAJIB ada (boleh null). */
    private static final List<String> ROW_KEYS = Arrays.asList(
            // kontrak endpoint (tambahan di atas kartu shapeQueueRow)
            "mine", "type", "payment_method", "void_pending", "resume_at_local", "tertunda_at_local", "due_at_local",
            // kartu shapeQueueRow yang dipakai kartu / detail / preview / aksi
            "id", "uuid", "receipt_no", "routed_uuid", "device_group_uuid", "device_group_label", "customer_uuid",
            "name", "phone", "address", "latitude", "longitude", "dest_name", "checkout", "galon", "total", "ongkir",
            "items", "note", "ordered_at", "source_wa", "queued_at", "tertunda_at", "tertunda_reason",
            "tertunda_photo_url", "resume_at", "pickup_only", "open_dispatch", "chat_session", "in_progress",
            "order_priority", "order_priority_reason");

    // ------------------------------------------------------------------ pemuat fixture

    private static String read(String resource) throws IOException {
        InputStream in = TertundaContractFixtureTest.class.getResourceAsStream(resource);
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

    private static JsonObject doc(String name) throws IOException {
        return JsonParser.parseString(read("/tertunda/" + name + ".json")).getAsJsonObject();
    }

    private static String bodyText(String name) throws IOException {
        return doc(name).get("body").toString();
    }

    private static int http(String name) throws IOException {
        return doc(name).get("http").getAsInt();
    }

    private static Map<String, Row> byName(Result r) {
        Map<String, Row> out = new HashMap<>();
        for (Row x : r.rows) out.put(x.name, x);
        return out;
    }

    /** uuid HP pemanggil saat fixture dibuat = device_group_uuid baris ber-mine=true. */
    private static String myDeviceUuid(Result r) {
        Set<String> mine = new HashSet<>();
        for (Row x : r.rows) if (x.mine) mine.add(x.deviceGroupUuid);
        assertEquals("semua baris mine harus satu perangkat", 1, mine.size());
        return mine.iterator().next();
    }

    // ------------------------------------------------------------------ uji

    @Test public void indexListsEveryFixtureFile() throws IOException {
        JsonArray index = JsonParser.parseString(read("/tertunda/_index.json")).getAsJsonArray();
        assertTrue(index.size() >= 11);
        for (JsonElement e : index) {
            JsonObject row = e.getAsJsonObject();
            JsonObject d = doc(row.get("name").getAsString());
            assertEquals(row.get("http").getAsInt(), d.get("http").getAsInt());
            assertEquals(row.get("path").getAsString(), d.get("path").getAsString());
        }
    }

    @Test public void everyListRowCarriesEveryKeyThePhoneReads() throws IOException {
        JsonObject body = doc("list").getAsJsonObject("body");
        assertTrue(body.has("count"));
        assertTrue(body.get("server_time").getAsString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        JsonArray rows = body.getAsJsonArray("tertunda");
        assertEquals(8, rows.size());
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            for (String k : ROW_KEYS) {
                assertTrue("kunci hilang di baris " + o.get("name") + ": " + k, o.has(k));
            }
            // *_local benar-benar waktu lokal datar (bukan ISO-UTC) atau null
            for (String k : Arrays.asList("resume_at_local", "tertunda_at_local", "due_at_local")) {
                JsonElement v = o.get(k);
                assertTrue(k + "=" + v, v.isJsonNull()
                        || v.getAsString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
            }
            assertTrue(o.get("mine").getAsJsonPrimitive().isBoolean());
            assertTrue(o.get("void_pending").getAsJsonPrimitive().isBoolean());
            assertTrue(o.get("open_dispatch").getAsJsonPrimitive().isBoolean());
        }
    }

    @Test public void listParsesIntoTheRowsTheCardsNeed() throws IOException {
        Result r = TertundaRows.parse(bodyText("list"));
        assertTrue(r.ok);
        assertEquals(8, r.rows.size());
        assertEquals(8, r.count);
        String me = myDeviceUuid(r);
        Map<String, Row> rows = byName(r);

        // Kasus produksi: asal HP lain, rute NULL, bukan Pesanan Terbuka.
        Row rafi = rows.get("Bu Rafi Satu");
        assertFalse(rafi.mine);
        assertFalse(rafi.openDispatch);
        assertFalse(rafi.unassigned());
        assertEquals("", rafi.routedUuid);
        assertEquals("HP RAFI", rafi.deviceGroupLabel);
        assertEquals("📱 HP RAFI", rafi.ownerLabel());
        assertEquals("JUAL", rafi.type);
        assertEquals("QRIS", rafi.paymentMethod);
        assertEquals("D-1210260700-AB12C", rafi.receiptNo);
        assertEquals("Admin 1", rafi.sourceWa);
        assertTrue(rafi.chatSession);
        assertEquals("Pelanggan minta besok", rafi.tertundaReason);
        assertEquals("2026-10-13 12:30:00", rafi.resumeAtLocal);
        assertEquals("2026-10-12 08:00:00", rafi.tertundaAtLocal);
        assertEquals("2026-10-13 08:00:00", rafi.dueAtLocal);   // jam buka cabang, bukan jam resume
        assertEquals(2, rafi.galon);
        assertEquals(14000.0, rafi.total, 0.001);
        assertEquals(-7.0051, rafi.latitude, 1e-9);
        assertTrue(TertundaRows.canClaim(rafi, true, true, me));

        // Dirutekan ke HP ini → "📥" tak ditawarkan.
        Row routed = rows.get("Bu Rute");
        assertTrue(routed.mine);
        assertTrue(routed.mineAndRoutedTo(me));
        assertFalse(TertundaRows.canClaim(routed, true, true, me));
        assertEquals("📱 HP ini", routed.ownerLabel());

        // Asal HP ini, rute kosong (jemput galon).
        Row mineRow = rows.get("Pak Saya");
        assertTrue(mineRow.mine);
        assertFalse(mineRow.mineAndRoutedTo(me));
        assertEquals("KEMBALI", mineRow.type);
        assertTrue(mineRow.pickupOnly);
        assertEquals("", mineRow.paymentMethod);
        assertEquals("", mineRow.tertundaReason);

        Row web = rows.get("Toko Web");
        assertTrue(web.unassigned());
        assertEquals("🌐 Web Dashboard", web.ownerLabel());
        assertEquals("Taruh di depan pagar", web.note);   // blob catatan web diringkas server
        assertFalse(TertundaRows.resumeDestinationHint(web).contains("Web Dashboard"));

        Row open = rows.get("Warung Terbuka");
        assertTrue(open.openDispatch);
        assertEquals("🎲 Pesanan Terbuka", open.ownerLabel());

        Row leg = rows.get("Kantor Leg");
        assertEquals(2, leg.checkoutSeq);
        assertEquals(3, leg.checkoutSize);
        assertEquals("Kantor", leg.destName);

        assertTrue(rows.get("Void Diajukan").voidPending);

        Row noSchedule = rows.get("Tanpa Jadwal");
        assertEquals("", noSchedule.resumeAtLocal);
        assertEquals("", noSchedule.tertundaAtLocal);
        assertEquals("", noSchedule.dueAtLocal);
    }

    @Test public void resumeResponsesDriveTheTwoStepClaim() throws IOException {
        String me = myDeviceUuid(TertundaRows.parse(bodyText("list")));

        // Pemilik (RAFI) tanpa staf absen → Pesanan Terbuka → klaim expected "" → server 200.
        ResumeOutcome open = TertundaRows.parseResume(bodyText("resume_open_dispatch"));
        assertTrue(open.openDispatch);
        assertTrue(open.message, open.message.contains("Pesanan Terbuka"));
        String expected = TertundaRows.claimExpectedAfterResume(open, me);
        assertEquals("", expected);
        JsonObject claim = doc("claim_after_resume_ok");
        assertEquals(200, claim.get("http").getAsInt());
        assertEquals(expected, claim.getAsJsonObject("request").get("expected_device_uuid").getAsString());

        // Baris HP ini sendiri, HP ini tanpa staf absen → ikut dibuka (konfirmasi tak boleh menjanjikan "HP ini").
        ResumeOutcome mineIdle = TertundaRows.parseResume(bodyText("resume_mine_idle"));
        assertTrue(mineIdle.openDispatch);
        assertEquals("", TertundaRows.claimExpectedAfterResume(mineIdle, me));

        // Pemilik berawak → tetap di RAFI → klaim memakai uuid RAFI.
        ResumeOutcome routed = TertundaRows.parseResume(bodyText("resume_routed"));
        assertFalse(routed.openDispatch);
        assertEquals("HP RAFI", routed.routedName);
        assertEquals(routed.routedUuid, TertundaRows.claimExpectedAfterResume(routed, me));
        assertFalse(routed.routedUuid.isEmpty());

        // Kembali ke antrean HP ini → klaim dilewati; server memang menolaknya 422 "sudah di antrian Anda".
        ResumeOutcome toMe = TertundaRows.parseResume(bodyText("resume_routed_to_me"));
        assertEquals(me, toMe.routedUuid);
        assertNull(TertundaRows.claimExpectedAfterResume(toMe, me));
        assertEquals(422, http("claim_already_mine"));
    }

    @Test public void errorResponsesAreStaleAndCarryAServerMessage() throws IOException {
        for (String name : Arrays.asList("resume_not_tertunda", "resume_not_found", "reschedule_not_tertunda")) {
            int code = http(name);
            assertTrue(name + " http=" + code, TertundaRows.isStaleCode(code));
            JsonObject body = doc(name).getAsJsonObject("body");
            assertFalse(name, body.get("ok").getAsBoolean());
            assertFalse(name, body.get("message").getAsString().isEmpty());   // SyncException.serverMessage
        }
        assertEquals(404, http("resume_not_found"));
        assertEquals("Order sudah tidak tertunda.",
                doc("resume_not_tertunda").getAsJsonObject("body").get("message").getAsString());
    }

    @Test public void rescheduleOkCarriesTheWaShapeAfterPostponeWaReads() throws IOException {
        JsonObject d = doc("reschedule_ok");
        assertEquals(200, d.get("http").getAsInt());
        assertFalse("tanpa kunci reason → server mempertahankan alasan lama",
                d.getAsJsonObject("request").has("reason"));
        JsonObject body = d.getAsJsonObject("body");
        assertTrue(body.get("ok").getAsBoolean());
        assertFalse(body.get("message").getAsString().isEmpty());
        JsonObject wa = body.getAsJsonObject("wa");
        for (String k : Arrays.asList("status", "text", "phone", "error")) {
            assertTrue("wa." + k, wa.has(k));
        }
    }
}
