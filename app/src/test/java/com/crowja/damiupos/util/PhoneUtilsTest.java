package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.model.Customer;

import org.junit.Test;

import java.util.Arrays;

/**
 * Normaliser nomor HP: SATU kanon untuk HP (PhoneUtils / CustomerDao.canonicalPhone) dan server
 * (App\Support\Phone::canonical). Matriks varian = persis yang dipakai bukti bug (nomor
 * 082142319379), termasuk dua yang dulu lolos: awalan "00" dan "62 0…".
 */
public class PhoneUtilsTest {

    /** Varian penulisan SATU nomor yang sama (bagian nasional 82142319379). */
    private static final String[] VARIANTS = {
            "082142319379",
            "+6282142319379",
            "6282142319379",
            "82142319379",
            "0821-4231-9379",
            "0821 4231 9379",
            "0821.4231.9379",
            "62 0821 4231 9379",
            "+62 0821-4231-9379",
            "(0821) 4231 9379",
            "+62 (821) 4231-9379",
            "0062 821 4231 9379",
            "006282142319379",
            "0082142319379",
            "  082142319379  ",
    };

    @Test public void everyVariantCanonicalisesToTheSameNationalNumber() {
        for (String v : VARIANTS) {
            assertEquals("canonical(" + v + ")", "82142319379", PhoneUtils.canonical(v));
        }
    }

    @Test public void daoCanonicalKeyIsSixtyTwoPlusNationalForEveryVariant() {
        for (String v : VARIANTS) {
            assertEquals("canonicalPhone(" + v + ")", "6282142319379", CustomerDao.canonicalPhone(v));
        }
    }

    @Test public void toLocal08GivesTheSameStoredFormForEveryVariant() {
        for (String v : VARIANTS) {
            assertEquals("toLocal08(" + v + ")", "082142319379", PhoneUtils.toLocal08(v));
        }
    }

    @Test public void sameNumberIsSymmetricAcrossAllVariants() {
        for (String a : VARIANTS) {
            for (String b : VARIANTS) {
                assertTrue(a + " vs " + b, PhoneUtils.sameNumber(a, b));
            }
        }
    }

    @Test public void differentNumbersAndOneDigitTyposAreNotTheSame() {
        assertFalse(PhoneUtils.sameNumber("082142319379", "082141319379"));   // typo satu digit
        assertFalse(PhoneUtils.sameNumber("082142319379", "082142319378"));
    }

    @Test public void noDigitsOrBlankIsEmptyAndNeverEqual() {
        for (String s : new String[]{null, "", "   ", "---", "abc", "+", "()"}) {
            assertEquals("", PhoneUtils.canonical(s));
            assertEquals("", CustomerDao.canonicalPhone(s));
            assertEquals("", PhoneUtils.toLocal08(s));
            assertFalse(PhoneUtils.sameNumber(s, s));
        }
    }

    @Test public void internationalPrefixAloneOrWithCountryCodeOnlyIsEmpty() {
        assertEquals("", PhoneUtils.canonical("00"));
        assertEquals("", PhoneUtils.canonical("0062"));
        assertEquals("", PhoneUtils.canonical("+62"));
        assertEquals("", PhoneUtils.canonical("0"));
    }

    @Test public void idempotent() {
        for (String v : VARIANTS) {
            String once = PhoneUtils.toLocal08(v);
            assertEquals(once, PhoneUtils.toLocal08(once));
            assertEquals(PhoneUtils.canonical(v), PhoneUtils.canonical(once));
        }
    }

    @Test public void placeholdersAreNotMatchable() {
        assertFalse(PhoneUtils.isMatchable("0"));
        assertFalse(PhoneUtils.isMatchable("62"));
        assertFalse(PhoneUtils.isMatchable("12345"));
        assertFalse(PhoneUtils.isMatchable("0812345"));      // kanonik 6 digit
        assertFalse(PhoneUtils.isMatchable("0812345 6"));   // 7 digit kanonik: masih di bawah 8
        assertTrue(PhoneUtils.isMatchable("081234567"));    // 8 digit kanonik: batas bawah sah
    }

    /**
     * Prefilter baca-kolom-mentah: dulu LIKE '%2319379%' pada kolom mentah melewatkan nomor
     * bersepirator. Sekarang deret digit kolom yang dicari — termasuk kolom JSON `phones`.
     */
    @Test public void rawColumnPrefilterFindsStoredVariantsWithSeparators() {
        String national = PhoneUtils.canonical("082142319379");
        for (String stored : VARIANTS) {
            assertTrue("kolom phone mentah '" + stored + "'", PhoneUtils.rawColumnMayContain(stored, national));
            String json = "[\"085854014831\",\"" + stored + "\"]";
            assertTrue("kolom phones JSON memuat '" + stored + "'", PhoneUtils.rawColumnMayContain(json, national));
        }
        assertFalse(PhoneUtils.rawColumnMayContain("0812-9999-0000", national));
        assertFalse(PhoneUtils.rawColumnMayContain(null, national));
        assertFalse(PhoneUtils.rawColumnMayContain("082142319379", ""));
    }

    @Test public void matchesCanonicalSeesSecondaryAndScalarDriftNumbers() {
        String key = CustomerDao.canonicalPhone("0821-4231-9379");

        Customer secondary = new Customer("Wulan", "085854014831", "");
        secondary.setPhones(Arrays.asList("085854014831", "+62 821 4231 9379"));
        assertTrue(CustomerDao.matchesCanonical(secondary, key));

        // phones[] tanpa skalar, tapi skalar `phone` berbeda (drift sinkron): skalar tetap milik pelanggan.
        Customer drift = new Customer("Drift", "082142319379", "");
        drift.setPhones(Arrays.asList("081111111111"));
        assertTrue(CustomerDao.matchesCanonical(drift, key));

        Customer other = new Customer("Lain", "081234567890", "");
        other.setPhones(Arrays.asList("081234567890"));
        assertFalse(CustomerDao.matchesCanonical(other, key));
    }

    /** Varian batch: satu baris, banyak nomor → indeks yang lolos prefilter (dasar findByPhonesCanonical). */
    @Test public void prefilterHitsReturnsEveryMatchingNumberInOnePass() {
        java.util.List<String> tails = Arrays.asList(
                PhoneUtils.matchTail(PhoneUtils.canonical("0821-4231-9379")),    // 0: ada di kolom phone
                PhoneUtils.matchTail(PhoneUtils.canonical("0812-9999-0000")),    // 1: tak ada
                PhoneUtils.matchTail(PhoneUtils.canonical("+62 858 5401 4831")), // 2: ada di phones JSON
                "");                                                             // 3: kosong → tak pernah cocok
        java.util.List<Integer> hits = PhoneUtils.prefilterHits(
                "+62 821 4231 9379", "[\"085854014831\",\"081100001111\"]", tails);
        assertEquals(Arrays.asList(0, 2), hits);
    }

    @Test public void prefilterHitsAgreesWithSingleNumberPrefilterAndHandlesNulls() {
        String national = PhoneUtils.canonical("082142319379");
        java.util.List<String> tails = Arrays.asList(PhoneUtils.matchTail(national));
        for (String stored : VARIANTS) {
            assertEquals("phone '" + stored + "'", PhoneUtils.rawColumnMayContain(stored, national),
                    !PhoneUtils.prefilterHits(stored, null, tails).isEmpty());
            String json = "[\"085854014831\",\"" + stored + "\"]";
            assertEquals("phones '" + json + "'", PhoneUtils.rawColumnMayContain(json, national),
                    !PhoneUtils.prefilterHits(null, json, tails).isEmpty());
        }
        assertTrue(PhoneUtils.prefilterHits(null, null, tails).isEmpty());
        assertTrue(PhoneUtils.prefilterHits("082142319379", null, null).isEmpty());
        assertTrue(PhoneUtils.prefilterHits("082142319379", null, new java.util.ArrayList<String>()).isEmpty());
    }

    @Test public void matchTailKeepsLastSevenDigitsOrWholeShortNumber() {
        assertEquals("2319379", PhoneUtils.matchTail("82142319379"));
        assertEquals("12345", PhoneUtils.matchTail("12345"));
        assertEquals("", PhoneUtils.matchTail(""));
        assertEquals("", PhoneUtils.matchTail(null));
    }

    /** #15: pemisah dibuang sebelum batas 13 karakter kolom form — "0856-6666-1234" tak lagi terpotong. */
    @Test public void gateNormalisationKeepsSeparatorFormattedNumberWithinThirteenChars() {
        String raw = "0856-6666-1234";
        assertEquals(14, raw.length());
        assertEquals("085666661234", PhoneUtils.toLocal08(raw));
        assertTrue(PhoneUtils.toLocal08(raw).length() <= 13);
        assertEquals("08123456789012", PhoneUtils.toLocal08("+62 812-3456-7890-12"));   // >13 → gate menolak
    }
}
