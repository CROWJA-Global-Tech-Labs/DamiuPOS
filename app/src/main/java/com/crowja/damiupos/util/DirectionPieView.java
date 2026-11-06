package com.crowja.damiupos.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagram lingkaran empat kuadran "Kirim ke Arah": Utara, Timur, Selatan, Barat. Tiap kuadran diketuk untuk
 * dicentang / dilepas, jadi beberapa arah bisa dipilih sekaligus. Sektornya 90° berpusat di arah mata angin
 * (U: 315–45, T: 45–135, S: 135–225, B: 225–315) — sama dengan penyaring server ({@code dirs} N/E/S/W).
 */
public class DirectionPieView extends View {
   public static final String[] CODES = {"N", "E", "S", "W"};
   public static final String[] NAMES = {"Utara", "Timur", "Selatan", "Barat"};
   private static final int SELECTED = 0xFF1565C0;     // putih di atasnya ≥ 5,5:1
   private static final int IDLE = 0xFFE3E8EF;         // 0xFF1F2933 di atasnya ≥ 11:1
   private static final int IDLE_TEXT = 0xFF1F2933;
   private static final int LINE = 0xFF5F6B7A;

   private final boolean[] on = new boolean[4];
   private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
   private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
   private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
   private final RectF oval = new RectF();
   private Runnable onChange;

   public DirectionPieView(Context c) {
      super(c);
      float d = c.getResources().getDisplayMetrics().density;
      stroke.setStyle(Paint.Style.STROKE);
      stroke.setStrokeWidth(2f * d);
      stroke.setColor(LINE);
      text.setTextAlign(Paint.Align.CENTER);
      text.setTextSize(15f * d);
      text.setTypeface(Typeface.DEFAULT_BOLD);
      setMinimumHeight((int) (210 * d));
      setContentDescription(describe());
   }

   /** Sektor yang diketuk di titik (dx, dy) dari pusat (dy ke bawah): 0 U, 1 T, 2 S, 3 B; -1 di pusat. */
   public static int sectorAt(float dx, float dy) {
      if (dx * dx + dy * dy < 1e-6f) return -1;
      if (Math.abs(dy) >= Math.abs(dx)) return dy < 0 ? 0 : 2;
      return dx > 0 ? 1 : 3;
   }

   public void setOnChange(Runnable r) {
      this.onChange = r;
   }

   /** Kode terpilih berurutan U, T, S, B ("N","E","S","W"). */
   public List<String> selected() {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < 4; i++) if (on[i]) out.add(CODES[i]);
      return out;
   }

   public String describe() {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < 4; i++) {
         if (!on[i]) continue;
         if (b.length() > 0) b.append(", ");
         b.append(NAMES[i]);
      }
      return b.length() == 0 ? "Semua arah" : b.toString();
   }

   public void clear() {
      for (int i = 0; i < 4; i++) on[i] = false;
      changed();
   }

   private void changed() {
      setContentDescription("Kirim ke arah: " + describe());
      invalidate();
      if (onChange != null) onChange.run();
   }

   @Override
   protected void onMeasure(int w, int h) {
      int size = Math.min(MeasureSpec.getSize(w), (int) (240 * getResources().getDisplayMetrics().density));
      setMeasuredDimension(size, size);
   }

   @Override
   protected void onDraw(Canvas c) {
      float pad = 4f * getResources().getDisplayMetrics().density;
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      float r = Math.min(cx, cy) - pad;
      oval.set(cx - r, cy - r, cx + r, cy + r);
      // Sudut Android: 0 = timur, searah jarum jam. Utara = 270°, jadi U menyapu 225→315.
      float[] start = {225f, 315f, 45f, 135f};
      float[] mid = {270f, 0f, 90f, 180f};
      for (int i = 0; i < 4; i++) {
         fill.setStyle(Paint.Style.FILL);
         fill.setColor(on[i] ? SELECTED : IDLE);
         c.drawArc(oval, start[i], 90f, true, fill);
         c.drawArc(oval, start[i], 90f, true, stroke);
         text.setColor(on[i] ? 0xFFFFFFFF : IDLE_TEXT);
         double a = Math.toRadians(mid[i]);
         float tx = cx + (float) Math.cos(a) * r * 0.58f;
         float ty = cy + (float) Math.sin(a) * r * 0.58f - (text.ascent() + text.descent()) / 2f;
         c.drawText(NAMES[i], tx, ty, text);
      }
   }

   @Override
   public boolean onTouchEvent(MotionEvent e) {
      if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
      if (e.getAction() != MotionEvent.ACTION_UP) return true;
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      float dx = e.getX() - cx;
      float dy = e.getY() - cy;
      if (Math.sqrt(dx * dx + dy * dy) > Math.min(cx, cy)) return true;
      int i = sectorAt(dx, dy);
      if (i >= 0) {
         on[i] = !on[i];
         performClick();
         changed();
      }
      return true;
   }

   @Override
   public boolean performClick() {
      return super.performClick();
   }
}
