package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logika MURNI (tanpa Android/org.json, bisa diuji JVM) dialog "📢 Broadcast WA Masalah" lintas tab
 * Antrian Delivery: order dari Antrian Saya, Perangkat Lain dan Pesanan Terbuka digabung jadi satu
 * daftar (dedup per uuid), sumber bawaan mengikuti tab yang sedang dibuka, saringan sumber + produk/jenis
 * galon (cermin {@code DeliveryQueueActivity.typedRowsMatch}), dan batas 300 uuid server
 * ({@code DeliveryBroadcastController}: {@code uuids max:300}).
 */
public final class BroadcastScope {

    private BroadcastScope() {}

    public static final int MINE = 0;
    public static final int OTHER = 1;
    public static final int OPEN = 2;
    public static final int SOURCE_COUNT = 3;

    /** Batas server per kirim. */
    public static final int MAX_UUIDS = 300;

    /** Kunci "Belum diketahui" (order tanpa jenis galon) di filter jenis — sama dengan kapsul filter tab. */
    public static final String TYPE_UNKNOWN = "?";

    /** Satu order calon penerima broadcast. */
    public static final class Item {
        public final String uuid;
        public final int source;
        /** Identitas pelanggan (customer_uuid); kosong = dihitung per order. Server mengirim 1 pesan per pelanggan. */
        public final String customerKey;
        /** Teks baris (nama + penanda ⚡ / ↩ ambil galon …). */
        public final String label;
        /** Badge sumber/perangkat ("📱 HP RAFI", "🎲 Terbuka"); kosong = tanpa badge. */
        public final String badge;
        /** Baris kapsul {slug, jenis galon ("" = tak diketahui)} untuk filter produk & jenis. */
        public final List<String[]> rows;
        /** Kunci produk (UPPERCASE) dari rows + item_slugs server. */
        public final Set<String> slugs;
        /** Objek asal (Transaction / JSONObject) untuk menggambar kapsul — tak dipakai logika ini. */
        public final Object ref;

        public Item(String uuid, int source, String customerKey, String label, String badge,
                    List<String[]> rows, Collection<String> extraSlugs, Object ref) {
            this.uuid = uuid == null ? "" : uuid.trim();
            this.source = source;
            this.customerKey = customerKey == null ? "" : customerKey.trim();
            this.label = label == null ? "" : label;
            this.badge = badge == null ? "" : badge;
            this.rows = rows == null ? Collections.<String[]>emptyList() : rows;
            Set<String> s = new LinkedHashSet<>();
            for (String[] r : this.rows) {
                if (r != null && r.length > 0 && r[0] != null && !r[0].trim().isEmpty()) s.add(key(r[0]));
            }
            if (extraSlugs != null) {
                for (String x : extraSlugs) if (x != null && !x.trim().isEmpty()) s.add(key(x));
            }
            this.slugs = s;
            this.ref = ref;
        }
    }

    static String key(String slug) {
        return slug.trim().toUpperCase(Locale.US);
    }

    /** Tab aktif (0 Antrian Saya, 1 Perangkat Lain, 2 Pesanan Terbuka) → sumber yang langsung dicentang. */
    public static int defaultSource(int activeTab) {
        return activeTab == 1 ? OTHER : activeTab == 2 ? OPEN : MINE;
    }

    public static Set<Integer> defaultSources(int activeTab) {
        Set<Integer> s = new HashSet<>();
        s.add(defaultSource(activeTab));
        return s;
    }

    /**
     * Gabung ketiga sumber; uuid kosong dibuang, uuid kembar dipakai SEKALI dengan urutan prioritas
     * Antrian Saya → Perangkat Lain → Pesanan Terbuka (data lokal milik HP ini paling dipercaya, lalu
     * pandangan server yang lebih segar daripada salinan Terbuka lokal).
     */
    public static List<Item> merge(List<Item> mine, List<Item> other, List<Item> open) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addAll(out, seen, mine);
        addAll(out, seen, other);
        addAll(out, seen, open);
        return out;
    }

    private static void addAll(List<Item> out, Set<String> seen, List<Item> src) {
        if (src == null) return;
        for (Item it : src) {
            if (it == null || it.uuid.isEmpty() || !seen.add(it.uuid)) continue;
            out.add(it);
        }
    }

    /** Hanya order dengan uuid di {@code uuids} (mode pilih "Aksi ⋮"), urutan daftar tetap. */
    public static List<Item> restrict(List<Item> items, Collection<String> uuids) {
        List<Item> out = new ArrayList<>();
        if (items == null || uuids == null || uuids.isEmpty()) return out;
        Set<String> want = new HashSet<>(uuids);
        for (Item it : items) if (want.contains(it.uuid)) out.add(it);
        return out;
    }

    /** Jumlah order per sumber (indeks MINE/OTHER/OPEN). */
    public static int[] countBySource(List<Item> items) {
        int[] n = new int[SOURCE_COUNT];
        if (items == null) return n;
        for (Item it : items) if (it.source >= 0 && it.source < SOURCE_COUNT) n[it.source]++;
        return n;
    }

    /** Ada produk terpilih yang dibatasi jenis galonnya? */
    static boolean typeFiltered(Set<String> sel, Map<String, Set<String>> typeSel) {
        if (typeSel == null) return false;
        for (String k : sel) {
            Set<String> w = typeSel.get(k);
            if (w != null && !w.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Filter produk & jenis galon (kosong = lolos semua). Tanpa batas jenis → cukup salah satu produk
     * order terpilih; dibatasi jenis → cocokkan per (produk, jenis), produk tanpa pilihan jenis = semua.
     */
    public static boolean productMatches(Item it, Set<String> sel, Map<String, Set<String>> typeSel) {
        if (sel == null || sel.isEmpty()) return true;
        if (!typeFiltered(sel, typeSel)) {
            for (String s : it.slugs) if (sel.contains(s)) return true;
            return false;
        }
        for (String[] r : it.rows) {
            if (r == null || r.length == 0 || r[0] == null) continue;
            String k = key(r[0]);
            if (!sel.contains(k)) continue;
            Set<String> want = typeSel.get(k);
            if (want == null || want.isEmpty()) return true;
            String ty = r.length > 1 && r[1] != null ? r[1] : "";
            if (want.contains(ty.isEmpty() ? TYPE_UNKNOWN : ty)) return true;
        }
        return false;
    }

    /** Baris tampil = sumbernya dicentang DAN lolos filter produk/jenis. */
    public static boolean visible(Item it, Set<Integer> sources, Set<String> sel, Map<String, Set<String>> typeSel) {
        return it != null && sources != null && sources.contains(it.source) && productMatches(it, sel, typeSel);
    }

    /** Yang dikirim: tampil (lihat {@link #visible}) dan tidak dilepas centangnya. */
    public static List<Item> picked(List<Item> items, Set<Integer> sources, Set<String> sel,
                                    Map<String, Set<String>> typeSel, Set<String> unchecked) {
        List<Item> out = new ArrayList<>();
        if (items == null) return out;
        for (Item it : items) {
            if (!visible(it, sources, sel, typeSel)) continue;
            if (unchecked != null && unchecked.contains(it.uuid)) continue;
            out.add(it);
        }
        return out;
    }

    public static List<String> uuids(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item it : items) out.add(it.uuid);
        return out;
    }

    /** Pelanggan unik (server mengirim satu pesan per pelanggan); order tanpa identitas dihitung sendiri. */
    public static int customerCount(List<Item> items) {
        Set<String> k = new HashSet<>();
        for (Item it : items) k.add(it.customerKey.isEmpty() ? "trx:" + it.uuid : it.customerKey);
        return k.size();
    }

    public static boolean overCap(int orders) {
        return orders > MAX_UUIDS;
    }

    /** Teks hitungan di atas daftar. */
    public static String summary(int orders, int customers) {
        return "📢 " + orders + " order dicentang · " + customers + " pelanggan";
    }

    /** null bila masih dalam batas; selain itu peringatan berbahasa Indonesia. */
    public static String capWarning(int orders) {
        if (!overCap(orders)) return null;
        return "⚠️ Maksimal " + MAX_UUIDS + " order sekali kirim (dicentang " + orders
                + "). Saring per produk/sumber atau kosongkan sebagian, lalu kirim bertahap.";
    }

    /** Rincian sumber untuk konfirmasi: "Antrian Saya 3 · Perangkat Lain 5". */
    public static String sourceBreakdown(List<Item> items) {
        int[] n = countBySource(items);
        String[] names = {"Antrian Saya", "Perangkat Lain", "Pesanan Terbuka"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SOURCE_COUNT; i++) {
            if (n[i] == 0) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(names[i]).append(' ').append(n[i]);
        }
        return b.toString();
    }
}
