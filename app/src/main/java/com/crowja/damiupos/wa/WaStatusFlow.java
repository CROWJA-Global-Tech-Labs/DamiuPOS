package com.crowja.damiupos.wa;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Tata letak "mengalir" sederhana untuk layar Status WhatsApp: anak-anak berjajar ke kanan lalu turun ke baris baru
 * bila tak muat (lencana & tombol aksi di kartu daftar, foto koleksi di form). Tanpa pustaka tambahan.
 */
final class WaStatusFlow extends ViewGroup {

    private final int hGap;
    private final int vGap;

    WaStatusFlow(Context c, int hGapPx, int vGapPx) {
        super(c);
        hGap = hGapPx;
        vGap = vGapPx;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0, maxW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChild(ch, MeasureSpec.makeMeasureSpec(Math.max(0, limit), mode == MeasureSpec.UNSPECIFIED
                    ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
            if (x > 0 && x + w > limit) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            x += w + hGap;
            rowH = Math.max(rowH, h);
            maxW = Math.max(maxW, x - hGap);
        }
        int height = y + rowH + getPaddingTop() + getPaddingBottom();
        int width = mode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthMeasureSpec)
                : Math.min(maxW + getPaddingLeft() + getPaddingRight(),
                mode == MeasureSpec.AT_MOST ? MeasureSpec.getSize(widthMeasureSpec) : Integer.MAX_VALUE);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int limit = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
            if (x > 0 && x + w > limit) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            int left = getPaddingLeft() + x, top = getPaddingTop() + y;
            ch.layout(left, top, left + w, top + h);
            x += w + hGap;
            rowH = Math.max(rowH, h);
        }
    }
}
