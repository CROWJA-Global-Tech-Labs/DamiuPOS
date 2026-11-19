package com.crowja.damiupos.checkout;

import static org.junit.Assert.assertEquals;

import com.crowja.damiupos.model.Transaction;

import org.junit.Test;

/** Galon gift kena ongkir per galon (owner 21/10/2026), sama dengan web (Gifts::applyProductGift 'append'). */
public class GiftOngkirTest {
    @Test
    public void perGalonRateIsChargedForEachGiftGalon() {
        assertEquals(2000.0, GiftOngkir.of(Transaction.ONGKIR_PER_GALON, 1000, 2), 0.001);
    }

    @Test
    public void bulkOrNoOngkirOrNoGiftAddsNothing() {
        assertEquals(0.0, GiftOngkir.of(Transaction.ONGKIR_BORONGAN, 5000, 1), 0.001);
        assertEquals(0.0, GiftOngkir.of(Transaction.ONGKIR_NONE, 1000, 1), 0.001);
        assertEquals(0.0, GiftOngkir.of(Transaction.ONGKIR_PER_GALON, 0, 1), 0.001);
        assertEquals(0.0, GiftOngkir.of(Transaction.ONGKIR_PER_GALON, 1000, 0), 0.001);
    }
}
