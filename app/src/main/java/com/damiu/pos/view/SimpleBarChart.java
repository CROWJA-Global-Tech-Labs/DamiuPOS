package com.damiu.pos.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.damiu.pos.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight bar chart view - no external library needed.
 */
public class SimpleBarChart extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<BarData> data = new ArrayList<>();
    private float maxValue = 0;

    public static class BarData {
        public String label;
        public float value;
        public int color;

        public BarData(String label, float value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    public SimpleBarChart(Context context) {
        super(context);
        init();
    }

    public SimpleBarChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setTextSize(24f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(0xFF757575);

        gridPaint.setColor(0xFFE0E0E0);
        gridPaint.setStrokeWidth(1f);
    }

    public void setData(List<BarData> data) {
        this.data = data;
        maxValue = 0;
        for (BarData d : data) {
            if (d.value > maxValue) maxValue = d.value;
        }
        if (maxValue == 0) maxValue = 1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data.isEmpty()) return;

        float w = getWidth();
        float h = getHeight();
        float paddingBottom = 60f;
        float paddingTop = 40f;
        float chartHeight = h - paddingBottom - paddingTop;
        float barWidth = (w - 40f) / data.size() * 0.6f;
        float gap = (w - 40f) / data.size();

        // Grid lines
        for (int i = 0; i <= 4; i++) {
            float y = paddingTop + chartHeight * (1 - i / 4f);
            canvas.drawLine(20, y, w - 20, y, gridPaint);
        }

        // Bars
        for (int i = 0; i < data.size(); i++) {
            BarData d = data.get(i);
            float x = 20 + gap * i + gap / 2f;
            float barHeight = (d.value / maxValue) * chartHeight;
            float top = paddingTop + chartHeight - barHeight;

            barPaint.setColor(d.color);
            RectF rect = new RectF(x - barWidth / 2, top, x + barWidth / 2, paddingTop + chartHeight);
            canvas.drawRoundRect(rect, 8f, 8f, canvas.isHardwareAccelerated() ? barPaint : barPaint);
            canvas.drawRoundRect(rect, 8f, 8f, barPaint);

            // Value text
            textPaint.setColor(d.color);
            canvas.drawText(String.valueOf((int) d.value), x, top - 8, textPaint);

            // Label
            canvas.drawText(d.label, x, h - 10, labelPaint);
        }
    }
}
