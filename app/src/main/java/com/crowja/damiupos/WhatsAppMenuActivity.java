package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.wa.WaAccountsActivity;
import com.crowja.damiupos.wa.WaAdmin;
import com.crowja.damiupos.wa.WaBroadcastActivity;
import com.crowja.damiupos.wa.WaSchedulesActivity;
import com.crowja.damiupos.wa.WaStatusActivity;

/**
 * 💬 WhatsApp — cermin grup menu web "WhatsApp" untuk Admin: Akun WhatsApp, Broadcast WhatsApp,
 * Broadcast Terjadwal, Status WhatsApp. Tiap submenu layar native sendiri (paket {@code wa}).
 * App light-only: teks #212121/#455A64 di atas putih ≥ 7:1.
 */
public class WhatsAppMenuActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (WaAdmin.gate(this) == null) return;
        setTitle("💬 WhatsApp");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(0xFFECEFF1);
        addItem(box, "📱", "Akun WhatsApp", "Akun WA Bridge cabang: tautkan (QR), default, aktif/nonaktif, konsol pesan.",
                WaAccountsActivity.class);
        addItem(box, "📣", "Broadcast WhatsApp", "Kirim pesan sekali ke banyak pelanggan + riwayat broadcast.",
                WaBroadcastActivity.class);
        addItem(box, "🗓️", "Broadcast Terjadwal", "Broadcast yang dikirim otomatis pada waktu tertentu.",
                WaSchedulesActivity.class);
        addItem(box, "🟢", "Status WhatsApp", "Buat, jadwalkan, dan posting Status WhatsApp.",
                WaStatusActivity.class);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFFECEFF1);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void addItem(LinearLayout box, String emoji, String title, String desc, Class<?> target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        row.setBackground(bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> startActivity(new Intent(this, target)));

        TextView ic = new TextView(this);
        ic.setText(emoji);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        ic.setPadding(0, 0, dp(14), 0);
        row.addView(ic);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFF212121);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(0xFF455A64);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(0xFF455A64);
        chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        row.addView(chev);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        box.addView(row, lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
