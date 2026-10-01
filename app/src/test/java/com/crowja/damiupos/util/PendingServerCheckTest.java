package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Mesin status pemeriksaan server di form pelanggan: batal/hancur → hasil telat diabaikan. */
public class PendingServerCheckTest {

    @Test public void completeAcceptsTheCurrentTokenExactlyOnce() {
        PendingServerCheck c = new PendingServerCheck();
        int t = c.begin();
        assertTrue(c.isActive());
        assertTrue(c.complete(t));
        assertFalse(c.isActive());
        assertFalse("hasil kedua untuk token yang sama diabaikan", c.complete(t));
    }

    @Test public void cancelMakesALateResultIgnoredAndNeverSaves() {
        PendingServerCheck c = new PendingServerCheck();
        int t = c.begin();
        assertTrue("ada yang dibatalkan", c.cancel());
        assertFalse(c.isActive());
        assertFalse("hasil jaringan yang telat tak boleh diproses (tak boleh menyimpan diam-diam)", c.complete(t));
    }

    @Test public void cancelWithNothingPendingIsANoOp() {
        PendingServerCheck c = new PendingServerCheck();
        assertFalse(c.cancel());
        int t = c.begin();
        assertTrue(c.complete(t));
        assertFalse("setelah selesai tak ada yang bisa dibatalkan", c.cancel());
    }

    @Test public void staleTokenFromAnEarlierAttemptCannotCompleteANewOne() {
        PendingServerCheck c = new PendingServerCheck();
        int first = c.begin();
        c.cancel();                       // staf menutup dialog, lalu mengetuk Simpan lagi
        int second = c.begin();
        assertFalse("token percobaan lama basi", c.complete(first));
        assertTrue("percobaan baru tak terganggu hasil lama", c.isActive());
        assertTrue(c.complete(second));
    }

    @Test public void beginWhileActiveSupersedesThePreviousCheck() {
        PendingServerCheck c = new PendingServerCheck();
        int first = c.begin();
        int second = c.begin();
        assertFalse(c.complete(first));
        assertTrue(c.complete(second));
    }

    @Test public void destroyDuringCheckIgnoresResultAndIsIdempotent() {
        PendingServerCheck c = new PendingServerCheck();
        int t = c.begin();
        c.cancel();                       // onDestroy
        assertFalse(c.cancel());          // onDestroy kedua / onCancel menyusul: aman
        assertFalse(c.complete(t));
        assertEquals(false, c.isActive());
    }
}
