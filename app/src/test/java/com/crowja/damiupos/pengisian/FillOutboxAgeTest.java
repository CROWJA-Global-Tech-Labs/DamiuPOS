package com.crowja.damiupos.pengisian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Konversi "kapan diketuk" → {@code age_seconds} (yang dikirim ke server, jam server yang mencap),
 * kedaluwarsa 24 jam, kepemilikan staf, dan riwayat (Batal hanya di baris sendiri, sesi hitung digabung).
 */
public class FillOutboxAgeTest {

    private static final long WALL = 1_780_000_000_000L;
    private static final long ELAPSED = 3_600_000L;   // HP menyala 1 jam saat diketuk

    private static FillOutbox.Op tap() {
        return FillOutbox.Op.fill("s", "p", 5, WALL, ELAPSED);
    }

    // ------------------------------------------------------------------ age_seconds

    @Test public void sameBootUsesElapsedAndWallAgreeing() {
        assertEquals(0, tap().ageSeconds(WALL, ELAPSED));
        assertEquals(95, tap().ageSeconds(WALL + 95_000L, ELAPSED + 95_000L));
        assertEquals(95, tap().ageSeconds(WALL + 95_999L, ELAPSED + 95_999L));   // dibulatkan ke bawah
    }

    @Test public void wallClockSetBackwardsDoesNotShrinkAge() {
        // jam dinding dimundurkan 1 jam (NTP), tapi sebenarnya 10 menit berlalu
        assertEquals(600, tap().ageSeconds(WALL - 3_000_000L, ELAPSED + 600_000L));
    }

    @Test public void rebootFallsBackToWallClock() {
        // reboot: elapsedRealtime mulai dari nol lagi (lebih kecil dari saat diketuk)
        assertEquals(1800, tap().ageSeconds(WALL + 1_800_000L, 120_000L));
    }

    @Test public void unknownElapsedOrLegacyCreatedAtAreSafe() {
        FillOutbox.Op noElapsed = FillOutbox.Op.fill("s", "p", 1, WALL);      // createdElapsedMs = 0
        assertEquals(30, noElapsed.ageSeconds(WALL + 30_000L, 999_999_999L));
        FillOutbox.Op legacy = FillOutbox.Op.fill("s", "p", 1, 0L, 0L);       // createdAtMs = 0
        assertEquals(0, legacy.ageSeconds(WALL, ELAPSED));                    // tak diketahui → umur 0, bukan 6 jam
    }

    @Test public void ageIsClampedToSixHoursAndNeverNegative() {
        assertEquals(FillOutbox.MAX_AGE_SECONDS, tap().ageSeconds(WALL + 7L * 3_600_000L, ELAPSED + 7L * 3_600_000L));
        assertEquals(21_600, FillOutbox.MAX_AGE_SECONDS);
        assertEquals(0, tap().ageSeconds(WALL - 5_000L, ELAPSED - 5_000L));   // jam mundur → 0
    }

    // ------------------------------------------------------------------ kedaluwarsa

    @Test public void expiryAt24Hours() {
        FillOutbox.Op o = tap();
        assertFalse(o.isExpired(WALL + 24L * 3_600_000L, ELAPSED + 24L * 3_600_000L));
        assertTrue(o.isExpired(WALL + 24L * 3_600_000L + 1L, ELAPSED + 24L * 3_600_000L + 1L));
        FillOutbox box = new FillOutbox();
        box.add(o);
        FillOutbox.Op young = FillOutbox.Op.fill("s", "p", 1, WALL + 25L * 3_600_000L, ELAPSED + 25L * 3_600_000L);
        box.add(young);
        assertEquals(1, box.removeExpired(WALL + 25L * 3_600_000L, ELAPSED + 25L * 3_600_000L));
        assertEquals(1, box.size());
        assertEquals(young.uuid, box.ops().get(0).uuid);
    }

    // ------------------------------------------------------------------ kepemilikan + serialisasi

    @Test public void ownershipFilter() {
        FillOutbox.Op a = FillOutbox.Op.fill("s-A", "p", 1, WALL, ELAPSED);
        FillOutbox.Op b = FillOutbox.Op.fill("s-B", "p", 1, WALL, ELAPSED);
        FillOutbox.Op legacy = FillOutbox.Op.fill("", "p", 1, WALL, ELAPSED);
        List<FillOutbox.Op> all = new ArrayList<>();
        all.add(a);
        all.add(b);
        all.add(legacy);
        List<FillOutbox.Op> mineB = FillOutbox.ownedBy(all, "s-B");
        assertEquals(2, mineB.size());                 // miliknya + yang tanpa pemilik (data lama)
        assertSame(b, mineB.get(0));
        assertSame(legacy, mineB.get(1));
        assertEquals(0, FillOutbox.ownedBy(all, null).size());
        assertEquals(1, FillOutbox.ownedBy(Collections.singletonList(a), "s-A").size());
    }

    @Test public void createdElapsedSurvivesRoundTripAndOldJsonDefaultsToZero() {
        FillOutbox box = new FillOutbox();
        box.add(tap());
        FillOutbox back = FillOutbox.fromJson(box.toJson());
        assertEquals(ELAPSED, back.ops().get(0).createdElapsedMs);
        FillOutbox old = FillOutbox.fromJson("[{\"type\":\"FILL\",\"uuid\":\"u1\",\"productUuid\":\"p\",\"qty\":3}]");
        assertEquals(0L, old.ops().get(0).createdElapsedMs);
    }

    // ------------------------------------------------------------------ riwayat

    private static FillPlan.Recent recent(String uuid, String kind, String batch, String staff, boolean voidable) {
        FillPlan.Recent r = new FillPlan.Recent();
        r.uuid = uuid;
        r.kind = kind;
        r.batchUuid = batch;
        r.staffUuid = staff;
        r.staffName = "Nama " + staff;
        r.productName = "Galon";
        r.qty = 5;
        r.loggedAt = "2026-10-01 09:30:00";
        r.voidable = voidable;
        return r;
    }

    @Test public void batalOnlyOnOwnRowsUnlessAdmin() {
        List<FillPlan.Recent> rec = new ArrayList<>();
        rec.add(recent("r-mine", FillPlan.KIND_FILL, "", "s-me", true));
        rec.add(recent("r-other", FillPlan.KIND_FILL, "", "s-other", true));
        rec.add(recent("r-old", FillPlan.KIND_FILL, "", "s-me", false));

        List<FillText.HistoryRow> rows = FillText.historyRows(rec, "s-me", false);
        assertEquals(3, rows.size());
        assertEquals("r-mine", rows.get(0).voidTarget);
        assertNull(rows.get(1).voidTarget);            // baris rekan: tanpa Batal
        assertNull(rows.get(2).voidTarget);            // lewat jendela 15 menit

        List<FillText.HistoryRow> admin = FillText.historyRows(rec, "s-adm", true);
        assertEquals("r-mine", admin.get(0).voidTarget);
        assertEquals("r-other", admin.get(1).voidTarget);
        assertNull(admin.get(2).voidTarget);           // admin pun tunduk pada voidable (window) di layar

        assertNull(FillText.historyRows(rec, "", false).get(0).voidTarget);   // tanpa identitas → tak ada Batal
    }

    @Test public void countSessionIsOneRowVoidedByBatchUuid() {
        List<FillPlan.Recent> rec = new ArrayList<>();
        rec.add(recent("c-1", FillPlan.KIND_COUNT, "batch-9", "s-me", true));
        rec.add(recent("c-2", FillPlan.KIND_COUNT, "batch-9", "s-me", true));
        rec.add(recent("c-3", FillPlan.KIND_COUNT, "batch-9", "s-me", true));
        rec.add(recent("f-1", FillPlan.KIND_FILL, "", "s-me", true));

        List<FillText.HistoryRow> rows = FillText.historyRows(rec, "s-me", false);
        assertEquals(2, rows.size());
        assertEquals("Hitung stok · 3 produk", rows.get(0).label);
        assertEquals("batch-9", rows.get(0).voidTarget);   // batch_uuid dari recent[].batch_uuid, bukan uuid baris
        assertEquals("f-1", rows.get(1).voidTarget);

        // satu baris sesi milik orang lain → seluruh sesi tak bisa dibatalkan di sini
        rec.get(1).staffUuid = "s-other";
        assertNull(FillText.historyRows(rec, "s-me", false).get(0).voidTarget);

        // sesi berisi satu produk tetap dibatalkan lewat batch_uuid-nya
        List<FillPlan.Recent> one = new ArrayList<>();
        one.add(recent("c-9", FillPlan.KIND_COUNT, "batch-1", "s-me", true));
        FillText.HistoryRow only = FillText.historyRows(one, "s-me", false).get(0);
        assertEquals("batch-1", only.voidTarget);
        assertEquals("Hitung stok · Galon · rak 5", only.label);
    }

    @Test public void batchUuidParsedFromPlanAndVoidOverlayHidesWholeBatch() {
        String json = "{\"ok\":true,\"rev\":\"x\",\"products\":[{\"product_uuid\":\"p-1\",\"name\":\"A\",\"target\":5,\"ready\":2,\"to_fill\":3}],"
                + "\"recent\":["
                + "{\"uuid\":\"c-1\",\"kind\":\"COUNT\",\"batch_uuid\":\"b-1\",\"product_uuid\":\"p-1\",\"qty\":2,\"voidable\":true},"
                + "{\"uuid\":\"c-2\",\"kind\":\"COUNT\",\"batch_uuid\":\"b-1\",\"product_uuid\":\"p-2\",\"qty\":4,\"voidable\":true},"
                + "{\"uuid\":\"f-1\",\"kind\":\"FILL\",\"batch_uuid\":null,\"product_uuid\":\"p-1\",\"qty\":1,\"voidable\":true}]}";
        FillPlan plan = FillPlan.parse(json);
        assertEquals("b-1", plan.recent.get(0).batchUuid);
        assertEquals("", plan.recent.get(2).batchUuid);          // FILL: batch null → kosong, bukan "null"

        List<FillOutbox.Op> ops = new ArrayList<>();
        ops.add(FillOutbox.Op.voidOf("s", "b-1", WALL, ELAPSED));
        FillPlan v = FillOverlay.apply(plan, ops);
        assertEquals(1, v.recent.size());                        // kedua baris sesi hilang
        assertEquals("f-1", v.recent.get(0).uuid);
    }
}
