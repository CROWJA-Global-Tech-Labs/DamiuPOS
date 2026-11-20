package com.crowja.damiupos.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Pemutar sirine komplain di DALAM aplikasi (dipakai untuk suara unggahan sendiri).
 *
 * <p>Suara unggahan disimpan di filesDir dan dibagikan lewat FileProvider. Memasangnya sebagai suara
 * kanal notifikasi ternyata tidak andal: di sebagian HP sistem menolak/diam (bahkan pembuatan kanal bisa
 * gagal sehingga notifikasi + popup komplain ikut batal). Jadi untuk pilihan "custom" kanalnya dibuat
 * TANPA suara dan aplikasi sendiri yang memutar berkasnya di stream ALARM — berulang bila "Bunyi terus"
 * aktif, berhenti saat notifikasi diketuk/digeser, layar komplain/chat dibuka, atau setelah 3 menit.</p>
 */
public final class SirenPlayer {

    private SirenPlayer() {}

    public static final String ACTION_STOP = "com.crowja.damiupos.SIREN_STOP";
    private static final long MAX_MS = 3L * 60L * 1000L;

    private static MediaPlayer player;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_STOP = SirenPlayer::stop;

    public static synchronized void start(Context ctx, Uri uri, boolean loop) {
        stop();
        if (uri == null) return;
        if (QuietHours.muteAndBuzz(ctx)) return;   // 🔕 jam senyap cabang: notifikasinya tetap tampil, getar saja, tanpa sirine
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(ctx.getApplicationContext(), uri);
            mp.setLooping(loop);
            mp.setOnCompletionListener(m -> { if (!loop) stop(); });
            mp.prepare();
            mp.start();
            player = mp;
            HANDLER.removeCallbacks(AUTO_STOP);
            HANDLER.postDelayed(AUTO_STOP, MAX_MS);
        } catch (Throwable t) {
            stop();
        }
    }

    public static synchronized void stop() {
        HANDLER.removeCallbacks(AUTO_STOP);
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try { mp.stop(); } catch (Throwable ignored) { }
        try { mp.release(); } catch (Throwable ignored) { }
    }

    /** Notifikasi komplain digeser / diketuk → sirine dalam aplikasi berhenti. */
    public static final class StopReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            stop();
        }
    }
}
