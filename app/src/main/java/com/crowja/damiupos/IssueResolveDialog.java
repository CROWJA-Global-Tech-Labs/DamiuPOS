package com.crowja.damiupos;

import android.app.Activity;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

/**
 * "Detail bermasalah pelanggan SUDAH DIPERBAIKI" — staf menulis catatan lalu MENGAJUKAN penyelesaian.
 * Bila perangkat terhubung ke server, usulan dikirim (server membuat permintaan PENDING & mengirim link
 * persetujuan ke email laporan); masalah baru ditandai selesai (issue_resolved_at) SETELAH owner
 * menyetujui, lalu tersinkron balik. Bila perangkat berdiri sendiri (belum enroll), tidak ada otoritas
 * persetujuan → ditandai selesai secara LOKAL. Cermin {@link AllocationDialog}.
 *
 * <p>Dipakai dari detail pelanggan (Tandai Bermasalah) dan sebagai gerbang penyelesaian Delivery
 * (submit-then-proceed: setelah pengajuan terkirim, {@link OnResolved#onResolved()} melanjutkan Selesai).
 */
public final class IssueResolveDialog {

    private IssueResolveDialog() {}

    /** Dipanggil setelah pengajuan berhasil (server) / penyelesaian lokal (standalone). */
    public interface OnResolved {
        void onResolved();
    }

    public static void show(final Activity act, final Customer c, final String source,
                            final OnResolved onDone) {
        if (act == null || c == null) return;

        final DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        final SettingsDao sdao = new SettingsDao(dbh);
        final SyncSettings cfg = new SyncSettings(sdao);
        final boolean enrolled = cfg.isEnrolled();

        final int pad = dp(act, 16);
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 4));

        final TextView hint = new TextView(act);
        hint.setText("Jelaskan apa yang sudah diperbaiki untuk \"" + safe(c.getName()) + "\". "
                + (enrolled
                        ? "Permintaan dikirim ke email laporan untuk disetujui owner sebelum masalah ditutup."
                        : "Perangkat belum terhubung ke server — masalah ditandai selesai secara lokal."));
        hint.setTextSize(13f);
        root.addView(hint);

        final EditText note = new EditText(act);
        note.setHint("Catatan perbaikan (wajib)…");
        note.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        root.addView(note);

        final ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("Tandai Sudah Diperbaiki")
                .setView(scroll)
                .setPositiveButton(enrolled ? "Kirim untuk Persetujuan" : "Tandai Selesai", null)
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                final String n = note.getText().toString().trim();
                if (n.isEmpty()) {
                    toast(act, "Isi catatan perbaikan dulu.");
                    return;
                }
                if (!enrolled) {
                    // Standalone: tak ada otoritas persetujuan → selesaikan lokal (cermin perilaku lama).
                    new CustomerDao(dbh).markProblemResolved(c.getId());
                    SyncScheduler.syncNow(act.getApplicationContext());
                    dialog.dismiss();
                    toast(act, "Masalah ditandai selesai (lokal).");
                    if (onDone != null) onDone.onResolved();
                    return;
                }
                // Kunci pembatalan selama pengiriman: agar "Batal"/BACK di tengah round-trip tak bisa
                // membuat callback sukses (yang melanjutkan Selesai) tetap jalan setelah user membatalkan.
                pos.setEnabled(false);
                pos.setText("Mengirim…");
                dialog.setCancelable(false);
                final Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (neg != null) neg.setEnabled(false);
                submit(act, dialog, pos, neg, dbh, cfg, c, source, n, onDone);
            });
        });
        dialog.show();
    }

    /** Kirim pengajuan ke server di background thread; laporkan hasil ke UI, lalu lanjut onDone. */
    private static void submit(final Activity act, final AlertDialog dialog, final Button pos,
                               final Button neg, final DatabaseHelper dbh, final SyncSettings cfg,
                               final Customer c, final String source, final String note,
                               final OnResolved onDone) {
        new Thread(() -> {
            String errMsg = null;
            String okMsg = null;
            try {
                String custUuid = new CustomerDao(dbh).getSyncUuidById(c.getId());
                if (custUuid == null || custUuid.isEmpty()) {
                    // Pelanggan belum tersinkron ke server → pancing sync, minta ulangi.
                    SyncScheduler.syncNow(act.getApplicationContext());
                    errMsg = "Pelanggan belum tersinkron ke server. Coba lagi sebentar.";
                } else {
                    final SettingsDao sdao = new SettingsDao(dbh);
                    final long uid = sdao.getCurrentUserId();
                    final String uname = sdao.getCurrentUserName();

                    JSONObject body = new JSONObject();
                    body.put("customer_uuid", custUuid);
                    body.put("note", note);
                    if (source != null) body.put("source", source);
                    if (uname != null && !uname.isEmpty()) body.put("requester_name", uname);
                    if (uid > 0) {
                        String reqUuid = new UserDao(dbh).getSyncUuidById(uid);
                        if (reqUuid != null && !reqUuid.isEmpty()) body.put("requester_staff_uuid", reqUuid);
                    }
                    if (c.getName() != null) body.put("customer_name", c.getName());
                    if (c.getIssueFlags() != null && !c.getIssueFlags().isEmpty()) {
                        body.put("issue_flags", c.getIssueFlags());
                    }

                    JSONObject r = new SyncApi(cfg).proposeIssueResolve(body);
                    okMsg = r.optString("message", "Permintaan dikirim untuk persetujuan.");
                    if (!r.optBoolean("emailed", true)) {
                        okMsg = r.optString("message",
                                "Permintaan dibuat, tetapi email ke pemberi persetujuan gagal terkirim.");
                    }
                }
            } catch (SyncApi.SyncException se) {
                errMsg = extractMessage(se.body);
                if (errMsg == null) errMsg = "Gagal mengirim (kode " + se.code + ").";
            } catch (Exception e) {
                errMsg = "Gagal mengirim — periksa koneksi internet.";
            }

            final String fOk = okMsg;
            final String fErr = errMsg;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (fOk != null) {
                    Toast.makeText(act, fOk, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    if (onDone != null) onDone.onResolved();
                } else {
                    Toast.makeText(act, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Kirim untuk Persetujuan");
                    // Buka lagi pembatalan agar user bisa membatalkan / mencoba ulang.
                    dialog.setCancelable(true);
                    if (neg != null) neg.setEnabled(true);
                }
            });
        }).start();
    }

    /** Ambil pesan ramah dari body JSON error server ({"message":"..."}); null bila gagal. */
    private static String extractMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return new JSONObject(body).optString("message", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static String safe(String s) {
        return s != null ? s : "Pelanggan";
    }

    private static void toast(Activity act, String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
