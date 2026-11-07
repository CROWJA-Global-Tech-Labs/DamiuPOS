package com.crowja.damiupos.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sisa target Ambil Antrean sesudah order diambil: jumlah bebas (total) atau komposisi (per produk). */
public final class TakeRemainder {
    private TakeRemainder() { }

    /** Sisa total galon; 0 bila tanpa target atau sudah terpenuhi. */
    public static int total(int target, int taken) {
        return target > 0 ? Math.max(0, target - taken) : 0;
    }

    /** Sisa per produk, urutan target dijaga; produk bertarget 0 (tidak diambil) tetap 0. */
    public static Map<String, Integer> perSlug(Map<String, Integer> targets, Map<String, Integer> taken) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            Integer got = taken.get(e.getKey());
            out.put(e.getKey(), Math.max(0, e.getValue() - (got != null ? got : 0)));
        }
        return out;
    }

    public static int sum(Map<String, Integer> values) {
        int n = 0;
        for (int v : values.values()) n += v;
        return n;
    }
}
