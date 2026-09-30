package com.crowja.damiupos.pengisian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Parsing rencana dari server. {@link #DEBUG_FAKE_PLAN_JSON} adalah CONTOH PALSU khusus uji (hanya
 * meniru bentuk kontrak spec §2 — HP tak bisa memanggil endpoint sungguhan saat dikembangkan);
 * JANGAN dipakai di kode produksi.
 */
public class FillPlanTest {

    /** DEBUG-ONLY fake: bentuk persis GET /api/fill/plan (spec §2) dengan dua produk + satu yatim. */
    static final String DEBUG_FAKE_PLAN_JSON = "{"
            + "\"ok\":true,"
            + "\"rev\":\"9f2c1d\","
            + "\"server_time\":\"2026-09-30 10:15:03\","
            + "\"spare\":2,"
            + "\"status\":\"need\","
            + "\"needs_count\":true,"
            + "\"products\":["
            + "  {\"product_uuid\":\"p-19l\",\"name\":\"Galon 19L\",\"slug\":\"galon-19l\",\"color\":\"#1E88E5\","
            + "   \"backlog\":10,\"orders\":4,\"spare\":2,\"target\":12,\"ready\":5,\"to_fill\":7,\"surplus\":0,"
            + "   \"filled_today\":20,\"needs_count\":false,\"counted_at\":\"2026-09-30 07:01:10\"},"
            + "  {\"product_uuid\":\"p-isi\",\"name\":\"Isi Ulang\",\"slug\":\"isi-ulang\",\"color\":\"#43A047\","
            + "   \"backlog\":3,\"orders\":1,\"spare\":2,\"target\":5,\"ready\":9,\"to_fill\":0,\"surplus\":4,"
            + "   \"filled_today\":0,\"needs_count\":true,\"counted_at\":null},"
            + "  {\"product_uuid\":\"p-idle\",\"name\":\"Tutup Galon\",\"slug\":\"tutup\",\"color\":\"\","
            + "   \"backlog\":0,\"orders\":0,\"spare\":0,\"target\":0,\"ready\":0,\"to_fill\":0,\"surplus\":0,"
            + "   \"filled_today\":0,\"needs_count\":false,\"counted_at\":null}"
            + "],"
            + "\"totals\":{\"backlog\":13,\"spare\":4,\"target\":17,\"ready\":14,\"to_fill\":7,"
            + "  \"filled_today\":20,\"orders\":5},"
            + "\"in_transit\":{\"pcs\":6,\"orders\":2},"
            + "\"unmapped\":{\"pcs\":3,\"orders\":1},"
            + "\"recent\":["
            + "  {\"uuid\":\"r-1\",\"kind\":\"FILL\",\"product_uuid\":\"p-19l\",\"product_name\":\"Galon 19L\","
            + "   \"qty\":10,\"logged_at\":\"2026-09-30 10:10:00\",\"staff_uuid\":\"s-1\",\"staff_name\":\"Budi\","
            + "   \"voidable\":true},"
            + "  {\"uuid\":\"r-2\",\"kind\":\"COUNT\",\"product_uuid\":\"p-19l\",\"product_name\":\"Galon 19L\","
            + "   \"qty\":15,\"logged_at\":\"2026-09-30 07:01:10\",\"staff_uuid\":\"s-1\",\"staff_name\":\"Budi\","
            + "   \"voidable\":false}"
            + "]}";

    @Test public void parsesFullPlanShape() {
        FillPlan p = FillPlan.parse(DEBUG_FAKE_PLAN_JSON);
        assertEquals("9f2c1d", p.rev);
        assertEquals("2026-09-30 10:15:03", p.serverTime);
        assertEquals(2, p.spare);
        assertEquals(FillPlan.STATUS_NEED, p.status);
        assertTrue(p.needsCount);

        assertEquals(3, p.products.size());
        FillPlan.Product a = p.products.get(0);
        assertEquals("p-19l", a.uuid);
        assertEquals("Galon 19L", a.name);
        assertEquals("#1E88E5", a.color);
        assertEquals(10, a.backlog);
        assertEquals(4, a.orders);
        assertEquals(2, a.spare);
        assertEquals(12, a.target);
        assertEquals(5, a.ready);
        assertEquals(7, a.toFill);
        assertEquals(20, a.filledToday);
        assertFalse(a.needsCount);
        assertEquals("2026-09-30 07:01:10", a.countedAt);

        FillPlan.Product b = p.products.get(1);
        assertEquals(4, b.surplus);
        assertTrue(b.needsCount);
        assertNull(b.countedAt);

        assertEquals(13, p.totals.backlog);
        assertEquals(17, p.totals.target);
        assertEquals(14, p.totals.ready);
        assertEquals(7, p.totals.toFill);
        assertEquals(20, p.totals.filledToday);
        assertEquals(5, p.totals.orders);
        assertEquals(6, p.inTransitPcs);
        assertEquals(2, p.inTransitOrders);
        assertEquals(3, p.unmappedPcs);
        assertEquals(1, p.unmappedOrders);

        assertEquals(2, p.recent.size());
        FillPlan.Recent r = p.recent.get(0);
        assertEquals("r-1", r.uuid);
        assertEquals(FillPlan.KIND_FILL, r.kind);
        assertEquals(10, r.qty);
        assertEquals("Budi", r.staffName);
        assertTrue(r.voidable);
        assertFalse(p.recent.get(1).voidable);
        assertNotNull(p.product("p-isi"));
        assertNull(p.product("tidak-ada"));
    }

    @Test public void sparseAndSloppyJsonDoesNotCrash() {
        // server lama/galat: field hilang, null, angka berupa string/pecahan, baris tanpa uuid
        FillPlan p = FillPlan.parse("{\"status\":\"idle\",\"products\":["
                + "{\"product_uuid\":\"x\",\"name\":\"X\",\"backlog\":\"4\",\"target\":6.0,\"ready\":null},"
                + "{\"name\":\"tanpa uuid\"},"
                + "\"bukan objek\"],"
                + "\"totals\":null,\"recent\":[{\"kind\":\"FILL\"}]}");
        assertEquals(1, p.products.size());
        assertEquals(4, p.products.get(0).backlog);
        assertEquals(6, p.products.get(0).target);
        assertEquals(0, p.products.get(0).ready);
        assertEquals(0, p.totals.toFill);
        assertTrue(p.recent.isEmpty());   // tanpa uuid → tak bisa dibatalkan → dilewati
    }

    @Test(expected = IllegalArgumentException.class)
    public void garbageIsRejected() {
        FillPlan.parse("<html>502 Bad Gateway</html>");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonObjectIsRejected() {
        FillPlan.parse("[1,2,3]");
    }

    @Test public void copyIsDeep() {
        FillPlan a = FillPlan.parse(DEBUG_FAKE_PLAN_JSON);
        FillPlan b = a.copy();
        b.products.get(0).ready = 99;
        b.totals.toFill = 99;
        b.recent.clear();
        assertEquals(5, a.products.get(0).ready);
        assertEquals(7, a.totals.toFill);
        assertEquals(2, a.recent.size());
    }

    @Test public void textHelpersMatchSpecWording() {
        FillPlan p = FillPlan.parse(DEBUG_FAKE_PLAN_JSON);
        FillPlan.Product a = p.products.get(0);
        assertEquals("ISI 7 LAGI", FillText.headline(a));
        assertEquals("Pesanan berjalan 10 (+2 cadangan) = 12 · Siap di rak 5", FillText.detail(a));
        assertEquals(41, FillText.progressPercent(a));           // 5/12

        FillPlan.Product b = p.products.get(1);
        assertEquals("✓ Terpenuhi · lebih 4", FillText.headline(b));
        assertEquals(100, FillText.progressPercent(b));          // ready>target dijepit 100

        FillPlan.Product c = p.products.get(2);
        assertEquals("Tak ada pesanan", FillText.headline(c));
        assertEquals("Tak ada pesanan berjalan · Siap di rak 0", FillText.detail(c));
        assertFalse(FillText.isActive(c));
        assertTrue(FillText.isActive(a));
        assertTrue(FillText.isActive(b));

        assertEquals("Perlu diisi sekarang", FillText.heroTitle(p));
        assertEquals("7 galon", FillText.heroValue(p));
        assertEquals("5 pesanan · 13 galon · 6 sudah di jalan", FillText.heroSub(p));
        assertNotNull(FillText.unmappedWarning(p));
        assertEquals("10:10", FillText.timeOf("2026-09-30 10:10:00.123456"));
        assertEquals("Isi · Galon 19L · +10", FillText.recentLabel(p.recent.get(0)));
        assertEquals("Hitung stok · Galon 19L · rak 15", FillText.recentLabel(p.recent.get(1)));
    }

    @Test public void metAndIdleHero() {
        FillPlan p = FillPlan.parse("{\"status\":\"met\",\"totals\":{\"backlog\":4,\"orders\":2}}");
        assertEquals("✓", FillText.heroValue(p));
        FillPlan q = FillPlan.parse("{\"status\":\"idle\"}");
        assertEquals("Belum ada pesanan berjalan", FillText.heroTitle(q));
        assertNull(FillText.unmappedWarning(q));
    }

    @Test public void cacheEnvelopeRoundTrips() {
        String enc = FillText.encodeCache(DEBUG_FAKE_PLAN_JSON, 1_780_000_000_123L);
        Object[] dec = FillText.decodeCache(enc);
        assertNotNull(dec);
        assertEquals(1_780_000_000_123L, ((Long) dec[0]).longValue());
        assertEquals("9f2c1d", FillPlan.parse((String) dec[1]).rev);
        assertNull(FillText.decodeCache(""));
        assertNull(FillText.decodeCache("rusak{"));
        assertNull(FillText.decodeCache("{\"t\":1}"));
    }

    @Test public void serverRejectionClassification() {
        // Status HTTP telanjang TIDAK cukup: 4xx tanpa badan JSON berkode dari server = bukan kata akhir soal ketukan
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(403, "<html>Attention Required! | Cloudflare</html>"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(404, "{\"message\":\"The route api/fill/log could not be found.\"}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(405, ""));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(410, "<html>retired</html>"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(422, "{\"message\":\"The given data was invalid.\",\"errors\":{}}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(401, "{\"message\":\"Unauthenticated.\"}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(429, "{\"ok\":false,\"code\":\"rate_limited\"}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(500, "{\"code\":\"boom\"}"));
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(503, null));
        // kode permanen yang dikenal FillPlanController (dan statusnya 4xx) → buang
        assertEquals(FillText.WriteVerdict.DROP, FillText.classifyWrite(422, "{\"ok\":false,\"code\":\"validation\"}"));
        assertEquals(FillText.WriteVerdict.DROP, FillText.classifyWrite(422, "{\"ok\":false,\"code\":\"product_invalid\"}"));
        assertEquals(FillText.WriteVerdict.DROP, FillText.classifyWrite(404, "{\"ok\":false,\"code\":\"log_not_found\"}"));
        assertEquals(FillText.WriteVerdict.DROP, FillText.classifyWrite(403, "{\"ok\":false,\"code\":\"not_owner\"}"));
        assertEquals(FillText.WriteVerdict.DROP, FillText.classifyWrite(403, "{\"ok\":false,\"code\":\"void_window_closed\"}"));
        // kode permanen tapi status 5xx → tak dipercaya
        assertEquals(FillText.WriteVerdict.RETRY, FillText.classifyWrite(502, "{\"ok\":false,\"code\":\"not_owner\"}"));
        // identitas → SIMPAN operasinya (bukan buang), termasuk staff_inactive
        for (String c : new String[]{"forbidden_role", "staff_not_found", "staff_uuid_missing", "staff_inactive"}) {
            assertEquals(c, FillText.WriteVerdict.KEEP_BLOCKED,
                    FillText.classifyWrite(403, "{\"ok\":false,\"code\":\"" + c + "\"}"));
        }

        String notOwner = "{\"ok\":false,\"code\":\"not_owner\",\"message\":\"Bukan catatanmu\"}";
        String forbidden = "{\"ok\":false,\"code\":\"forbidden_role\",\"message\":\"Peran tak diizinkan\"}";
        assertEquals("Bukan catatanmu", FillText.rejectionMessage(notOwner, "x"));
        assertEquals("x", FillText.rejectionMessage("<html>", "x"));
        // 403 not_owner adalah penolakan ketukan, BUKAN penolakan identitas (layar jangan terkunci)
        assertFalse(FillText.isIdentityRejection(notOwner));
        assertTrue(FillText.isIdentityRejection(forbidden));
        assertTrue(FillText.isIdentityRejection("{\"code\":\"staff_not_found\"}"));
        assertTrue(FillText.isIdentityRejection("{\"code\":\"staff_uuid_missing\"}"));
        assertTrue(FillText.isIdentityRejection("{\"code\":\"staff_inactive\"}"));
        assertFalse(FillText.isIdentityRejection("plain 404"));
    }
}
