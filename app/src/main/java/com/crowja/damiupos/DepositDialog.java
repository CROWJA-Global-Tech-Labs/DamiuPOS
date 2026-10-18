package com.crowja.damiupos;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * 💵 Setoran: staf mencatat setoran tunainya sendiri — tanggal (default hari ini) + nominal.
 * Disimpan di server (device_deposits, sama dengan "Catat Setoran" web) lewat /api/deposits, jadi
 * butuh sinyal. Dialog menampilkan nominal TUNAI "seharusnya" s/d tanggal itu + setoran terakhir.
 */
public final class DepositDialog {

    private DepositDialog() {}

    private static final SimpleDateFormat API = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SHOW = new SimpleDateFormat("EEEE, d MMM yyyy", new Locale("id", "ID"));

    public static void show(Activity a) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(a)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(a, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * d), pad, 0);

        final Calendar day = Calendar.getInstance();
        TextView lblDate = label(a, "Tanggal setoran");
        box.addView(lblDate);
        final Button btnDate = new Button(a);
        btnDate.setAllCaps(false);
        btnDate.setText("📅 " + SHOW.format(day.getTime()));
        box.addView(btnDate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(label(a, "Nominal setoran (Rp)"));
        final EditText amount = new EditText(a);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        amount.setHint("mis. 250000");
        amount.setTextSize(20f);
        box.addView(amount);

        final TextView info = new TextView(a);
        info.setPadding(0, Math.round(10 * d), 0, 0);
        info.setTextSize(13f);
        info.setText("Memuat nominal seharusnya…");
        box.addView(info);

        final Runnable[] reload = new Runnable[1];
        reload[0] = () -> loadInfo(a, cfg, API.format(day.getTime()), info);
        btnDate.setOnClickListener(v -> {
            DatePickerDialog dp = new DatePickerDialog(a, (view, y, m, dd) -> {
                day.set(y, m, dd);
                btnDate.setText("📅 " + SHOW.format(day.getTime()));
                reload[0].run();
            }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());   // tak boleh tanggal mendatang (aturan server)
            dp.show();
        });
        reload[0].run();

        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("💵 Catat Setoran")
                .setView(box)
                .setPositiveButton("Simpan", null)
                .setNegativeButton("Batal", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long nominal;
            try {
                nominal = Long.parseLong(amount.getText().toString().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                nominal = 0;
            }
            if (nominal <= 0) {
                amount.setError("Isi nominal setoran");
                return;
            }
            final long n = nominal;
            final String date = API.format(day.getTime());
            new AlertDialog.Builder(a)
                    .setTitle("Simpan setoran?")
                    .setMessage("Rp " + rp(n) + " — " + SHOW.format(day.getTime()))
                    .setPositiveButton("Simpan", (dd, w) -> submit(a, cfg, date, n, dlg))
                    .setNegativeButton("Batal", null)
                    .show();
        }));
        dlg.show();
    }

    private static TextView label(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, Math.round(8 * a.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static void loadInfo(Activity a, SyncSettings cfg, String date, TextView info) {
        info.setText("Memuat nominal seharusnya…");
        new Thread(() -> {
            String text;
            try {
                JSONObject r = new SyncApi(cfg).deposits(date);
                StringBuilder sb = new StringBuilder();
                sb.append("Tunai seharusnya s/d tanggal ini: Rp ").append(rp(Math.round(r.optDouble("expected_amount", 0))));
                JSONArray recent = r.optJSONArray("recent");
                if (recent != null && recent.length() > 0) {
                    sb.append("\n\nSetoran terakhir:");
                    for (int i = 0; i < recent.length() && i < 3; i++) {
                        JSONObject o = recent.optJSONObject(i);
                        if (o == null) continue;
                        sb.append("\n• ").append(o.optString("date")).append(" — Rp ")
                                .append(rp(Math.round(o.optDouble("amount", 0)))).append(" (").append(o.optString("by")).append(")");
                    }
                }
                text = sb.toString();
            } catch (Exception e) {
                text = "Nominal seharusnya tak bisa dimuat — periksa koneksi.";
            }
            final String t = text;
            a.runOnUiThread(() -> info.setText(t));
        }).start();
    }

    private static void submit(Activity a, SyncSettings cfg, String date, long amount, AlertDialog dlg) {
        String staffUuid = LocationReporter.currentStaffUuid(a);
        Toast.makeText(a, "Menyimpan setoran…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                JSONObject r = new SyncApi(cfg).recordDeposit(date, amount, staffUuid);
                ok = r.optBoolean("ok", false);
                msg = r.optString("message", ok ? "Setoran dicatat." : "Gagal mencatat setoran.");
            } catch (SyncApi.SyncException se) {
                String m = null;
                try {
                    m = new JSONObject(se.body).optString("message", null);
                } catch (Exception ignored) {
                }
                msg = m != null && !m.isEmpty() ? m : "Gagal mencatat setoran (kode " + se.code + ").";
            } catch (Exception e) {
                msg = "Gagal mencatat setoran — periksa koneksi.";
            }
            final boolean fok = ok;
            final String fm = msg;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (fok) dlg.dismiss();
                new AlertDialog.Builder(a).setTitle(fok ? "✅ Setoran Dicatat" : "⚠️ Setoran Belum Dicatat")
                        .setMessage(fm).setPositiveButton("OK", null).show();
            });
        }).start();
    }

    private static String rp(long v) {
        return NumberFormat.getIntegerInstance(new Locale("id", "ID")).format(v);
    }
}
