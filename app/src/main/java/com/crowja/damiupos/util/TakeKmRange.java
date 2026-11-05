package com.crowja.damiupos.util;

import java.util.Locale;

/** Rentang jarak slider Ambil Antrean. Ujung kanan skala berarti tanpa batas atas. */
public final class TakeKmRange {
   public static final float SCALE_MIN = 0f;
   public static final float SCALE_MAX = 20f;
   public static final float STEP = 0.5f;
   public static final float DEFAULT_MIN = 0f;
   public static final float DEFAULT_MAX = 5f;

   private TakeKmRange() {
   }

   public static boolean isUnlimited(float max) {
      return max >= SCALE_MAX;
   }

   /** Ada batas jarak yang harus disaring server. */
   public static boolean hasLimit(float min, float max) {
      return min > SCALE_MIN || !isUnlimited(max);
   }

   public static String label(float min, float max) {
      return "Min " + km(min) + " · Maks " + (isUnlimited(max) ? "tanpa batas" : km(max));
   }

   private static String km(float v) {
      String s = v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
      return s + " km";
   }
}
