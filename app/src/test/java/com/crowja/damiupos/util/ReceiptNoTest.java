package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** Nomor struk: suffix acak 5 karakter [A-Z0-9], susunan KODE-DDMMYYHHMM-XXXXX, dan pembaca dua format. */
public class ReceiptNoTest {

    @Test public void randomSuffixIsFiveUppercaseAlphanumerics() {
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String s = ReceiptNo.randomSuffix(rng);
            assertEquals(5, s.length());
            assertTrue(s, s.matches("[A-Z0-9]{5}"));
        }
    }

    @Test public void randomSuffixIsRoughlyUniformOverAllThirtySixCharacters() {
        Random rng = new Random(20260928L);
        int draws = 20000;
        int[] counts = new int[ReceiptNo.ALPHABET.length()];
        for (int i = 0; i < draws; i++) {
            for (char ch : ReceiptNo.randomSuffix(rng).toCharArray()) {
                int idx = ReceiptNo.ALPHABET.indexOf(ch);
                assertTrue("char outside alphabet: " + ch, idx >= 0);
                counts[idx]++;
            }
        }
        double mean = (double) draws * ReceiptNo.SUFFIX_LENGTH / counts.length;
        for (int i = 0; i < counts.length; i++) {
            char ch = ReceiptNo.ALPHABET.charAt(i);
            assertTrue("'" + ch + "' never drawn", counts[i] > 0);
            assertTrue("'" + ch + "' count " + counts[i] + " far from mean " + mean,
                    Math.abs(counts[i] - mean) <= 0.30 * mean);
        }
    }

    @Test public void composeJoinsCodeStampAndSuffix() {
        String no = ReceiptNo.compose("ZK", "2809260945", ReceiptNo.randomSuffix(new Random(7)));
        assertTrue(no, no.matches("ZK-2809260945-[A-Z0-9]{5}"));
        assertEquals("ZK-2809260945-K7P2M", ReceiptNo.compose("ZK", "2809260945", "K7P2M"));
        assertTrue(ReceiptNo.isReceiptNo(no));
    }

    @Test public void stampIsDateThenHourMinuteOfTheLocalTimestamp() {
        assertEquals("2809260945", ReceiptNo.stamp("2026-09-28 09:45:12"));
        assertEquals("2809260945", ReceiptNo.stamp("2026-09-28T09:45:12"));
        assertEquals("2809261745", ReceiptNo.stamp("2026-09-28 17:45:59.123456"));   // jam 24, detik dibuang
        assertEquals("0501260007", ReceiptNo.stamp("2026-01-05 00:07:00"));          // nol di depan
        assertEquals("2809260000", ReceiptNo.stamp("2026-09-28"));                   // hanya tanggal → 0000 (seperti web)
        assertEquals(null, ReceiptNo.stamp(null));
        assertEquals(null, ReceiptNo.stamp(""));
        assertEquals(null, ReceiptNo.stamp("kemarin"));
    }

    @Test public void acceptsOldAndNewFormats() {
        String[] ok = {
                "RF-190926-2", "ZK-140926-15", "ZK-140926-123456",   // lama: suffix angka urut
                "ZK-2809260945-K7P2M", "ZK-2809260945-7K2M1",        // baru: stempel + 5 karakter acak
                "ZK-2809260945-48213", "A-0101260000-ABCDE",
                "WEB-190926-1"
        };
        for (String s : ok) assertTrue(s, ReceiptNo.isReceiptNo(s));
    }

    @Test public void rejectsEverythingElse() {
        String[] bad = {
                "ZK-2809260945-K7P2MX",   // 6 karakter
                "ZK-2809260945-K7P2",     // 4 karakter, bukan angka
                "ZK-2809260945",          // tanpa suffix
                "RF-190926",              // tanpa suffix
                "ZK-2809260945-k7p2m",    // huruf kecil
                "ZK-280926-K7P2M",        // tanggal saja + suffix acak: bentuk yang TIDAK pernah diterbitkan
                "",
                null,
                "PUSAT-1",
                "ZK-28092609-K7P2M",      // stempel hanya 8 digit
                "ZK-2809260945-K7P2M\n",  // sisa baris baru
                " ZK-2809260945-K7P2M"
        };
        for (String s : bad) assertFalse(String.valueOf(s), ReceiptNo.isReceiptNo(s));
    }
}
