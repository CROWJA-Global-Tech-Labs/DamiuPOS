package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.SejalurPlanLogic.Absorb;
import com.crowja.damiupos.util.SejalurPlanLogic.Order;
import com.crowja.damiupos.util.SejalurPlanLogic.Pending;
import com.crowja.damiupos.util.SejalurPlanLogic.PlanRow;
import com.crowja.damiupos.util.SejalurPlanLogic.Target;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SejalurPlanLogicTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    // ------------------------------------------------------------------ umur

    @Test
    public void ageUsesServerMinutesBeforePhoneClock() {
        // HP 10 menit lebih cepat dari server: queued_at bilang 70 mnt, server bilang 60 → server menang.
        assertEquals(60L, SejalurPlanLogic.ageMinutes(60L, NOW - 70 * MIN, NOW));
    }

    @Test
    public void ageFallsBackToQueuedAtThenUnknown() {
        assertEquals(42L, SejalurPlanLogic.ageMinutes(-1L, NOW - 42 * MIN - 5000L, NOW));
        assertEquals(SejalurPlanLogic.AGE_UNKNOWN, SejalurPlanLogic.ageMinutes(-1L, Long.MAX_VALUE, NOW));
        assertEquals("", SejalurPlanLogic.ageText(SejalurPlanLogic.AGE_UNKNOWN));
        assertEquals("", SejalurPlanLogic.mapAgeLine(SejalurPlanLogic.AGE_UNKNOWN));
    }

    @Test
    public void futureQueuedAtIsScheduledEvenWhenServerSaysZero() {
        // Pesanan Terjadwal: server mengirim age_minutes=0, queued_at 90 mnt lagi → negatif (hitung mundur).
        long age = SejalurPlanLogic.ageMinutes(0L, NOW + 90 * MIN, NOW);
        assertEquals(-90L, age);
        assertEquals("terjadwal · 1 jam 30 mnt lagi", SejalurPlanLogic.ageText(age));
        assertEquals("🗓️ Terjadwal — 1 jam 30 mnt lagi", SejalurPlanLogic.mapAgeLine(age));
        assertEquals(-1, SejalurPlanLogic.ageLevel(age));
    }

    @Test
    public void smallClockSkewIntoFutureIsNotScheduled() {
        // queued_at 1 mnt di depan jam HP (selisih jam) → masih "baru masuk", bukan terjadwal.
        assertEquals(0L, SejalurPlanLogic.ageMinutes(-1L, NOW + MIN, NOW));
        assertEquals(0L, SejalurPlanLogic.ageMinutes(0L, NOW + MIN, NOW));
    }

    @Test
    public void durationLabels() {
        assertEquals("< 1 mnt", SejalurPlanLogic.durationText(0));
        assertEquals("45 mnt", SejalurPlanLogic.durationText(45));
        assertEquals("1 jam 5 mnt", SejalurPlanLogic.durationText(65));
        assertEquals("2 jam", SejalurPlanLogic.durationText(120));
        assertEquals("1 hari 3 jam", SejalurPlanLogic.durationText(1440 + 185));
        assertEquals("2 hari", SejalurPlanLogic.durationText(2880));
        assertEquals("⏱ menunggu 1 jam 5 mnt", SejalurPlanLogic.mapAgeLine(65));
    }

    @Test
    public void ageLevelsMatchQueueBadgeThresholds() {
        assertEquals(0, SejalurPlanLogic.ageLevel(59));
        assertEquals(1, SejalurPlanLogic.ageLevel(60));
        assertEquals(1, SejalurPlanLogic.ageLevel(119));
        assertEquals(2, SejalurPlanLogic.ageLevel(120));
    }

    // ------------------------------------------------------------------ geometri

    @Test
    public void originPrefersDepotThenGps() {
        double[] o = SejalurPlanLogic.origin(-7.0, 110.4, -7.1, 110.5);
        assertEquals(-7.0, o[0], 1e-9);
        o = SejalurPlanLogic.origin(0, 0, -7.1, 110.5);
        assertEquals(-7.1, o[0], 1e-9);
        assertNull(SejalurPlanLogic.origin(0, 0, 0, 0));
        assertNull(SejalurPlanLogic.origin(0, 0, Double.NaN, Double.NaN));
    }

    @Test
    public void kmText() {
        assertEquals("850 m", SejalurPlanLogic.kmText(0.85));
        assertEquals("12,3 km", SejalurPlanLogic.kmText(12.34));
        assertEquals("", SejalurPlanLogic.kmText(Double.MAX_VALUE));
    }

    // ------------------------------------------------------------------ pemilih

    private static Target target(String uuid, String name, double km, long ageMin, boolean geo) {
        Target t = new Target();
        t.uuid = uuid;
        t.name = name;
        t.lat = geo ? -7.0 + km / 111.0 : 0;
        t.lng = geo ? 110.4 : 0;
        t.elapsedMs = ageMin * MIN;
        return t;
    }

    @Test
    public void mergeDedupsByUuidKeepingFirstSource() {
        Target mine = target("u1", "Saya", 1, 10, true);
        Target open = target("u1", "Terbuka", 1, 10, true);
        open.source = SejalurPlanLogic.SRC_OPEN;
        Target other = target("u2", "Lain", 2, 5, true);
        Target blank = target("", "Tanpa uuid", 2, 5, true);
        List<Target> out = SejalurPlanLogic.merge(Collections.singletonList(mine), Arrays.asList(open, blank),
                Collections.singletonList(other));
        assertEquals(2, out.size());
        assertEquals("Saya", out.get(0).name);
        assertEquals("u2", out.get(1).uuid);
    }

    @Test
    public void sortFarthestFirstWithUnselectableLast() {
        List<Target> rows = new ArrayList<>();
        rows.add(target("near", "Dekat", 1, 200, true));
        rows.add(target("nogeo", "Tanpa Lokasi", 0, 500, false));
        rows.add(target("far", "Jauh", 9, 5, true));
        rows.add(target("mid", "Tengah", 4, 90, true));
        SejalurPlanLogic.fillDistances(rows, new double[]{-7.0, 110.4});
        List<Target> byDist = SejalurPlanLogic.filterSort(rows, "", false);
        assertEquals(Arrays.asList("far", "mid", "near", "nogeo"), uuids(byDist));
        List<Target> byAge = SejalurPlanLogic.filterSort(rows, "", true);
        assertEquals(Arrays.asList("near", "mid", "far", "nogeo"), uuids(byAge));
        assertFalse(rows.get(1).selectable());
        assertEquals("tanpa lokasi — tidak bisa dipilih", rows.get(1).blockReason());
        assertEquals(Double.MAX_VALUE, rows.get(1).distKm, 0.0);
    }

    @Test
    public void scheduledTargetIsNotSelectable() {
        Target t = target("s", "Terjadwal", 3, -60, true);
        assertFalse(t.selectable());
        assertEquals("pesanan terjadwal — belum boleh diantar", t.blockReason());
        Target skew = target("k", "Selisih jam", 3, -1, true);
        assertTrue(skew.selectable());
    }

    @Test
    public void searchMatchesNameAreaItemsAndDevice() {
        Target a = target("a", "Bu Sri", 1, 1, true);
        a.area = "Banyumanik";
        Target b = target("b", "Pak Budi", 1, 1, true);
        b.items = "MIN×2";
        b.deviceLabel = "Motor 2";
        List<Target> rows = Arrays.asList(a, b);
        assertEquals(Collections.singletonList("a"), uuids(SejalurPlanLogic.filterSort(rows, "banyu", false)));
        assertEquals(Collections.singletonList("b"), uuids(SejalurPlanLogic.filterSort(rows, "min", false)));
        assertEquals(Collections.singletonList("b"), uuids(SejalurPlanLogic.filterSort(rows, "MOTOR", false)));
        assertEquals(2, SejalurPlanLogic.filterSort(rows, "  ", false).size());
    }

    // ------------------------------------------------------------------ pratinjau

    private static PlanRow row(String uuid, int galon, double km, boolean pickup) {
        PlanRow r = new PlanRow();
        r.uuid = uuid;
        r.name = uuid;
        r.galon = galon;
        r.kmFromOrigin = km;
        r.pickupOnly = pickup;
        return r;
    }

    @Test
    public void capacityCountsCheckedSalesOnlyAndAlwaysTarget() {
        PlanRow a = row("a", 10, 1, false);
        PlanRow p = row("p", 6, 2, true);       // ambil galon: 0 muatan
        PlanRow t = row("t", 12, 9, false);
        t.isTarget = true;
        Set<String> checked = new HashSet<>(Arrays.asList("a", "p"));
        assertEquals(22, SejalurPlanLogic.checkedGalon(Arrays.asList(a, p, t), checked));
        assertEquals(3, SejalurPlanLogic.checkedCount(Arrays.asList(a, p, t), checked));
        checked.remove("a");
        assertEquals(12, SejalurPlanLogic.checkedGalon(Arrays.asList(a, p, t), checked));
    }

    @Test
    public void startUuidsKeepsShownOrderInsertsAlternatesByKmAndTargetLast() {
        PlanRow a = row("a", 5, 1.0, false);
        PlanRow b = row("b", 5, 3.0, false);
        PlanRow c = row("c", 5, 6.0, false);
        PlanRow t = row("t", 5, 9.0, false);
        t.isTarget = true;
        PlanRow x = row("x", 5, 4.5, false);    // alternatif di antara b dan c
        PlanRow y = row("y", 5, 0.5, false);    // alternatif paling dekat
        PlanRow z = row("z", 5, 7.0, false);    // alternatif tak dicentang
        x.alternate = y.alternate = z.alternate = true;
        Set<String> checked = new HashSet<>(Arrays.asList("a", "c", "x", "y", "t"));
        List<String> out = SejalurPlanLogic.startUuids(Arrays.asList(a, b, c, t), Arrays.asList(x, y, z), checked, "t");
        assertEquals(Arrays.asList("y", "a", "x", "c", "t"), out);
    }

    @Test
    public void startUuidsAlwaysIncludesTargetEvenUncheckedAndFarAlternateStaysBeforeIt() {
        PlanRow t = row("t", 5, 9.0, false);
        t.isTarget = true;
        PlanRow far = row("far", 5, 9.4, false);   // sedikit lebih jauh dari target (≤ 5%) tetap sebelum target
        Set<String> checked = new HashSet<>(Collections.singletonList("far"));
        List<String> out = SejalurPlanLogic.startUuids(Collections.singletonList(t), Collections.singletonList(far), checked, "t");
        assertEquals(Arrays.asList("far", "t"), out);
    }

    @Test
    public void startUuidsCapsAtMaxStopsWithoutDroppingTarget() {
        List<PlanRow> main = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            main.add(row("o" + i, 1, i, false));
            checked.add("o" + i);
        }
        List<String> out = SejalurPlanLogic.startUuids(main, null, checked, "T");
        assertEquals(SejalurPlanLogic.MAX_STOPS, out.size());
        assertEquals("T", out.get(out.size() - 1));
    }

    @Test
    public void summaryLine() {
        assertEquals("8 order · 38/40 galon · rute ≈ 12,3 km (jalan)",
                SejalurPlanLogic.summaryLine(8, 38, 40, 12.3, "driving"));
        assertEquals("1 order · 12 galon · rute ≈ 850 m (garis lurus)",
                SejalurPlanLogic.summaryLine(1, 12, 0, 0.85, "straight_line"));
    }

    // ------------------------------------------------------------------ titipan & urutan

    @Test
    public void pendingRoundTripAndTtl() {
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "b", "t"));
        Pending back = SejalurPlanLogic.decodePending(SejalurPlanLogic.encodePending(p));
        assertNotNull(back);
        assertEquals(NOW, back.at);
        assertEquals("t", back.target);
        assertEquals(Arrays.asList("a", "b", "t"), back.uuids);
        assertFalse(back.expired(NOW + SejalurPlanLogic.PENDING_TTL_MS));
        assertTrue(back.expired(NOW + SejalurPlanLogic.PENDING_TTL_MS + 1));
        assertNull(SejalurPlanLogic.decodePending(""));
        assertNull(SejalurPlanLogic.decodePending("abc|t|a"));
        assertNull(SejalurPlanLogic.decodePending(NOW + "|t|"));
        // target tak dipegang server → kosong, tetap terbaca
        Pending noTarget = SejalurPlanLogic.decodePending(NOW + "||a,b");
        assertNotNull(noTarget);
        assertEquals("", noTarget.target);
    }

    @Test
    public void orderRoundTrip() {
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        ids.put("a", 11L);
        ids.put("t", 13L);
        Order back = SejalurPlanLogic.decodeOrder(SejalurPlanLogic.encodeOrder(new Order("t", ids)));
        assertNotNull(back);
        assertEquals(Arrays.asList(11L, 13L), back.idList());
        assertEquals(13L, back.targetId());
        assertNull(SejalurPlanLogic.decodeOrder("t|"));
        assertEquals(-1L, new Order("", ids).targetId());
    }

    @Test
    public void absorbStartsLocalRowsInPlanOrderAndKeepsMissingPending() {
        // a (milik sendiri) & t (Pesanan Terbuka, sudah ditandai lokal) ada; b (perangkat lain) belum tersinkron.
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "b", "t"));
        Map<String, Long> local = new HashMap<>();
        local.put("t", 30L);
        local.put("a", 10L);
        Absorb r = SejalurPlanLogic.absorb(p, null, local, Collections.<Long>emptySet(), NOW);
        assertEquals(Arrays.asList(10L, 30L), r.add);
        assertEquals(Arrays.asList("a", "t"), r.addUuids);
        assertEquals(Collections.singletonList("b"), r.missing);
        assertFalse(r.finished);
        assertEquals(Arrays.asList(10L, 30L), r.order.idList());

        // Sinkron berikutnya: b tiba → hanya b yang ditambahkan, urutan rit a, b, t (target terakhir).
        local.put("b", 20L);
        Absorb r2 = SejalurPlanLogic.absorb(p, r.order, local, Arrays.asList(10L, 30L), NOW + MIN);
        assertEquals(Collections.singletonList(20L), r2.add);
        assertTrue(r2.finished);
        assertFalse(r2.expiredMissing);
        assertEquals(Arrays.asList(10L, 20L, 30L), r2.order.idList());
        assertEquals(Arrays.asList(10L, 20L, 30L),
                SejalurPlanLogic.orderStops(Arrays.asList(30L, 10L, 20L), r2.order));
    }

    @Test
    public void absorbNeverReaddsAlreadyAbsorbedStop() {
        // a sudah diserap lalu Selesai (tak lagi berjalan, baris masih ada sebentar) → jangan dijalankan ulang.
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "b", "t"));
        LinkedHashMap<String, Long> prevIds = new LinkedHashMap<>();
        prevIds.put("a", 10L);
        prevIds.put("t", 30L);
        Order prev = new Order("t", prevIds);
        Map<String, Long> local = new HashMap<>();
        local.put("a", 10L);
        local.put("t", 30L);
        Absorb r = SejalurPlanLogic.absorb(p, prev, local, Collections.singletonList(30L), NOW);
        assertTrue(r.add.isEmpty());
        assertEquals(Collections.singletonList("b"), r.missing);
    }

    @Test
    public void absorbExpiryReportsMissingOnce() {
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "t"));
        Map<String, Long> local = new HashMap<>();
        local.put("t", 30L);
        Absorb r = SejalurPlanLogic.absorb(p, null, local, Collections.<Long>emptySet(),
                NOW + SejalurPlanLogic.PENDING_TTL_MS + 1);
        assertTrue(r.finished);
        assertTrue(r.expiredMissing);
        assertEquals(Collections.singletonList("a"), r.missing);
        assertEquals(Collections.singletonList(30L), r.add);
    }

    @Test
    public void orderStopsForcesTargetLastAndKeepsExtrasBeforeIt() {
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        ids.put("a", 1L);
        ids.put("b", 2L);
        ids.put("p", 3L);    // ambil galon sejalur — DeliveryPlanner menaruh KEMBALI paling belakang
        ids.put("t", 4L);
        Order o = new Order("t", ids);
        // Urutan planner lokal: prioritas t di depan, KEMBALI p di belakang, order tambahan 9 di tengah.
        List<Long> planner = Arrays.asList(4L, 2L, 9L, 1L, 3L);
        assertEquals(Arrays.asList(1L, 2L, 3L, 9L, 4L), SejalurPlanLogic.orderStops(planner, o));
    }

    @Test
    public void orderStopsWithoutTargetHeldOrDeliveredKeepsPlanThenExtras() {
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        ids.put("a", 1L);
        ids.put("b", 2L);
        Order noTarget = new Order("", ids);
        assertEquals(Arrays.asList(1L, 2L, 7L), SejalurPlanLogic.orderStops(Arrays.asList(7L, 2L, 1L), noTarget));
        // tanpa urutan aktif / satu pemberhentian → apa adanya
        assertEquals(Arrays.asList(7L, 2L), SejalurPlanLogic.orderStops(Arrays.asList(7L, 2L), null));
        assertEquals(Collections.singletonList(2L), SejalurPlanLogic.orderStops(Collections.singletonList(2L), noTarget));
    }

    private static List<String> uuids(List<Target> rows) {
        List<String> out = new ArrayList<>();
        for (Target t : rows) out.add(t.uuid);
        return out;
    }
}
