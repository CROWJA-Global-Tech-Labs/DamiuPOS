package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logika murni layar ⚙️ Pengaturan Perangkat ({@code DeviceAdminActivity}) — penguraian isian
 * form ke nilai yang dikirim ke {@code POST /api/admin/devices/{uuid}/{section}}. Tanpa Android /
 * org.json supaya bisa diuji JVM.
 */
public final class DeviceAdminRules {

    private DeviceAdminRules() {}

    /**
     * Angka bulat ≥ 0 dari isian (max_load, delivery_max_age_minutes). Kosong / 0 / tak terurai →
     * {@code null} (server: kosong/0 = belum diatur / ikut setelan cabang). Pemisah ribuan dibuang.
     */
    public static Integer optPositiveInt(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        try {
            long v = Long.parseLong(d.length() > 9 ? d.substring(0, 9) : d);
            return v > 0 ? (int) v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Desimal ≥ 0 (fill_delivery_ratio). Koma diterima sebagai titik desimal. Kosong / tak terurai
     * / negatif → 0 (server: 0 = mati). Dibulatkan 2 angka seperti server.
     */
    public static double ratio(String raw) {
        if (raw == null) return 0;
        String s = raw.trim().replace(',', '.');
        if (s.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return 0;
            return Math.round(v * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Radius 📥 Peringatan Galon Kembali (meter) dari isian: bilangan bulat 10–500 (sama dengan validasi
     * server, flags pickup_alert_radius_m). Kosong / bukan angka bulat / di luar rentang → {@code null} (tolak).
     */
    public static Integer pickupRadius(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!s.matches("[0-9]{1,4}")) return null;
        int v = Integer.parseInt(s);
        return v >= PickupNearbyLogic.MIN_RADIUS_M && v <= PickupNearbyLogic.MAX_RADIUS_M ? v : null;
    }

    /** Kode struk: huruf/angka saja, kapital, maks 3. "" = otomatis (3 huruf pertama nama). */
    public static String receiptCode(String raw) {
        if (raw == null) return "";
        String s = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s.length() > 3 ? s.substring(0, 3) : s;
    }

    /** Harga rupiah dari isian ("5.000", "Rp 5000") → angka; kosong → {@code null}. */
    public static Long price(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 8) return d.isEmpty() ? null : 99_999_999L;
        return Long.parseLong(d);
    }

    /**
     * Harga marketing yang dikirim: hanya produk yang diisi DAN berbeda dari harga standar —
     * kosong / sama dengan standar = ikut harga standar (tidak dikirim). Urutan masukan dipertahankan.
     *
     * @param inputs   product_uuid → teks isian
     * @param standard product_uuid → harga_jual standar (boleh null / hilang)
     */
    public static Map<String, Long> marketingPrices(Map<String, String> inputs, Map<String, Double> standard) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : inputs.entrySet()) {
            Long p = price(e.getValue());
            if (p == null) continue;
            Double std = standard != null ? standard.get(e.getKey()) : null;
            if (std != null && Math.abs(std - p) < 0.005) continue;
            out.put(e.getKey(), p);
        }
        return out;
    }

    /** Angka tanpa ".0" berlebih: 5000.0 → "5000", 2.5 → "2.5". */
    public static String plain(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String s = String.format(Locale.US, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Rupiah bertitik ribuan: 15000 → "Rp 15.000". */
    public static String rupiah(double v) {
        long r = Math.round(v);
        String s = String.format(Locale.US, "%,d", r).replace(',', '.');
        return "Rp " + s;
    }

    /**
     * Label "terakhir aktif" dari selisih waktu.
     *
     * @param seenMs {@link Long#MAX_VALUE} / ≤ 0 = belum pernah terlihat
     */
    public static String lastSeen(long nowMs, long seenMs) {
        if (seenMs == Long.MAX_VALUE || seenMs <= 0) return "belum pernah aktif";
        long sec = Math.max(0, (nowMs - seenMs) / 1000);
        if (sec < 60) return "baru saja";
        long min = sec / 60;
        if (min < 60) return min + " menit lalu";
        long h = min / 60;
        if (h < 24) return h + " jam lalu";
        long d = h / 24;
        if (d < 30) return d + " hari lalu";
        long mo = d / 30;
        if (mo < 12) return mo + " bulan lalu";
        return (d / 365) + " tahun lalu";
    }

    /** "#RRGGBB" / "#AARRGGBB" → ARGB; tak terurai → {@code fallback}. */
    public static int parseColor(String hex, int fallback) {
        if (hex == null) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return (int) (0xFF000000L | Long.parseLong(s, 16));
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    /**
     * Kandidat "wilayah tambahan": perangkat delivery LAIN. Server sendiri menyaring ulang
     * (hanya perangkat delivery aktif cabang ini), jadi ini cuma menentukan apa yang ditampilkan.
     *
     * @param deliveryUuids uuid semua perangkat yang is_delivery, urut tampilan
     */
    public static List<String> extraWilayahCandidates(String selfUuid, Collection<String> deliveryUuids) {
        List<String> out = new ArrayList<>();
        for (String u : deliveryUuids) {
            if (u == null || u.isEmpty() || u.equals(selfUuid) || out.contains(u)) continue;
            out.add(u);
        }
        return out;
    }

    /** Yang dikirim sebagai extra_wilayah: centangan yang memang masih kandidat (urut kandidat). */
    public static List<String> extraWilayahToSend(List<String> candidates, Set<String> checked) {
        List<String> out = new ArrayList<>();
        for (String u : candidates) if (checked.contains(u)) out.add(u);
        return out;
    }
}
