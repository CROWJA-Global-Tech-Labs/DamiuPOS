package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

public class IderRankingTest {

    private static final TimeZone WIB = TimeZone.getTimeZone("Asia/Jakarta");
    // Titik acuan (sekitar Solo). 0,001° lintang ≈ 111 m.
    private static final double LAT = -7.5600, LNG = 110.8200;

    private static IderRanking.Person person(String key, String name, double[]... coords) {
        List<IderRanking.Spot> spots = new ArrayList<>();
        for (int i = 0; i < coords.length; i++) {
            spots.add(new IderRanking.Spot("Lokasi " + i, coords[i][0], coords[i][1], 1));
        }
        return new IderRanking.Person(key, 1, name, Long.MIN_VALUE, spots);
    }

    @Test
    public void nearestSortsByDistanceAndCapsAtLimit() {
        List<IderRanking.Person> people = new ArrayList<>();
        for (int i = 12; i >= 1; i--) {
            people.add(person("k" + i, "P" + i, new double[]{LAT + i * 0.001, LNG}));
        }
        List<IderRanking.Ranked> top = IderRanking.nearest(people, LAT, LNG, IderRanking.MAX_LIST);
        assertEquals(10, top.size());
        assertEquals("k1", top.get(0).person.key);
        assertEquals("k10", top.get(9).person.key);
        for (int i = 1; i < top.size(); i++) assertTrue(top.get(i - 1).meters <= top.get(i).meters);
    }

    @Test
    public void multiLocationPersonRanksByNearestSpotAndAppearsOnce() {
        IderRanking.Person far = person("far", "Jauh", new double[]{LAT + 0.005, LNG});
        // Rumah jauh (titik 0), toko dekat (titik 1).
        IderRanking.Person multi = person("multi", "Multi",
                new double[]{LAT + 0.009, LNG}, new double[]{LAT + 0.0005, LNG});
        List<IderRanking.Ranked> top = IderRanking.nearest(Arrays.asList(far, multi), LAT, LNG, 10);
        assertEquals(2, top.size());
        assertEquals("multi", top.get(0).person.key);
        assertEquals(1, top.get(0).spotIndex);
        assertEquals(55.6, top.get(0).meters, 1.0);
    }

    @Test
    public void radiusFilterDropsFartherPeople() {
        List<IderRanking.Person> people = new ArrayList<>();
        for (int i = 1; i <= 5; i++) people.add(person("k" + i, "P" + i, new double[]{LAT + i * 0.0005, LNG}));
        // 0,0005° ≈ 55,6 m → dalam 120 m hanya k1 (56 m) & k2 (111 m).
        List<IderRanking.Ranked> near = IderRanking.nearest(people, LAT, LNG, 10, 120);
        assertEquals(2, near.size());
        assertEquals("k2", near.get(1).person.key);
        assertEquals(5, IderRanking.nearest(people, LAT, LNG, 10, 0).size());
    }

    @Test
    public void tieBreaksByNameSoOrderIsStable() {
        IderRanking.Person b = person("b", "Budi", new double[]{LAT + 0.001, LNG});
        IderRanking.Person a = person("a", "Ani", new double[]{LAT + 0.001, LNG});
        List<IderRanking.Ranked> top = IderRanking.nearest(Arrays.asList(b, a), LAT, LNG, 10);
        assertEquals("a", top.get(0).person.key);
    }

    @Test
    public void personWithoutSpotsIsSkipped() {
        IderRanking.Person none = new IderRanking.Person("x", 1, "X", Long.MIN_VALUE,
                Collections.<IderRanking.Spot>emptyList());
        assertTrue(IderRanking.nearest(Collections.singletonList(none), LAT, LNG, 10).isEmpty());
    }

    @Test
    public void nearestSpotsCountsEveryLocation() {
        IderRanking.Person multi = person("multi", "Multi",
                new double[]{LAT + 0.002, LNG}, new double[]{LAT + 0.001, LNG});
        List<IderRanking.SpotHit> hits = IderRanking.nearestSpots(Collections.singletonList(multi), LAT, LNG, 10);
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).spotIndex);
    }

    @Test
    public void formatsDistance() {
        assertEquals("35 m", IderRanking.formatDistance(35.4));
        assertEquals("120 m", IderRanking.formatDistance(123));
        assertEquals("1 km", IderRanking.formatDistance(997));
        assertEquals("1,2 km", IderRanking.formatDistance(1234));
        assertEquals("10 km", IderRanking.formatDistance(9970));
        assertEquals("15 km", IderRanking.formatDistance(15200));
    }

    private static long wib(int y, int mo, int d, int h, int mi) {
        java.util.Calendar c = java.util.Calendar.getInstance(WIB);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void reorderBadgeMirrorsForecastStatus() {
        long now = wib(2026, 10, 28, 9, 0);
        assertEquals(null, IderRanking.reorderBadge(null, now, WIB));
        IderRanking.BuyBadge late = IderRanking.reorderBadge("2026-10-25", now, WIB);
        assertEquals("⏰ Jatuh tempo order · lewat 3 hari", late.text);
        assertEquals(IderRanking.BuyLevel.STALE, late.level);
        assertEquals("⌛ Prediksi order lagi hari ini", IderRanking.reorderBadge("2026-10-28", now, WIB).text);
        assertEquals("⌛ Prediksi order lagi besok", IderRanking.reorderBadge("2026-10-29", now, WIB).text);
        assertEquals(IderRanking.BuyLevel.WARN, IderRanking.reorderBadge("2026-10-31", now, WIB).level);
        IderRanking.BuyBadge later = IderRanking.reorderBadge("2026-11-05", now, WIB);
        assertEquals("📅 Prediksi order lagi 8 hari · 5 Nov", later.text);
        assertEquals(IderRanking.BuyLevel.FRESH, later.level);
    }

    @Test
    public void lastBuyBadgeUsesCalendarDays() {
        long now = wib(2026, 10, 28, 9, 0);
        assertEquals(IderRanking.BuyLevel.NEVER, IderRanking.lastBuyBadge(Long.MIN_VALUE, now, WIB).level);
        assertEquals("🛒 Beli hari ini", IderRanking.lastBuyBadge(wib(2026, 10, 28, 7, 30), now, WIB).text);
        // Tadi malam pukul 23:00 = kemarin, walau baru 10 jam.
        assertEquals("🛒 Beli kemarin", IderRanking.lastBuyBadge(wib(2026, 10, 27, 23, 0), now, WIB).text);
        IderRanking.BuyBadge three = IderRanking.lastBuyBadge(wib(2026, 10, 25, 10, 0), now, WIB);
        assertEquals("🛒 Beli 3 hari lalu · 25 Okt", three.text);
        assertEquals(IderRanking.BuyLevel.FRESH, three.level);
        assertEquals(IderRanking.BuyLevel.WARN, IderRanking.lastBuyBadge(wib(2026, 10, 10, 10, 0), now, WIB).level);
        IderRanking.BuyBadge old = IderRanking.lastBuyBadge(wib(2026, 6, 1, 10, 0), now, WIB);
        assertEquals(IderRanking.BuyLevel.STALE, old.level);
        assertEquals("🛒 Beli 4 bulan lalu · 1 Jun", old.text);
    }
}
