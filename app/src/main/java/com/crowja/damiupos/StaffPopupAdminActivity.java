package com.crowja.damiupos;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 📢 POPUP LOGIN STAF (Admin, native) — cermin halaman web "Popup Login Staf": pesan yang muncul di HP saat staf
 * login / logout, di hari & perangkat terpilih; popup baru bisa ditutup setelah "Saya mengerti" dicentang
 * ({@link com.crowja.damiupos.util.StaffPopup}). Daftar (svList) + form ubah (svDetail) di layout Pengaturan Perangkat.
 */
public class StaffPopupAdminActivity extends AppCompatActivity {

    private SyncApi api;
    private String staffUuid;
    private TextView tvStatus;
    private ScrollView svList, svDetail;
    private LinearLayout listBox, detailBox;
    private JSONArray devices = new JSONArray(), days = new JSONArray();
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_admin);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("📢 Popup Login Staf");
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server (Pengaturan → Sinkronisasi).", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!UserDao.isCurrentUserAdmin(this)) {
            Toast.makeText(this, "Popup Login Staf hanya untuk Admin.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        staffUuid = LocationReporter.currentStaffUuid(this);
        if (staffUuid == null || staffUuid.isEmpty()) {
            Toast.makeText(this, "Akun Admin ini belum tersinkron ke server — sinkronkan data dulu.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        api = new SyncApi(cfg);
        tvStatus = findViewById(R.id.tvStatus);
        svList = findViewById(R.id.svList);
        svDetail = findViewById(R.id.svDetail);
        listBox = findViewById(R.id.listBox);
        detailBox = findViewById(R.id.detailBox);
        load();
    }

    private void goBack() {
        if (svDetail.getVisibility() == View.VISIBLE) {
            svDetail.setVisibility(View.GONE);
            svList.setVisibility(View.VISIBLE);
            return;
        }
        finish();
    }

    // ------------------------------------------------------------------ daftar

    private void load() {
        tvStatus.setText("Memuat…");
        new Thread(() -> {
            JSONObject r = null;
            Exception err = null;
            try {
                r = api.adminStaffPopups(staffUuid);
            } catch (Exception e) {
                err = e;
            }
            final JSONObject fr = r;
            final Exception fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    tvStatus.setText(errorText(fe) + " Ketuk untuk mencoba lagi.");
                    tvStatus.setOnClickListener(v -> load());
                    return;
                }
                tvStatus.setOnClickListener(null);
                devices = fr.optJSONArray("devices") != null ? fr.optJSONArray("devices") : new JSONArray();
                days = fr.optJSONArray("days") != null ? fr.optJSONArray("days") : new JSONArray();
                renderList(fr.optJSONArray("messages"));
            });
        }).start();
    }

    private void renderList(@Nullable JSONArray msgs) {
        int n = msgs != null ? msgs.length() : 0;
        tvStatus.setText(n == 0 ? "Belum ada pesan popup." : n + " pesan · muncul saat staf login/logout di HP sasaran.");
        listBox.removeAllViews();
        Button add = button("➕ Pesan baru", true);
        add.setOnClickListener(v -> openEditor(null));
        listBox.addView(add, full(dp(4)));
        for (int i = 0; i < n; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            LinearLayout card = card();
            TextView t = text((m.optBoolean("is_active") ? "" : "⏸ ") + m.optString("title"), 16, 0xFF263238, true);
            card.addView(t);
            card.addView(text(m.optString("summary"), 12, 0xFF607D8B, false));
            String body = m.optString("body");
            card.addView(text(body.length() > 160 ? body.substring(0, 160) + "…" : body, 13, 0xFF455A64, false));
            if (!m.optBoolean("is_active")) card.setAlpha(0.6f);
            card.setOnClickListener(v -> openEditor(m));
            listBox.addView(card, full(dp(10)));
        }
        svDetail.setVisibility(View.GONE);
        svList.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ form

    private void openEditor(@Nullable JSONObject m) {
        detailBox.removeAllViews();
        final long id = m != null ? m.optLong("id") : 0;
        detailBox.addView(text(id > 0 ? "✏️ Ubah pesan" : "➕ Pesan baru", 18, 0xFF263238, true));

        detailBox.addView(text("Judul", 13, 0xFF607D8B, false), full(dp(12)));
        EditText etTitle = input(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 1);
        etTitle.setText(m != null ? m.optString("title") : "");
        detailBox.addView(etTitle, full(0));

        detailBox.addView(text("Pesan", 13, 0xFF607D8B, false), full(dp(10)));
        EditText etBody = input(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 4);
        etBody.setText(m != null ? m.optString("body") : "");
        detailBox.addView(etBody, full(0));

        detailBox.addView(text("Tampilkan saat", 13, 0xFF607D8B, false), full(dp(12)));
        CheckBox cbLogin = check("Login", m == null || m.optBoolean("on_login"));
        CheckBox cbLogout = check("Logout", m != null && m.optBoolean("on_logout"));
        detailBox.addView(cbLogin);
        detailBox.addView(cbLogout);

        detailBox.addView(text("Hari (tak dicentang = setiap hari)", 13, 0xFF607D8B, false), full(dp(12)));
        JSONArray chosenDays = m != null ? m.optJSONArray("days") : null;
        Map<Integer, CheckBox> dayBoxes = new LinkedHashMap<>();
        LinearLayout dayRow = new LinearLayout(this);
        dayRow.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) continue;
            int day = d.optInt("day");
            CheckBox cb = check(d.optString("label"), contains(chosenDays, day));
            dayBoxes.put(day, cb);
            dayRow.addView(cb);
        }
        detailBox.addView(dayRow);

        detailBox.addView(text("Perangkat (tak dicentang = semua perangkat, termasuk HP baru)", 13, 0xFF607D8B, false), full(dp(12)));
        JSONArray chosenDevs = m != null ? m.optJSONArray("device_uuids") : null;
        Map<String, CheckBox> devBoxes = new LinkedHashMap<>();
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String uuid = d.optString("uuid");
            CheckBox cb = check("📱 " + d.optString("name") + (d.optBoolean("is_me") ? " (HP ini)" : ""), contains(chosenDevs, uuid));
            devBoxes.put(uuid, cb);
            detailBox.addView(cb);
        }

        CheckBox cbActive = check("Aktif", m == null || m.optBoolean("is_active", true));
        detailBox.addView(cbActive, full(dp(12)));

        Button save = button("💾 Simpan & kirim ke HP", true);
        save.setOnClickListener(v -> {
            try {
                JSONObject b = new JSONObject();
                b.put("title", etTitle.getText().toString().trim());
                b.put("body", etBody.getText().toString().trim());
                b.put("on_login", cbLogin.isChecked());
                b.put("on_logout", cbLogout.isChecked());
                JSONArray ds = new JSONArray();
                for (Map.Entry<Integer, CheckBox> e : dayBoxes.entrySet()) if (e.getValue().isChecked()) ds.put(e.getKey());
                b.put("days", ds);
                JSONArray dv = new JSONArray();
                for (Map.Entry<String, CheckBox> e : devBoxes.entrySet()) if (e.getValue().isChecked()) dv.put(e.getKey());
                b.put("device_uuids", dv);
                b.put("is_active", cbActive.isChecked());
                if (!cbLogin.isChecked() && !cbLogout.isChecked()) {
                    alert("Centang minimal satu: tampil saat login atau saat logout.");
                    return;
                }
                submit(() -> api.adminStaffPopupSave(id, b, staffUuid));
            } catch (Exception e) {
                alert("Isian tak bisa disiapkan.");
            }
        });
        detailBox.addView(save, full(dp(16)));

        Button preview = button("👁 Pratinjau popup", false);
        preview.setOnClickListener(v -> previewPopup(etTitle.getText().toString(), etBody.getText().toString()));
        detailBox.addView(preview, full(dp(8)));

        if (id > 0) {
            Button del = button("🗑 Hapus pesan", false);
            del.setTextColor(0xFFC62828);
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Hapus pesan?")
                    .setMessage("Pesan \"" + m.optString("title") + "\" tidak akan muncul lagi di HP.")
                    .setPositiveButton("Hapus", (d, w) -> submit(() -> api.adminStaffPopupDelete(id, staffUuid)))
                    .setNegativeButton("Batal", null).show());
            detailBox.addView(del, full(dp(8)));
        }
        svList.setVisibility(View.GONE);
        svDetail.setVisibility(View.VISIBLE);
        svDetail.scrollTo(0, 0);
    }

    /** Pratinjau tampilan popup (tanpa menyimpan) — sama persis dengan yang dilihat staf. */
    private void previewPopup(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text(body, 16, 0xFF263238, false));
        CheckBox ok = check("Saya mengerti", false);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(ok, full(dp(12)));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("📢 " + title).setView(box).setCancelable(false)
                .setPositiveButton("Tutup", null).create();
        dlg.setOnShowListener(d -> {
            Button btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            btn.setEnabled(false);
            ok.setOnCheckedChangeListener((b, on) -> btn.setEnabled(on));
        });
        dlg.show();
    }

    private interface Call {
        JSONObject run() throws Exception;
    }

    private void submit(Call call) {
        if (busy) return;
        busy = true;
        tvStatus.setText("Menyimpan…");
        new Thread(() -> {
            JSONObject r = null;
            Exception err = null;
            try {
                r = call.run();
            } catch (Exception e) {
                err = e;
            }
            final JSONObject fr = r;
            final Exception fe = err;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    tvStatus.setText("");
                    alert(errorText(fe));
                    return;
                }
                String msg = fr.optString("message", "Disimpan.");
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                load();
            });
        }).start();
    }

    // ------------------------------------------------------------------ pembantu

    private static String errorText(@Nullable Exception e) {
        if (e instanceof SyncApi.SyncException) {
            String m = ((SyncApi.SyncException) e).serverMessage();
            return m != null ? m : "Gagal (kode " + ((SyncApi.SyncException) e).code + ").";
        }
        return "Gagal — periksa koneksi internet.";
    }

    private static boolean contains(@Nullable JSONArray a, Object v) {
        for (int i = 0; a != null && i < a.length(); i++) {
            Object x = a.opt(i);
            if (x != null && String.valueOf(x).equals(String.valueOf(v))) return true;
        }
        return false;
    }

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams full(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText input(int type, int lines) {
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setMinLines(lines);
        e.setTextSize(15);
        return e;
    }

    private CheckBox check(String label, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(15);
        cb.setChecked(on);
        return cb;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        if (primary) {
            b.setTextColor(0xFFFFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(com.crowja.damiupos.util.ThemeColors.primary(this));
            bg.setCornerRadius(dp(10));
            b.setBackground(bg);
        }
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFCFD8DC);
        c.setBackground(bg);
        return c;
    }
}
