package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class HomeTilesTest {

    @Test
    public void parseTrimsDedupesAndDropsJunk() {
        assertNull(HomeTiles.parse(null));
        assertNull(HomeTiles.parse("  "));
        assertEquals(Arrays.asList("antrean", "pelanggan"), HomeTiles.parse(" antrean, ,pelanggan,antrean,Bad Id"));
    }

    @Test
    public void serializeRoundTrip() {
        List<String> ids = Arrays.asList("komplain", "jual", "stok");
        assertEquals("komplain,jual,stok", HomeTiles.serialize(ids));
        assertEquals(ids, HomeTiles.parse(HomeTiles.serialize(ids)));
        assertEquals("", HomeTiles.serialize(null));
    }

    @Test
    public void sanitizeKeepsKnownAndCaps() {
        List<String> known = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m");
        assertEquals(Arrays.asList("a", "b"), HomeTiles.sanitize(Arrays.asList("a", "zzz", "b", "a"), known));
        assertEquals(HomeTiles.MAX, HomeTiles.sanitize(known, known).size());
    }

    @Test
    public void defaultsSkipHidden() {
        List<String> d = HomeTiles.defaults(id -> !id.equals("chatwa"));
        assertEquals(Arrays.asList("antrean", "pelanggan", "komplain", "followup", "tren", "stok", "transaksi"), d);
        assertEquals(HomeTiles.DEFAULT_COUNT, HomeTiles.defaults(null).size());
    }

    @Test
    public void pickFiltersSavedAndFallsBack() {
        assertEquals(Arrays.asList("jual", "stok"),
                HomeTiles.pick(Arrays.asList("jual", "laporan", "stok", "unknown"),
                        id -> !id.equals("laporan") && !id.equals("unknown")));
        assertEquals(HomeTiles.defaults(null), HomeTiles.pick(null, null));
        assertEquals(HomeTiles.defaults(id -> !id.equals("x")),
                HomeTiles.pick(Arrays.asList("x"), id -> !id.equals("x")));
    }

    @Test
    public void trenInDefaultsOnlyWhenVisible() {
        List<String> staf = HomeTiles.defaults(id -> !id.equals("tren") && !id.equals("laporan") && !id.equals("stok"));
        assertEquals(Arrays.asList("antrean", "pelanggan", "komplain", "followup", "chatwa", "transaksi", "jual"), staf);
        assertTrue(HomeTiles.defaults(null).contains("tren"));
    }

    @Test
    public void withAddedInsertsAfterAnchor() {
        assertEquals(Arrays.asList("jual", "followup", "tren", "stok"),
                HomeTiles.withAdded(Arrays.asList("jual", "followup", "stok"), "tren", "followup"));
        assertEquals(Arrays.asList("jual", "stok", "tren"),
                HomeTiles.withAdded(Arrays.asList("jual", "stok"), "tren", "followup"));
        List<String> has = Arrays.asList("tren", "jual");
        assertEquals(has, HomeTiles.withAdded(has, "tren", "followup"));
    }

    @Test
    public void withAddedKeepsNewTileWhenFull() {
        List<String> full = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k");
        List<String> out = HomeTiles.withAdded(full, "tren", null);
        assertEquals(HomeTiles.MAX, out.size());
        assertTrue(out.contains("tren"));
        assertEquals("j", out.get(HomeTiles.MAX - 2));
        out = HomeTiles.withAdded(full, "tren", "b");
        assertEquals(HomeTiles.MAX, out.size());
        assertEquals("tren", out.get(2));
        assertEquals("j", out.get(HomeTiles.MAX - 1));
    }

    @Test
    public void migratedKeyPerUser() {
        assertEquals("home_tiles_added_tren_7", HomeTiles.migratedKey(7, "tren"));
    }
}
