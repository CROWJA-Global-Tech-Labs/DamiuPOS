package com.crowja.damiupos;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.db.CustomerGiftDao;
import com.crowja.damiupos.db.DatabaseHelper;

import java.util.List;

/**
 * Riwayat gift SATU pelanggan (native, baca saja): semua gift lintas salinan nomor yang sama,
 * terbaru dulu — belum diberikan, menunggu order Selesai, dan sudah diberikan (kapan, oleh siapa,
 * lewat transaksi atau manual). Data dari tabel customer_gifts lokal (tersinkron branch-wide),
 * jadi bisa dibuka tanpa koneksi. Cermin halaman web "Riwayat Gift" (gifts.history).
 */
public class GiftHistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long customerId = getIntent().getLongExtra("customer_id", -1);
        String name = getIntent().getStringExtra("customer_name");
        setTitle("Riwayat Gift" + (name != null && !name.isEmpty() ? " · " + name : ""));

        List<CustomerGiftDao.Row> rows =
                new CustomerGiftDao(DatabaseHelper.getInstance(this)).listForCustomer(customerId);
        int given = 0;
        for (CustomerGiftDao.Row r : rows) if (r.given()) given++;

        float d = getResources().getDisplayMetrics().density;
        int p16 = (int) (16 * d), p8 = (int) (8 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p16, p16, p16, p16);

        TextView summary = new TextView(this);
        summary.setText(rows.size() + " gift tercatat · " + given + " sudah diberikan");
        summary.setTextSize(14);
        summary.setTextColor(0xFF57534E);
        summary.setPadding(0, 0, 0, p8);
        root.addView(summary);

        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Belum ada gift untuk pelanggan ini.");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(0xFF9CA3AF);
            empty.setPadding(0, p16 * 2, 0, 0);
            root.addView(empty);
        }

        for (CustomerGiftDao.Row r : rows) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(r.given() ? 0xFFF5F5F4 : 0xFFFFFBEB);
            card.setPadding(p16, p8 + 4, p16, p8 + 4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = p8;
            card.setLayoutParams(lp);

            TextView title = new TextView(this);
            title.setText((r.given() ? "✓ " : "🎁 ") + r.qty + " pcs " + (r.itemName == null ? "" : r.itemName));
            title.setTextSize(15);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(0xFF1F2937);
            card.addView(title);

            StringBuilder sb = new StringBuilder();
            if (r.reason != null && !r.reason.isEmpty()) sb.append("Alasan: ").append(r.reason).append('\n');
            sb.append("Dibuat ").append(shortDate(r.createdAt)).append('\n');
            if (r.given()) {
                sb.append("Diberikan ").append(shortDate(r.redeemedAt));
                if (r.redeemedBy != null && !r.redeemedBy.isEmpty()) sb.append(" · ").append(r.redeemedBy);
                sb.append(r.redeemedTrxUuid != null && !r.redeemedTrxUuid.isEmpty()
                        ? "\nLewat transaksi" : "\nDiberikan manual");
            } else if (r.pendingTrxUuid != null && !r.pendingTrxUuid.isEmpty()) {
                sb.append("⏳ Menunggu order Selesai");
            } else {
                sb.append("Belum diberikan");
            }
            TextView body = new TextView(this);
            body.setText(sb);
            body.setTextSize(13);
            body.setTextColor(0xFF57534E);
            card.addView(body);
            root.addView(card);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private static String shortDate(String iso) {
        if (iso == null) return "-";
        String s = iso.replace('T', ' ');
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }
}
