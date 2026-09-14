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

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

/**
 * VOID item antrian delivery — staf menulis ALASAN (wajib) lalu MENGAJUKAN pembatalan. Server
 * membuat permintaan PENDING & mengirim link persetujuan bertoken ke email izin void; transaksi
 * baru benar-benar dibatalkan (soft-delete + pasangan KEMBALI) SETELAH super admin menyetujui,
 * lalu tombstone tersinkron balik ke HP (antrian menyusut sendiri lewat sync). TIDAK ada perubahan
 * lokal langsung — wajib terhubung ke server (tanpa otoritas persetujuan, void tak tersedia).
 * Cermin {@link IssueResolveDialog}.
 */
public final class DeliveryVoidDialog {

    private DeliveryVoidDialog() {}

    public static void show(final Activity act, final Transaction t) {
        if (act == null || t == null) return;

        final DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        final SyncSettings cfg = new SyncSettings(new SettingsDao(dbh));
        if (!cfg.isEnrolled()) {
            toast(act, "Perangkat belum terhubung ke server — void perlu persetujuan super admin.");
            return;
        }

        final int pad = dp(act, 16);
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 4));

        final TextView hint = new TextView(act);
        hint.setText("Jelaskan alasan membatalkan pesanan \"" + safe(t.getCustomerName()) + "\" ("
                + t.getJumlahGalon() + " galon). Permintaan dikirim ke email super admin; pesanan "
                + "dibatalkan SETELAH disetujui, lalu hilang dari antrian saat sinkron.");
        hint.setTextSize(13f);
        root.addView(hint);

        final EditText note = new EditText(act);
        note.setHint("Alasan void (wajib, min. 5 karakter)…");
        note.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        root.addView(note);
        root.addView(buildQuickReasonRow(act, note));

        final ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("🗑️ Void Pesanan")
                .setView(scroll)
                .setPositiveButton("Kirim untuk Persetujuan", null)
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                final String n = note.getText().toString().trim();
                if (n.length() < 5) {
                    toast(act, "Isi alasan void dulu (min. 5 karakter).");
                    return;
                }
                pos.setEnabled(false);
                pos.setText("Mengirim…");
                dialog.setCancelable(false);
                final Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (neg != null) neg.setEnabled(false);
                submit(act, dialog, pos, neg, dbh, cfg, t, n);
            });
        });
        dialog.show();
    }

    private static void submit(final Activity act, final AlertDialog dialog, final Button pos,
                               final Button neg, final DatabaseHelper dbh, final SyncSettings cfg,
                               final Transaction t, final String reason) {
        new Thread(() -> {
            String errMsg = null;
            String okMsg = null;
            try {
                String trxUuid = new TransactionDao(dbh).getSyncUuidById(t.getId());
                if (trxUuid == null || trxUuid.isEmpty()) {
                    // Transaksi belum tersinkron ke server → pancing sync, minta ulangi.
                    SyncScheduler.syncNow(act.getApplicationContext());
                    errMsg = "Transaksi belum tersinkron ke server. Coba lagi sebentar.";
                } else {
                    final SettingsDao sdao = new SettingsDao(dbh);
                    final long uid = sdao.getCurrentUserId();
                    final String uname = sdao.getCurrentUserName();

                    JSONObject body = new JSONObject();
                    body.put("transaction_uuid", trxUuid);
                    body.put("reason", reason);
                    if (uname != null && !uname.isEmpty()) body.put("requester_name", uname);
                    if (uid > 0) {
                        String reqUuid = new UserDao(dbh).getSyncUuidById(uid);
                        if (reqUuid != null && !reqUuid.isEmpty()) body.put("requester_staff_uuid", reqUuid);
                    }
                    if (t.getCustomerName() != null) body.put("customer_name", t.getCustomerName());

                    JSONObject r = new SyncApi(cfg).proposeVoid(body);
                    okMsg = r.optString("message", "Pengajuan void dikirim untuk persetujuan.");
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
                } else {
                    Toast.makeText(act, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Kirim untuk Persetujuan");
                    dialog.setCancelable(true);
                    if (neg != null) neg.setEnabled(true);
                }
            });
        }).start();
    }

    /**
     * Baris preset alasan cepat ("Berubah pikiran"/"Tidak ada orang"/"Kediaman tutup"/"Lainnya
     * (jelaskan)") — klik mengisi {@code target}, staf tetap bisa menyunting sebelum kirim.
     * "Lainnya" TIDAK mengisi teks literal, cuma mengosongkan & fokus supaya siap diketik.
     * Dibungkus HorizontalScrollView: 4 preset tak selalu muat satu baris di layar sempit.
     * Package-private (bukan {@code private}) — dipakai bersama {@link DeliveryEditDialog}, satu
     * kata "alasan cepat" yang sama untuk Edit maupun Void, tak perlu duplikasi.
     */
    static android.widget.HorizontalScrollView buildQuickReasonRow(Activity act, EditText target) {
        return buildQuickReasonRow(act, target,
                new String[]{"Berubah pikiran", "Tidak ada orang", "Kediaman tutup", "Lainnya (jelaskan)"});
    }

    /**
     * Versi ber-PRESET SENDIRI — tampilan & perilaku chip persis sama, cuma katanya yang beda.
     * Dipakai layar yang alasannya bukan soal pembatalan order, mis. "Laporan Kendala Pengiriman"
     * (motor rusak/banjir); memakai preset Void di sana akan menawarkan kalimat yang tak masuk akal.
     */
    static android.widget.HorizontalScrollView buildQuickReasonRow(Activity act, EditText target,
                                                                   String[] presets) {
        final LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(act, 6), 0, 0);
        for (String preset : presets) {
            boolean other = preset.startsWith("Lainnya");
            Button chip = new Button(act);
            chip.setText(preset);
            chip.setAllCaps(false);
            chip.setTextSize(12f);
            chip.setPadding(dp(act, 10), dp(act, 2), dp(act, 10), dp(act, 2));
            chip.setMinHeight(0);
            chip.setMinimumHeight(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(act, 6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                target.setText(other ? "" : preset);
                target.setSelection(target.getText().length());
                target.requestFocus();
            });
            chips.addView(chip);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(chips);
        return scroll;
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
        return s != null && !s.isEmpty() ? s : "Pelanggan";
    }

    private static void toast(Activity act, String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
