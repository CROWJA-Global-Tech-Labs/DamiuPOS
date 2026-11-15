package com.crowja.damiupos.util;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Matematika "Tren Penjualan" (Laporan → Tren & ubin Beranda): arah naik/turun/stagnan, % perubahan,
 * regresi linear, rentetan hari, momentum, proyeksi akhir bulan, dan kalimat analisis. Java murni
 * supaya bisa dites di JVM.
 */
public final class SalesTrendMath {

    /** Ambang stagnan: perubahan di dalam ±5% dianggap stagnan (sama dengan chip tren Beranda lama). */
    public static final double STAGNANT_PCT = 5.0;
    /** Di bawah ini jumlah hari dianggap sampel kecil; kalimat analisis diberi catatan. */
    public static final int SMALL_SAMPLE_DAYS = 14;

    public static final int DOWN = -1;
    public static final int FLAT = 0;
    public static final int UP = 1;

    /** Label bila periode lalu nol (tak ada % yang bermakna). */
    public static final String NEW_WORD = "Baru";
    public static final String NEW_SPOKEN = "baru, belum ada pembanding";

    private static final String[] DOW = {"Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu"};

    private SalesTrendMath() {
    }

    /**
     * % perubahan cur terhadap prev. Basis nol (prev 0) → null: tak terdefinisi. Bila cur &gt; 0
     * itu "baru" ({@link #isNew}), arahnya tetap {@link #UP} lewat {@link #direction}.
     */
    public static Double pctChange(double cur, double prev) {
        if (prev == 0) return null;
        return (cur - prev) / Math.abs(prev) * 100.0;
    }

    /** Ada nilai sekarang tapi periode lalu nol: belum ada pembanding. */
    public static boolean isNew(double cur, double prev) {
        return prev == 0 && cur > 0;
    }

    public static int direction(double cur, double prev) {
        return direction(cur, prev, STAGNANT_PCT);
    }

    /** Naik/turun bila |%| &gt; ambang; keduanya nol → stagnan; basis nol → ikut tanda cur. */
    public static int direction(double cur, double prev, double thresholdPct) {
        if (prev == 0) return cur > 0 ? UP : cur < 0 ? DOWN : FLAT;
        Double p = pctChange(cur, prev);
        if (p == null) return FLAT;
        if (p > thresholdPct) return UP;
        if (p < -thresholdPct) return DOWN;
        return FLAT;
    }

    public static String arrow(int dir) {
        return dir == UP ? "▲" : dir == DOWN ? "▼" : "▬";
    }

    public static String word(int dir) {
        return dir == UP ? "Naik" : dir == DOWN ? "Turun" : "Stagnan";
    }

    /** "▲ 12%", "▼ 3%", "▬ 0%"; % dibulatkan. Basis nol & naik → "▲ Baru"; tanpa data → "▬". */
    public static String tag(int dir, Double pct) {
        if (pct == null) return dir == UP ? arrow(dir) + " " + NEW_WORD : arrow(dir);
        return arrow(dir) + " " + Math.round(Math.abs(pct)) + "%";
    }

    /** Kemiringan regresi linear kuadrat terkecil (satuan per hari, x = 0..n-1). n &lt; 2 → 0. */
    public static double slope(double[] y) {
        if (y == null || y.length < 2) return 0;
        int n = y.length;
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            sx += i;
            sy += y[i];
            sxy += i * y[i];
            sxx += (double) i * i;
        }
        double den = n * sxx - sx * sx;
        return den == 0 ? 0 : (n * sxy - sx * sy) / den;
    }

    public static double sum(double[] y) {
        double s = 0;
        if (y != null) for (double v : y) s += v;
        return s;
    }

    public static double avg(double[] y) {
        return y == null || y.length == 0 ? 0 : sum(y) / y.length;
    }

    /** Rata-rata bergerak (jendela {@code w}, hari awal pakai jendela yang tersedia). */
    public static double[] movingAverage(double[] y, int w) {
        if (y == null) return new double[0];
        double[] out = new double[y.length];
        double run = 0;
        for (int i = 0; i < y.length; i++) {
            run += y[i];
            if (i >= w) run -= y[i - w];
            out[i] = run / Math.min(i + 1, w);
        }
        return out;
    }

    /**
     * Rentetan di ujung deret: +k = k hari terakhir berturut-turut naik dibanding hari sebelumnya,
     * −k = turun, 0 = hari terakhir sama dengan sebelumnya / data kurang.
     */
    public static int trailingStreak(double[] y) {
        if (y == null || y.length < 2) return 0;
        int n = y.length;
        double d = y[n - 1] - y[n - 2];
        if (d == 0) return 0;
        int sign = d > 0 ? 1 : -1;
        int k = 0;
        for (int i = n - 1; i >= 1; i--) {
            double di = y[i] - y[i - 1];
            if ((sign > 0 && di > 0) || (sign < 0 && di < 0)) k++;
            else break;
        }
        return sign * k;
    }

    /** Rentetan naik terpanjang dalam deret (jumlah hari naik berturut-turut). */
    public static int longestRise(double[] y) {
        return longestRun(y, true);
    }

    public static int longestFall(double[] y) {
        return longestRun(y, false);
    }

    private static int longestRun(double[] y, boolean up) {
        if (y == null) return 0;
        int best = 0, cur = 0;
        for (int i = 1; i < y.length; i++) {
            double d = y[i] - y[i - 1];
            if (up ? d > 0 : d < 0) {
                cur++;
                best = Math.max(best, cur);
            } else {
                cur = 0;
            }
        }
        return best;
    }

    /**
     * Proyeksi total bulan bila tren berlanjut: total s/d kemarin + tiap sisa hari dari garis tren
     * bulan berjalan (rata-rata ada di tengah hari terhitung, jadi hari ke-k sesudah kemarin =
     * rata-rata + slope × ((mtdDays−1)/2 + k)). Hasil tak pernah negatif per hari. Tak ada data → 0.
     *
     * @param mtdTotal    total bulan berjalan s/d kemarin
     * @param mtdDays     jumlah hari yang sudah terhitung (s/d kemarin)
     * @param daysInMonth jumlah hari bulan ini
     * @param slopePerDay kemiringan harian deret bulan berjalan ({@link #slope(double[])})
     */
    public static double projectMonth(double mtdTotal, int mtdDays, int daysInMonth, double slopePerDay) {
        if (mtdDays <= 0) return 0;
        double base = mtdTotal / mtdDays;
        double total = mtdTotal;
        for (int k = 1; k <= daysInMonth - mtdDays; k++) {
            total += Math.max(0, base + slopePerDay * ((mtdDays - 1) / 2.0 + k));
        }
        return total;
    }

    // ---------------------------------------------------------------- kalimat analisis

    /** Input analisis: deret harian periode ini (urut naik) & periode lalu, plus tanggal "yyyy-MM-dd". */
    public static final class Input {
        public double[] cur = new double[0];
        public double[] prev = new double[0];
        public String[] dates = new String[0];
        public boolean rupiah = true;
        /** Total bulan s/d kemarin & jumlah harinya (proyeksi); mtdDays 0 = lewati. */
        public double mtdTotal;
        public int mtdDays;
        public int daysInMonth;
        /** Deret harian bulan berjalan s/d kemarin (sumber slope proyeksi); null = server lama. */
        public double[] mtdDaily;
    }

    public static List<String> analyze(Input in) {
        List<String> out = new ArrayList<>();
        int n = in.cur.length;
        double tc = sum(in.cur), tp = sum(in.prev);
        if (n == 0 || (tc == 0 && tp == 0)) {
            out.add("Belum ada penjualan selesai di periode ini maupun sebelumnya, jadi tren belum bisa dibaca.");
            return out;
        }
        Double pct = pctChange(tc, tp);
        int dir = direction(tc, tp);
        String unit = in.rupiah ? "pendapatan" : "galon terjual";
        if (tp == 0) {
            out.add("Periode sebelumnya belum ada penjualan, jadi " + unit + " " + fmt(tc, in.rupiah)
                    + " belum punya pembanding.");
        } else if (dir == FLAT) {
            out.add("Arah umum STAGNAN: " + unit + " " + fmt(tc, in.rupiah) + ", berubah "
                    + signed(pct) + " dibanding " + n + " hari sebelumnya (masih di dalam ±"
                    + (int) STAGNANT_PCT + "%).");
        } else {
            out.add("Arah umum " + (dir == UP ? "NAIK" : "TURUN") + ": " + unit + " " + fmt(tc, in.rupiah)
                    + ", " + signed(pct) + " dibanding " + n + " hari sebelumnya (" + fmt(tp, in.rupiah) + ").");
        }

        // Hari terbaik/terburuk
        int best = 0, worst = 0;
        for (int i = 1; i < n; i++) {
            if (in.cur[i] > in.cur[best]) best = i;
            if (in.cur[i] < in.cur[worst]) worst = i;
        }
        if (n >= 2 && in.cur[best] != in.cur[worst]) {
            out.add("Hari terbaik " + dayLabel(in.dates, best) + " (" + fmt(in.cur[best], in.rupiah)
                    + "), terendah " + dayLabel(in.dates, worst) + " (" + fmt(in.cur[worst], in.rupiah) + ").");
        }

        // Pola hari dalam pekan (butuh ≥ 14 hari supaya tiap hari muncul minimal 2x)
        if (n >= 14 && in.dates.length == n) {
            double[] s = new double[7];
            int[] c = new int[7];
            for (int i = 0; i < n; i++) {
                int d = dow(in.dates[i]);
                if (d < 0) continue;
                s[d] += in.cur[i];
                c[d]++;
            }
            int hi = -1, lo = -1;
            for (int d = 0; d < 7; d++) {
                if (c[d] == 0) continue;
                double a = s[d] / c[d];
                if (hi < 0 || a > s[hi] / c[hi]) hi = d;
                if (lo < 0 || a < s[lo] / c[lo]) lo = d;
            }
            if (hi >= 0 && lo >= 0 && hi != lo) {
                out.add("Pola pekanan: rata-rata tertinggi hari " + DOW[hi] + " (" + fmt(s[hi] / c[hi], in.rupiah)
                        + "/hari), terendah hari " + DOW[lo] + " (" + fmt(s[lo] / c[lo], in.rupiah) + "/hari).");
            }
        }

        // Momentum 7 hari terakhir vs 7 hari sebelumnya, di dalam periode ini saja (periode 7 hari
        // sudah tercakup kalimat arah umum).
        if (n >= 14) {
            double last = 0, before = 0;
            for (int i = n - 7; i < n; i++) last += in.cur[i];
            for (int i = n - 14; i < n - 7; i++) before += in.cur[i];
            Double mp = pctChange(last, before);
            int md = direction(last, before);
            if (mp != null) {
                out.add("Momentum 7 hari terakhir " + word(md).toLowerCase(Locale.ROOT) + " (" + signed(mp)
                        + " dibanding 7 hari sebelumnya).");
            }
        }

        // Rentetan
        int streak = trailingStreak(in.cur);
        if (Math.abs(streak) >= 2) {
            out.add(Math.abs(streak) + " hari terakhir berturut-turut " + (streak > 0 ? "naik" : "turun")
                    + " dari hari sebelumnya.");
        }
        int rise = longestRise(in.cur), fall = longestFall(in.cur);
        if (rise >= 3 || fall >= 3) {
            out.add("Rentetan terpanjang: naik " + rise + " hari, turun " + fall + " hari berturut-turut.");
        }

        // Regresi
        double sl = slope(in.cur);
        if (n >= 3) {
            double avg = avg(in.cur);
            int sd = avg == 0 ? FLAT : direction(avg + sl, avg, 1.0);   // ±1%/hari dari rata-rata
            out.add("Garis tren (regresi linear) " + (sd == UP ? "menanjak" : sd == DOWN ? "menurun" : "datar")
                    + " sekitar " + fmt(Math.abs(sl), in.rupiah) + " per hari.");
        }

        // Proyeksi
        if (in.mtdDays > 0 && in.daysInMonth >= in.mtdDays) {
            // Slope dari bulan ini sendiri (tak bergantung chip); < 3 hari → rata-rata saja.
            double msl = in.mtdDaily == null ? sl : in.mtdDaily.length >= 3 ? slope(in.mtdDaily) : 0;
            double proj = projectMonth(in.mtdTotal, in.mtdDays, in.daysInMonth, msl);
            out.add("Bila tren bulan ini berlanjut, akhir bulan diperkirakan sekitar " + fmt(proj, in.rupiah)
                    + " (s/d kemarin " + fmt(in.mtdTotal, in.rupiah) + " dalam " + in.mtdDays + " hari).");
        }

        if (n < SMALL_SAMPLE_DAYS) {
            out.add("Catatan: hanya " + n + " hari data, jadi pola di atas mudah berubah oleh satu-dua hari ramai/sepi.");
        }
        return out;
    }

    /** 0=Minggu..6=Sabtu dari "yyyy-MM-dd"; gagal → -1. */
    static int dow(String ymd) {
        try {
            String[] p = ymd.split("-");
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            return c.get(Calendar.DAY_OF_WEEK) - 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String dayLabel(String[] dates, int i) {
        if (dates == null || i >= dates.length) return "ke-" + (i + 1);
        String d = dates[i];
        int w = dow(d);
        String[] p = d.split("-");
        String dm = p.length == 3 ? Integer.parseInt(p[2]) + "/" + Integer.parseInt(p[1]) : d;
        return (w >= 0 ? DOW[w] + " " : "") + dm;
    }

    public static String signed(Double pct) {
        if (pct == null) return "–";
        long r = Math.round(pct);
        return (r > 0 ? "+" : r < 0 ? "−" : "±") + Math.abs(r) + "%";
    }

    public static String fmt(double v, boolean rupiah) {
        if (rupiah) return "Rp " + NumberFormat.getInstance(new Locale("in", "ID")).format(Math.round(v));
        double r = Math.round(v * 10) / 10.0;
        return (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r).replace('.', ',')) + " galon";
    }
}
