package com.crowja.damiupos.sync;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * Satu OkHttpClient untuk seluruh proses. Sebelumnya tiap {@code new SyncApi}/{@code LocationReporter}/
 * unduh APK membangun client sendiri → tiap request TLS handshake baru (boros CPU+radio di HP lama,
 * ribuan handshake/hari saat polling tiap ~12 detik). Client tunggal berbagi ConnectionPool +
 * Dispatcher, jadi koneksi keep-alive dipakai ulang. Turunan (mis. timeout beda untuk unduh)
 * dibuat via {@code SHARED.newBuilder()} agar tetap berbagi pool.
 */
public final class Http {

    public static final OkHttpClient SHARED = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(4, 5, TimeUnit.MINUTES))
            .build();

    private Http() {}
}
