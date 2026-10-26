package com.crowja.damiupos;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.BitmapUtils;
import com.crowja.damiupos.util.Ts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 🧾 Rincian "Hutang sebelumnya" — ketuk barisnya di Detail Order Antrean Delivery. Buku besar
 * hutang ORANG ini dari server ({@code GET /api/customers/{uuid}/debt-detail}), tiap baris dengan
 * transaksi asalnya + foto (bukti selesai, bukti bayar, foto tertunda). Dari server, bukan DB
 * lokal: transaksi asal hutang sering dibuat HP lain, jadi tak ada di HP kurir.
 */
final class DebtDetailDialog {

    private DebtDetailDialog() {}

    /**
     * @param customerUuid  sync_uuid pelanggan order yang dibuka
     * @param excludeTrxUuid order yang sedang dibuka — barisnya disisihkan, sama dengan angka
     *                       "Hutang sebelumnya"
     */
    static void show(AppCompatActivity a, @Nullable String customerUuid, @Nullable String excludeTrxUuid,
                     String customerName) {
        if (customerUuid == null || customerUuid.isEmpty()) {
            Toast.makeText(a, "Pelanggan belum tersinkron — sinkronkan dulu.", Toast.LENGTH_LONG).show();
            return;
        }
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(a)));
        Toast.makeText(a, "Memuat rincian hutang…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONObject r = null;
            try {
                r = new SyncApi(cfg).debtDetail(customerUuid, excludeTrxUuid);
            } catch (Exception ignored) {
                // pesan di bawah
            }
            final JSONObject res = r;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (res == null || !res.optBoolean("ok", false)) {
                    new AlertDialog.Builder(a).setTitle("Rincian Hutang")
                            .setMessage("Gagal memuat rincian hutang — periksa koneksi internet, atau pelanggan belum tersinkron.")
                            .setPositiveButton("Tutup", null).show();
                    return;
                }
                render(a, res, customerName);
            });
        }).start();
    }

    private static void render(AppCompatActivity a, JSONObject res, String customerName) {
        int textPrimary = ContextCompat.getColor(a, R.color.text_primary);
        int textSecondary = ContextCompat.getColor(a, R.color.text_secondary);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));

        TextView head = new TextView(a);
        head.setText("Hutang sebelumnya: " + rp(res.optDouble("balance", 0)));
        head.setTextSize(16);
        head.setTextColor(textPrimary);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(head);
        TextView hint = new TextView(a);
        hint.setText("Hutang & pembayaran digabung per orang (bukan per struk) — pembayaran mengurangi total, "
                + "tidak dipasangkan ke transaksi tertentu. Ketuk foto untuk memperbesar.");
        hint.setTextSize(12);
        hint.setTextColor(textSecondary);
        hint.setPadding(0, dp(a, 2), 0, dp(a, 4));
        col.addView(hint);

        JSONArray rows = res.optJSONArray("rows");
        if (rows == null || rows.length() == 0) {
            TextView empty = new TextView(a);
            empty.setText("Belum ada catatan hutang/pembayaran di server.");
            empty.setTextColor(textSecondary);
            empty.setPadding(0, dp(a, 12), 0, 0);
            col.addView(empty);
        }
        TransactionDao trxDao = new TransactionDao(DatabaseHelper.getInstance(a));
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) col.addView(rowView(a, row, trxDao, textPrimary, textSecondary));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        new AlertDialog.Builder(a)
                .setTitle("🧾 Rincian Hutang — " + (customerName == null ? "" : customerName))
                .setView(sv)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private static LinearLayout rowView(AppCompatActivity a, JSONObject row, TransactionDao trxDao,
                                        int textPrimary, int textSecondary) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(a, 10));
        border.setStroke(dp(a, 1), 0xFF9E9E9E);
        card.setBackground(border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 10);
        card.setLayoutParams(lp);

        boolean payment = "payment".equals(str(row, "kind"));
        // Lencana warna muda + teks gelap: terbaca di tema terang maupun gelap.
        TextView badge = new TextView(a);
        badge.setText(payment ? "💵 BAYAR −" + rp(row.optDouble("amount", 0))
                : "🧾 HUTANG +" + rp(row.optDouble("amount", 0)));
        badge.setTextSize(13);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setTextColor(payment ? 0xFF1B5E20 : 0xFFB71C1C);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(a, 8));
        pill.setColor(payment ? 0xFFC8E6C9 : 0xFFFFCDD2);
        badge.setBackground(pill);
        badge.setPadding(dp(a, 8), dp(a, 2), dp(a, 8), dp(a, 2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        badge.setLayoutParams(blp);
        card.addView(badge);

        StringBuilder meta = new StringBuilder(fmt(str(row, "at")));
        if (!str(row, "by").isEmpty()) meta.append(" · dicatat ").append(str(row, "by"));
        if (!str(row, "reason").isEmpty()) meta.append("\n").append(str(row, "reason"));
        card.addView(text(a, meta.toString(), 12, textSecondary, dp(a, 4)));

        JSONObject t = row.optJSONObject("transaction");
        if (t == null) {
            card.addView(text(a, payment ? "Pembayaran tanpa transaksi terkait." : "Dicatat manual — tanpa transaksi terkait.",
                    13, textSecondary, dp(a, 4)));
            return card;
        }
        card.addView(text(a, trxText(t), 13, textPrimary, dp(a, 6)));

        LinearLayout photos = new LinearLayout(a);
        photos.setOrientation(LinearLayout.HORIZONTAL);
        addPhoto(a, photos, str(t, "photo_url"), "📷 Bukti selesai", textSecondary);
        addPhoto(a, photos, str(t, "payment_proof_url"), "💳 Bukti bayar", textSecondary);
        addPhoto(a, photos, str(t, "tertunda_photo_url"), "⏸ Foto tertunda", textSecondary);
        if (photos.getChildCount() > 0) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setPadding(0, dp(a, 6), 0, 0);
            hs.addView(photos);
            card.addView(hs);
        } else {
            card.addView(text(a, "Tidak ada foto untuk transaksi ini.", 12, textSecondary, dp(a, 4)));
        }

        // Struk hanya untuk transaksi yang ADA di HP ini (transaksi HP lain tak tersimpan lokal).
        long localId = trxDao.getIdBySyncUuid(str(t, "uuid"));
        if (localId > 0) {
            TextView open = text(a, "🧾 Buka Struk →", 14, 0xFF1565C0, dp(a, 8));
            open.setOnClickListener(v -> a.startActivity(new Intent(a, ReceiptActivity.class)
                    .putExtra("transaction_id", localId)));
            card.addView(open);
        }
        return card;
    }

    /** Ringkasan transaksi asal — isinya sama dengan popup Detail Transaksi histori pelanggan. */
    private static String trxText(JSONObject t) {
        StringBuilder sb = new StringBuilder();
        String receipt = str(t, "receipt_no");
        sb.append("🧾 ").append(receipt.isEmpty() ? "Transaksi" : receipt);
        String tgl = fmt(str(t, "tanggal"));
        if (!tgl.isEmpty()) sb.append(" · ").append(tgl);
        sb.append('\n');
        if (t.optBoolean("voided", false)) sb.append("🗑️ Transaksi ini sudah di-VOID\n");
        JSONArray lines = t.optJSONArray("lines");
        if (lines != null && lines.length() > 0) {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.optJSONObject(i);
                if (l == null) continue;
                double q = l.optDouble("qty", 0), price = l.optDouble("price", 0);
                String qs = q == Math.floor(q) ? String.valueOf((long) q) : String.valueOf(q);
                sb.append("• ").append(str(l, "name")).append("  ").append(qs).append(" galon");
                if (price > 0) sb.append(" × ").append(rp(price)).append(" = ").append(rp(price * q));
                sb.append('\n');
            }
        } else {
            String items = str(t, "items");
            sb.append("• ").append(items.isEmpty() ? t.optInt("galon", 0) + " galon" : items).append('\n');
        }
        double ongkir = t.optDouble("ongkir", 0);
        if (ongkir > 0) sb.append("Ongkir: ").append(rp(ongkir)).append('\n');
        sb.append("Total: ").append(rp(t.optDouble("total", 0)));
        String pay = payLabel(str(t, "payment_method"));
        if (!pay.isEmpty()) sb.append(" · ").append(pay);
        sb.append('\n');
        String dest = str(t, "dest");
        if (!dest.isEmpty()) sb.append("📍 ").append(dest).append('\n');
        String st = str(t, "delivery_status");
        if ("PENDING".equals(st)) sb.append("🚚 Masih dalam antrean\n");
        else if ("TERTUNDA".equals(st)) {
            sb.append("⏸ Ditunda");
            if (!str(t, "tertunda_reason").isEmpty()) sb.append(": ").append(str(t, "tertunda_reason"));
            sb.append('\n');
        }
        String done = fmt(str(t, "done_at"));
        if (!done.isEmpty()) {
            sb.append("✅ Selesai ").append(done);
            if (!str(t, "completed_by").isEmpty()) sb.append(" oleh ").append(str(t, "completed_by"));
            sb.append('\n');
        }
        String staff = str(t, "staff"), device = str(t, "device");
        String who = staff.isEmpty() ? device : (device.isEmpty() ? staff : staff + " (" + device + ")");
        if (!who.isEmpty()) sb.append("Oleh: ").append(who).append('\n');
        String note = str(t, "catatan");
        if (!note.isEmpty()) sb.append("📝 ").append(note.length() > 200 ? note.substring(0, 200) + "…" : note);
        return sb.toString().trim();
    }

    private static void addPhoto(AppCompatActivity a, LinearLayout row, String url, String label, int labelColor) {
        if (url.isEmpty()) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, 0, dp(a, 8), 0);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFFE0E0E0);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 110), dp(a, 110)));
        String key = "debt_" + Integer.toHexString(url.hashCode());
        BitmapUtils.loadIntoView(iv, url, key);
        iv.setOnClickListener(v -> openFull(a, url, key));
        box.addView(iv);
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(labelColor);
        box.addView(tv);
        row.addView(box);
    }

    /** Foto layar penuh (ketuk untuk menutup) — berkasnya sudah di-cache oleh thumbnail. */
    private static void openFull(AppCompatActivity a, String url, String key) {
        new Thread(() -> {
            File f = BitmapUtils.downloadToCache(a.getApplicationContext(), url, key + ".img");
            final Bitmap bmp = f != null ? BitmapUtils.decodeForScreen(a, f.getAbsolutePath()) : null;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (bmp == null) {
                    Toast.makeText(a, "Foto tidak dapat dimuat", Toast.LENGTH_SHORT).show();
                    return;
                }
                ImageView iv = new ImageView(a);
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(Color.BLACK);
                Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                d.setContentView(iv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                iv.setOnClickListener(v -> d.dismiss());
                d.show();
            });
        }).start();
    }

    private static TextView text(AppCompatActivity a, String s, int sp, int color, int top) {
        TextView tv = new TextView(a);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setPadding(0, top, 0, 0);
        return tv;
    }

    private static String payLabel(String m) {
        switch (m) {
            case "TUNAI": return "Tunai";
            case "QRIS": return "QRIS";
            case "TRANSFER": return "Transfer";
            case "HUTANG": return "Hutang";
            default: return "";
        }
    }

    private static String fmt(String stamp) {
        if (stamp.isEmpty()) return "";
        long ms = Ts.millis(stamp);
        return ms == Long.MAX_VALUE ? "" : new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("id", "ID")).format(new Date(ms));
    }

    private static String rp(double v) {
        return "Rp " + NumberFormat.getNumberInstance(new Locale("in", "ID")).format(Math.round(v));
    }

    /** optString tanpa "null" untuk JSON null. */
    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return o.optString(k, "").trim();
    }

    private static int dp(AppCompatActivity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
