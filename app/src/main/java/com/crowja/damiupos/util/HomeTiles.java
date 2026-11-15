package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Daftar ubin grid Beranda (Tema v2) yang bisa diatur user: parse/serialize/sanitize + pemilihan ubin
 * yang tampil. Disimpan per user per perangkat (SettingsDao key {@link #key(long)}, id dipisah koma).
 * Kelas Java murni supaya bisa dites di JVM.
 */
public final class HomeTiles {

    /** Maks ubin custom (3 baris x 4, termasuk "Lainnya"). */
    public static final int MAX = 11;
    /** Min ubin custom yang boleh disimpan. */
    public static final int MIN = 3;
    /** Jumlah ubin bawaan (2 baris x 4, termasuk "Lainnya"). */
    public static final int DEFAULT_COUNT = 7;

    /** Urutan bawaan: 7 pertama yang tampil dipakai; sisanya cadangan bila ada yang tersembunyi. */
    public static final List<String> DEFAULT_ORDER = Collections.unmodifiableList(java.util.Arrays.asList(
            "antrean", "pelanggan", "komplain", "followup", "tren", "chatwa",
            "laporan", "stok", "transaksi", "jual", "kembali", "pengeluaran", "setoran"));

    /**
     * Ubin yang masuk bawaan SETELAH user bisa menyimpan daftar sendiri: {id, sisipkan-setelah-id}.
     * Disisipkan sekali ke daftar tersimpan (penanda {@link #migratedKey}); bila user lalu membuangnya,
     * tidak muncul lagi.
     */
    public static final String[][] ADDED_LATER = {{"tren", "followup"}};

    public interface Visible {
        boolean ok(String id);
    }

    private HomeTiles() {
    }

    public static String key(long uid) {
        return "home_tiles_" + uid;
    }

    public static String migratedKey(long uid, String id) {
        return "home_tiles_added_" + id + "_" + uid;
    }

    /**
     * Daftar tersimpan + {@code id} disisipkan setelah {@code afterId} (atau di akhir). Sudah ada → tak
     * berubah. Penuh ({@link #MAX}) → ubin terakhir tergeser supaya ubin baru tetap terlihat.
     */
    public static List<String> withAdded(List<String> saved, String id, String afterId) {
        List<String> out = new ArrayList<>(saved != null ? saved : Collections.<String>emptyList());
        if (out.contains(id)) return out;
        int at = afterId != null ? out.indexOf(afterId) : -1;
        out.add(at >= 0 ? at + 1 : out.size(), id);
        while (out.size() > MAX) out.remove(out.size() - 1 == out.indexOf(id) ? out.size() - 2 : out.size() - 1);
        return out;
    }

    /** null/kosong → null (= pakai bawaan). Id dirapikan, duplikat & id tak valid dibuang. */
    public static List<String> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : raw.split(",")) {
            String id = p.trim();
            if (!id.isEmpty() && id.matches("[a-z0-9_]+")) out.add(id);
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    public static String serialize(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        return sb.toString();
    }

    /** Hanya id yang dikenal, tanpa duplikat, maksimal {@link #MAX}. */
    public static List<String> sanitize(List<String> ids, Collection<String> known) {
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        for (String id : ids) {
            if (id == null || out.contains(id)) continue;
            if (known != null && !known.contains(id)) continue;
            out.add(id);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** Daftar bawaan untuk user ini: {@link #DEFAULT_COUNT} id pertama yang tampil. */
    public static List<String> defaults(Visible vis) {
        List<String> out = new ArrayList<>();
        for (String id : DEFAULT_ORDER) {
            if (out.size() >= DEFAULT_COUNT) break;
            if (vis == null || vis.ok(id)) out.add(id);
        }
        return out;
    }

    /**
     * Ubin yang dirender: simpanan user (difilter yang masih tampil, maks {@link #MAX}); bila tak ada
     * simpanan atau hasil saringnya kosong → bawaan.
     */
    public static List<String> pick(List<String> saved, Visible vis) {
        if (saved != null) {
            List<String> out = new ArrayList<>();
            for (String id : saved) {
                if (out.size() >= MAX) break;
                if (id != null && !out.contains(id) && (vis == null || vis.ok(id))) out.add(id);
            }
            if (!out.isEmpty()) return out;
        }
        return defaults(vis);
    }
}
