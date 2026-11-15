package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
        assertEquals(Arrays.asList("antrean", "pelanggan", "komplain", "followup", "laporan", "stok", "transaksi"), d);
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
}
