package com.crowja.damiupos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.util.Ts;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * ⏰ Batas Antar berbasis sisa waktu (owner 2026-10-29) — kasus "Ayam Geprek Sawahan": batas 10:00, ⚡×1
 * terkubur di bawah ⚡×3 yang sekadar lama, rit 1 & rit 2 berangkat masih sisa slot, kurir tiba 10:00:30.
 * Order ber-batas antar aktif hari ini kini PALING DEPAN (jam berangkat paling lambat terdekat dulu).
 */
public class DeliveryPlannerDeadlineTest {

    private static final double DEPOT_LAT = -7.5600, DEPOT_LNG = 110.8200;

    private TimeZone prev;
    private long now;

    @Before public void wib() {
        prev = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jakarta"));
        now = Ts.millis("2026-10-29 08:30:00");
    }

    @After public void restore() {
        TimeZone.setDefault(prev);
    }

    private static Transaction order(String name, int level, int seq, String queuedAt, double km, int galon) {
        Transaction t = new Transaction();
        t.setType(Transaction.TYPE_JUAL);
        t.setDeliveryStatus(Transaction.DELIVERY_PENDING);
        t.setCustomerName(name);
        t.setJumlahGalon(galon);
        t.setDeliveryQueuedAt(queuedAt);
        t.setDeliverySeq(seq);
        t.setPriorityLevel(level);
        if (level > 0) t.setOrderPriorityAt("2026-10-29T00:00:00Z");
        t.setCustomerLat(DEPOT_LAT + km / 111.0);   // ±1 km per 1/111 derajat lintang
        t.setCustomerLng(DEPOT_LNG);
        return t;
    }

    private static Transaction due(Transaction t, String dueAt, String departBy) {
        t.setDeliveryDueAt(dueAt);
        t.setDeliveryDepartBy(departBy);
        return t;
    }

    private static List<String> names(List<Transaction> stops) {
        List<String> out = new ArrayList<>();
        for (Transaction t : stops) out.add(t.getCustomerName());
        return out;
    }

    private List<String> flat(List<Transaction> q, int maxLoad) {
        List<String> out = new ArrayList<>();
        for (DeliveryPlanner.Trip trip : DeliveryPlanner.plan(q, maxLoad, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST, now)) {
            out.addAll(names(trip.stops));
        }
        return out;
    }

    @Test public void deadlineBeatsStarAndLevel() {
        Transaction star = order("Bintang", 0, 0, "2026-10-29 07:00:00", 1.0, 1);
        star.setCustomerPriority(true);
        List<Transaction> q = Arrays.asList(
                order("Level 9", 9, 1, "2026-10-29 06:00:00", 0.5, 1),
                star,
                due(order("Batas", 1, 0, "2026-10-29 08:00:00", 4.0, 1), "2026-10-29 10:00:00", "2026-10-29 09:31:00"));

        assertEquals(Arrays.asList("Batas", "Bintang", "Level 9"), flat(q, 0));
    }

    @Test public void deadlinesAreOrderedByDepartByThenDue() {
        List<Transaction> q = Arrays.asList(
                due(order("Siang", 5, 1, "2026-10-29 06:00:00", 1.0, 1), "2026-10-29 11:00:00", "2026-10-29 10:20:00"),
                due(order("Jauh", 1, 0, "2026-10-29 08:00:00", 6.0, 1), "2026-10-29 10:30:00", "2026-10-29 10:05:00"),
                // depart_by belum dihitung server → batas − 35 menit = 09:55
                due(order("Tanpa brkt", 1, 0, "2026-10-29 08:10:00", 2.0, 1), "2026-10-29 10:30:00", null),
                // depart_by sama dengan "Jauh" → batas lebih awal menang
                due(order("Seri", 1, 0, "2026-10-29 08:20:00", 3.0, 1), "2026-10-29 10:15:00", "2026-10-29 10:05:00"));

        assertEquals(Arrays.asList("Tanpa brkt", "Seri", "Jauh", "Siang"), flat(q, 0));
        assertEquals(Ts.millis("2026-10-29 09:55:00"), DeliveryPlanner.deadlineRankMillis(q.get(2)));
    }

    @Test public void notTodayLevelZeroAndPickupAreNotActive() {
        Transaction tomorrow = due(order("Besok", 1, 0, "2026-10-29 06:00:00", 1.0, 1), "2026-10-30 09:00:00", "2026-10-30 08:30:00");
        Transaction yesterday = due(order("Kemarin", 1, 0, "2026-10-29 06:00:00", 1.0, 1), "2026-10-28 09:00:00", "2026-10-28 08:30:00");
        Transaction cancelled = due(order("Batal ⚡", 0, 0, "2026-10-29 06:00:00", 1.0, 1), "2026-10-29 09:00:00", "2026-10-29 08:30:00");
        Transaction pickup = due(order("Ambil", 1, 0, "2026-10-29 06:00:00", 1.0, 1), "2026-10-29 09:00:00", "2026-10-29 08:30:00");
        pickup.setType(Transaction.TYPE_KEMBALI);
        Transaction done = due(order("Selesai", 1, 0, "2026-10-29 06:00:00", 1.0, 1), "2026-10-29 09:00:00", "2026-10-29 08:30:00");
        done.setDeliveryStatus("DONE");

        assertFalse(DeliveryPlanner.deadlineActive(tomorrow, now));
        assertFalse(DeliveryPlanner.deadlineActive(yesterday, now));
        assertFalse(DeliveryPlanner.deadlineActive(cancelled, now));
        assertFalse(DeliveryPlanner.deadlineActive(pickup, now));
        assertFalse(DeliveryPlanner.deadlineActive(done, now));

        // Tak aktif = ikut kunci lama: ⚡×3 tetap di atas ⚡×1 yang batasnya besok; AMBIL GALON tetap terakhir.
        List<Transaction> q = Arrays.asList(tomorrow, pickup, order("Level 3", 3, 0, "2026-10-29 07:00:00", 2.0, 1));
        assertEquals(Arrays.asList("Level 3", "Besok", "Ambil"), flat(q, 0));
    }

    @Test public void deadlineKeepsItsPositionInsideTheTripUnderNearest() {
        List<Transaction> q = Arrays.asList(
                order("Dekat", 0, 0, "2026-10-29 07:00:00", 0.3, 1),
                order("Level 2", 2, 0, "2026-10-29 07:30:00", 0.8, 1),
                due(order("Jauh ber-batas", 1, 0, "2026-10-29 08:00:00", 7.0, 1), "2026-10-29 10:00:00", "2026-10-29 09:20:00"));

        List<DeliveryPlanner.Trip> trips = DeliveryPlanner.plan(q, 5, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST, now);

        assertEquals(1, trips.size());
        assertEquals(Arrays.asList("Jauh ber-batas", "Level 2", "Dekat"), names(trips.get(0).stops));
    }

    private static Transaction at(String name, double lat, double lng) {
        Transaction t = order(name, 0, 0, "2026-10-29 07:00:00", 0, 1);
        t.setCustomerLat(lat);
        t.setCustomerLng(lng);
        return t;
    }

    @Test public void tripMinutesChainsLegsAndFallsBackWithoutGeo() {
        Transaction near = order("2 km", 0, 0, "2026-10-29 07:00:00", 2.0, 1);
        Transaction noGeo = at("Tanpa titik", 0, 0);

        // Menit PECAHAN, tak dibulatkan (cermin DeliveryPlan::tripMinutes yang float). Fixture: 2/111° lintang
        // = 2,0035 km → pergi-pulang 4,0070 km × 1,3 ÷ 20 km/j = 15,6274 mnt + 5 serah terima.
        assertEquals(20.6274, DeliveryPlanner.tripMinutes(Arrays.asList(near), DEPOT_LAT, DEPOT_LNG), 0.0001);
        assertEquals(5 + 15, DeliveryPlanner.tripMinutes(Arrays.asList(noGeo), DEPOT_LAT, DEPOT_LNG), 1e-9);
        assertEquals(5 + 15, DeliveryPlanner.tripMinutes(Arrays.asList(near), 0, 0), 1e-9);
        assertEquals(20.6274 + 5 + 15, DeliveryPlanner.tripMinutes(Arrays.asList(near, noGeo), DEPOT_LAT, DEPOT_LNG), 0.0001);
        assertEquals(0, DeliveryPlanner.tripMinutes(new ArrayList<>(), DEPOT_LAT, DEPOT_LNG), 0);
        assertEquals(DeliveryPlanner.DL_MIN_TRAVEL_MIN, DeliveryPlanner.travelMin(0.1));
        assertEquals(8, DeliveryPlanner.travelMin(2.0));   // 2 × 1,3 ÷ 20 × 60 = 7,8 → 8 (satu kaki: dibulatkan ke atas)

        // Angka yang SAMA dengan DeliveryPlanTest::test_trip_minutes… di web: tepat 3 km ke utara dari (-7, 110)
        // → 6 km × 1,3 ÷ 20 × 60 = 23,4 + 5 = 28,4; + satu tanpa titik (15 + 5) = 48,4; tanpa depot = 40.
        Transaction north3km = at("3 km", -7.0 + 3 / 111.19492664455873, 110.0);
        assertEquals(28.4, DeliveryPlanner.tripMinutes(Arrays.asList(north3km), -7.0, 110.0), 0.001);
        assertEquals(28.4 + 20, DeliveryPlanner.tripMinutes(Arrays.asList(north3km, at("C", 0, 0)), -7.0, 110.0), 0.001);
        assertEquals(40.0, DeliveryPlanner.tripMinutes(Arrays.asList(north3km, at("C", 0, 0)), 0, 0), 0.001);
    }

    @Test public void deadlineRisksOkLastSafeAndLate() {
        // maxLoad 2, tiap order 2 galon 2 km → satu order per rit, tiap rit 20,6274 menit + 10 menit muat
        // = 1837,64 dtk → 1838 dtk (dibulatkan ke DETIK seperti web, bukan ke menit).
        // Rit 1 berangkat 08:40:00, rit 2 09:10:38, rit 3 09:41:16, rit 4 (hipotetis) 10:11:54.
        List<Transaction> q = Arrays.asList(
                due(order("Aman", 1, 0, "2026-10-29 07:00:00", 2.0, 2), "2026-10-29 11:00:00", "2026-10-29 10:30:00"),
                due(order("Terakhir", 1, 0, "2026-10-29 07:00:00", 2.0, 2), "2026-10-29 10:00:00", "2026-10-29 09:20:00"),
                due(order("Telat", 1, 0, "2026-10-29 07:00:00", 2.0, 2), "2026-10-29 09:15:00", "2026-10-29 08:35:00"),
                order("Biasa", 0, 0, "2026-10-29 06:00:00", 1.0, 1));
        List<DeliveryPlanner.Trip> trips = DeliveryPlanner.plan(q, 2, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST, now);
        assertEquals(Arrays.asList("Telat"), names(trips.get(0).stops));
        assertEquals(Arrays.asList("Terakhir"), names(trips.get(1).stops));
        assertEquals(Arrays.asList("Aman"), names(trips.get(2).stops));

        long firstDepart = now + DeliveryPlanner.DL_LOAD_MIN * 60000L;
        List<DeliveryPlanner.DeadlineRisk> risks = DeliveryPlanner.deadlineRisks(trips, DEPOT_LAT, DEPOT_LNG, firstDepart, now);

        assertEquals(3, risks.size());   // "Biasa" tak punya batas antar → tak dinilai
        assertRisk(risks.get(0), "Telat", 1, "2026-10-29 08:40:00", DeliveryPlanner.RISK_LATE);
        assertRisk(risks.get(1), "Terakhir", 2, "2026-10-29 09:10:38", DeliveryPlanner.RISK_LAST_SAFE);
        assertRisk(risks.get(2), "Aman", 3, "2026-10-29 09:41:16", DeliveryPlanner.RISK_OK);
        assertEquals(Ts.millis("2026-10-29 09:15:00"), risks.get(0).dueMs);
        assertEquals(Ts.millis("2026-10-29 08:35:00"), risks.get(0).departByMs);
        assertEquals(1, risks.get(0).level);
    }

    /** Fixture yang SAMA dengan DeliveryPlanTest::test_deadline_risks_flag_ok_last_safe_and_late di web. */
    @Test public void deadlineRisksMatchTheServerFixture() {
        long at0800 = Ts.millis("2026-10-29 08:00:00");
        // Tanpa depot: tiap pemberhentian 15 + 5 menit. Rit 1 berangkat 08:10 (40 mnt), rit 2 09:00 (20), rit 3 09:30, rit 4 10:00.
        DeliveryPlanner.Trip r1 = new DeliveryPlanner.Trip(1);
        r1.stops.add(due(order("A", 1, 0, "2026-10-29 07:00:00", 0, 1), "2026-10-29 10:30:00", "2026-10-29 09:30:00"));
        r1.stops.add(at("R", 0, 0));
        DeliveryPlanner.Trip r2 = new DeliveryPlanner.Trip(2);
        r2.stops.add(due(order("B", 1, 0, "2026-10-29 07:00:00", 0, 1), "2026-10-29 10:00:00", "2026-10-29 09:15:00"));
        DeliveryPlanner.Trip r3 = new DeliveryPlanner.Trip(3);
        r3.stops.add(due(order("C", 1, 0, "2026-10-29 07:00:00", 0, 1), "2026-10-29 10:00:00", "2026-10-29 09:20:00"));

        List<DeliveryPlanner.DeadlineRisk> risks = DeliveryPlanner.deadlineRisks(Arrays.asList(r1, r2, r3), 0, 0,
                at0800 + DeliveryPlanner.DL_LOAD_MIN * 60000L, at0800);

        assertEquals(3, risks.size());
        assertRisk(risks.get(0), "A", 1, "2026-10-29 08:10:00", DeliveryPlanner.RISK_OK);
        assertRisk(risks.get(1), "B", 2, "2026-10-29 09:00:00", DeliveryPlanner.RISK_LAST_SAFE);
        assertRisk(risks.get(2), "C", 3, "2026-10-29 09:30:00", DeliveryPlanner.RISK_LATE);
        assertEquals(Ts.millis("2026-10-29 09:15:00"), risks.get(1).departByMs);
        assertEquals(Ts.millis("2026-10-29 10:00:00"), risks.get(1).dueMs);
    }

    /**
     * Di dekat batas HP & web harus sepakat. Rit 1 = satu pemberhentian 2 km (20,6274 mnt); berangkat 08:40:20 →
     * rit 2 berangkat 08:40:20 + 30 mnt 38 dtk = 09:10:58, sebelum jam berangkat X 09:11:00 → "rit terakhir yang
     * aman" (web: last_safe). Dulu HP membulatkan rit ke atas jadi 31 menit → 09:11:20 → "terlambat": dua perintah
     * berlawanan untuk rencana yang sama.
     */
    @Test public void nextTripDepartureIsRoundedToTheSecondLikeTheServer() {
        long nowAt = Ts.millis("2026-10-29 08:30:20");
        DeliveryPlanner.Trip r1 = new DeliveryPlanner.Trip(1);
        r1.stops.add(order("2 km", 0, 0, "2026-10-29 07:00:00", 2.0, 2));
        DeliveryPlanner.Trip r2 = new DeliveryPlanner.Trip(2);
        r2.stops.add(due(order("X", 1, 0, "2026-10-29 07:00:00", 1.0, 2), "2026-10-29 10:00:00", "2026-10-29 09:11:00"));
        long firstDepart = nowAt + DeliveryPlanner.DL_LOAD_MIN * 60000L;

        assertEquals(Ts.millis("2026-10-29 09:10:58"), DeliveryPlanner.nextDepartMillis(firstDepart, r1.stops, DEPOT_LAT, DEPOT_LNG));
        List<DeliveryPlanner.DeadlineRisk> risks = DeliveryPlanner.deadlineRisks(Arrays.asList(r1, r2), DEPOT_LAT, DEPOT_LNG, firstDepart, nowAt);
        assertEquals(1, risks.size());
        assertRisk(risks.get(0), "X", 2, "2026-10-29 09:10:58", DeliveryPlanner.RISK_LAST_SAFE);
    }

    private static void assertRisk(DeliveryPlanner.DeadlineRisk r, String name, int trip, String depart, String status) {
        assertEquals(name, r.order.getCustomerName());
        assertEquals(trip, r.trip);
        assertEquals(Ts.millis(depart), r.departMs);
        assertEquals(status, r.status);
    }

    @Test public void ayamGeprekReplayGoesInTrip1() {
        // Muatan 5. Empat ⚡×3 "lama" @2 galon + satu lagi, lalu Ayam Geprek ⚡×1 1 galon batas 10:00.
        Transaction ayam = order("Ayam Geprek Sawahan", 1, 0, "2026-10-29 07:09:00", 3.0, 1);
        List<Transaction> q = new ArrayList<>(Arrays.asList(
                order("cahyo", 3, 0, "2026-10-29 06:40:00", 1.0, 2),
                order("rully", 3, 0, "2026-10-29 06:45:00", 1.5, 2),
                order("Pak Budi", 3, 0, "2026-10-29 06:50:00", 2.0, 2),
                order("Bu Sri", 3, 0, "2026-10-29 06:55:00", 2.5, 2),
                order("Warung Tegal", 3, 0, "2026-10-29 07:00:00", 0.5, 2),
                ayam));

        // Tanpa batas antar (perilaku lama): Ayam Geprek terdorong ke rit 3.
        List<DeliveryPlanner.Trip> before = DeliveryPlanner.plan(q, 5, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST, now);
        assertEquals(3, before.size());
        assertTrue(names(before.get(2).stops).contains("Ayam Geprek Sawahan"));

        due(ayam, "2026-10-29T03:00:00.000000Z", "2026-10-29T02:31:00.000000Z");   // 10:00 / 09:31 WIB dari server
        List<DeliveryPlanner.Trip> trips = DeliveryPlanner.plan(q, 5, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST, now);

        assertEquals(Arrays.asList("Ayam Geprek Sawahan", "cahyo", "rully"), names(trips.get(0).stops));
        assertEquals(5, trips.get(0).galon);
    }

    @Test public void localAndIsoUtcDueStringsBothParse() {
        Transaction local = due(order("Lokal", 1, 0, "2026-10-29 07:00:00", 1.0, 1), "2026-10-29 10:00:00", "2026-10-29 09:31:00");
        Transaction iso = due(order("ISO", 1, 0, "2026-10-29 07:00:00", 1.0, 1), "2026-10-29T03:00:00.000000Z", "2026-10-29T02:31:00Z");

        assertTrue(DeliveryPlanner.deadlineActive(local, now));
        assertTrue(DeliveryPlanner.deadlineActive(iso, now));
        assertEquals(DeliveryPlanner.dueMillis(local), DeliveryPlanner.dueMillis(iso));
        assertEquals(DeliveryPlanner.deadlineRankMillis(local), DeliveryPlanner.deadlineRankMillis(iso));

        // Hari dinilai menurut tanggal LOKAL, bukan teksnya: 28 Okt 18:00Z = 29 Okt 01:00 WIB (hari ini),
        // 29 Okt 17:30Z = 30 Okt 00:30 WIB (besok).
        Transaction utcYesterday = due(order("UTC kemarin", 1, 0, "2026-10-29 07:00:00", 1.0, 1), "2026-10-28T18:00:00Z", null);
        Transaction utcToday = due(order("UTC hari ini", 1, 0, "2026-10-29 07:00:00", 1.0, 1), "2026-10-29T17:30:00.000000Z", null);
        assertTrue(DeliveryPlanner.deadlineActive(utcYesterday, now));
        assertFalse(DeliveryPlanner.deadlineActive(utcToday, now));
    }
}
