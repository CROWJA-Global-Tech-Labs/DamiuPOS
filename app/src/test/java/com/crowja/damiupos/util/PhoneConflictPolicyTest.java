package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.PhoneConflictPolicy.Conflict;
import com.crowja.damiupos.util.PhoneConflictPolicy.ServerResult;
import com.crowja.damiupos.util.PhoneConflictPolicy.Verdict;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Keputusan murni guard nomor ganda di form pelanggan (tanpa Android/jaringan). */
public class PhoneConflictPolicyTest {

    private static List<String> l(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    // ---------------------------------------------------------------- numbersToCheck

    @Test public void createChecksEveryNumberDedupedByCanonical() {
        List<String> out = PhoneConflictPolicy.numbersToCheck(null,
                l("082142319379", "+62 821-4231-9379", "085854014831"), true);
        assertEquals(l("082142319379", "085854014831"), out);
    }

    @Test public void editWithoutNumberChangeChecksNothingSoLegacyDuplicatesStayEditable() {
        List<String> before = l("082142319379", "085854014831");
        // alamat/foto diedit; nomor sama tapi ditulis ulang dalam format lain
        List<String> after = l("0821-4231-9379", "+6285854014831");
        assertTrue(PhoneConflictPolicy.numbersToCheck(before, after, false).isEmpty());
    }

    @Test public void editChecksOnlyAddedNumbers() {
        List<String> before = l("082142319379");
        List<String> after = l("082142319379", "085854014831");
        assertEquals(l("085854014831"), PhoneConflictPolicy.numbersToCheck(before, after, false));
    }

    @Test public void editReplacingThePrimaryChecksTheNewPrimary() {
        List<String> before = l("082142319379", "085854014831");
        List<String> after = l("081234567890", "085854014831");
        assertEquals(l("081234567890"), PhoneConflictPolicy.numbersToCheck(before, after, false));
    }

    @Test public void editReorderingAnExistingSecondaryToPrimaryIsAChange() {
        List<String> before = l("082142319379", "085854014831");
        List<String> after = l("085854014831", "082142319379");
        // tak ada nomor baru, tetapi nomor UTAMA berubah → yang jadi utama diperiksa
        assertEquals(l("085854014831"), PhoneConflictPolicy.numbersToCheck(before, after, false));
    }

    @Test public void editRemovingNumbersChecksNothing() {
        assertTrue(PhoneConflictPolicy.numbersToCheck(l("082142319379", "085854014831"),
                l("082142319379"), false).isEmpty());
    }

    @Test public void editFromNoNumberToANumberChecksIt() {
        assertEquals(l("082142319379"),
                PhoneConflictPolicy.numbersToCheck(Collections.<String>emptyList(), l("082142319379"), false));
        assertEquals(l("082142319379"),
                PhoneConflictPolicy.numbersToCheck(null, l("082142319379"), false));
    }

    @Test public void placeholdersAndBlanksNeverBlockOrAreChecked() {
        assertTrue(PhoneConflictPolicy.numbersToCheck(null, l("0", "62", "12345", "---", "", "0812345"), true).isEmpty());
        assertTrue(PhoneConflictPolicy.numbersToCheck(null, null, true).isEmpty());
    }

    @Test public void capsAtFifteenNumbers() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(String.format("0812345%05d", 10000 + i));
        assertEquals(PhoneConflictPolicy.MAX_PHONES, PhoneConflictPolicy.numbersToCheck(null, many, true).size());
    }

    // ---------------------------------------------------------------- request JSON

    @Test public void requestJsonCarriesPhonesAndOptionalExcludeUuid() {
        JsonObject o = JsonParser.parseString(PhoneConflictPolicy.buildRequestJson(
                l("0821-4231-9379", "+6285"), "abc-123")).getAsJsonObject();
        JsonArray arr = o.getAsJsonArray("phones");
        assertEquals(2, arr.size());
        assertEquals("0821-4231-9379", arr.get(0).getAsString());
        assertEquals("abc-123", o.get("exclude_uuid").getAsString());

        JsonObject create = JsonParser.parseString(PhoneConflictPolicy.buildRequestJson(l("0821"), null)).getAsJsonObject();
        assertFalse(create.has("exclude_uuid"));
        JsonObject blank = JsonParser.parseString(PhoneConflictPolicy.buildRequestJson(l("0821"), "  ")).getAsJsonObject();
        assertFalse(blank.has("exclude_uuid"));
    }

    // ---------------------------------------------------------------- parseResponse

    private static final String CONFLICT_BODY = "{\"ok\":true,\"conflicts\":[{\"phone\":\"0821-4231-9379\","
            + "\"canonical\":\"82142319379\",\"customer\":{\"uuid\":\"u-1\",\"name\":\"Es teh KUY\","
            + "\"phone\":\"082142319379\",\"role\":\"primary\"}}]}";

    @Test public void parsesAConflict() {
        ServerResult r = PhoneConflictPolicy.parseResponse(CONFLICT_BODY, null);
        assertEquals(Verdict.BLOCKED, r.verdict);
        Conflict c = r.first();
        assertNotNull(c);
        assertEquals("0821-4231-9379", c.phone);
        assertEquals("82142319379", c.canonical);
        assertEquals("Es teh KUY", c.holderName);
        assertEquals("u-1", c.holderUuid);
        assertEquals("082142319379", c.holderPhone);
        assertEquals("primary", c.holderRole);
        assertTrue(c.atServer);
    }

    @Test public void emptyConflictsMeansFree() {
        ServerResult r = PhoneConflictPolicy.parseResponse("{\"ok\":true,\"conflicts\":[]}", "x");
        assertEquals(Verdict.FREE, r.verdict);
        assertNull(r.first());
    }

    @Test public void aConflictWithTheCustomerBeingEditedIsIgnoredEvenIfServerReportsIt() {
        ServerResult r = PhoneConflictPolicy.parseResponse(CONFLICT_BODY, "U-1");   // case-insensitive
        assertEquals(Verdict.FREE, r.verdict);
    }

    @Test public void tolerantOfMissingFields() {
        ServerResult r = PhoneConflictPolicy.parseResponse(
                "{\"ok\":true,\"conflicts\":[{\"phone\":\"0821\",\"customer\":{\"name\":null}}]}", null);
        assertEquals(Verdict.BLOCKED, r.verdict);
        assertEquals("", r.first().holderName);
        assertEquals("", r.first().holderUuid);
        assertEquals("", r.first().holderRole);
    }

    @Test public void unusableBodiesAreUnavailableNeverFreeAndNeverBlocking() {
        String[] bad = {
                null, "", "   ", "not json", "[]", "\"x\"", "{}",
                "{\"ok\":false,\"message\":\"Too many\"}",
                "{\"ok\":true}",
                "{\"ok\":true,\"conflicts\":{}}",
                "{\"ok\":\"maybe\",\"conflicts\":[]}",
                "<html>502 Bad Gateway</html>",
        };
        for (String b : bad) {
            assertEquals("body=" + b, Verdict.UNAVAILABLE, PhoneConflictPolicy.parseResponse(b, null).verdict);
        }
        assertEquals(Verdict.UNAVAILABLE, PhoneConflictPolicy.unavailable().verdict);
    }

    // ---------------------------------------------------------------- teks dialog

    @Test public void localCreateMessageKeepsTheOriginalWording() {
        String m = PhoneConflictPolicy.blockedMessage(new Conflict("0821", "", "Wulan", "", "", "", false), false);
        assertTrue(m, m.startsWith("Nomor 0821 sudah terdaftar atas nama:\n\n• Wulan"));
        assertTrue(m, m.contains("Penambahan diblokir"));
        assertFalse(m, m.contains("server"));
    }

    @Test public void serverConflictNamesHolderRoleAndSaysItIsRegisteredAtTheServer() {
        ServerResult r = PhoneConflictPolicy.parseResponse(CONFLICT_BODY, null);
        String m = PhoneConflictPolicy.blockedMessage(r.first(), false);
        assertTrue(m, m.contains("Es teh KUY"));
        assertTrue(m, m.contains("(nomor utama)"));
        assertTrue(m, m.contains("Tercatat di server"));
    }

    @Test public void editMessageSaysChangeBlockedAndSecondaryRoleIsLabelled() {
        Conflict c = new Conflict("0858", "", "Budi", "u", "0858", "secondary", true);
        String m = PhoneConflictPolicy.blockedMessage(c, true);
        assertTrue(m, m.contains("(nomor tambahan)"));
        assertTrue(m, m.contains("Perubahan diblokir"));
        assertFalse(m, m.contains("Penambahan diblokir"));
    }

    @Test public void blankHolderNameFallsBack() {
        String m = PhoneConflictPolicy.blockedMessage(new Conflict("0821", "", "  ", "", "", "", false), false);
        assertTrue(m, m.contains("(tanpa nama)"));
    }
}
