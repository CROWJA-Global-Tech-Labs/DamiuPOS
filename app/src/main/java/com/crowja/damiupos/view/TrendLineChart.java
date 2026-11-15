package com.crowja.damiupos.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Grafik garis ringan tanpa library: beberapa deret di atas sumbu waktu yang sama (deret pertama
 * = utama, tebal + titik; deret lain boleh putus-putus), garis bantu horizontal + label sumbu Y
 * ringkas, label sumbu X jarang supaya tak tumpang tindih.
 */
public class TrendLineChart extends View {

    public static final class Series {
        final String name;
        final double[] values;
        final int color;
        final boolean dashed;
        final boolean main;

        public Series(String name, double[] values, int color, boolean dashed, boolean main) {
            this.name = name;
            this.values = values;
            this.color = color;
            this.dashed = dashed;
            this.main = main;
        }
    }

    private final List<Series> series = new ArrayList<>();
    private String[] labels = new String[0];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    public TrendLineChart(Context c) {
        this(c, null);
    }

    public TrendLineChart(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(dp);
        grid.setColor(0xFFEBEBEB);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        text.setColor(0xFF4A4A4A);
        fill.setStyle(Paint.Style.FILL);
    }

    public void setColors(int gridColor, int textColor) {
        grid.setColor(gridColor);
        text.setColor(textColor);
        invalidate();
    }

    public void setData(String[] xLabels, List<Series> data) {
        labels = xLabels != null ? xLabels : new String[0];
        series.clear();
        if (data != null) series.addAll(data);
        StringBuilder cd = new StringBuilder("Grafik garis");
        for (Series s : series) cd.append(", ").append(s.name);
        setContentDescription(cd.toString());
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : (int) (200 * dp);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = 0;
        double max = 0;
        for (Series s : series) {
            n = Math.max(n, s.values.length);
            for (double v : s.values) max = Math.max(max, v);
        }
        if (n == 0) return;
        if (max <= 0) max = 1;
        double step = niceStep(max / 4);
        double top = Math.ceil(max / step) * step;

        float left = text.measureText(compact(top)) + 8 * dp;
        float right = getWidth() - 8 * dp;
        float tY = 8 * dp;
        float bY = getHeight() - 20 * dp;
        float h = bY - tY;

        // Garis bantu + label Y
        for (double g = 0; g <= top + step / 2; g += step) {
            float y = (float) (bY - g / top * h);
            c.drawLine(left, y, right, y, grid);
            String l = compact(g);
            c.drawText(l, left - 6 * dp - text.measureText(l), y + text.getTextSize() / 3, text);
        }
        float dx = n > 1 ? (right - left) / (n - 1) : 0;

        // Label X jarang (maks ~6)
        int every = Math.max(1, (int) Math.ceil(n / 6.0));
        for (int i = 0; i < labels.length && i < n; i++) {
            if (i % every != 0 && i != n - 1) continue;
            if (i != n - 1 && n - 1 - i < every && i % every == 0 && i != 0) continue;
            String l = labels[i];
            float x = left + dx * i;
            float tw = text.measureText(l);
            float tx = Math.max(0, Math.min(getWidth() - tw, x - tw / 2));
            c.drawText(l, tx, getHeight() - 4 * dp, text);
        }

        // Deret: yang bukan utama dulu supaya garis utama di atas.
        for (int pass = 0; pass < 2; pass++) {
            for (Series s : series) {
                if (s.main != (pass == 1)) continue;
                line.setColor(s.color);
                line.setStrokeWidth((s.main ? 2.5f : 1.75f) * dp);
                line.setPathEffect(s.dashed ? new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0) : null);
                path.reset();
                for (int i = 0; i < s.values.length; i++) {
                    float x = left + dx * i;
                    float y = (float) (bY - s.values[i] / top * h);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                c.drawPath(path, line);
                if (s.main && s.values.length <= 31) {
                    dot.setColor(s.color);
                    for (int i = 0; i < s.values.length; i++) {
                        c.drawCircle(left + dx * i, (float) (bY - s.values[i] / top * h), 2.5f * dp, dot);
                    }
                }
            }
        }
    }

    private static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / mag;
        double nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return nf * mag;
    }

    static String compact(double v) {
        if (v >= 1_000_000) return trim(v / 1_000_000) + "jt";
        if (v >= 1_000) return trim(v / 1_000) + "rb";
        return trim(v);
    }

    private static String trim(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r).replace('.', ',');
    }
}
