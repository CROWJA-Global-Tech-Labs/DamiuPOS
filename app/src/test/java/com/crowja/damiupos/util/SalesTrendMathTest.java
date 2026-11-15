package com.crowja.damiupos.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SalesTrendMathTest {

    private static final double E = 1e-9;

    @Test
    public void pctChangeZeroBaseline() {
        assertNull(SalesTrendMath.pctChange(0, 0));
        assertEquals(100.0, SalesTrendMath.pctChange(50, 0), E);
        assertEquals(-50.0, SalesTrendMath.pctChange(50, 100), E);
        assertEquals(20.0, SalesTrendMath.pctChange(120, 100), E);
    }

    @Test
    public void directionUsesFivePercentBand() {
        assertEquals(SalesTrendMath.FLAT, SalesTrendMath.direction(105, 100));
        assertEquals(SalesTrendMath.FLAT, SalesTrendMath.direction(95, 100));
        assertEquals(SalesTrendMath.UP, SalesTrendMath.direction(106, 100));
        assertEquals(SalesTrendMath.DOWN, SalesTrendMath.direction(94, 100));
        assertEquals(SalesTrendMath.FLAT, SalesTrendMath.direction(0, 0));
        assertEquals(SalesTrendMath.UP, SalesTrendMath.direction(1, 0));
    }

    @Test
    public void indicatorsAreNotColorOnly() {
        assertEquals("▲ 12%", SalesTrendMath.tag(SalesTrendMath.UP, 12.4));
        assertEquals("▼ 30%", SalesTrendMath.tag(SalesTrendMath.DOWN, -30.0));
        assertEquals("▬ 2%", SalesTrendMath.tag(SalesTrendMath.FLAT, 2.0));
        assertEquals("▬", SalesTrendMath.tag(SalesTrendMath.FLAT, null));
        assertEquals("Naik", SalesTrendMath.word(SalesTrendMath.UP));
        assertEquals("Turun", SalesTrendMath.word(SalesTrendMath.DOWN));
        assertEquals("Stagnan", SalesTrendMath.word(SalesTrendMath.FLAT));
        assertEquals("+12%", SalesTrendMath.signed(12.4));
        assertEquals("−3%", SalesTrendMath.signed(-3.0));
        assertEquals("–", SalesTrendMath.signed(null));
    }

    @Test
    public void slopeOfLine() {
        assertEquals(2.0, SalesTrendMath.slope(new double[]{1, 3, 5, 7}), E);
        assertEquals(0.0, SalesTrendMath.slope(new double[]{4, 4, 4}), E);
        assertEquals(0.0, SalesTrendMath.slope(new double[]{4}), E);
    }

    @Test
    public void movingAverageUsesAvailableWindowAtStart() {
        assertArrayEquals(new double[]{2, 3, 4, 6}, SalesTrendMath.movingAverage(new double[]{2, 4, 6, 8}, 3), E);
    }

    @Test
    public void streaks() {
        assertEquals(3, SalesTrendMath.trailingStreak(new double[]{5, 1, 2, 3, 4}));
        assertEquals(-2, SalesTrendMath.trailingStreak(new double[]{1, 5, 3, 2}));
        assertEquals(0, SalesTrendMath.trailingStreak(new double[]{1, 2, 2}));
        assertEquals(3, SalesTrendMath.longestRise(new double[]{1, 2, 3, 4, 1, 2}));
        assertEquals(1, SalesTrendMath.longestFall(new double[]{1, 2, 3, 4, 1, 2}));
    }

    @Test
    public void projectMonthFlatAndClamped() {
        // 10 hari @100, slope 0, bulan 30 hari → 3000
        assertEquals(3000.0, SalesTrendMath.projectMonth(1000, 10, 30, 0), E);
        // Slope sangat negatif: sisa hari tak pernah di bawah nol
        assertEquals(1000.0, SalesTrendMath.projectMonth(1000, 10, 30, -500), E);
        assertEquals(0.0, SalesTrendMath.projectMonth(0, 0, 30, 5), E);
    }

    @Test
    public void analyzeEmpty() {
        SalesTrendMath.Input in = new SalesTrendMath.Input();
        in.cur = new double[]{0, 0, 0};
        in.prev = new double[]{0, 0, 0};
        List<String> s = SalesTrendMath.analyze(in);
        assertEquals(1, s.size());
        assertTrue(s.get(0).startsWith("Belum ada penjualan"));
    }

    @Test
    public void analyzeRisingFortnight() {
        SalesTrendMath.Input in = new SalesTrendMath.Input();
        in.rupiah = false;
        in.cur = new double[14];
        in.prev = new double[14];
        in.dates = new String[14];
        for (int i = 0; i < 14; i++) {
            in.cur[i] = 20 + i;
            in.prev[i] = 10;
            in.dates[i] = String.format(java.util.Locale.US, "2026-11-%02d", i + 1);
        }
        in.mtdTotal = 300;
        in.mtdDays = 10;
        in.daysInMonth = 30;
        String all = String.join("\n", SalesTrendMath.analyze(in));
        assertTrue(all, all.contains("Arah umum NAIK"));
        assertTrue(all, all.contains("Hari terbaik Sabtu 14/11"));
        assertTrue(all, all.contains("Pola pekanan"));
        assertTrue(all, all.contains("Momentum 7 hari terakhir naik"));
        assertTrue(all, all.contains("13 hari terakhir berturut-turut naik"));
        assertTrue(all, all.contains("menanjak"));
        assertTrue(all, all.contains("akhir bulan diperkirakan"));
        assertTrue(all, !all.contains("Catatan: hanya"));
    }

    @Test
    public void analyzeNoBaselineAndSmallSample() {
        SalesTrendMath.Input in = new SalesTrendMath.Input();
        in.cur = new double[]{100, 0, 50, 0, 0, 0, 0};
        in.prev = new double[7];
        String all = String.join("\n", SalesTrendMath.analyze(in));
        assertTrue(all, all.contains("belum punya pembanding"));
        assertTrue(all, all.contains("Catatan: hanya 7 hari"));
    }
}
