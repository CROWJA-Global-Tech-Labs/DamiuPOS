package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DeliveryNavCollectSummaryTest {

    @Test
    public void totalDenganRincianPesananDanHutangBertanggal() {
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(12000, 0, 12000, true);
        assertEquals("💰 Tagih Rp 24.000 (pesanan Rp 12.000 + hutang 8 Nov Rp 12.000)",
                DeliveryNavLogic.collectSummary(m, "8 Nov"));
    }

    @Test
    public void tanpaTanggalPakaiHutangLama() {
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(12000, 0, 5000, true);
        assertEquals("💰 Tagih Rp 17.000 (pesanan Rp 12.000 + hutang lama Rp 5.000)",
                DeliveryNavLogic.collectSummary(m, ""));
    }

    @Test
    public void tanpaHutangKosong() {
        assertEquals("", DeliveryNavLogic.collectSummary(DeliveryNavLogic.DoorMoney.plain(12000), "8 Nov"));
    }

    @Test
    public void hutangDitagihDiPintuLainKosong() {
        assertEquals("", DeliveryNavLogic.collectSummary(new DeliveryNavLogic.DoorMoney(12000, 0, 9000, false), "8 Nov"));
    }

    @Test
    public void dayMonth() {
        assertEquals("8 Nov", DeliveryNavLogic.dayMonth("2026-11-08 10:00:00"));
        assertEquals("21 Agu", DeliveryNavLogic.dayMonth("2026-08-21 01:02:03"));
        assertEquals("", DeliveryNavLogic.dayMonth(""));
        assertEquals("", DeliveryNavLogic.dayMonth(null));
    }
}
