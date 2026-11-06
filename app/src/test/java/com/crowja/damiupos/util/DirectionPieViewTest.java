package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DirectionPieViewTest {
   @Test
   public void sectorsFollowCompassWithYPointingDown() {
      assertEquals(0, DirectionPieView.sectorAt(0f, -10f));    // atas = Utara
      assertEquals(1, DirectionPieView.sectorAt(10f, 0f));     // kanan = Timur
      assertEquals(2, DirectionPieView.sectorAt(0f, 10f));     // bawah = Selatan
      assertEquals(3, DirectionPieView.sectorAt(-10f, 0f));    // kiri = Barat
   }

   @Test
   public void diagonalsBelongToTheNearerAxisAndCenterIsNone() {
      assertEquals(0, DirectionPieView.sectorAt(3f, -9f));
      assertEquals(1, DirectionPieView.sectorAt(9f, -3f));
      assertEquals(2, DirectionPieView.sectorAt(-3f, 9f));
      assertEquals(3, DirectionPieView.sectorAt(-9f, 3f));
      assertEquals(-1, DirectionPieView.sectorAt(0f, 0f));
   }
}
