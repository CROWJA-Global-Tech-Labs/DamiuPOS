package com.crowja.damiupos.util;

import android.app.Activity;
import android.widget.TextView;
import android.widget.Toast;

import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 📜 Jurnal Pesanan dari server (GET /api/transactions/{uuid}/journal): siapa membuat, mengedit,
 * mengalihkan, menunda, menyelesaikan order — cermin halaman Jurnal di web. Satu tampilan dipakai
 * Detail Pelanggan (histori) dan semua popup Detail Order antrean.
 */
public final class OrderJournal {

    private OrderJournal() {
    }

    /** Tautan "📜 Lihat Jurnal Pesanan →" untuk disisipkan ke isi popup; null bila uuid kosong. */
    public static TextView link(Activity a, String uuid) {
        if (uuid == null || uuid.trim().isEmpty() || "null".equals(uuid)) return null;
        float d = a.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(a);
        tv.setText("📜 Lihat Jurnal Pesanan →");
        tv.setTextSize(15);
        tv.setTextColor(ThemeColors.primary(a));
        tv.setPadding(0, Math.round(12 * d), 0, Math.round(4 * d));
        tv.setOnClickListener(v -> show(a, uuid));
        return tv;
    }

    public static void show(Activity a, String uuid) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(a)));
        Toast.makeText(a, "Memuat jurnal…", Toast.LENGTH_SHORT).show();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", new java.util.Locale("id", "ID"));
        new Thread(() -> {
            String text;
            try {
                JSONObject r = new SyncApi(cfg).transactionJournal(uuid);
                StringBuilder sb = new StringBuilder();
                String receipt = r.optString("receipt_no", "");
                if (!receipt.isEmpty() && !"null".equals(receipt)) sb.append("🧾 ").append(receipt).append('\n');
                String dev = r.optString("current_device", "");
                if (!dev.isEmpty() && !"null".equals(dev)) sb.append("📱 Sekarang di: ").append(dev).append('\n');
                if (sb.length() > 0) sb.append('\n');
                JSONArray es = r.optJSONArray("entries");
                for (int i = 0; es != null && i < es.length(); i++) {
                    JSONObject e = es.optJSONObject(i);
                    if (e == null) continue;
                    // Ts.millis: stempel server bisa ISO-UTC — jangan dipotong mentah (jam UTC).
                    long ms = Ts.millis(e.optString("at", ""));
                    String at = ms == Long.MAX_VALUE ? e.optString("at", "") : fmt.format(new java.util.Date(ms));
                    sb.append("• ").append(at).append(" — ").append(e.optString("summary", e.optString("event")));
                    String who = e.optString("actor", "");
                    if (!who.isEmpty() && !"null".equals(who)) sb.append("\n   oleh ").append(who);
                    sb.append("\n\n");
                }
                text = sb.length() > 0 && es != null && es.length() > 0 ? sb.toString().trim()
                        : (sb.toString() + "Belum ada catatan jurnal untuk pesanan ini.").trim();
            } catch (Exception e) {
                text = "Gagal memuat jurnal — periksa koneksi internet.";
            }
            final String ft = text;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                float d = a.getResources().getDisplayMetrics().density;
                TextView tv = new TextView(a);
                tv.setText(ft);
                tv.setTextSize(14);
                tv.setTextIsSelectable(true);
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.text_primary));
                tv.setPadding(Math.round(22 * d), Math.round(8 * d), Math.round(22 * d), 0);
                android.widget.ScrollView sv = new android.widget.ScrollView(a);
                sv.addView(tv);
                new androidx.appcompat.app.AlertDialog.Builder(a).setTitle("📜 Jurnal Pesanan")
                        .setView(sv).setPositiveButton("Tutup", null).show();
            });
        }).start();
    }
}
