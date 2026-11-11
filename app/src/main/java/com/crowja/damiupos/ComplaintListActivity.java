package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * 😠 Daftar komplain pesanan untuk perangkat ini. Komplain AKTIF = pesanan yang dikomplain pelanggan dan BELUM
 * diselesaikan (PENDING/Tertunda); komplain SELESAI = pesanan terkait sudah diselesaikan. Jumlah aktif = badge
 * menu Komplain di dashboard ({@link TransactionDao#countActiveComplaints}). Ketuk kartu → chat komplain pesanan itu.
 */
public class ComplaintListActivity extends AppCompatActivity {

    private static final int C_TEXT = 0xFF212121;
    private static final int C_MUTED = 0xFF616161;
    private static final int C_RED = 0xFFB71C1C;
    private static final int C_GREEN = 0xFF1B5E20;

    private TransactionDao dao;
    private LinearLayout box;
    private TextView summary;
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("😠 Komplain Pesanan");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        dao = new TransactionDao(DatabaseHelper.getInstance(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);
        summary = new TextView(this);
        summary.setTextSize(14f);
        summary.setTypeface(null, Typeface.BOLD);
        summary.setPadding(dp(16), dp(12), dp(16), dp(6));
        root.addView(summary);
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(16));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void render() {
        box.removeAllViews();
        List<Transaction> all = dao.getComplaintOrders(50);
        int active = 0;
        for (Transaction t : all) if (TransactionDao.isComplaintActive(t)) active++;
        summary.setText(active + " aktif · " + (all.size() - active) + " selesai");
        summary.setTextColor(active > 0 ? C_RED : C_GREEN);
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Belum ada komplain pesanan di perangkat ini.");
            empty.setTextColor(C_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(24));
            box.addView(empty);
            return;
        }
        boolean headedResolved = false;
        boolean headedActive = false;
        for (Transaction t : all) {
            boolean act = TransactionDao.isComplaintActive(t);
            if (act && !headedActive) {
                box.addView(header("⏳ Aktif — pesanan belum selesai"));
                headedActive = true;
            }
            if (!act && !headedResolved) {
                box.addView(header("✅ Selesai — pesanan sudah diselesaikan"));
                headedResolved = true;
            }
            box.addView(card(t, act));
        }
    }

    private TextView header(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextColor(C_TEXT);
        h.setTextSize(13f);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(dp(4), dp(12), dp(4), dp(4));
        return h;
    }

    private View card(final Transaction t, boolean active) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(active ? 2 : 1), active ? C_RED : 0xFFBDBDBD);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText("😠 " + (t.getCustomerName() != null && !t.getCustomerName().isEmpty() ? t.getCustomerName() : "Pelanggan"));
        name.setTextColor(C_TEXT);
        name.setTextSize(16f);
        name.setTypeface(null, Typeface.BOLD);
        c.addView(name);

        StringBuilder items = new StringBuilder();
        if (t.getItems() != null) {
            for (TransactionItem it : t.getItems()) {
                if (it == null) continue;
                if (items.length() > 0) items.append(", ");
                items.append(it.productName != null ? it.productName : "Produk").append(" ×").append(it.jumlah);
            }
        }
        if (items.length() == 0) items.append(t.getJumlahGalon()).append(" galon");
        TextView meta = new TextView(this);
        meta.setText(items + " · Rp " + nf.format(Math.round(t.getTotalHarga()))
                + (t.getReceiptNo() != null && !t.getReceiptNo().isEmpty() ? "\n🧾 " + t.getReceiptNo() : "")
                + "\n🕒 Komplain: " + shortTime(t.getComplainedAt()));
        meta.setTextColor(C_MUTED);
        meta.setTextSize(13f);
        meta.setPadding(0, dp(4), 0, dp(4));
        c.addView(meta);

        TextView status = new TextView(this);
        status.setText(active ? "⏳ Aktif — selesaikan pesanan ini untuk menutup komplain"
                : "✅ Selesai — pesanan sudah diselesaikan");
        status.setTextColor(active ? C_RED : C_GREEN);
        status.setTextSize(13f);
        status.setTypeface(null, Typeface.BOLD);
        c.addView(status);

        TextView open = new TextView(this);
        open.setText("💬 Ketuk untuk membuka chat komplain");
        open.setTextColor(0xFF1565C0);
        open.setTextSize(12f);
        open.setPadding(0, dp(4), 0, 0);
        c.addView(open);

        c.setOnClickListener(v -> {
            String uuid = dao.getSyncUuidById(t.getId());
            if (uuid == null || uuid.isEmpty()) {
                Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ChatLogActivity.class)
                    .putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, uuid)
                    .putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, t.getCustomerName() != null ? t.getCustomerName() : ""));
        });
        return c;
    }

    /** "2026-11-11T09:30:00Z" / "2026-11-11 09:30:00" → "11/11 09:30" (apa adanya bila tak dikenal). */
    private static String shortTime(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace('T', ' ');
        if (s.length() >= 16 && s.charAt(4) == '-') {
            return s.substring(8, 10) + "/" + s.substring(5, 7) + " " + s.substring(11, 16);
        }
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
