package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.PickupNearbyLogic.Candidate;
import com.crowja.damiupos.util.PickupNearbyLogic.Event;
import com.crowja.damiupos.util.PickupNearbyLogic.Tracker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PickupNearbyLogicTest {

    /** Titik acuan (Semarang); 1° lintang = 2πR/360 m pada R haversine 6.371.000 m. */
    private static final double LAT0 = -7.0;
    private static final double LNG0 = 110.4;
    private static final double M_PER_DEG_LAT = 2 * Math.PI * 6371000.0 / 360.0;

    private static double northOf(double meters) {
        return LAT0 + meters / M_PER_DEG_LAT;
    }

    private static Candidate cand(String uuid) {
        return new Candidate(uuid, 0L, "Bu Sri", 3, LAT0, LNG0, PickupNearbyLogic.SRC_OTHER, "Motor 2", "dev-2");
    }

    private static List<Integer> types(List<Event> evs) {
        List<Integer> out = new ArrayList<>();
        for (Event e : evs) out.add(e.type);
        return out;
    }

    // ------------------------------------------------------------------ pengaturan

    @Test
    public void radiusIsClampedToServerRange() {
        assertEquals(10, PickupNearbyLogic.clampRadius(5));
        assertEquals(50, PickupNearbyLogic.clampRadius(50));
        assertEquals(500, PickupNearbyLogic.clampRadius(900));
    }

    @Test
    public void parseRadiusKeepsCurrentWhenAbsentOrGarbage() {
        assertEquals(80, PickupNearbyLogic.parseRadius(80, 50));
        assertEquals(120, PickupNearbyLogic.parseRadius("120", 50));
        assertEquals(50, PickupNearbyLogic.parseRadius(49.6, 70));
        assertEquals(70, PickupNearbyLogic.parseRadius(null, 70));
        assertEquals(70, PickupNearbyLogic.parseRadius("abc", 70));
        assertEquals(70, PickupNearbyLogic.parseRadius(new Object(), 70));
        assertEquals(10, PickupNearbyLogic.parseRadius(3, 70));
        assertEquals(500, PickupNearbyLogic.parseRadius(99999, 70));
    }

    @Test
    public void parseBoolAcceptsCommonShapesAndFallsBack() {
        assertTrue(PickupNearbyLogic.parseBool(Boolean.TRUE, false));
        assertFalse(PickupNearbyLogic.parseBool(Boolean.FALSE, true));
        assertTrue(PickupNearbyLogic.parseBool(1, false));
        assertFalse(PickupNearbyLogic.parseBool(0, true));
        assertTrue(PickupNearbyLogic.parseBool("1", false));
        assertTrue(PickupNearbyLogic.parseBool("TRUE", false));
        assertFalse(PickupNearbyLogic.parseBool("0", true));
        assertFalse(PickupNearbyLogic.parseBool("false", true));
        assertTrue(PickupNearbyLogic.parseBool(null, true));
        assertFalse(PickupNearbyLogic.parseBool(new Object(), false));
        assertTrue(PickupNearbyLogic.parseBool("ya", true));
    }

    @Test
    public void activeOnlyWhenEnabledAndRitOrNavigation() {
        assertFalse(PickupNearbyLogic.shouldBeActive(false, true, true));
        assertFalse(PickupNearbyLogic.shouldBeActive(true, false, false));
        assertTrue(PickupNearbyLogic.shouldBeActive(true, true, false));
        assertTrue(PickupNearbyLogic.shouldBeActive(true, false, true));
    }

    // ------------------------------------------------------------------ geometri

    @Test
    public void fixAcceptedOnlyWithinMaxOf50OrRadius() {
        assertFalse(PickupNearbyLogic.acceptFix(false, 5f, 50));
        assertFalse(PickupNearbyLogic.acceptFix(true, Float.NaN, 50));
        assertTrue(PickupNearbyLogic.acceptFix(true, 30f, 50));
        assertTrue(PickupNearbyLogic.acceptFix(true, 50f, 10));     // max(50, 10) = 50
        assertFalse(PickupNearbyLogic.acceptFix(true, 51f, 50));
        assertTrue(PickupNearbyLogic.acceptFix(true, 150f, 200));
        assertFalse(PickupNearbyLogic.acceptFix(true, 201f, 200));
    }

    @Test
    public void leaveThresholdIsLargerOfDoubleRadiusAndRadiusPlus60() {
        assertEquals(110.0, PickupNearbyLogic.leaveThresholdM(50), 1e-9);
        assertEquals(70.0, PickupNearbyLogic.leaveThresholdM(10), 1e-9);
        assertEquals(200.0, PickupNearbyLogic.leaveThresholdM(100), 1e-9);
        assertEquals(1000.0, PickupNearbyLogic.leaveThresholdM(500), 1e-9);
    }

    @Test
    public void haversineMatchesMeridianDistance() {
        assertEquals(100.0, PickupNearbyLogic.distanceM(LAT0, LNG0, northOf(100), LNG0), 0.01);
        assertEquals(0.0, PickupNearbyLogic.distanceM(LAT0, LNG0, LAT0, LNG0), 1e-9);
        // 0,001° bujur di lintang -7° ≈ 110,4 m (cos 7°).
        assertEquals(110.4, PickupNearbyLogic.distanceM(LAT0, LNG0, LAT0, LNG0 + 0.001), 0.2);
    }

    @Test
    public void geoAndKeyHelpers() {
        assertFalse(PickupNearbyLogic.hasGeo(0, 0));
        assertFalse(PickupNearbyLogic.hasGeo(Double.NaN, 110));
        assertFalse(PickupNearbyLogic.hasGeo(95, 110));
        assertTrue(PickupNearbyLogic.hasGeo(LAT0, LNG0));
        assertEquals("abc", PickupNearbyLogic.keyFor(" abc ", 5));
        assertEquals("local:7", PickupNearbyLogic.keyFor("", 7));
        assertEquals("local:7", PickupNearbyLogic.keyFor(null, 7));
    }

    // ------------------------------------------------------------------ kandidat

    @Test
    public void localCandidateSkipsNonPickupVisitRunningAndNoGeo() {
        assertNull(PickupNearbyLogic.localCandidate(1, "u1", "JUAL", null, 3, LAT0, LNG0, false, false, "A"));
        assertNull(PickupNearbyLogic.localCandidate(1, "u1", "KEMBALI", "[KUNJUNGAN FOLLOW UP] telp dulu", 0,
                LAT0, LNG0, false, false, "A"));
        assertNull(PickupNearbyLogic.localCandidate(1, "u1", "KEMBALI", null, 0, LAT0, LNG0, false, false, "A"));
        assertNull(PickupNearbyLogic.localCandidate(1, "u1", "KEMBALI", null, 3, LAT0, LNG0, false, true, "A"));
        assertNull(PickupNearbyLogic.localCandidate(1, "u1", "KEMBALI", null, 3, 0, 0, false, false, "A"));

        Candidate mine = PickupNearbyLogic.localCandidate(9, "u9", "KEMBALI", "catatan", 2, LAT0, LNG0, false, false, " Pak Budi ");
        assertNotNull(mine);
        assertEquals(PickupNearbyLogic.SRC_MINE, mine.source);
        assertEquals("u9", mine.key);
        assertEquals(9L, mine.localId);
        assertEquals("Pak Budi", mine.name);

        Candidate open = PickupNearbyLogic.localCandidate(4, null, "KEMBALI", null, 1, LAT0, LNG0, true, false, null);
        assertNotNull(open);
        assertEquals(PickupNearbyLogic.SRC_OPEN, open.source);
        assertEquals("local:4", open.key);
        assertEquals("Pelanggan", open.name);
    }

    @Test
    public void remoteCandidateSkipsSalesInProgressVisitAndNoGeo() {
        assertNull(PickupNearbyLogic.remoteCandidate("", "A", 2, LAT0, LNG0, true, false, false, "M2", "d2", null));
        assertNull(PickupNearbyLogic.remoteCandidate("u", "A", 2, LAT0, LNG0, false, false, false, "M2", "d2", null));
        assertNull(PickupNearbyLogic.remoteCandidate("u", "A", 2, LAT0, LNG0, true, true, false, "M2", "d2", null));
        assertNull(PickupNearbyLogic.remoteCandidate("u", "A", 2, null, LNG0, true, false, false, "M2", "d2", null));
        assertNull(PickupNearbyLogic.remoteCandidate("u", "A", 0, LAT0, LNG0, true, false, false, "M2", "d2", null));
        assertNull(PickupNearbyLogic.remoteCandidate("u", "A", 2, LAT0, LNG0, true, false, false, "M2", "d2",
                "[KUNJUNGAN FOLLOW UP]"));

        Candidate other = PickupNearbyLogic.remoteCandidate("u5", "Bu Ani", 4, LAT0, LNG0, true, false, false,
                "Motor 2", "dev-2", "taruh di teras");
        assertNotNull(other);
        assertEquals(PickupNearbyLogic.SRC_OTHER, other.source);
        assertEquals("Motor 2", other.holder);
        assertEquals("dev-2", other.expectedDevice);
        assertEquals(0L, other.localId);

        Candidate open = PickupNearbyLogic.remoteCandidate("u6", "Bu Ina", 1, LAT0, LNG0, true, false, true,
                "Belum Ditugaskan", "null", null);
        assertNotNull(open);
        assertEquals(PickupNearbyLogic.SRC_OPEN, open.source);
        assertEquals("", open.holder);
        assertEquals("", open.expectedDevice);
    }

    @Test
    public void mergePrefersLocalRowForSameUuid() {
        Candidate local = new Candidate("u1", 11L, "Lokal", 2, LAT0, LNG0, PickupNearbyLogic.SRC_OPEN, "", "");
        Candidate remoteSame = cand("u1");
        Candidate remoteOther = cand("u2");
        List<Candidate> merged = PickupNearbyLogic.merge(Collections.singletonList(local),
                Arrays.asList(remoteSame, remoteOther, cand("u2")));
        assertEquals(2, merged.size());
        assertEquals(11L, merged.get(0).localId);
        assertEquals(PickupNearbyLogic.SRC_OPEN, merged.get(0).source);
        assertEquals("u2", merged.get(1).key);
        assertEquals(0, PickupNearbyLogic.merge(null, null).size());
    }

    @Test
    public void nearestPicksClosestInsideRadius() {
        Candidate far = new Candidate("far", 0L, "Jauh", 1, northOf(45), LNG0, PickupNearbyLogic.SRC_OTHER, "", "");
        Candidate near = new Candidate("near", 0L, "Dekat", 1, northOf(20), LNG0, PickupNearbyLogic.SRC_OTHER, "", "");
        Candidate out = new Candidate("out", 0L, "Luar", 1, northOf(80), LNG0, PickupNearbyLogic.SRC_OTHER, "", "");
        PickupNearbyLogic.Hit hit = PickupNearbyLogic.nearest(Arrays.asList(far, out, near), LAT0, LNG0, 50);
        assertNotNull(hit);
        assertEquals("near", hit.cand.key);
        assertEquals(20.0, hit.distanceM, 0.01);
        assertNull(PickupNearbyLogic.nearest(Collections.singletonList(out), LAT0, LNG0, 50));
        assertNull(PickupNearbyLogic.nearest(null, LAT0, LNG0, 50));
    }

    // ------------------------------------------------------------------ teks

    @Test
    public void textsMatchContract() {
        assertEquals("Antrean kamu", PickupNearbyLogic.sourceLabel(PickupNearbyLogic.SRC_MINE, "x"));
        assertEquals("Pesanan Terbuka", PickupNearbyLogic.sourceLabel(PickupNearbyLogic.SRC_OPEN, "x"));
        assertEquals("Antrean Motor 2", PickupNearbyLogic.sourceLabel(PickupNearbyLogic.SRC_OTHER, "Motor 2"));
        assertEquals("Antrean perangkat lain", PickupNearbyLogic.sourceLabel(PickupNearbyLogic.SRC_OTHER, "null"));
        assertEquals("📥 AMBIL GALON — Bu Sri", PickupNearbyLogic.alertTitle("Bu Sri"));
        assertEquals("3 galon kembali · 42 m dari kamu · Antrean Motor 2",
                PickupNearbyLogic.alertText(3, 41.6, PickupNearbyLogic.SRC_OTHER, "Motor 2"));
        assertEquals("⚠️ Galon kembali Bu Sri terlewat — tercatat di Jurnal Pesanan", PickupNearbyLogic.passedTitle("Bu Sri"));
        assertEquals("📥 Galon kembali dekat: Pelanggan · 7 m", PickupNearbyLogic.bannerText("  ", 7.2));
        assertEquals("? m", PickupNearbyLogic.meters(Double.NaN));
        assertEquals(0, PickupNearbyLogic.reportDistance(-3));
        assertEquals(23, PickupNearbyLogic.reportDistance(22.6));
    }

    // ------------------------------------------------------------------ mesin keadaan

    @Test
    public void enteringRadiusAlertsOnceThenUpdatesSilently() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        assertTrue(tr.onFix(cs, northOf(80), LNG0, 50, 0L).isEmpty());          // di luar radius
        List<Event> a = tr.onFix(cs, northOf(40), LNG0, 50, 4_000L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_ALERT), types(a));
        assertEquals(40.0, a.get(0).distanceM, 0.01);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_UPDATE), types(tr.onFix(cs, northOf(30), LNG0, 50, 8_000L)));
        assertEquals(Collections.singletonList("u1"), tr.alertedKeys());
        assertEquals(30.0, tr.nearestAlerted().distanceM, 0.01);
    }

    @Test
    public void realertsEvery45sInsideAtMostThreeTimes() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(20), LNG0, 50, 0L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_UPDATE), types(tr.onFix(cs, northOf(20), LNG0, 50, 44_000L)));
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_REALERT), types(tr.onFix(cs, northOf(20), LNG0, 50, 45_000L)));
        // Kurir berhenti (tak ada fix baru): detak tetap membunyikan ulang.
        assertTrue(tr.onTick(60_000L).isEmpty());
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_REALERT), types(tr.onTick(90_000L)));
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_REALERT), types(tr.onTick(135_000L)));
        assertTrue(tr.onTick(180_000L).isEmpty());   // sudah 3× ulang
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_UPDATE), types(tr.onFix(cs, northOf(20), LNG0, 50, 400_000L)));
    }

    @Test
    public void noRealertInHysteresisBand() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(20), LNG0, 50, 0L);
        // 80 m: di luar radius 50 tapi belum lewat ambang menjauh 110 m → tetap diperingatkan, tanpa bunyi ulang.
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_UPDATE), types(tr.onFix(cs, northOf(80), LNG0, 50, 50_000L)));
        assertTrue(tr.onTick(100_000L).isEmpty());
    }

    @Test
    public void leavingWithoutAmbilIsPassedOnceAfterTwoFarFixes() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(40), LNG0, 50, 0L);
        tr.onFix(cs, northOf(12), LNG0, 50, 4_000L);   // paling dekat 12 m
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_UPDATE), types(tr.onFix(cs, northOf(130), LNG0, 50, 8_000L)));
        List<Event> p = tr.onFix(cs, northOf(150), LNG0, 50, 12_000L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_PASSED), types(p));
        assertEquals(12.0, p.get(0).minDistanceM, 0.01);
        assertEquals("u1", p.get(0).cand.key);
        assertTrue(tr.alertedKeys().isEmpty());
        assertNull(tr.nearestAlerted());
        // Jeda 20 menit: kembali lagi → diam; jauh lagi → tak dilaporkan dua kali.
        assertTrue(tr.onFix(cs, northOf(10), LNG0, 50, 60_000L).isEmpty());
        assertTrue(tr.onFix(cs, northOf(300), LNG0, 50, 64_000L).isEmpty());
        assertTrue(tr.onFix(cs, northOf(300), LNG0, 50, 68_000L).isEmpty());
        // Jeda habis → kedatangan baru diperingatkan lagi.
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_ALERT),
                types(tr.onFix(cs, northOf(10), LNG0, 50, 12_000L + PickupNearbyLogic.COOLDOWN_MS)));
    }

    @Test
    public void singleJumpyFixDoesNotCountAsPassed() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(20), LNG0, 50, 0L);
        tr.onFix(cs, northOf(200), LNG0, 50, 4_000L);   // lompatan GPS
        tr.onFix(cs, northOf(25), LNG0, 50, 8_000L);    // kembali di dekat → hitungan menjauh direset
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_UPDATE), types(tr.onFix(cs, northOf(200), LNG0, 50, 12_000L)));
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_PASSED), types(tr.onFix(cs, northOf(200), LNG0, 50, 16_000L)));
    }

    @Test
    public void ambilIsNeverReportedAndCoolsDown() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(20), LNG0, 50, 0L);
        assertTrue(tr.markActed("u1", 5_000L));
        assertNull(tr.nearestAlerted());
        assertTrue(tr.onFix(cs, northOf(20), LNG0, 50, 60_000L).isEmpty());   // tak bunyi ulang
        assertTrue(tr.onFix(cs, northOf(500), LNG0, 50, 64_000L).isEmpty());  // menjauh: tak dilaporkan
        assertTrue(tr.onFix(cs, northOf(500), LNG0, 50, 68_000L).isEmpty());
        assertTrue(tr.onTick(100_000L).isEmpty());
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_ALERT),
                types(tr.onFix(cs, northOf(20), LNG0, 50, 5_000L + PickupNearbyLogic.COOLDOWN_MS)));
        // Ambil dari notifikasi lama (kunci belum dilacak) tetap memberi jeda.
        Tracker t2 = new Tracker();
        assertFalse(t2.markActed("u9", 0L));
        assertTrue(t2.onFix(Collections.singletonList(cand("u9")), LAT0, LNG0, 50, 1_000L).isEmpty());
        assertFalse(t2.markActed("", 0L));
    }

    @Test
    public void nantiDismissesButLeavingIsStillPassed() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(15), LNG0, 50, 0L);
        assertTrue(tr.markSnoozed("u1", 2_000L));
        assertFalse(tr.markSnoozed("u1", 3_000L));      // sudah ditutup
        assertNull(tr.nearestAlerted());
        assertTrue(tr.onFix(cs, northOf(10), LNG0, 50, 50_000L).isEmpty());   // diam: tanpa update/bunyi
        assertTrue(tr.onTick(100_000L).isEmpty());
        assertTrue(tr.onFix(cs, northOf(200), LNG0, 50, 104_000L).isEmpty());
        List<Event> p = tr.onFix(cs, northOf(220), LNG0, 50, 108_000L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_PASSED), types(p));
        assertEquals(10.0, p.get(0).minDistanceM, 0.01);
        assertTrue(tr.onFix(cs, northOf(10), LNG0, 50, 200_000L).isEmpty());   // masih jeda
    }

    @Test
    public void nantiCooldownExpiringWhileInsideIsANewArrival() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Collections.singletonList(cand("u1"));
        tr.onFix(cs, northOf(15), LNG0, 50, 0L);
        tr.markSnoozed("u1", 0L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_ALERT),
                types(tr.onFix(cs, northOf(15), LNG0, 50, PickupNearbyLogic.COOLDOWN_MS)));
    }

    @Test
    public void vanishedCandidateIsCancelledWithoutReport() {
        Tracker tr = new Tracker();
        tr.onFix(Collections.singletonList(cand("u1")), northOf(10), LNG0, 50, 0L);
        List<Event> c = tr.onFix(Collections.<Candidate>emptyList(), northOf(300), LNG0, 50, 4_000L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_CANCEL), types(c));
        assertTrue(tr.alertedKeys().isEmpty());
        // sync() (tanpa fix) juga menutupnya.
        Tracker t2 = new Tracker();
        t2.onFix(Collections.singletonList(cand("u2")), northOf(10), LNG0, 50, 0L);
        assertTrue(t2.sync(Collections.singletonList(cand("u2")), 1_000L).isEmpty());
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_CANCEL),
                types(t2.sync(Collections.<Candidate>emptyList(), 2_000L)));
    }

    @Test
    public void clearCancelsAlertsButKeepsAmbilCooldown() {
        Tracker tr = new Tracker();
        List<Candidate> cs = Arrays.asList(cand("a"), new Candidate("b", 0L, "B", 1, northOf(5), LNG0,
                PickupNearbyLogic.SRC_OPEN, "", ""));
        assertEquals(Arrays.asList(PickupNearbyLogic.EV_ALERT, PickupNearbyLogic.EV_ALERT),
                types(tr.onFix(cs, LAT0, LNG0, 50, 0L)));
        tr.markActed("b", 1_000L);
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_CANCEL), types(tr.clear()));
        // Monitor aktif lagi (navigasi dibuka ulang): "a" diperingatkan lagi, "b" masih dijeda.
        assertEquals(Collections.singletonList(PickupNearbyLogic.EV_ALERT), types(tr.onFix(cs, LAT0, LNG0, 50, 10_000L)));
        assertEquals("a", tr.nearestAlerted().cand.key);
    }

    @Test
    public void twoNearbyPickupsAreTrackedSeparatelyNearestFirst() {
        Tracker tr = new Tracker();
        Candidate a = new Candidate("a", 0L, "A", 1, northOf(30), LNG0, PickupNearbyLogic.SRC_OTHER, "", "");
        Candidate b = new Candidate("b", 0L, "B", 2, northOf(-10), LNG0, PickupNearbyLogic.SRC_MINE, "", "");
        List<Event> evs = tr.onFix(Arrays.asList(a, b), LAT0, LNG0, 50, 0L);
        assertEquals(Arrays.asList(PickupNearbyLogic.EV_ALERT, PickupNearbyLogic.EV_ALERT), types(evs));
        assertEquals("b", tr.nearestAlerted().cand.key);
        tr.markSnoozed("b", 1_000L);
        assertEquals("a", tr.nearestAlerted().cand.key);
    }
}
