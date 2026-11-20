package com.crowja.damiupos.util;

import android.app.Activity;
import android.app.Dialog;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

/**
 * Status provider AI agen WhatsApp (GET /api/ai-status) untuk layar induk: ambil (dibatasi 1x/30 dtk),
 * beri tahu pemanggil lewat {@link Listener} (label/dot digambar pemanggil), popup "AI MATI" dengan
 * konfirmasi DUA ketukan, dan sirine komplain berulang selama popup tampil.
 *
 * <p>Semantik sama persis dengan salinan di MainActivity (refreshAiStatus / paintAiDot / showAiDownDialog /
 * startAiSiren): popup muncul tiap kali status MATI terbaca dan belum dikonfirmasi; konfirmasi berlaku untuk
 * seluruh proses ({@link #acked}) dan direset begitu AI terbaca HIDUP lagi; sirine berhenti saat popup
 * ditutup atau layar tak tampil ({@link #onStop}), dan menyala lagi pada {@link #refresh} berikutnya bila
 * popup masih terbuka. Pilihan "Tanpa suara" dari web Konfigurasi dihormati.</p>
 */
public final class AiStatusMonitor {

    private static final String TAG = "AiStatusMonitor";

    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_ALIVE = 1;
    public static final int STATE_DEAD = 2;

    private static final long THROTTLE_MS = 30_000L;

    /** Pemanggil menggambar label ("AI HIDUP"/"AI MATI"/"AI ?") + dot dari state ini. */
    public interface Listener {
        void onAiStatus(int state, String text);
    }

    /** Konfirmasi "AI mati" sudah diberikan di proses ini (reset saat AI hidup lagi). */
    private static boolean acked;

    private final Activity activity;
    private final Listener listener;

    private long fetchedAt;
    private boolean busy;
    private int state = STATE_UNKNOWN;
    private String text = "";
    private String reason = "";
    private Dialog downDialog;
    private MediaPlayer siren;

    public AiStatusMonitor(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public int getState() {
        return state;
    }

    public boolean isDead() {
        return state == STATE_DEAD;
    }

    public String getText() {
        return text;
    }

    /** Label pendek yang sama dengan beranda lama. */
    public static String label(int state) {
        return state == STATE_ALIVE ? "AI HIDUP" : state == STATE_DEAD ? "AI MATI" : "AI ?";
    }

    /** Ketuk indikator: MATI → popup; selain itu toast teks status. */
    public void onIndicatorClick() {
        if (state == STATE_DEAD) {
            showDownDialog();
        } else {
            Toast.makeText(activity, text.isEmpty() ? "Memuat status AI…" : text, Toast.LENGTH_LONG).show();
        }
    }

    /** Segarkan status (aman dipanggil sering; dibatasi 30 dtk setelah ambil yang berhasil). */
    public void refresh() {
        stopped = false;
        if (downDialog != null && downDialog.isShowing()) startSiren();   // kembali dari background
        if (busy || (fetchedAt != 0 && SystemClock.elapsedRealtime() - fetchedAt < THROTTLE_MS)) return;
        final SyncSettings cfg;
        try {
            cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(activity)));
        } catch (Throwable t) {
            Log.w(TAG, "settings", t);
            return;
        }
        if (!cfg.isEnrolled()) {
            apply(STATE_UNKNOWN, "🤖 Status AI tak diketahui — perangkat belum terhubung ke server.");
            return;
        }
        busy = true;
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = new SyncApi(cfg).aiStatus();
            } catch (Throwable t) {
                Log.w(TAG, "aiStatus gagal: " + t);
            }
            final JSONObject fr = r;
            activity.runOnUiThread(() -> {
                busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    if (fr == null) {
                        apply(STATE_UNKNOWN, "🤖 Status AI tak diketahui — periksa koneksi internet.");
                        return;
                    }
                    fetchedAt = SystemClock.elapsedRealtime();
                    boolean alive = fr.optBoolean("alive", false);
                    String st = fr.optString("state", "silent");
                    String provider = clean(fr.optString("provider", ""));
                    String fallback = clean(fr.optString("fallback", ""));
                    String why = clean(fr.optString("reason", ""));
                    String until = clean(fr.optString("until", ""));
                    StringBuilder sb = new StringBuilder("🤖 AI provider ").append(alive ? "HIDUP" : "MATI");
                    if (alive && "fallback".equals(st)) {
                        sb.append(" (pakai cadangan").append(fallback.isEmpty() ? "" : ": " + fallback).append(")");
                    } else if (alive && !provider.isEmpty()) {
                        sb.append(" · ").append(provider);
                    }
                    if (!alive && !why.isEmpty()) sb.append("\n").append(why);
                    if (!alive && !until.isEmpty()) sb.append("\nPulih sekitar: ").append(until);
                    reason = why;
                    apply(alive ? STATE_ALIVE : STATE_DEAD, sb.toString());
                } catch (Throwable t) {
                    Log.w(TAG, "render", t);
                }
            });
        }, "ai-status").start();
    }

    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s;
    }

    private void apply(int newState, String newText) {
        state = newState;
        if (newState == STATE_DEAD) {
            if (!acked) showDownDialog();
        } else if (newState == STATE_ALIVE) {
            acked = false;
            if (downDialog != null && downDialog.isShowing()) downDialog.dismiss();
        }
        text = newText;
        if (listener != null) {
            try {
                listener.onAiStatus(newState, newText);
            } catch (Throwable t) {
                Log.w(TAG, "listener", t);
            }
        }
    }

    /** Popup "AI mati": sirine komplain + konfirmasi DUA ketukan bahwa staf memantau WhatsApp manual. */
    public void showDownDialog() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (downDialog != null && downDialog.isShowing()) {
            startSiren();
            return;
        }
        StringBuilder msg = new StringBuilder("AI provider sedang MATI. Pesan WhatsApp pelanggan TIDAK dibalas otomatis.")
                .append("\n\nDengan mengonfirmasi, Anda menyatakan staf akan memantau WhatsApp secara manual dan rutin sampai AI hidup kembali.");
        if (reason != null && !reason.isEmpty()) msg.append("\n\nPenyebab: ").append(reason);
        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("🤖 AI MATI")
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton("Saya mengerti", null)
                .create();
        dlg.setOnDismissListener(d -> stopSiren());
        dlg.show();
        final int[] taps = {0};
        Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setOnClickListener(v -> {
                taps[0]++;
                if (taps[0] < 2) {
                    ((Button) v).setText("Ketuk sekali lagi untuk konfirmasi");
                    return;
                }
                acked = true;
                dlg.dismiss();
            });
        }
        downDialog = dlg;
        startSiren();
    }

    private boolean stopped;

    private void startSiren() {
        if (siren != null || stopped) return;
        MediaPlayer mp = null;
        try {
            ComplaintSirenSound.Choice ch = ComplaintSirenSound.current(activity);
            if (ch.silent()) return;   // "Tanpa suara" dari web Konfigurasi dihormati
            if (QuietHours.isQuiet(activity)) return;   // 🔕 jam senyap cabang
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(activity, ch.uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            siren = mp;
        } catch (Throwable t) {
            Log.w(TAG, "sirine gagal: " + t);
            if (mp != null) try { mp.release(); } catch (Throwable ignored) {}
            siren = null;
        }
    }

    private void stopSiren() {
        MediaPlayer mp = siren;
        siren = null;
        if (mp == null) return;
        try {
            mp.stop();
        } catch (Throwable ignored) {
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    /** Layar tak tampil: sirine berhenti (menyala lagi lewat {@link #refresh} bila popup masih terbuka). */
    public void onStop() {
        stopped = true;
        stopSiren();
    }

    /** Layar dihancurkan: hentikan sirine + tutup popup (cegah window bocor). */
    public void release() {
        stopSiren();
        try {
            if (downDialog != null && downDialog.isShowing()) {
                downDialog.setOnDismissListener(null);
                downDialog.dismiss();
            }
        } catch (Throwable ignored) {
        }
        downDialog = null;
    }
}
