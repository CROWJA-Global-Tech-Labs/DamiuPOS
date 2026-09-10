package com.crowja.damiupos.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Jarum "arah ke tujuan" — dipakai panel Preview (peta+foto+kompas gabungan) di
 * DeliveryQueueActivity. Sudut yang diterima {@link #pointTo} SELALU RELATIF terhadap arah
 * perangkat menghadap (bearing tujuan − heading kompas perangkat, lihat
 * DeliveryQueueActivity#updateCompassBearing), bukan bearing absolut — 0° = tujuan tepat di depan
 * layar, 90° = tujuan di kanan, dst. Konversi absolut→relatif sengaja dilakukan di pemanggil supaya
 * widget ini tetap murni "gambar jarum pada sudut X", tidak tahu-menahu soal kompas atau GPS.
 *
 * <p>Update sensor datang 5-10×/detik dan akan terlihat gemetar bila digambar apa adanya — jarum
 * diperhalus lewat {@link ValueAnimator} yang mengambil jalur PUTARAN TERPENDEK (mis. 350°→10°
 * dianimasikan sebagai +20°, bukan mundur −340°).</p>
 */
public class CompassArrowView extends View {

    private float displayedDeg = 0f;
    private ValueAnimator animator;
    private boolean hasHeading = false;

    public CompassArrowView(Context c) {
        super(c);
    }

    public CompassArrowView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** Sudut relatif (0..360, searah jarum jam, 0 = lurus di depan) tempat tujuan berada sekarang. */
    public void pointTo(float relativeDeg) {
        hasHeading = true;
        float target = ((relativeDeg % 360f) + 360f) % 360f;
        float delta = ((target - displayedDeg + 540f) % 360f) - 180f;   // jalur terpendek, bisa negatif
        float end = displayedDeg + delta;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(displayedDeg, end);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            displayedDeg = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** Belum ada fix GPS/kompas — tampilkan lingkaran kosong (titik tengah saja, tanpa jarum). */
    public void clearHeading() {
        hasHeading = false;
        if (animator != null) animator.cancel();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(4);
        if (r <= 0) return;

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(0xFFBAE6FD);
        canvas.drawCircle(cx, cy, r, ring);

        Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
        hub.setColor(hasHeading ? 0xFF0369A1 : 0xFFBAE6FD);
        canvas.drawCircle(cx, cy, dp(3.5f), hub);

        if (!hasHeading) return;

        canvas.save();
        canvas.rotate(displayedDeg, cx, cy);

        // Jarum dua-warna khas kompas: separuh depan (menuju tujuan) tegas, separuh belakang pudar.
        Paint front = new Paint(Paint.ANTI_ALIAS_FLAG);
        front.setColor(0xFF0369A1);
        Path head = new Path();
        head.moveTo(cx, cy - r * 0.92f);
        head.lineTo(cx - r * 0.16f, cy - r * 0.12f);
        head.lineTo(cx + r * 0.16f, cy - r * 0.12f);
        head.close();
        canvas.drawPath(head, front);

        Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        back.setColor(0xFFBAE6FD);
        Path tail = new Path();
        tail.moveTo(cx, cy + r * 0.5f);
        tail.lineTo(cx - r * 0.1f, cy + r * 0.08f);
        tail.lineTo(cx + r * 0.1f, cy + r * 0.08f);
        tail.close();
        canvas.drawPath(tail, back);

        canvas.restore();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
