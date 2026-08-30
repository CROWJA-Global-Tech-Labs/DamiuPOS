package com.crowja.damiupos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.model.Customer;

/**
 * Popup perintah "⚠️ Lengkapi Data Pelanggan" — sumber tunggal untuk peringatan
 * "foto rumah / koordinat belum lengkap". Dipakai saat antrean pesanan BARU untuk
 * perangkat ini tiba (lihat {@code SyncEngine} + {@code MainActivity}) sehingga operator
 * melengkapi data di tempat sebelum kredit galon penjualan dicabut oleh sistem.
 *
 * <p>Kelengkapan = punya FOTO rumah DAN KOORDINAT. Dikecualikan (tidak diperingatkan) bila:
 * <ul>
 *   <li>pelanggan null;</li>
 *   <li>pelanggan "Umum" (walk-in) — tidak pernah diminta foto/koordinat.</li>
 * </ul>
 * Predikat ini sengaja dicocokkan dengan {@code CreditGuard::customerRevocable} di server
 * (afiliasi reseller TIDAK lagi mengecualikan pencabutan kredit).
 */
public final class IncompleteCustomerDialog {

    private IncompleteCustomerDialog() {}

    /** Pelanggan ini perlu diperingatkan? (tidak lengkap & bukan Umum). */
    public static boolean shouldWarn(Customer c) {
        if (c == null) return false;
        if (CustomerDao.UMUM_NAME.equals(c.getName())) return false;      // walk-in → lewati
        return c.isIncomplete();
    }

    /**
     * Tampilkan popup + suara alarm bila {@link #shouldWarn} true; tombol positif membuka form
     * edit pelanggan agar foto/koordinat bisa dilengkapi. No-op bila sudah lengkap / dikecualikan
     * / activity sudah tutup.
     */
    public static void warn(Activity ctx, Customer c) {
        if (ctx == null || ctx.isFinishing() || !shouldWarn(c)) return;

        StringBuilder missing = new StringBuilder();
        if (!c.hasPhoto()) missing.append("• Belum ada FOTO rumah\n");
        if (!c.hasCoordinates()) missing.append("• KOORDINAT lokasi belum ditandai\n");

        playAlarm(ctx);
        new AlertDialog.Builder(ctx)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Lengkapi Data Pelanggan!")
                .setMessage("Pelanggan \"" + c.getName() + "\" (ada pesanan baru) belum lengkap:\n\n"
                        + missing
                        + "\nSegera FOTO rumah dan/atau TANDAI koordinat pelanggan ini "
                        + "agar kredit galon penjualan TIDAK dibatalkan oleh sistem.")
                .setPositiveButton("LENGKAPI SEKARANG", (d, w) ->
                        ctx.startActivity(new Intent(ctx, CustomerFormActivity.class)
                                .putExtra("customer_id", c.getId())))
                .setNegativeButton("NANTI", null)
                .show();
    }

    /** Nada alarm default (stream ALARM = nyaring, tidak ikut volume media); dibatasi 4 detik. */
    public static void playAlarm(Context ctx) {
        try {
            android.net.Uri uri = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION);
            }
            if (uri == null) return;
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(ctx, uri);
            mp.prepare();
            mp.start();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }, 4000);
        } catch (Exception ignored) {
            // Suara hanya penekanan — popup tetap tampil walau audio gagal.
        }
    }
}
