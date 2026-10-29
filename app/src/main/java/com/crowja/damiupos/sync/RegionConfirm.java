package com.crowja.damiupos.sync;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;

import com.crowja.damiupos.DamiuApplication;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Popup "order di luar wilayah" untuk Ambil Alih / Kirim ke Perangkat Lain. Server membalas 409
 * {@code needs_region_confirm} (hanya ke HP yang mengirim {@code region_confirm_supported}); pemanggil jaringan
 * (thread latar) memanggil {@link #ask} yang MEMBLOKIR sampai kurir menjawab Ya/Batal, lalu mengulang permintaan
 * dengan {@code confirm_out_of_region=true} atau berhenti.
 */
public final class RegionConfirm {

    private RegionConfirm() {}

    /** Tunggu jawaban maksimal ini — lewat itu dianggap Batal (jangan menggantung thread selamanya). */
    private static final long TIMEOUT_SECONDS = 120;

    /** "Ya, lanjutkan semua" (pemindahan massal): popup berikutnya dilewati sampai waktu ini. */
    private static volatile long yesAllUntil = 0;
    private static final long YES_ALL_MS = 5 * 60 * 1000L;

    /**
     * @return true bila kurir memilih tetap lanjut. false bila Batal, tak ada layar di depan, atau dipanggil dari
     *         thread utama (tak boleh memblokir UI).
     */
    public static boolean ask(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        if (System.currentTimeMillis() < yesAllUntil) return true;
        DamiuApplication app = DamiuApplication.get();
        final Activity a = app != null ? app.getCurrentActivity() : null;
        if (a == null || a.isFinishing()) return false;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean yes = new AtomicBoolean(false);
        a.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(a)
                        .setTitle("📍 Di luar wilayah")
                        .setMessage(message == null || message.isEmpty()
                                ? "Order ini di luar wilayah perangkat. Tetap lanjutkan?" : message)
                        .setCancelable(false)
                        .setPositiveButton("Ya, lanjutkan", (d, w) -> { yes.set(true); latch.countDown(); })
                        .setNeutralButton("Ya, lanjutkan semua", (d, w) -> {
                            yesAllUntil = System.currentTimeMillis() + YES_ALL_MS;
                            yes.set(true);
                            latch.countDown();
                        })
                        .setNegativeButton("Batal", (d, w) -> latch.countDown())
                        .show();
            } catch (Exception e) {
                latch.countDown();   // activity keburu mati → anggap Batal
            }
        });
        try {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) && yes.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
