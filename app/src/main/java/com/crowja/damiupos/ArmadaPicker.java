package com.crowja.damiupos;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * Pilih armada: wajib sesudah login dan saat mode Delivery dinyalakan. Kapasitas armada terpilih
 * menjadi muatan max HP ini. Satu armada boleh dipakai beberapa HP, jadi pemakainya hanya ditampilkan.
 */
final class ArmadaPicker {

    /** Jeda antar percobaan otomatis saat server tak terjangkau, supaya onResume tak mengulang toast. */
    private static final long RETRY_MS = 60_000L;

    private static long lastAttemptAt = 0L;
    private static boolean loading = false;
    private static WeakReference<AlertDialog> openDialog = new WeakReference<>(null);
    private static WeakReference<Activity> openOwner = new WeakReference<>(null);

    private ArmadaPicker() {}

    /** Dipanggil dari onResume layar utama: tampilkan popup wajib bila HP ini masih harus memilih. */
    static void askIfNeeded(Activity a) {
        SettingsDao dao = new SettingsDao(DatabaseHelper.getInstance(a));
        SyncSettings cfg = new SyncSettings(dao);
        if (!cfg.isEnrolled() || !cfg.isDeliveryDevice()) return;
        if (!cfg.isArmadaPromptPending() && !cfg.isArmadaRequired()) return;
        if (dao.isMultiUserEnabled() && dao.getCurrentUserId() <= 0) return;
        // Rit sedang berjalan: muatannya sudah di kendaraan. Tanyakan lagi setelah rit selesai.
        if (!dao.getDeliveryRunningTrxIds().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < RETRY_MS) return;
        lastAttemptAt = now;
        show(a, true, null, null);
    }

    /** Ganti armada dari menu; ditolak selama rit berjalan. */
    static void change(Activity a) {
        SettingsDao dao = new SettingsDao(DatabaseHelper.getInstance(a));
        if (!dao.getDeliveryRunningTrxIds().isEmpty()) {
            Toast.makeText(a, "Selesaikan rit yang berjalan sebelum ganti armada.", Toast.LENGTH_LONG).show();
            return;
        }
        show(a, false, null, null);
    }

    /**
     * Menyalakan mode Delivery: {@code onReady} jalan setelah armada dipilih, atau bila cabang belum
     * punya armada. {@code onCancel} jalan bila dibatalkan atau daftar gagal dimuat.
     */
    static void forDeliveryOn(Activity a, Runnable onReady, Runnable onCancel) {
        show(a, false, onReady, onCancel);
    }

    private static boolean isOpen() {
        AlertDialog d = openDialog.get();
        Activity owner = openOwner.get();
        return d != null && d.isShowing() && owner != null && !owner.isFinishing() && !owner.isDestroyed();
    }

    private static void show(Activity a, boolean mandatory, @Nullable Runnable onReady, @Nullable Runnable onCancel) {
        if (loading || isOpen()) {
            if (onCancel != null) onCancel.run();
            return;
        }
        loading = true;
        final SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(a)));
        new Thread(() -> {
            JSONObject res = null;
            try {
                res = new SyncApi(cfg).armadas();
            } catch (Exception ignored) {
            }
            final JSONObject resF = res;
            a.runOnUiThread(() -> {
                loading = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                JSONArray list = resF != null ? resF.optJSONArray("armadas") : null;
                if (list == null) {
                    Toast.makeText(a, "Daftar armada gagal dimuat. Periksa koneksi.", Toast.LENGTH_LONG).show();
                    if (onCancel != null) onCancel.run();
                } else if (list.length() == 0) {
                    cfg.setArmadaPromptPending(false);
                    cfg.setArmadaRequired(false);
                    if (onReady != null) onReady.run();
                    else if (!mandatory) Toast.makeText(a, "Belum ada armada di cabang ini.", Toast.LENGTH_LONG).show();
                } else {
                    showList(a, cfg, list, resF.optLong("current_id", 0L), mandatory, onReady, onCancel);
                }
            });
        }).start();
    }

    private static void showList(Activity a, SyncSettings cfg, JSONArray list, long currentId, boolean mandatory,
                                 @Nullable Runnable onReady, @Nullable Runnable onCancel) {
        String[] labels = new String[list.length()];
        for (int i = 0; i < list.length(); i++) labels[i] = label(list.optJSONObject(i), currentId);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("🚚 Pilih armada yang dipakai")
                .setItems(labels, (d, which) -> select(a, cfg, list.optJSONObject(which), mandatory, onReady, onCancel))
                .setCancelable(!mandatory);
        if (!mandatory) {
            b.setNegativeButton("Batal", (d, w) -> { if (onCancel != null) onCancel.run(); });
            b.setOnCancelListener(d -> { if (onCancel != null) onCancel.run(); });
        }
        openDialog = new WeakReference<>(b.show());
        openOwner = new WeakReference<>(a);
    }

    private static String label(JSONObject o, long currentId) {
        StringBuilder sb = new StringBuilder();
        if (o.optLong("id") == currentId) sb.append("✓ ");
        sb.append(o.optString("name")).append(" · ").append(o.optInt("capacity")).append(" galon");
        JSONArray used = o.optJSONArray("used_by");
        if (used != null && used.length() > 0) {
            sb.append("\nDipakai: ");
            for (int i = 0; i < used.length(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(used.optString(i));
            }
        }
        return sb.toString();
    }

    private static void select(Activity a, SyncSettings cfg, JSONObject armada, boolean mandatory,
                               @Nullable Runnable onReady, @Nullable Runnable onCancel) {
        final String staffUuid = LocationReporter.currentStaffUuid(a);
        new Thread(() -> {
            boolean ok = false;
            try {
                JSONObject r = new SyncApi(cfg).selectArmada(armada.optLong("id"), staffUuid);
                cfg.setMaxLoad(r.optInt("max_load", armada.optInt("capacity")));
                cfg.setArmadaName(armada.optString("name"));
                cfg.setArmadaRequired(false);
                cfg.setArmadaPromptPending(false);
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean okF = ok;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (okF) {
                    Toast.makeText(a, "Armada: " + armada.optString("name") + " · "
                            + armada.optInt("capacity") + " galon", Toast.LENGTH_LONG).show();
                    a.invalidateOptionsMenu();
                    if (onReady != null) onReady.run();
                } else {
                    Toast.makeText(a, "Armada gagal disimpan. Periksa koneksi.", Toast.LENGTH_LONG).show();
                    if (mandatory) lastAttemptAt = 0L;
                    if (mandatory) askIfNeeded(a);
                    else if (onCancel != null) onCancel.run();
                }
            });
        }).start();
    }
}
