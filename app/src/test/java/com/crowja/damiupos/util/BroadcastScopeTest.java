package com.crowja.damiupos.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.BroadcastScope.Item;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dialog "📢 Broadcast WA Masalah" lintas tab: gabung/dedup sumber, sumber bawaan, filter, batas 300. */
public class BroadcastScopeTest {

    private static Item item(String uuid, int source, String cust, String... slugType) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i + 1 < slugType.length; i += 2) rows.add(new String[]{slugType[i], slugType[i + 1]});
        return new Item(uuid, source, cust, "Pelanggan " + uuid, "", rows, null, null);
    }

    private static Set<Integer> src(int... s) {
        Set<Integer> out = new HashSet<>();
        for (int x : s) out.add(x);
        return out;
    }

    private static Set<String> sel(String... k) {
        return new LinkedHashSet<>(Arrays.asList(k));
    }

    private static List<String> uuidsOf(List<Item> items) {
        return BroadcastScope.uuids(items);
    }

    @Test
    public void defaultSourceFollowsActiveTab() {
        assertEquals(BroadcastScope.MINE, BroadcastScope.defaultSource(0));
        assertEquals(BroadcastScope.OTHER, BroadcastScope.defaultSource(1));
        assertEquals(BroadcastScope.OPEN, BroadcastScope.defaultSource(2));
        // Tab tak dikenal (mis. -1) → perilaku lama: Antrian Saya.
        assertEquals(BroadcastScope.MINE, BroadcastScope.defaultSource(-1));
        assertEquals(src(BroadcastScope.OTHER), BroadcastScope.defaultSources(1));
    }

    @Test
    public void mergeKeepsOrderDropsBlankAndDedupesByUuidWithMineFirst() {
        List<Item> mine = Arrays.asList(item("a", 0, "c1"), item("", 0, "c9"), item("b", 0, "c2"));
        List<Item> other = Arrays.asList(item("b", 1, "c2"), item("c", 1, "c3"), item("c", 1, "c3"));
        List<Item> open = Arrays.asList(item("c", 2, "c3"), item("d", 2, "c4"), null);
        List<Item> m = BroadcastScope.merge(mine, other, open);
        assertEquals(Arrays.asList("a", "b", "c", "d"), uuidsOf(m));
        assertEquals(BroadcastScope.MINE, m.get(1).source);   // b: Antrian Saya menang atas Perangkat Lain
        assertEquals(BroadcastScope.OTHER, m.get(2).source);  // c: Perangkat Lain menang atas Terbuka
        assertArrayEquals(new int[]{2, 1, 1}, BroadcastScope.countBySource(m));
    }

    @Test
    public void mergeDropsOrdersWithoutCustomerFromEverySource() {
        // Order "Umum"/tanpa pelanggan dilewati server (whereNotNull customer_uuid) → jangan tampil,
        // tercentang, atau terhitung "N pelanggan" — sama untuk Antrian Saya, Perangkat Lain, Terbuka.
        List<Item> mine = Arrays.asList(item("a", 0, "c1"), item("u1", 0, null), item("u2", 0, "  "));
        List<Item> other = Arrays.asList(item("b", 1, "c2"), item("u3", 1, ""));
        List<Item> open = Arrays.asList(item("u4", 2, null), item("c", 2, "c3"));
        List<Item> m = BroadcastScope.merge(mine, other, open);
        assertEquals(Arrays.asList("a", "b", "c"), uuidsOf(m));
        assertEquals(3, BroadcastScope.customerCount(m));
        assertFalse(item("x", 0, null).hasCustomer());
        assertTrue(item("x", 0, "c9").hasCustomer());
        // Mode pilih: order terpilih tanpa pelanggan → tak ada di daftar, jadi terlapor "dilewati".
        List<String> picked = Arrays.asList("a", "u1", "u4");
        assertEquals(Arrays.asList("a"), uuidsOf(BroadcastScope.restrict(m, picked)));
    }

    @Test
    public void visibleSelectedFollowsTabFilterAndDropsStaleSelections() {
        // 12 dipilih ("Semua"), lalu dicari → hanya b & d tampil: broadcast = b, d (bukan 12).
        List<String> visible = Arrays.asList("b", "d", "e");
        Set<String> selected = new LinkedHashSet<>(Arrays.asList("a", "d", "b", "gone", "c"));
        assertEquals(Arrays.asList("b", "d"), BroadcastScope.visibleSelected(visible, selected));
        assertEquals(2, BroadcastScope.visibleSelected(visible, selected).size());
        // Urutan = urutan daftar tab; blank/null/dobel diabaikan.
        assertEquals(Arrays.asList("d", "b"),
                BroadcastScope.visibleSelected(Arrays.asList("d", null, "", "b", "d"), selected));
        assertTrue(BroadcastScope.visibleSelected(visible, Collections.<String>emptySet()).isEmpty());
        assertTrue(BroadcastScope.visibleSelected(null, selected).isEmpty());
        assertTrue(BroadcastScope.visibleSelected(visible, null).isEmpty());
    }

    @Test
    public void markNewKeepsLateRowsUncheckedAfterClearAll() {
        Set<String> known = new HashSet<>();
        Set<String> unchecked = new HashSet<>();
        List<Item> first = Arrays.asList(item("a", 0, "c1"), item("b", 0, "c2"));
        assertEquals(2, BroadcastScope.markNew(first, known, unchecked, true));
        assertTrue(unchecked.isEmpty());
        // "☐ Kosongkan" lalu centang a saja; antrean Perangkat Lain tiba belakangan.
        unchecked.add("b");
        List<Item> reloaded = Arrays.asList(item("a", 0, "c1"), item("b", 0, "c2"),
                item("x", 1, "c3"), item("y", 1, "c4"));
        assertEquals(2, BroadcastScope.markNew(reloaded, known, unchecked, false));
        assertEquals(new HashSet<>(Arrays.asList("b", "x", "y")), unchecked);
        assertEquals(Arrays.asList("a"), uuidsOf(BroadcastScope.picked(reloaded, src(0, 1, 2), sel(), null, unchecked)));
        // Sudah dikenal → tak diubah lagi; tanpa "Kosongkan" baris baru tercentang seperti biasa.
        assertEquals(0, BroadcastScope.markNew(reloaded, known, unchecked, false));
        Set<String> none = new HashSet<>();
        BroadcastScope.markNew(Collections.singletonList(item("z", 2, "c5")), known, none, true);
        assertTrue(none.isEmpty());
    }

    @Test
    public void otherQueueLoadsOnlyWhenThatSourceIsTickedAndNotLoaded() {
        // Dibuka dari Antrian Saya: Perangkat Lain tak dicentang → tak ada panggilan jaringan.
        assertFalse(BroadcastScope.needsOtherLoad(false, false, false, src(BroadcastScope.MINE)));
        assertTrue(BroadcastScope.needsOtherLoad(false, false, false, src(BroadcastScope.MINE, BroadcastScope.OTHER)));
        // Sudah dimuat (walau kosong), sedang memuat, atau mode pilih → tidak.
        assertFalse(BroadcastScope.needsOtherLoad(false, true, false, src(BroadcastScope.OTHER)));
        assertFalse(BroadcastScope.needsOtherLoad(false, false, true, src(BroadcastScope.OTHER)));
        assertFalse(BroadcastScope.needsOtherLoad(true, false, false, src(BroadcastScope.OTHER)));
        assertFalse(BroadcastScope.needsOtherLoad(false, false, false, null));
    }

    @Test
    public void sourceCountTextShowsSpinnerBlankOrNumber() {
        assertEquals("⏳", BroadcastScope.sourceCountText(5, true, true));
        assertEquals("", BroadcastScope.sourceCountText(0, false, false));
        assertEquals("0", BroadcastScope.sourceCountText(0, false, true));
        assertEquals("12", BroadcastScope.sourceCountText(12, false, true));
    }

    @Test
    public void itemCarriesPrecomputedChipsUntouched() {
        Object chips = new Object();
        Item it = new Item("a", 0, "c1", "x", "", null, null, null, chips);
        assertTrue(it.chips == chips);
        assertNull(item("b", 0, "c2").chips);
    }

    @Test
    public void mergeToleratesNullSources() {
        assertTrue(BroadcastScope.merge(null, null, null).isEmpty());
        assertEquals(1, BroadcastScope.merge(null, Collections.singletonList(item("x", 1, "c")), null).size());
    }

    @Test
    public void restrictKeepsOnlySelectedUuidsInListOrder() {
        List<Item> all = Arrays.asList(item("a", 1, "c1"), item("b", 1, "c2"), item("c", 2, "c3"));
        assertEquals(Arrays.asList("a", "c"), uuidsOf(BroadcastScope.restrict(all, Arrays.asList("c", "a", "zz"))));
        assertTrue(BroadcastScope.restrict(all, Collections.<String>emptyList()).isEmpty());
        assertTrue(BroadcastScope.restrict(all, null).isEmpty());
    }

    @Test
    public void sourceFilterHidesUncheckedSources() {
        Item mine = item("a", 0, "c1", "MIN", "");
        Item other = item("b", 1, "c2", "MIN", "");
        Set<Integer> s = src(BroadcastScope.MINE);
        assertTrue(BroadcastScope.visible(mine, s, sel(), null));
        assertFalse(BroadcastScope.visible(other, s, sel(), null));
        assertFalse(BroadcastScope.visible(mine, src(), sel(), null));
        assertFalse(BroadcastScope.visible(null, s, sel(), null));
    }

    @Test
    public void productFilterWithoutTypeMatchesAnySlugCaseInsensitive() {
        Item it = item("a", 0, "c1", "min", "KERAN", "Aqua 19L", "");
        assertTrue(BroadcastScope.productMatches(it, sel(), null));
        assertTrue(BroadcastScope.productMatches(it, sel("MIN"), null));
        assertTrue(BroadcastScope.productMatches(it, sel("AQUA 19L"), new HashMap<String, Set<String>>()));
        assertFalse(BroadcastScope.productMatches(it, sel("ISI"), null));
    }

    @Test
    public void productFilterUsesServerItemSlugsToo() {
        Item it = new Item("a", 1, "c1", "x", "", Collections.<String[]>emptyList(), Arrays.asList(" isi "), null);
        assertTrue(BroadcastScope.productMatches(it, sel("ISI"), null));
    }

    @Test
    public void typeFilterMatchesPerProductAndUnknownKey() {
        Item keran = item("a", 0, "c1", "MIN", "KERAN");
        Item unknown = item("b", 0, "c2", "MIN", "");
        Item other = item("c", 0, "c3", "ISI", "NON_KERAN");
        Set<String> s = sel("MIN", "ISI");
        Map<String, Set<String>> types = new HashMap<>();
        types.put("MIN", new HashSet<>(Collections.singletonList("KERAN")));
        assertTrue(BroadcastScope.productMatches(keran, s, types));
        assertFalse(BroadcastScope.productMatches(unknown, s, types));
        // ISI terpilih tanpa pilihan jenis = semua jenis.
        assertTrue(BroadcastScope.productMatches(other, s, types));
        types.get("MIN").add(BroadcastScope.TYPE_UNKNOWN);
        assertTrue(BroadcastScope.productMatches(unknown, s, types));
    }

    @Test
    public void pickedRespectsSourcesFiltersAndUnchecked() {
        List<Item> items = Arrays.asList(item("a", 0, "c1", "MIN", ""), item("b", 1, "c2", "ISI", ""),
                item("c", 1, "c3", "MIN", ""), item("d", 2, "c1", "MIN", ""));
        Set<String> unchecked = new HashSet<>(Collections.singletonList("c"));
        assertEquals(Arrays.asList("a"), uuidsOf(BroadcastScope.picked(items, src(0), sel(), null, unchecked)));
        assertEquals(Arrays.asList("a", "b", "d"), uuidsOf(BroadcastScope.picked(items, src(0, 1, 2), sel(), null, unchecked)));
        assertEquals(Arrays.asList("a", "d"), uuidsOf(BroadcastScope.picked(items, src(0, 1, 2), sel("MIN"), null, unchecked)));
        assertEquals(Arrays.asList("a", "b", "c", "d"), uuidsOf(BroadcastScope.picked(items, src(0, 1, 2), sel(), null, null)));
    }

    @Test
    public void customerCountDedupesByCustomerAndCountsAnonymousOrdersSeparately() {
        List<Item> items = Arrays.asList(item("a", 0, "c1"), item("b", 1, "c1"), item("c", 2, ""), item("d", 2, null));
        assertEquals(3, BroadcastScope.customerCount(items));
        assertEquals("📢 4 order dicentang · 3 pelanggan", BroadcastScope.summary(4, 3));
    }

    @Test
    public void capIsServerLimitOf300() {
        assertEquals(300, BroadcastScope.MAX_UUIDS);
        assertFalse(BroadcastScope.overCap(300));
        assertNull(BroadcastScope.capWarning(300));
        assertTrue(BroadcastScope.overCap(301));
        String w = BroadcastScope.capWarning(301);
        assertTrue(w.contains("300"));
        assertTrue(w.contains("301"));
    }

    @Test
    public void sourceBreakdownListsNonEmptySourcesOnly() {
        List<Item> items = Arrays.asList(item("a", 0, "c1"), item("b", 2, "c2"), item("c", 2, "c3"));
        assertEquals("Antrian Saya 1 · Pesanan Terbuka 2", BroadcastScope.sourceBreakdown(items));
        assertEquals("", BroadcastScope.sourceBreakdown(Collections.<Item>emptyList()));
    }
}
