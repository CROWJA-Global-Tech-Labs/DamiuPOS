package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TakeKmRangeTest {
   @Test
   public void defaultIsZeroToFive() {
      assertEquals("Min 0 km · Maks 5 km", TakeKmRange.label(TakeKmRange.DEFAULT_MIN, TakeKmRange.DEFAULT_MAX));
      assertTrue(TakeKmRange.hasLimit(TakeKmRange.DEFAULT_MIN, TakeKmRange.DEFAULT_MAX));
   }

   @Test
   public void halfStepsUseComma() {
      assertEquals("Min 1,5 km · Maks 7,5 km", TakeKmRange.label(1.5f, 7.5f));
   }

   @Test
   public void rightEndMeansNoUpperLimit() {
      assertEquals("Min 0 km · Maks tanpa batas", TakeKmRange.label(0f, TakeKmRange.SCALE_MAX));
      assertFalse(TakeKmRange.hasLimit(0f, TakeKmRange.SCALE_MAX));
      assertTrue(TakeKmRange.hasLimit(2f, TakeKmRange.SCALE_MAX));
   }

   @Test
   public void defaultsFitTheSliderSteps() {
      for (float v : new float[]{TakeKmRange.DEFAULT_MIN, TakeKmRange.DEFAULT_MAX, TakeKmRange.SCALE_MAX}) {
         assertEquals(0f, (v - TakeKmRange.SCALE_MIN) % TakeKmRange.STEP, 0f);
      }
   }
}
