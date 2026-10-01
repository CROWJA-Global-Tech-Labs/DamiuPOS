package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Aturan "nama lokasi unik per pelanggan" (cermin server) — murni, tanpa Android. */
public class LocationNamesTest {

    private static List<String> l(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    // ---------------------------------------------------------------- key / same

    @Test public void keyTrimsCollapsesSpacesAndIgnoresCase() {
        assertEquals("kediaman", LocationNames.key("  Kediaman "));
        assertEquals("kediaman 2", LocationNames.key("KEDIAMAN \t  2"));
        assertEquals("rumah bu aci", LocationNames.key("Rumah  Bu　Aci"));
        assertEquals("", LocationNames.key(null));
        assertEquals("", LocationNames.key("   "));
    }

    @Test public void keyUsesRootLocaleSoTurkishIStaysAscii() {
        // Locale.ROOT, bukan locale perangkat: "I" → "i" (bukan "ı" dotless di locale tr).
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("indah", LocationNames.key("INDAH"));
            assertTrue(LocationNames.same("INDAH", "indah"));
        } finally {
            Locale.setDefault(prev);
        }
    }

    @Test public void cleanKeepsCaseButTidiesSpaces() {
        assertEquals("Warung Bu Aci", LocationNames.clean("  Warung   Bu Aci "));
        assertEquals("", LocationNames.clean(null));
    }

    @Test public void sameIsCaseAndSpaceInsensitiveButBlankNeverMatches() {
        assertTrue(LocationNames.same("Kediaman", " kediaman "));
        assertTrue(LocationNames.same("Kedai  Es Teh", "kedai es teh"));
        assertFalse(LocationNames.same("Kediaman", "Kediaman 2"));
        assertFalse(LocationNames.same("", ""));
        assertFalse(LocationNames.same(null, " "));
    }

    // ---------------------------------------------------------------- nextFree / nextDefault

    @Test public void nextDefaultIsKediamanThenNumbered() {
        assertEquals("Kediaman", LocationNames.nextDefault(Collections.<String>emptyList()));
        assertEquals("Kediaman", LocationNames.nextDefault(null));
        assertEquals("Kediaman 2", LocationNames.nextDefault(l("Kediaman")));
        assertEquals("Kediaman 3", LocationNames.nextDefault(l("kediaman", "KEDIAMAN 2")));
        // Celah dipakai lebih dulu: "Kediaman 2" bebas walau "Kediaman 3" terpakai.
        assertEquals("Kediaman 2", LocationNames.nextDefault(l("Kediaman", "Kediaman 3")));
        assertEquals("Kediaman", LocationNames.nextDefault(l("Warung", "Kediaman 2")));
    }

    @Test public void nextFreeUsesGivenBaseAndBlankBaseFallsBackToDefault() {
        assertEquals("Warung", LocationNames.nextFree("Warung", l("Kediaman")));
        // Huruf yang diketik dipertahankan; hanya pembandingnya yang mengabaikan huruf besar/kecil.
        assertEquals("warung 2", LocationNames.nextFree(" warung ", l("Warung")));
        assertEquals("Kediaman 2", LocationNames.nextFree("  ", l("Kediaman")));
    }

    @Test public void nextFreeMatchesServerForNew() {
        // Cermin server test_for_new_and_next_free (forNew = nextFree atas nama yang sudah dipakai).
        List<String> existing = l("Kediaman", "Kediaman 2");
        assertEquals("Kediaman 3", LocationNames.nextFree("", existing));
        assertEquals("kediaman 3", LocationNames.nextFree(" kediaman ", existing));
        assertEquals("Kantor", LocationNames.nextFree("Kantor", existing));
        assertEquals("Kediaman", LocationNames.nextFree(null, Collections.<String>emptyList()));
    }

    // ---------------------------------------------------------------- suggest

    @Test public void suggestAppendsNumberToTheWholeNameLikeTheServer() {
        assertEquals("Kediaman 2", LocationNames.suggest("Kediaman", l("Kediaman")));
        assertEquals("Warung 3", LocationNames.suggest("Warung", l("Warung", "warung 2")));
        assertEquals("Toko 2", LocationNames.suggest("  Toko  ", l("toko")));
        // Nama berakhiran angka: angka ditambahkan ke nama UTUH (server nextFree), bukan dilanjutkan
        // — saran di form = nama yang akan dipilih backfill/gerbang sinkron untuk kembaran itu.
        assertEquals("Kediaman 2 2", LocationNames.suggest("Kediaman 2", l("Kediaman", "Kediaman 2")));
        assertEquals("Rumah 1 2", LocationNames.suggest("Rumah 1", l("Rumah 1")));
        assertEquals("Kediaman", LocationNames.suggest("  ", l("Warung")));
    }

    // ---------------------------------------------------------------- duplicateIndexes

    @Test public void duplicateIndexesFlagsLaterOccurrencesOnly() {
        // Kasus insiden: dua lokasi "Kediaman" → yang KEDUA ditandai.
        assertEquals(Collections.singletonList(1),
                LocationNames.duplicateIndexes(l("Kediaman", "Kediaman")));
        assertEquals(Arrays.asList(2, 3),
                LocationNames.duplicateIndexes(l("Kediaman", "Warung", " kediaman", "WARUNG ")));
    }

    @Test public void duplicateIndexesIgnoresBlanksAndDistinctNames() {
        assertTrue(LocationNames.duplicateIndexes(l("", "  ", null, "Kediaman")).isEmpty());
        assertTrue(LocationNames.duplicateIndexes(l("Kediaman", "Kediaman 2", "Warung")).isEmpty());
        assertTrue(LocationNames.duplicateIndexes(null).isEmpty());
    }

    // ---------------------------------------------------------------- unique (port server)
    // Kasus-kasus di bawah = CustomerLocationUniqueNamesTest server (bagian helper) — HP & server
    // wajib membaca daftar yang sama jadi nama yang sama. Kasus "koordinat tak berguna" server tak
    // ada di sini: pemanggil (CustomerDao/form) sudah membuang baris (0,0) sebelum memanggil unique().

    @Test public void uniqueComparesCaseAndWhitespaceInsensitively() {
        assertEquals(l("Kediaman", "kediaman 2", "KEDIAMAN 3", "Rumah  Ibu", "rumah ibu 2"),
                LocationNames.unique(l("Kediaman", "  kediaman ", "KEDIAMAN", "Rumah  Ibu", "rumah ibu")));
    }

    @Test public void uniqueSuffixNeverCollidesWithAnExistingNameAnywhereInTheList() {
        // "Kediaman 2" sudah ada di baris akhir — kembaran di atasnya tak boleh memakainya.
        assertEquals(l("Kediaman", "Kediaman 3", "Kediaman 2"),
                LocationNames.unique(l("Kediaman", "Kediaman", "Kediaman 2")));
    }

    @Test public void uniqueGivesBlanksTheNextFreeDefaultAndNamedEntriesKeepTheirs() {
        assertEquals(l("Kediaman"), LocationNames.unique(l("")));
        // Nama TERISI menang atas nama kosong ("terserah") — "Kediaman" yang diketik tetap.
        assertEquals(l("Kediaman 2", "Kediaman", "Kediaman 3"), LocationNames.unique(l("", "Kediaman", "   ")));
        // Dulu dua baris kosong → dua "Kediaman" (akar insiden).
        assertEquals(l("Kediaman", "Kediaman 2"), LocationNames.unique(l("", " ")));
        assertEquals(l("Warung", "Kediaman", "Toko", "Kediaman 2"),
                LocationNames.unique(l("Warung", "", " Toko ", null)));
        assertEquals(l("Kediaman 3", "kediaman", "Kediaman 2"),
                LocationNames.unique(l(null, "kediaman", "Kediaman 2")));
    }

    @Test public void uniqueReadsTheIncidentRowAsKediamanAndKediaman2() {
        // Baris lama "Warung Bu Aci": dua "Kediaman" → terbaca "Kediaman" / "Kediaman 2" (sama dengan
        // Customer::locationsOrDefault di web), jadi form HP tak menandai apa pun saat dibuka.
        List<String> out = LocationNames.unique(l(" Kediaman ", "Kediaman"));
        assertEquals(l("Kediaman", "Kediaman 2"), out);
        assertTrue(LocationNames.duplicateIndexes(out).isEmpty());
        assertEquals(l("Kediaman", "Kediaman 2", "Kediaman 3", "Kediaman 4"),
                LocationNames.unique(l("Kediaman", "Kediaman", "", "Kediaman")));
    }

    @Test public void uniqueAppendsNumberToTheWholeNameForNumberedDuplicates() {
        // Sama dengan server nextFree(clean(nama)): akhiran angka ditambahkan ke nama UTUH.
        assertEquals(l("Kediaman 2", "Kediaman 2 2"), LocationNames.unique(l("Kediaman 2", "Kediaman 2")));
        // Kembaran memakai ejaan barisnya sendiri (server: nextFree(clean($l['name']))).
        assertEquals(l("Rumah 1", "rumah 1 2"), LocationNames.unique(l("Rumah 1", "rumah 1")));
    }

    @Test public void uniqueKeepsOrderAndLengthAndIsIdempotent() {
        List<String> in = l("Kediaman", "Kediaman", "", "Warung", "warung", null);
        List<String> once = LocationNames.unique(in);
        assertEquals(in.size(), once.size());
        assertEquals(l("Kediaman", "Kediaman 2", "Kediaman 3", "Warung", "warung 2", "Kediaman 4"), once);
        assertEquals(once, LocationNames.unique(once));
        assertTrue(LocationNames.duplicateIndexes(once).isEmpty());
        assertTrue(LocationNames.unique(null).isEmpty());
    }

    @Test public void suggestForEachDuplicateEqualsWhatUniqueWouldPick() {
        // Saran di error inline form = nama yang akan dipakai unique() (dan server) untuk baris itu.
        List<String> names = l("Kediaman 2", "Kediaman 2", "Rumah");
        assertEquals(LocationNames.unique(names).get(1), LocationNames.suggest(names.get(1), names));
    }
}
