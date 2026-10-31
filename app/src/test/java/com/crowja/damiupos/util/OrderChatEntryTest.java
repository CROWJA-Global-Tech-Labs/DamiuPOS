package com.crowja.damiupos.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OrderChatEntryTest {

    private static final String CUST = "6f1c2a90-0000-4000-8000-000000000001";

    @Test
    public void sessionAlwaysOpens() {
        assertTrue(OrderChatEntry.available(true, "JUAL", false, CUST));
        // Sesi menempel di leg checkout / order agen tanpa pelanggan tersinkron — tetap bisa dibuka.
        assertTrue(OrderChatEntry.available(true, "JUAL", false, ""));
        assertTrue(OrderChatEntry.available(true, "KEMBALI", true, null));
    }

    @Test
    public void saleWithCustomerOpensOnDemand() {
        // Perangkat Lain: baris server tanpa kolom tipe — pickup_only=false berarti order jual.
        assertTrue(OrderChatEntry.available(false, null, false, CUST));
        assertTrue(OrderChatEntry.available(false, "", false, CUST));
        // Tertunda: kolom tipe ada.
        assertTrue(OrderChatEntry.available(false, "JUAL", false, CUST));
        assertTrue(OrderChatEntry.available(false, "jual", false, " " + CUST + " "));
    }

    @Test
    public void pickupOnlyWithoutSessionHidden() {
        assertFalse(OrderChatEntry.available(false, null, true, CUST));
        assertFalse(OrderChatEntry.available(false, "KEMBALI", false, CUST));
        assertFalse(OrderChatEntry.available(false, "KEMBALI", true, CUST));
    }

    @Test
    public void umumWithoutSessionHidden() {
        assertFalse(OrderChatEntry.available(false, "JUAL", false, null));
        assertFalse(OrderChatEntry.available(false, "JUAL", false, ""));
        assertFalse(OrderChatEntry.available(false, "JUAL", false, "  "));
        assertFalse(OrderChatEntry.available(false, null, false, "null"));
    }
}
