package com.crowja.damiupos;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 🚦 Status Order native (Admin &amp; SPV) — cermin halaman web /status-order: OPEN/CLOSED penerimaan order hari
 * ini, tutup/buka manual, Kondisi Operasional (info untuk agen AI) dan saklar "tolak order agen AI saat ditutup".
 * Data &amp; aturan seluruhnya di server (/api/admin/order-status); layar ini hanya menampilkan dan memanggil.
 */
public class StatusOrderActivity extends AppCompatActivity {

    private SyncSettings cfg;
    private String staffUuid;
    private SwipeRefreshLayout swipe;
    private LinearLayout content;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DatabaseHelper db = DatabaseHelper.getInstance(this);
        SettingsDao settings = new SettingsDao(db);
        cfg = new SyncSettings(settings);
        long uid = settings.getCurrentUserId();
        User u = uid > 0 ? new UserDao(db).getById(uid) : null;
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (u == null || !(u.isAdmin() || u.isSpv())) {
            Toast.makeText(this, "Status Order hanya untuk Admin dan SPV.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        staffUuid = LocationReporter.currentStaffUuid(this);
        if (staffUuid == null || staffUuid.isEmpty()) {
            Toast.makeText(this, "Akun ini belum tersinkron ke server — sinkronkan data dulu.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(col(R.color.grey_light));
        Toolbar tb = new Toolbar(this);
        tb.setBackgroundColor(com.crowja.damiupos.util.ThemeColors.primary(this));
        tb.setTitleTextColor(0xFFFFFFFF);
        tb.setNavigationIcon(R.drawable.ic_arrow_back);
        tb.setNavigationOnClickListener(v -> finish());
        tb.setTitle("🚦 Status Order");
        root.addView(tb, new LinearLayout.LayoutParams(-1, dp(56)));
        swipe = new SwipeRefreshLayout(this);
        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(content);
        swipe.addView(sv);
        swipe.setOnRefreshListener(this::load);
        root.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        load();
    }

    // ------------------------------------------------------------------ network

    private interface Call { JSONObject run(SyncApi api) throws Exception; }

    private void load() {
        request(api -> api.adminOrderStatus(staffUuid));
    }

    private void request(Call c) {
        if (busy) return;
        busy = true;
        swipe.setRefreshing(true);
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = c.run(new SyncApi(cfg));
            } catch (SyncApi.SyncException se) {
                String m = se.serverMessage();
                err = m != null && !m.isEmpty() ? m : "Gagal (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal memuat — periksa koneksi internet.";
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                swipe.setRefreshing(false);
                if (fe != null) {
                    Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                    if (content.getChildCount() == 0) render(null, fe);
                    return;
                }
                String msg = fr.optString("message", "");
                if (!msg.isEmpty() && !"null".equals(msg)) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                render(fr.optJSONObject("data"), null);
            });
        }).start();
    }

    // ------------------------------------------------------------------ render

    private void render(JSONObject d, String error) {
        content.removeAllViews();
        if (d == null) {
            content.addView(text(error != null ? error : "Tidak ada data.", 15, 0xFF616161, false));
            return;
        }
        boolean open = "OPEN".equalsIgnoreCase(d.optString("order_status"));
        String reason = d.optString("order_status_reason");
        JSONObject cond = d.optJSONObject("ops_condition");

        // Banner kondisi operasional (selain Normal).
        if (cond != null && !cond.optBoolean("is_normal", true)) {
            TextView b = text("Kondisi operasional: " + cond.optString("label")
                    + (str(cond, "note").isEmpty() ? "" : " — " + str(cond, "note"))
                    + " — kembali Normal otomatis saat jam buka berikutnya.", 14, 0xFF1B5E20, false);
            b.setPadding(dp(14), dp(12), dp(14), dp(12));
            b.setBackground(box(0xFFE8F5E9, 0xFF66BB6A));
            content.addView(b, lp(0, 12));
        }

        // Kartu status.
        LinearLayout card = card();
        TextView pill = text(open ? "🟢 OPEN" : "🔴 CLOSED", 26, open ? 0xFF1B5E20 : 0xFFB71C1C, true);
        pill.setPadding(dp(18), dp(8), dp(18), dp(8));
        pill.setBackground(box(open ? 0xFFE8F5E9 : 0xFFFFEBEE, open ? 0xFF66BB6A : 0xFFE57373));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.bottomMargin = dp(12);
        card.addView(pill, pl);
        card.addView(text(reasonLabel(reason), 16, 0xFF212121, true));
        String closes = str(d, "closes_at"), reopens = str(d, "reopens_at"), next = str(d, "next_delivery_at");
        if (open && !closes.isEmpty()) card.addView(text("Tutup order hari ini jam " + time(closes) + " WIB", 14, 0xFF424242, false));
        if (!open && !reopens.isEmpty()) card.addView(text("Buka lagi " + dateTime(reopens) + " WIB", 14, 0xFF424242, false));
        if (!next.isEmpty()) card.addView(text("Order baru dijadwalkan " + dateTime(next) + ".", 14, 0xFF424242, false));
        JSONObject early = d.optJSONObject("early_close");
        if (early != null) {
            StringBuilder sb = new StringBuilder("Ditutup lebih awal");
            if (!str(early, "by_name").isEmpty()) sb.append(" oleh ").append(str(early, "by_name"));
            if (!str(early, "at").isEmpty()) sb.append(" jam ").append(time(str(early, "at")));
            if ("wa_status".equals(str(early, "source"))) sb.append(" (dari Status WA)");
            if (!str(early, "note").isEmpty()) sb.append("\nCatatan: ").append(str(early, "note"));
            card.addView(text(sb.toString(), 13, 0xFF616161, false), lp(8, 0));
        }
        card.addView(text("Order normal: buka " + str(d, "open_time") + " · tutup " + str(d, "close_time")
                + " WIB\nJam normal diubah owner di web: Konfigurasi → Jam Operasional.", 13, 0xFF757575, false), lp(12, 0));
        content.addView(card, lp(0, 12));

        // Tutup / buka manual.
        LinearLayout manual = card();
        manual.addView(text("Tutup / buka manual", 16, 0xFF212121, true));
        if ("early_close".equals(reason)) {
            manual.addView(text("Order hari ini sedang ditutup lebih awal. Buka kembali untuk mengikuti jadwal normal.", 14, 0xFF616161, false));
            manual.addView(button("🔓 Buka Kembali Order Hari Ini", 0xFF2E7D32, v -> confirm("Buka kembali order hari ini?",
                    "Order baru kembali diantar hari ini sesuai jadwal normal.", () -> request(api -> api.adminOrderStatusAction("reopen", new JSONObject(), staffUuid)))));
        } else if (!d.optBoolean("order_open", true)) {
            manual.addView(text("Order hari ini sudah tutup (lewat jam Close Order). Status kembali OPEN otomatis "
                    + (reopens.isEmpty() ? "saat jam buka berikutnya" : dateTime(reopens) + " WIB") + ".", 14, 0xFF616161, false));
        } else {
            manual.addView(text("Tutup penerimaan order HARI INI lebih awal. Order baru (agen AI, halaman pesan, reseller) "
                    + "dijadwalkan untuk besok. Kembali OPEN otomatis saat jam buka besok.", 14, 0xFF616161, false));
            manual.addView(button("🔒 Tutup Order Hari Ini", 0xFFC62828, v -> askClose()));
        }
        content.addView(manual, lp(0, 12));

        // Kondisi operasional.
        LinearLayout ops = card();
        ops.addView(text("Kondisi operasional", 16, 0xFF212121, true));
        ops.addView(text("Info untuk agen AI saat menerima order / menjawab komplain. Tidak menutup order. "
                + "Kembali Normal otomatis tiap jam buka.", 13, 0xFF616161, false));
        String curKey = cond != null ? cond.optString("key", "normal") : "normal";
        String curLabel = cond != null ? cond.optString("label", "Normal") : "Normal";
        ops.addView(text("Sekarang: " + curLabel, 14, 0xFF212121, true), lp(6, 6));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        JSONObject presets = d.optJSONObject("condition_presets");
        if (presets != null) {
            for (Iterator<String> it = presets.keys(); it.hasNext(); ) {
                String k = it.next();
                String label = presets.optString(k);
                boolean on = k.equals(curKey);
                chips.addView(button((on ? "✓ " : "") + label, on ? 0xFF1565C0 : 0xFF78909C,
                        v -> setCondition(k, null)));
            }
        }
        JSONArray saved = d.optJSONArray("condition_saved");
        for (int i = 0; saved != null && i < saved.length(); i++) {
            String label = saved.optString(i);
            boolean on = "custom".equals(curKey) && label.equalsIgnoreCase(curLabel);
            chips.addView(button((on ? "✓ " : "") + label, on ? 0xFF1565C0 : 0xFF90A4AE, v -> setCondition("custom", label)));
        }
        chips.addView(button("✏️ Teks lain…", 0xFF607D8B, v -> askCustomCondition()));
        ops.addView(chips);
        content.addView(ops, lp(0, 12));

        // Saklar agen AI.
        LinearLayout guard = card();
        SwitchCompat sw = new SwitchCompat(this);
        sw.setText("Tolak order HARI INI dari agen AI saat order ditutup");
        sw.setTextSize(14);
        sw.setChecked(d.optBoolean("agent_guard", false));
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (!b.isPressed()) return;
            request(api -> api.adminOrderStatusAction("agent-guard", new JSONObject().put("enabled", checked), staffUuid));
        });
        guard.addView(sw);
        guard.addView(text("Aktif: agen AI ditolak membuat order untuk hari ini selama status CLOSED (pelanggan ditawari besok).",
                12, 0xFF757575, false));
        content.addView(guard, lp(0, 12));
    }

    private void askClose() {
        EditText et = new EditText(this);
        et.setHint("Catatan (opsional), mis. Hujan deras");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("🔒 Tutup order hari ini?")
                .setMessage("Semua order baru hari ini dijadwalkan untuk besok sampai jam buka berikutnya.")
                .setView(wrap)
                .setPositiveButton("Tutup Order", (dd, w) -> {
                    String note = et.getText().toString().trim();
                    request(api -> api.adminOrderStatusAction("close",
                            note.isEmpty() ? new JSONObject() : new JSONObject().put("note", note), staffUuid));
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void askCustomCondition() {
        EditText et = new EditText(this);
        et.setHint("Mis. Listrik padam");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Kondisi operasional")
                .setView(wrap)
                .setPositiveButton("Simpan", (dd, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) setCondition("custom", t);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void setCondition(String key, String label) {
        request(api -> {
            JSONObject b = new JSONObject().put("key", key);
            if (label != null) b.put("label", label);
            return api.adminOrderStatusAction("condition", b, staffUuid);
        });
    }

    private void confirm(String title, String msg, Runnable ok) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Ya", (d, w) -> ok.run()).setNegativeButton("Batal", null).show();
    }

    // ------------------------------------------------------------------ helpers

    private static String reasonLabel(String r) {
        switch (r) {
            case "early_close": return "Ditutup lebih awal hari ini";
            case "before_open": return "Belum jam buka — order tetap untuk hari ini";
            case "after_close": return "Lewat jam Close Order";
            default: return "Jam order normal";
        }
    }

    private static String str(JSONObject o, String k) {
        String v = o.optString(k, "");
        return "null".equals(v) ? "" : v;
    }

    private static Date parse(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String time(String s) {
        Date d = parse(s);
        return d == null ? s : new SimpleDateFormat("HH:mm", Locale.US).format(d);
    }

    private static String dateTime(String s) {
        Date d = parse(s);
        return d == null ? s : new SimpleDateFormat("EEEE, d MMM yyyy HH:mm", new Locale("id", "ID")).format(d);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setOnClickListener(l);
        b.setLayoutParams(lp(8, 0));
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(box(0xFFFFFFFF, 0xFFE0E0E0));
        return c;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private int col(int res) {
        return androidx.core.content.ContextCompat.getColor(this, res);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
