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
    public void slowPhoneClockNeverTurnsQueuedOrderIntoScheduled() {
        // HP 10 menit TERLAMBAT: order masuk 3 mnt lalu (server: age_minutes=3) → queued_at terbaca
        // 7 mnt di masa depan menurut jam HP. Tetap "menunggu 3 mnt", bukan Pesanan Terjadwal.
        long phoneNow = NOW - 10 * MIN;
        long queued = NOW - 3 * MIN;
        long age = SejalurPlanLogic.ageMinutes(3L, queued, phoneNow);
        assertEquals(3L, age);
        assertEquals("⏱ menunggu 3 mnt", SejalurPlanLogic.mapAgeLine(age));
        assertEquals("", SejalurPlanLogic.mapBlockReason(age, -7.0, 110.4, false));
        // Selisih kecil (HP 4 mnt terlambat, order 1 mnt) pun begitu.
        assertEquals(1L, SejalurPlanLogic.ageMinutes(1L, NOW - MIN, NOW - 4 * MIN));
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
        // Yang terpotong DILAPORKAN (pratinjau menolak start selama ada) — paling jauh dulu yang terpotong.
        assertEquals(Arrays.asList("o24", "o25", "o26", "o27", "o28", "o29"),
                SejalurPlanLogic.startOverflow(main, null, checked, "T"));
    }

    @Test
    public void checkingLocksAtMaxStopsSoNothingIsSilentlyDropped() {
        // Server: 24 order + titik terjauh (25 pemberhentian) + 1 alternatif.
        List<PlanRow> main = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        for (int i = 0; i < 24; i++) {
            main.add(row("o" + i, 1, i, true));
            checked.add("o" + i);
        }
        PlanRow t = row("T", 2, 30, false);
        t.isTarget = true;
        main.add(t);
        PlanRow alt = row("x", 1, 5.5, false);
        alt.alternate = true;
        List<PlanRow> all = new ArrayList<>(main);
        all.add(alt);
        assertFalse(SejalurPlanLogic.canCheckMore(all, checked));
        assertTrue(SejalurPlanLogic.startOverflow(main, Collections.singletonList(alt), checked, "T").isEmpty());

        // Kalau alternatif tetap tercentang (mis. dari keadaan lama) → 26 tercentang, satu terpotong & dilaporkan.
        checked.add("x");
        assertEquals(26, SejalurPlanLogic.checkedCount(all, checked));
        List<String> over = SejalurPlanLogic.startOverflow(main, Collections.singletonList(alt), checked, "T");
        assertEquals(Collections.singletonList("o23"), over);
        List<String> sent = SejalurPlanLogic.startUuids(main, Collections.singletonList(alt), checked, "T");
        assertEquals(SejalurPlanLogic.MAX_STOPS, sent.size());
        assertFalse(sent.contains("o23"));

        // Tukar: lepas satu order rencana → alternatif muat, tak ada yang terpotong.
        checked.remove("o3");
        assertTrue(SejalurPlanLogic.startOverflow(main, Collections.singletonList(alt), checked, "T").isEmpty());
        assertFalse(SejalurPlanLogic.canCheckMore(all, checked));
        checked.remove("x");
        assertTrue(SejalurPlanLogic.canCheckMore(all, checked));
    }

    @Test
    public void mapPinEligibilityMatchesListPicker() {
        assertEquals("", SejalurPlanLogic.mapBlockReason(30, -7.0, 110.4, false));
        assertEquals("", SejalurPlanLogic.mapBlockReason(SejalurPlanLogic.AGE_UNKNOWN, -7.0, 110.4, false));
        // Pesanan Terjadwal (umur negatif) — sama dengan Target.blockReason pemilih daftar.
        Target sched = target("s", "Terjadwal", 3, -120, true);
        assertEquals(sched.blockReason(), SejalurPlanLogic.mapBlockReason(-120, sched.lat, sched.lng, false));
        assertEquals(SejalurPlanLogic.REASON_NO_GEO, SejalurPlanLogic.mapBlockReason(30, 0, 0, false));
        // Perangkat lain dengan permintaan Ambil Alih yang masih mengunci (pemilih daftar membuangnya).
        assertEquals(SejalurPlanLogic.REASON_CLAIM_PENDING, SejalurPlanLogic.mapBlockReason(30, -7.0, 110.4, true));
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
        Order back = SejalurPlanLogic.decodeOrder(SejalurPlanLogic.encodeOrder(new Order(NOW, "t", ids)));
        assertNotNull(back);
        assertEquals(NOW, back.planAt);
        assertEquals(Arrays.asList(11L, 13L), back.idList());
        assertEquals(13L, back.targetId());
        assertNull(SejalurPlanLogic.decodeOrder(NOW + "|t|"));
        assertNull(SejalurPlanLogic.decodeOrder("t|a=11"));          // format lama tanpa identitas rencana
        assertEquals(-1L, new Order(NOW, "", ids).targetId());
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
        Order prev = new Order(NOW, "t", prevIds);
        Map<String, Long> local = new HashMap<>();
        local.put("a", 10L);
        local.put("t", 30L);
        Absorb r = SejalurPlanLogic.absorb(p, prev, local, Collections.singletonList(30L), NOW);
        assertTrue(r.add.isEmpty());
        assertEquals(Collections.singletonList("b"), r.missing);
        assertEquals(0, r.dropped);
    }

    @Test
    public void expiredPlanWithNothingRunningNeverStartsRit() {
        // Rencana 1 jam lalu (semua order perangkat lain) baru tersinkron sekarang → JANGAN memulai rit
        // sendiri; rencana dibuang, order-nya tinggal di Antrian Saya.
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "t"));
        Map<String, Long> local = new HashMap<>();
        local.put("t", 30L);
        Absorb r = SejalurPlanLogic.absorb(p, null, local, Collections.<Long>emptySet(),
                NOW + SejalurPlanLogic.PENDING_TTL_MS + 1);
        assertTrue(r.finished);
        assertEquals(SejalurPlanLogic.DROP_EXPIRED, r.dropped);
        assertTrue(r.add.isEmpty());
        assertTrue(r.order.ids.isEmpty());
        assertEquals(Arrays.asList("a", "t"), r.left);
        assertEquals(Collections.singletonList("a"), r.missing);
        assertFalse(r.expiredMissing);

        // Semua tiba (tanpa ada yang hilang) pun tetap tidak dimulai.
        local.put("a", 10L);
        Absorb all = SejalurPlanLogic.absorb(p, null, local, null, NOW + 60 * MIN);
        assertTrue(all.add.isEmpty());
        assertEquals(SejalurPlanLogic.DROP_EXPIRED, all.dropped);
    }

    @Test
    public void expiredPlanStillFeedsTheRunningRitAndReportsMissingOnce() {
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "b", "t"));
        LinkedHashMap<String, Long> prevIds = new LinkedHashMap<>();
        prevIds.put("t", 30L);
        Order prev = new Order(NOW, "t", prevIds);
        Map<String, Long> local = new HashMap<>();
        local.put("t", 30L);
        local.put("a", 10L);
        Absorb r = SejalurPlanLogic.absorb(p, prev, local, Collections.singletonList(30L),
                NOW + SejalurPlanLogic.PENDING_TTL_MS + 1);
        assertEquals(0, r.dropped);
        assertEquals(Collections.singletonList(10L), r.add);
        assertTrue(r.finished);
        assertTrue(r.expiredMissing);
        assertEquals(Collections.singletonList("b"), r.missing);
    }

    @Test
    public void lateOrderAfterSejalurRitEndedDoesNotStartNewRit() {
        // A, B, T diantar & Selesai sebelum C (perangkat lain) tersinkron — masih dalam 15 mnt.
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "b", "c", "t"));
        LinkedHashMap<String, Long> prevIds = new LinkedHashMap<>();
        prevIds.put("a", 10L);
        prevIds.put("b", 20L);
        prevIds.put("t", 40L);
        Order prev = new Order(NOW, "t", prevIds);
        Map<String, Long> local = new HashMap<>();
        local.put("c", 30L);
        Absorb r = SejalurPlanLogic.absorb(p, prev, local, Collections.<Long>emptySet(), NOW + 5 * MIN);
        assertEquals(SejalurPlanLogic.DROP_RIT_ENDED, r.dropped);
        assertTrue(r.add.isEmpty());
        assertTrue(r.finished);
        // A/B/T sudah diantar → bukan "tertinggal"; hanya C.
        assertEquals(Collections.singletonList("c"), r.left);
        assertTrue(r.missing.isEmpty());
    }

    @Test
    public void staleOrderOfAnotherPlanWithSameTargetIsIgnored() {
        // Rit sejalur lama ke titik t sudah selesai; kurir Kirim Sejalur lagi ke t → rencana BARU (at lain)
        // tak boleh dianggap "rit-nya sudah habis".
        LinkedHashMap<String, Long> oldIds = new LinkedHashMap<>();
        oldIds.put("t", 30L);
        Order stale = new Order(NOW - 60 * MIN, "t", oldIds);
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "t"));
        Map<String, Long> local = new HashMap<>();
        local.put("a", 10L);
        local.put("t", 30L);
        assertFalse(SejalurPlanLogic.samePlan(p, stale));
        Absorb r = SejalurPlanLogic.absorb(p, stale, local, Collections.<Long>emptySet(), NOW);
        assertEquals(0, r.dropped);
        assertEquals(Arrays.asList(10L, 30L), r.add);
        assertEquals(NOW, r.order.planAt);
    }

    @Test
    public void releaseCarriesOnlyNeverAbsorbedUuidsWhileRunning() {
        // Kirim Sejalur A, B, T jalan; C belum tersinkron. Kurir mengantar A lalu menekan 🔄 (sukses):
        // rencana dibuang, hanya C (tak pernah ikut rit) diteruskan ke titipan "masukkan ke rit" —
        // A yang sudah Selesai tidak, dan urutan koridor tak dibangun ulang dari rencana.
        Pending p = new Pending(NOW, "t", Arrays.asList("a", "b", "c", "t"));
        Map<String, Long> local = new HashMap<>();
        local.put("a", 10L);
        local.put("b", 20L);
        local.put("t", 40L);
        Absorb first = SejalurPlanLogic.absorb(p, null, local, Collections.<Long>emptySet(), NOW);
        assertEquals(Arrays.asList(10L, 20L, 40L), first.add);
        assertEquals(Collections.singletonList("c"), first.missing);
        assertEquals(Collections.singletonList("c"), SejalurPlanLogic.releaseCarry(p, first.order, true));
        // ▶ rit baru (tak ada rit berjalan) → tak ada yang diteruskan.
        assertTrue(SejalurPlanLogic.releaseCarry(p, first.order, false).isEmpty());
        // Tanpa urutan rencana ini (belum pernah diserap) → seluruh rencana diteruskan.
        assertEquals(p.uuids, SejalurPlanLogic.releaseCarry(p, null, true));
        assertTrue(SejalurPlanLogic.releaseCarry(null, first.order, true).isEmpty());
    }

    @Test
    public void waitingPlanBlocksNewStartUntilTtl() {
        String raw = SejalurPlanLogic.encodePending(new Pending(NOW, "t", Arrays.asList("a", "t")));
        assertNotNull(SejalurPlanLogic.waiting(raw, NOW + MIN));
        assertNull(SejalurPlanLogic.waiting(raw, NOW + SejalurPlanLogic.PENDING_TTL_MS + 1));
        assertNull(SejalurPlanLogic.waiting("", NOW));
        assertNull(SejalurPlanLogic.waiting(null, NOW));
    }

    @Test
    public void orderStopsForcesTargetLastAndKeepsExtrasBeforeIt() {
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        ids.put("a", 1L);
        ids.put("b", 2L);
        ids.put("p", 3L);    // ambil galon sejalur — DeliveryPlanner menaruh KEMBALI paling belakang
        ids.put("t", 4L);
        Order o = new Order(NOW, "t", ids);
        // Urutan planner lokal: prioritas t di depan, KEMBALI p di belakang, order tambahan 9 di tengah.
        List<Long> planner = Arrays.asList(4L, 2L, 9L, 1L, 3L);
        assertEquals(Arrays.asList(1L, 2L, 3L, 9L, 4L), SejalurPlanLogic.orderStops(planner, o));
    }

    @Test
    public void orderStopsWithoutTargetHeldOrDeliveredKeepsPlanThenExtras() {
        LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
        ids.put("a", 1L);
        ids.put("b", 2L);
        Order noTarget = new Order(NOW, "", ids);
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

    // ------------------------------------------------------------------ ↔ Perlebar jalur

    private static PlanRow row(String uuid, boolean target) {
        PlanRow r = new PlanRow();
        r.uuid = uuid;
        r.isTarget = target;
        return r;
    }

    private static SejalurPlanLogic.WidenOption opt(int level, double km, int addedOrders, int addedGalon) {
        return new SejalurPlanLogic.WidenOption(level, km, 0, 0, addedOrders, addedGalon);
    }

    @Test
    public void widenChoicesOnlyWhenUnderCapacityAndOnlyUsefulHigherLevels() {
        List<SejalurPlanLogic.WidenOption> opts = Arrays.asList(opt(3, 5.0, 2, 4), opt(1, 2.0, 1, 1), opt(2, 3.5, 0, 0),
                opt(1, 2.0, 1, 1), opt(0, 1.1, 1, 1));
        List<SejalurPlanLogic.WidenOption> c = SejalurPlanLogic.widenChoices(opts, 0, 3, 5, false);
        assertEquals(2, c.size());
        assertEquals(1, c.get(0).level);   // langkah berikutnya = tingkat terkecil yang menambah
        assertEquals(3, c.get(1).level);
        // Dari tingkat 1: hanya yang lebih lebar.
        assertEquals(1, SejalurPlanLogic.widenChoices(opts, 1, 3, 5, false).size());
        // Penuh, melebihi muatan, kapasitas tak diketahui, tanpa pilihan → tak ditawarkan.
        assertTrue(SejalurPlanLogic.widenChoices(opts, 0, 5, 5, false).isEmpty());
        assertTrue(SejalurPlanLogic.widenChoices(opts, 0, 3, 5, true).isEmpty());
        assertTrue(SejalurPlanLogic.widenChoices(opts, 0, 3, 0, false).isEmpty());
        assertTrue(SejalurPlanLogic.widenChoices(null, 0, 3, 5, false).isEmpty());
        assertTrue(SejalurPlanLogic.widenChoices(new ArrayList<>(), 0, 0, 5, false).isEmpty());
    }

    @Test
    public void widenTexts() {
        assertEquals("↔ Perlebar jalur: +2 order · +3 galon (memutar ≤ 2,0 km)",
                SejalurPlanLogic.widenButtonText(opt(1, 2.0, 2, 3)));
        assertEquals("↔ Perlebar jalur: +4 galon (memutar ≤ 5,0 km)", SejalurPlanLogic.widenButtonText(opt(3, 5.0, 0, 4)));
        assertEquals("Jalur biasa (memutar ≤ 1,1 km)", SejalurPlanLogic.widenStateText(0, 1.1));
        assertEquals("↔ Jalur diperlebar (tingkat 2 · memutar ≤ 3,5 km)", SejalurPlanLogic.widenStateText(2, 3.5));
        assertEquals("Jalur biasa", SejalurPlanLogic.widenStateText(0, Double.NaN));
    }

    @Test
    public void carryChecksKeepsManualUnticksAndTicksNewRows() {
        Set<String> shown = new HashSet<>(Arrays.asList("a", "b", "x", "T"));
        Set<String> was = new HashSet<>(Arrays.asList("a", "x", "T"));   // b dilepas kurir, x = alternatif dicentang
        List<PlanRow> main = Arrays.asList(row("a", false), row("b", false), row("n", false), row("T", true));
        List<PlanRow> alts = Arrays.asList(row("x", false), row("m", false));
        Set<String> c = SejalurPlanLogic.carryChecks(main, alts, shown, was);
        assertEquals(new HashSet<>(Arrays.asList("a", "n", "T", "x")), c);
        // Titik terjauh tetap tercentang meski entah bagaimana tak ada di centang lama.
        assertTrue(SejalurPlanLogic.carryChecks(main, alts, shown, new HashSet<>()).contains("T"));
        // Pratinjau pertama: semua baris rencana, tanpa alternatif.
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "n", "T")), SejalurPlanLogic.carryChecks(main, alts, null, null));
    }

    // ------------------------------------------------------------------ saringan jenis order pemilih

    private static Target kind(String uuid, boolean pickup, double km) {
        Target t = new Target();
        t.uuid = uuid;
        t.name = uuid;
        t.pickupOnly = pickup;
        t.lat = -7.5;
        t.lng = 110.8;
        t.distKm = km;
        return t;
    }

    @Test
    public void pickerKindFilterCombinesWithSearchAndSort() {
        List<Target> rows = Arrays.asList(kind("jual-dekat", false, 1.0), kind("ambil-jauh", true, 9.0),
                kind("jual-jauh", false, 5.0), kind("ambil-dekat", true, 2.0));
        List<String> both = new ArrayList<>();
        for (Target t : SejalurPlanLogic.filterSort(rows, "", false, true, true)) both.add(t.uuid);
        assertEquals(Arrays.asList("ambil-jauh", "jual-jauh", "ambil-dekat", "jual-dekat"), both);
        List<String> sales = new ArrayList<>();
        for (Target t : SejalurPlanLogic.filterSort(rows, "", false, true, false)) sales.add(t.uuid);
        assertEquals(Arrays.asList("jual-jauh", "jual-dekat"), sales);
        List<String> pick = new ArrayList<>();
        for (Target t : SejalurPlanLogic.filterSort(rows, "dekat", false, false, true)) pick.add(t.uuid);
        assertEquals(Collections.singletonList("ambil-dekat"), pick);
        assertEquals(2, SejalurPlanLogic.selectableCount(SejalurPlanLogic.filterSort(rows, "", false, true, false)));
        assertFalse(SejalurPlanLogic.kindMatches(null, true, true));
    }

    @Test
    public void pickerKindToggleNeverLeavesBothUnchecked() {
        // Lepas Penjualan saat Ambil galon sudah lepas → tukar ke Ambil galon saja.
        assertTrue(Arrays.equals(new boolean[]{false, true}, SejalurPlanLogic.kindToggle(false, false, true)));
        assertTrue(Arrays.equals(new boolean[]{true, false}, SejalurPlanLogic.kindToggle(false, false, false)));
        assertTrue(Arrays.equals(new boolean[]{false, true}, SejalurPlanLogic.kindToggle(false, true, true)));
        assertTrue(Arrays.equals(new boolean[]{true, true}, SejalurPlanLogic.kindToggle(true, true, false)));
        assertEquals("", SejalurPlanLogic.kindLabel(true, true));
        assertEquals("Penjualan saja", SejalurPlanLogic.kindLabel(true, false));
        assertEquals("Ambil galon saja", SejalurPlanLogic.kindLabel(false, true));
    }
}
