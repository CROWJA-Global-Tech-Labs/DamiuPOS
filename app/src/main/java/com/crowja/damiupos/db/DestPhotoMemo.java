package com.crowja.damiupos.db;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foto lokasi tujuan yang BARU diambil kurir (panel navigasi mengunggahnya langsung ke server, salinan
 * lokal pelanggan baru ikut berubah setelah pull). Selama 12 jam lokasi itu dianggap sudah berfoto, supaya
 * gerbang "Wajib Foto Lokasi" tak meminta foto kedua kali saat Selesai menyusul sebelum sinkron.
 */
public final class DestPhotoMemo {

    private static final long TTL_MS = 12 * 60 * 60 * 1000L;
    private static final ConcurrentHashMap<String, Long> TAKEN = new ConcurrentHashMap<>();

    private DestPhotoMemo() {
    }

    private static String key(long customerId, String destName) {
        return customerId + "|" + (destName == null ? "" : destName.trim().toLowerCase(Locale.ROOT));
    }

    public static void markTaken(long customerId, String destName) {
        if (customerId > 0) TAKEN.put(key(customerId, destName), System.currentTimeMillis());
    }

    public static boolean recentlyTaken(long customerId, String destName) {
        Long at = TAKEN.get(key(customerId, destName));
        return at != null && System.currentTimeMillis() - at < TTL_MS;
    }
}
