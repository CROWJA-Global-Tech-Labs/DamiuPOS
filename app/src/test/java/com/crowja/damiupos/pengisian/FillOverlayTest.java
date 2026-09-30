package com.crowja.damiupos.pengisian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Matematika overlay kotak-keluar + serialisasi kotak keluar. */
public class FillOverlayTest {

    private static FillPlan base() {
        return FillPlan.parse(FillPlanTest.DEBUG_FAKE_PLAN_JSON);
    }

    private static List<FillOutbox.Op> ops(FillOutbox.Op... o) {
        return new ArrayList<>(Arrays.asList(o));
    }

    @Test public void noOpsReturnsSameInstance() {
        FillPlan b = base();
        assertSame(b, FillOverlay.apply(b, Collections.<FillOutbox.Op>emptyList()));
        assertSame(b, FillOverlay.apply(b, null));
    }

    @Test public void pendingFillLowersToFillAndRaisesReady() {
        FillPlan b = base();   // 19L: target 12, ready 5, to_fill 7
        FillPlan v = FillOverlay.apply(b, ops(FillOutbox.Op.fill("s-1", "p-19l", 5, 1000L)));
        FillPlan.Product p = v.product("p-19l");
        assertEquals(10, p.ready);
        assertEquals(2, p.toFill);
        assertEquals(0, p.surplus);
        assertEquals(25, p.filledToday);
        assertEquals(2, v.totals.toFill);                 // produk lain sudah 0
        assertEquals(FillPlan.STATUS_NEED, v.status);
        // dasar tak berubah
        assertEquals(5, b.product("p-19l").ready);
        assertEquals(7, b.totals.toFill);
        assertNotSame(b, v);
    }

    @Test public void overfillTurnsIntoSurplusAndStatusMet() {
        FillPlan v = FillOverlay.apply(base(), ops(FillOutbox.Op.fill("s-1", "p-19l", 10, 1L)));
        FillPlan.Product p = v.product("p-19l");
        assertEquals(15, p.ready);
        assertEquals(0, p.toFill);
        assertEquals(3, p.surplus);
        assertEquals(0, v.totals.toFill);
        assertEquals(FillPlan.STATUS_MET, v.status);       // backlog > 0, tak ada kekurangan
    }

    @Test public void statusIdleWhenNoBacklogAtAll() {
        FillPlan b = FillPlan.parse("{\"status\":\"idle\",\"products\":[{\"product_uuid\":\"a\",\"name\":\"A\"}],"
                + "\"totals\":{\"backlog\":0}}");
        FillPlan v = FillOverlay.apply(b, ops(FillOutbox.Op.fill("s", "a", 3, 1L)));
        assertEquals(FillPlan.STATUS_IDLE, v.status);
        assertEquals(3, v.product("a").ready);
    }

    @Test public void fillAlreadyKnownByServerIsNotCountedTwice() {
        FillPlan b = base();
        FillOutbox.Op op = FillOutbox.Op.fill("s-1", "p-19l", 10, 1L);
        op.uuid = "r-1";   // server sudah punya baris ber-uuid ini (balasan kirim hilang di jalan)
        FillPlan v = FillOverlay.apply(b, ops(op));
        assertSame(b, v);  // tak ada yang berubah → dasar apa adanya
        assertEquals(5, v.product("p-19l").ready);
    }

    @Test public void unknownProductIsIgnored() {
        FillPlan b = base();
        assertSame(b, FillOverlay.apply(b, ops(FillOutbox.Op.fill("s", "tak-ada", 5, 1L))));
    }

    @Test public void countOverridesAbsoluteAndLaterFillsStack() {
        List<FillOutbox.Count> cs = new ArrayList<>();
        cs.add(new FillOutbox.Count("p-19l", 8));
        cs.add(new FillOutbox.Count("p-isi", 0));
        FillPlan v = FillOverlay.apply(base(), ops(
                FillOutbox.Op.fill("s", "p-19l", 100, 1L),      // disapu hitung sesudahnya
                FillOutbox.Op.count("s", cs, 2L),
                FillOutbox.Op.fill("s", "p-19l", 2, 3L)));      // menumpuk di atas hitung
        assertEquals(10, v.product("p-19l").ready);           // 8 + 2
        assertEquals(2, v.product("p-19l").toFill);
        assertEquals(0, v.product("p-isi").ready);
        assertEquals(5, v.product("p-isi").toFill);            // target 5, ready 0
        assertFalse(v.product("p-19l").needsCount);
        assertFalse(v.product("p-isi").needsCount);
        assertFalse(v.needsCount);                              // kedua produk berpesanan sudah dihitung
        assertEquals(7, v.totals.toFill);                       // 2 + 5
    }

    @Test public void needsCountStaysWhileAnActiveProductIsUncounted() {
        List<FillOutbox.Count> cs = new ArrayList<>();
        cs.add(new FillOutbox.Count("p-19l", 8));
        FillPlan v = FillOverlay.apply(base(), ops(FillOutbox.Op.count("s", cs, 1L)));
        assertTrue(v.product("p-isi").needsCount);   // masih butuh hitung, backlog 3 > 0
        assertTrue(v.needsCount);
    }

    @Test public void voidOfServerFillRollsItBackAndHidesTheRow() {
        FillPlan b = base();   // r-1 = FILL 10 untuk 19L (voidable)
        FillPlan v = FillOverlay.apply(b, ops(FillOutbox.Op.voidOf("s-1", "r-1", 1L)));
        FillPlan.Product p = v.product("p-19l");
        assertEquals(0, p.ready);                               // 5 - 10 dijepit 0
        assertEquals(12, p.toFill);
        assertEquals(10, p.filledToday);                        // 20 - 10
        assertEquals(1, v.recent.size());
        assertEquals("r-2", v.recent.get(0).uuid);
        assertEquals(2, b.recent.size());                       // dasar utuh
    }

    @Test public void voidOfCountRowOnlyHidesIt() {
        FillPlan v = FillOverlay.apply(base(), ops(FillOutbox.Op.voidOf("s", "r-2", 1L)));
        assertEquals(5, v.product("p-19l").ready);              // tak diketahui HP → server yang hitung ulang
        assertEquals(1, v.recent.size());
        assertEquals("r-1", v.recent.get(0).uuid);
    }

    @Test public void toFillIncreasedAlertRule() {
        assertFalse(FillOverlay.toFillIncreased(null, 9));      // render pertama: diam
        assertFalse(FillOverlay.toFillIncreased(7, 7));
        assertFalse(FillOverlay.toFillIncreased(7, 3));         // turun (ketukan sendiri): diam
        assertTrue(FillOverlay.toFillIncreased(0, 1));
        assertTrue(FillOverlay.toFillIncreased(7, 12));
    }

    // ------------------------------------------------------------------ kotak keluar

    @Test public void outboxRoundTripsAllOpTypesInOrder() {
        FillOutbox box = new FillOutbox();
        FillOutbox.Op f = FillOutbox.Op.fill("s-1", "p-19l", 5, 1_780_000_000_000L);
        List<FillOutbox.Count> cs = new ArrayList<>();
        cs.add(new FillOutbox.Count("p-19l", 12));
        cs.add(new FillOutbox.Count("p-isi", 0));
        FillOutbox.Op c = FillOutbox.Op.count("s-1", cs, 1_780_000_000_500L);
        FillOutbox.Op v = FillOutbox.Op.voidOf("s-1", "r-1", 1_780_000_001_000L);
        box.add(f);
        box.add(c);
        box.add(v);

        FillOutbox back = FillOutbox.fromJson(box.toJson());
        assertEquals(3, back.size());
        List<FillOutbox.Op> o = back.ops();
        assertEquals(FillOutbox.TYPE_FILL, o.get(0).type);
        assertEquals(f.uuid, o.get(0).uuid);
        assertEquals("p-19l", o.get(0).productUuid);
        assertEquals(5, o.get(0).qty);
        assertEquals(1_780_000_000_000L, o.get(0).createdAtMs);   // > Integer.MAX_VALUE tetap utuh
        assertEquals("s-1", o.get(0).staffUuid);
        assertEquals(FillOutbox.TYPE_COUNT, o.get(1).type);
        assertEquals(2, o.get(1).counts.size());
        assertEquals(12, o.get(1).counts.get(0).qty);
        assertEquals(0, o.get(1).counts.get(1).qty);
        assertEquals(FillOutbox.TYPE_VOID, o.get(2).type);
        assertEquals("r-1", o.get(2).targetUuid);
    }

    @Test public void outboxRemoveByUuidAndUuidsAreUnique() {
        FillOutbox box = new FillOutbox();
        FillOutbox.Op a = FillOutbox.Op.fill("s", "p", 1, 1L);
        FillOutbox.Op b = FillOutbox.Op.fill("s", "p", 1, 2L);
        assertFalse(a.uuid.equals(b.uuid));                    // tiap ketukan uuid sendiri
        box.add(a);
        box.add(b);
        assertTrue(box.remove(a.uuid));
        assertFalse(box.remove(a.uuid));
        assertEquals(1, box.size());
        assertEquals(b.uuid, box.ops().get(0).uuid);
    }

    @Test public void outboxToleratesGarbageAndDropsBrokenOps() {
        assertTrue(FillOutbox.fromJson(null).isEmpty());
        assertTrue(FillOutbox.fromJson("").isEmpty());
        assertTrue(FillOutbox.fromJson("{oops").isEmpty());
        assertTrue(FillOutbox.fromJson("{\"a\":1}").isEmpty());
        FillOutbox box = FillOutbox.fromJson("[{\"type\":\"FILL\",\"uuid\":\"u1\",\"productUuid\":\"p\",\"qty\":3},"
                + "{\"type\":\"FILL\",\"uuid\":\"u2\",\"productUuid\":\"p\",\"qty\":0},"
                + "{\"type\":\"COUNT\",\"uuid\":\"u3\",\"counts\":[]},"
                + "{\"type\":\"???\",\"uuid\":\"u4\"},"
                + "{\"type\":\"VOID\",\"uuid\":\"u5\"}]");
        assertEquals(1, box.size());
        assertEquals("u1", box.ops().get(0).uuid);
        assertEquals("", box.ops().get(0).staffUuid);          // data lama tanpa staf tetap terbaca
    }
}
