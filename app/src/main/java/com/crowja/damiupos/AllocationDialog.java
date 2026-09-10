package com.crowja.damiupos;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Editor "Alokasi Galon per Karyawan" untuk sebuah transaksi JUAL, dari menu transaksi maupun
 * antrian delivery. Staf mengusulkan pembagian galon (siapa yang BERHAK untuk Bonus Penjualan &
 * slip gaji); usulan DIKIRIM ke server yang mengirim link persetujuan ke email laporan. Alokasi
 * baru berlaku setelah pemberi persetujuan menyetujui — tidak ada perubahan langsung di HP.
 *
 * <p>UI dibangun programatik (tanpa XML) agar mandiri: daftar baris {Spinner staf + jumlah galon},
 * tombol tambah/hapus baris, dan indikator total-vs-target.
 */
public final class AllocationDialog {

    private AllocationDialog() {}

    /** Satu baris alokasi: pemilih staf + input galon. */
    private static final class Row {
        final View view;
        final Spinner spinner;
        final EditText et;

        Row(View view, Spinner spinner, EditText et) {
            this.view = view;
            this.spinner = spinner;
            this.et = et;
        }

        int galon() {
            try {
                return Integer.parseInt(et.getText().toString().trim());
            } catch (Exception e) {
                return 0;
            }
        }

        UserDao.StaffPick staff() {
            Object o = spinner.getSelectedItem();
            return o instanceof UserDao.StaffPick ? (UserDao.StaffPick) o : null;
        }
    }

    /**
     * @param source "trx" (menu transaksi) atau "delivery" (antrian delivery) — informasi audit.
     */
    public static void show(final Activity act, final Transaction trx, final String source) {
        if (act == null || trx == null) return;

        if (!Transaction.TYPE_JUAL.equals(trx.getType())) {
            toast(act, "Alokasi galon hanya untuk transaksi penjualan (JUAL).");
            return;
        }
        final int target = trx.getJumlahGalon();
        if (target < 1) {
            toast(act, "Transaksi ini tidak memiliki galon untuk dialokasikan.");
            return;
        }

        final DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        final SettingsDao sdao = new SettingsDao(dbh);
        final SyncSettings cfg = new SyncSettings(sdao);
        if (!cfg.isEnrolled()) {
            toast(act, "Perangkat belum terhubung ke dashboard — alokasi membutuhkan koneksi ke server.");
            return;
        }

        final UserDao userDao = new UserDao(dbh);
        final List<UserDao.StaffPick> staff = userDao.getActiveForAllocation();
        if (staff.isEmpty()) {
            toast(act, "Belum ada karyawan tersinkron. Lakukan sinkronisasi lalu coba lagi.");
            return;
        }

        final long curUid = sdao.getCurrentUserId();
        final String curName = sdao.getCurrentUserName();

        // ---- Bangun tampilan ----
        final int pad = dp(act, 16);
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 4));

        final TextView hint = new TextView(act);
        hint.setText("Bagi " + target + " galon transaksi ini ke karyawan yang BERHAK (Bonus Penjualan & slip gaji). "
                + "Total alokasi harus = " + target + " galon. Usulan dikirim untuk disetujui pemberi persetujuan.");
        hint.setTextSize(13f);
        root.addView(hint);

        final LinearLayout rowsBox = new LinearLayout(act);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setPadding(0, dp(act, 10), 0, 0);
        root.addView(rowsBox);

        final TextView totalLabel = new TextView(act);
        totalLabel.setTextSize(14f);
        totalLabel.setPadding(0, dp(act, 6), 0, dp(act, 6));
        root.addView(totalLabel);

        final Button addBtn = new Button(act);
        addBtn.setText("+ Tambah Karyawan");
        root.addView(addBtn);

        final ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        final List<Row> rows = new ArrayList<>();
        final Runnable recompute = () -> {
            int sum = 0;
            for (Row r : rows) sum += r.galon();
            boolean ok = sum == target;
            totalLabel.setText("Total alokasi: " + sum + " / " + target + " galon" + (ok ? "  ✓" : ""));
            totalLabel.setTextColor(ok ? 0xFF15803D : 0xFFB45309);
        };

        // Baris pertama: staf yang login (jika ada di daftar) memegang seluruh galon → satu-staf 1 ketuk.
        int firstSel = 0;
        for (int i = 0; i < staff.size(); i++) {
            if (curUid > 0 && staff.get(i).id == curUid) {
                firstSel = i;
                break;
            }
        }
        addRow(act, rowsBox, rows, staff, firstSel, target, recompute);
        recompute.run();

        addBtn.setOnClickListener(v -> {
            addRow(act, rowsBox, rows, staff, 0, 0, recompute);
            recompute.run();
        });

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("Alokasi Galon Karyawan")
                .setView(scroll)
                .setPositiveButton("Kirim untuk Persetujuan", null)   // di-override agar tak auto-dismiss saat invalid
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                if (rows.isEmpty()) {
                    toast(act, "Tambah minimal satu karyawan.");
                    return;
                }
                // Kumpulkan & validasi (mirror aturan server: tiap galon > 0, tak ada staf dobel, total = target).
                final List<UserDao.StaffPick> picks = new ArrayList<>();
                final List<Integer> galons = new ArrayList<>();
                final Set<String> seen = new HashSet<>();
                int sum = 0;
                for (Row r : rows) {
                    UserDao.StaffPick sp = r.staff();
                    int g = r.galon();
                    if (sp == null || sp.uuid == null || sp.uuid.isEmpty()) {
                        toast(act, "Pilih karyawan pada setiap baris.");
                        return;
                    }
                    if (g <= 0) {
                        toast(act, "Isi jumlah galon (> 0) pada setiap baris.");
                        return;
                    }
                    if (!seen.add(sp.uuid)) {
                        toast(act, "Karyawan yang sama dipilih lebih dari sekali.");
                        return;
                    }
                    picks.add(sp);
                    galons.add(g);
                    sum += g;
                }
                if (sum != target) {
                    toast(act, "Total alokasi (" + sum + " galon) harus sama dengan jumlah galon transaksi (" + target + ").");
                    return;
                }

                pos.setEnabled(false);
                pos.setText("Mengirim…");
                submit(act, dialog, pos, cfg, userDao, trx, target, source, curUid, curName, picks, galons);
            });
        });
        dialog.show();
    }

    /** Tambah satu baris {Spinner staf + galon + hapus} ke {@code rowsBox}. */
    private static void addRow(final Activity act, final LinearLayout rowsBox, final List<Row> rows,
                               final List<UserDao.StaffPick> staff, int preselect, int galon,
                               final Runnable recompute) {
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(act, 4), 0, dp(act, 4));

        final Spinner spinner = new Spinner(act);
        ArrayAdapter<UserDao.StaffPick> adapter = new ArrayAdapter<>(act,
                android.R.layout.simple_spinner_item, staff);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (preselect >= 0 && preselect < staff.size()) spinner.setSelection(preselect);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(spinner, spLp);

        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("galon");
        et.setGravity(Gravity.CENTER);
        if (galon > 0) et.setText(String.valueOf(galon));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(act, 68),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.leftMargin = dp(act, 6);
        row.addView(et, etLp);

        final Button del = new Button(act, null, android.R.attr.buttonStyleSmall);
        del.setText("✕");
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(dp(act, 44),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(del, delLp);

        final Row holder = new Row(row, spinner, et);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { recompute.run(); }
        });
        del.setOnClickListener(v -> {
            // Baris terakhir tidak dihapus (biar selalu ada minimal satu) — cukup dikosongkan.
            if (rows.size() <= 1) {
                et.setText("");
                return;
            }
            rowsBox.removeView(row);
            rows.remove(holder);
            recompute.run();
        });

        rowsBox.addView(row);
        rows.add(holder);
    }

    /** Kirim usulan ke server di background thread; laporkan hasil ke UI. */
    private static void submit(final Activity act, final AlertDialog dialog, final Button pos,
                               final SyncSettings cfg, final UserDao userDao, final Transaction trx,
                               final int target, final String source, final long curUid,
                               final String curName, final List<UserDao.StaffPick> picks,
                               final List<Integer> galons) {
        new Thread(() -> {
            String errMsg = null;
            String okMsg = null;
            try {
                String trxUuid = new TransactionDao(DatabaseHelper.getInstance(act)).getSyncUuidById(trx.getId());
                if (trxUuid == null || trxUuid.isEmpty()) {
                    // Transaksi belum tersinkron ke server → pancing sync, minta ulangi.
                    SyncScheduler.syncNow(act.getApplicationContext());
                    errMsg = "Transaksi belum tersinkron ke server. Coba lagi sebentar.";
                } else {
                    JSONArray alloc = new JSONArray();
                    for (int i = 0; i < picks.size(); i++) {
                        JSONObject a = new JSONObject();
                        a.put("staff_uuid", picks.get(i).uuid);
                        a.put("staff_name", picks.get(i).name);
                        a.put("galon", galons.get(i));
                        alloc.put(a);
                    }
                    JSONObject body = new JSONObject();
                    body.put("transaction_uuid", trxUuid);
                    if (source != null) body.put("source", source);
                    body.put("jumlah_galon", target);
                    if (curName != null && !curName.isEmpty()) body.put("requester_name", curName);
                    if (curUid > 0) {
                        String reqUuid = userDao.getSyncUuidById(curUid);
                        if (reqUuid != null && !reqUuid.isEmpty()) body.put("requester_staff_uuid", reqUuid);
                    }
                    if (trx.getCustomerName() != null) body.put("customer_name", trx.getCustomerName());
                    body.put("total_harga", trx.getTotalHarga());
                    if (trx.getTanggal() != null) body.put("tanggal", trx.getTanggal());
                    body.put("allocations", alloc);

                    JSONObject r = new SyncApi(cfg).proposeAllocation(body);
                    okMsg = r.optString("message", "Usulan alokasi dikirim untuk persetujuan.");
                    if (!r.optBoolean("emailed", true)) {
                        okMsg = r.optString("message",
                                "Usulan dibuat, tetapi email ke pemberi persetujuan gagal terkirim.");
                    }
                }
            } catch (SyncApi.SyncException se) {
                errMsg = extractMessage(se.body);
                if (errMsg == null) errMsg = "Gagal mengirim usulan (kode " + se.code + ").";
            } catch (Exception e) {
                errMsg = "Gagal mengirim usulan — periksa koneksi internet.";
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

    private static void toast(Activity act, String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
