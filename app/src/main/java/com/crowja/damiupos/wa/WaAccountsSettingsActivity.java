package com.crowja.damiupos.wa;

import static com.crowja.damiupos.wa.WaAccountsUi.GREEN_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.GREEN_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.RED_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.RED_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.TEXT;
import static com.crowja.damiupos.wa.WaAccountsUi.TEXT_2;
import static com.crowja.damiupos.wa.WaAccountsUi.nz;
import static com.crowja.damiupos.wa.WaAccountsUi.str;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ⚙️ Akun WhatsApp → Setelan Auto-Send — form yang sama dengan kartu "⚙️ Auto-Send" di halaman web
 * WhatsApp (saklar struk/selesai/diantar/follow-up/perkenalan/kendala/jadwal-ulang/maaf/LID, template +
 * placeholder, akun pengirim, jam kerja proaktif, batas & jeda). Disimpan utuh lewat
 * {@code POST /api/admin/wa/accounts/settings} (aturan & efek sama dengan web). Di bawahnya CRON Job dan
 * template WA struk/perkenalan/follow-up BACA-SAJA — sama seperti web, yang mengubahnya hanya Owner.
 */
public class WaAccountsSettingsActivity extends AppCompatActivity {

    private String staffUuid;
    private ScrollView svForm;
    private LinearLayout formBox;
    private ProgressBar pb;
    private TextView tvState;
    private boolean loading, saving;
    @Nullable
    private JSONObject data;

    // Field form (diisi ulang tiap build()).
    private final Map<String, CheckBox> toggles = new LinkedHashMap<>();
    private final Map<String, EditText> texts = new LinkedHashMap<>();
    private final List<String> senderValues = new ArrayList<>();
    private Spinner spSender;
    private final String[] windowStart = {"08:00"}, windowEnd = {"20:00"};
    private final String[] introSince = {null};
    private Button btSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setContentView(R.layout.activity_wa_accounts_settings);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        svForm = findViewById(R.id.svForm);
        formBox = findViewById(R.id.formBox);
        pb = findViewById(R.id.pbLoading);
        tvState = findViewById(R.id.tvState);
        tvState.setOnClickListener(v -> {
            if (data == null) load();
        });
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🔄 Muat ulang (buang perubahan)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        if (loading || saving) return;
        loading = true;
        pb.setVisibility(View.VISIBLE);
        tvState.setVisibility(View.GONE);
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaGet("accounts", null, staffUuid), r -> {
            loading = false;
            pb.setVisibility(View.GONE);
            data = r;
            build(0);
        }, msg -> {
            loading = false;
            pb.setVisibility(View.GONE);
            if (data == null) {
                svForm.setVisibility(View.GONE);
                tvState.setText("✕ " + msg + "\n\nKetuk untuk coba lagi.");
                tvState.setTextColor(RED_FG);
                tvState.setVisibility(View.VISIBLE);
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------------ form

    private void build(int scrollY) {
        if (data == null) return;
        JSONObject s = data.optJSONObject("settings");
        if (s == null) s = new JSONObject();
        JSONObject meta = data.optJSONObject("settings_meta");
        if (meta == null) meta = new JSONObject();
        JSONObject defaults = meta.optJSONObject("defaults");
        JSONObject placeholders = meta.optJSONObject("placeholders");
        toggles.clear();
        texts.clear();
        formBox.removeAllViews();

        // Kartu 1 — saklar auto-send (urutan & teks sama dengan web)
        LinearLayout c1 = WaAccountsUi.card(this);
        c1.addView(title("⚙️ Auto-Send"));
        toggle(c1, s, "struk_enabled", "Kirim struk otomatis", "Struk WA terkirim sendiri begitu transaksi tersimpan.");
        toggle(c1, s, "selesai_enabled", "Kabari Pesanan Selesai + Foto",
                "Kirim WA berisi foto bukti begitu pesanan selesai diantar, LUNAS, dan kurir memotret bukti serah terima.");
        toggle(c1, s, "diantar_enabled", "Beritahu Sedang Diantar",
                "Kirim WA begitu kurir menekan ▶ Jalankan (status jadi Sedang Diantar).");
        toggle(c1, s, "followup_enabled", "Follow-up otomatis", "Kirim follow-up terjadwal ke pelanggan overdue.");
        LinearLayout pantun = indent();
        toggle(pantun, s, "followup_pantun_enabled", "Mode pantun",
                "Aktif: pesan berupa pantun. Mati: pakai template \"Pesan Follow-up (non-pantun)\" (diatur Owner, lihat bawah).");
        c1.addView(pantun);
        toggle(c1, s, "intro_enabled", "WA Perkenalan otomatis", "Sapa pelanggan baru/promo yang belum dikirimi.");
        LinearLayout since = indent();
        since.addView(WaAccountsUi.label(this, "Hanya pelanggan terdaftar sejak:"));
        introSince[0] = str(s, "intro_since");
        LinearLayout sinceRow = new LinearLayout(this);
        sinceRow.setOrientation(LinearLayout.HORIZONTAL);
        final MaterialButton btSince = outlined(sinceLabel());
        final MaterialButton btClear = outlined("✕ Kosongkan");
        btSince.setOnClickListener(v -> pickSince(btSince));
        btClear.setOnClickListener(v -> {
            introSince[0] = null;
            btSince.setText(sinceLabel());
        });
        sinceRow.addView(btSince);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = WaAccountsUi.dp(this, 8);
        sinceRow.addView(btClear, clp);
        since.addView(sinceRow);
        since.addView(WaAccountsUi.hint(this, "Kosong = semua pelanggan."));
        c1.addView(since);
        toggle(c1, s, "obstacle_enabled", "Info kendala pengiriman otomatis",
                "WA + foto bukti otomatis ke pelanggan terdampak saat kurir lapor kendala.");
        toggle(c1, s, "reschedule_enabled", "WA jadwal ulang otomatis",
                "Kirim WA ke pelanggan begitu pesanannya dijadwalkan ulang (Tunda), dari web maupun HP.");
        toggle(c1, s, "apology_enabled", "WA permintaan maaf otomatis",
                "Order yang masih PENDING lewat ambang umur di bawah diberi tahu WA sekali.");
        toggle(c1, s, "lid_reconcile_enabled", "Rekonsiliasi LID otomatis",
                "Setelah struk/notifikasi/follow-up WA di atas terkirim (termasuk follow-up yang membawa link Pesan Cepat), "
                        + "cocokkan/tambahkan/koreksi otomatis pemetaan nomor HP ↔ LID WhatsApp pelanggan.");
        formBox.addView(c1);

        // Kartu 2 — isi pesan + placeholder
        LinearLayout c2 = WaAccountsUi.card(this);
        c2.addView(title("✉️ Isi pesan otomatis"));
        template(c2, s, defaults, placeholders, "reschedule_template", "Pesan WA jadwal ulang", 2,
                "{alasan} diisi alasan yang dipilih/diketik staf saat menjadwalkan ulang (web maupun HP; \"Lainnya\" → penjelasan "
                        + "yang diketik). Tanpa alasan → \"" + nz(str(meta, "reschedule_reason_fallback")) + "\". Tanpa {alasan} di "
                        + "template, alasan tak pernah disebut — kecuali ada foto bukti (mis. kendaraan bermasalah), yang selalu "
                        + "disertai baris \"Kendala: …\".");
        c2.addView(WaAccountsUi.label(this, "Ambang umur order sebelum diberi tahu (menit, "
                + meta.optInt("apology_threshold_min", 15) + "–" + meta.optInt("apology_threshold_max", 1440) + ")"));
        EditText etThr = WaAccountsUi.number(this, s.has("apology_threshold_minutes") ? String.valueOf(s.optInt("apology_threshold_minutes")) : null,
                "mis. 120", 4);
        texts.put("apology_threshold_minutes", etThr);
        c2.addView(etThr);
        c2.addView(WaAccountsUi.hint(this, "Dihitung sejak order masuk antrean aktif. Mis. 120 = order yang sudah 2 jam PENDING diberi tahu."));
        template(c2, s, defaults, placeholders, "apology_template", "Template pesan permintaan maaf", 3, null);
        template(c2, s, defaults, placeholders, "selesai_template",
                "Isi pesan \"Kabari Pesanan Selesai + Foto\" (foto bukti dilampirkan, teks ini jadi keterangannya)", 2, null);
        template(c2, s, defaults, placeholders, "diantar_template", "Isi pesan \"Beritahu Sedang Diantar\"", 2,
                "{link_lacak} kosong bila tautan lacak belum ada.");
        template(c2, s, defaults, placeholders, "obstacle_template", "Template pesan kendala pengiriman", 3,
                "Placeholder otomatis terisi dari laporan kendala.");
        formBox.addView(c2);

        // Kartu 3 — pengirim, jam kerja, batas & jeda
        LinearLayout c3 = WaAccountsUi.card(this);
        c3.addView(title("📤 Pengiriman"));
        c3.addView(WaAccountsUi.label(this, "Akun pengirim auto-send"));
        senderValues.clear();
        List<String> senderNames = new ArrayList<>();
        senderValues.add("");
        senderNames.add("— pakai akun default cabang —");
        senderValues.add("auto:device");
        senderNames.add("🤖 Otomatis berdasarkan perangkat yang ditugaskan");
        JSONArray accs = data.optJSONArray("accounts");
        for (int i = 0; accs != null && i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a == null || str(a, "bridge_account") == null) continue;
            senderValues.add(str(a, "bridge_account"));
            senderNames.add(nz(str(a, "label")) + " (" + str(a, "bridge_account") + ")");
        }
        String curSender = nz(str(s, "sender_account"));
        int sel = senderValues.indexOf(curSender);
        if (sel < 0) {   // akun yang tersimpan sudah tak dipetakan — tetap tampil supaya tak terganti diam-diam
            senderValues.add(curSender);
            senderNames.add(curSender + " (tidak dipetakan)");
            sel = senderValues.size() - 1;
        }
        spSender = new Spinner(this);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, senderNames);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSender.setAdapter(sa);
        spSender.setSelection(sel);
        c3.addView(spSender);
        final TextView deviceHint = WaAccountsUi.hint(this, "Mode \"otomatis\" mengirim dari akun milik perangkat yang menangani "
                + "order/pelanggan itu (atur pemetaan perangkat di ⋯ Ubah tiap akun) — jatuh ke akun default kalau perangkatnya belum "
                + "dipetakan ke akun manapun.");
        c3.addView(deviceHint);
        WaAccountsUi.gone(deviceHint, !"auto:device".equals(curSender));
        spSender.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                WaAccountsUi.gone(deviceHint, !"auto:device".equals(senderValues.get(pos)));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        c3.addView(WaAccountsUi.hint(this, "Kalau akun yang terpilih (dari mode manapun) sedang offline, otomatis dialihkan ke akun "
                + "cadangan yang online — urutannya diatur lewat \"Urutan cadangan\" di ⋯ Ubah tiap akun."));
        c3.addView(WaAccountsUi.hint(this, "Struk & info pengiriman ke pelanggan yang sudah pernah chat 2 arah selalu keluar dari akun "
                + "yang PALING BARU menerima pesan dari pelanggan itu (akun perangkat yang ditugaskan hanya didahulukan bila waktunya "
                + "sama persis)."));

        c3.addView(WaAccountsUi.label(this, "Jam kerja proaktif (mulai–selesai)"));
        windowStart[0] = nz(str(s, "window_start")).isEmpty() ? "08:00" : str(s, "window_start");
        windowEnd[0] = nz(str(s, "window_end")).isEmpty() ? "20:00" : str(s, "window_end");
        LinearLayout win = new LinearLayout(this);
        win.setOrientation(LinearLayout.HORIZONTAL);
        final MaterialButton btStart = outlined("🕗 " + windowStart[0]);
        final MaterialButton btEnd = outlined("🕗 " + windowEnd[0]);
        btStart.setOnClickListener(v -> pickTime(windowStart, btStart));
        btEnd.setOnClickListener(v -> pickTime(windowEnd, btEnd));
        win.addView(btStart);
        TextView dash = WaAccountsUi.text(this, "  –  ", 14f, TEXT_2, false);
        win.addView(dash);
        win.addView(btEnd);
        win.setGravity(android.view.Gravity.CENTER_VERTICAL);
        c3.addView(win);

        numberField(c3, s, "proaktif_daily_limit", "Batas proaktif per akun / hari", 4, null);
        numberField(c3, s, "followup_gap_days", "Jeda antar follow-up ke pelanggan sama (hari)", 3, null);
        numberField(c3, s, "send_delay_seconds", "Jeda antar pesan WA (detik)", 4,
                "Jarak minimal antar pesan WA berurutan ke akun Bridge yang sama — berlaku untuk struk, WA Perkenalan, follow-up, "
                        + "jadwal-ulang, info kendala, dan \"sedang diantar\". Isi 0 untuk kirim langsung tanpa jeda.");
        TextView quotaNote = WaAccountsUi.hint(this, "Kuota proaktif Bridge (PROACTIVE_PER_HOUR/DAY) tetap berlaku di luar batas di atas — "
                + "batas di sini cap tambahan sisi DAMIU POS, dievaluasi tiap siklus scheduler.");
        quotaNote.setPadding(0, WaAccountsUi.dp(this, 10), 0, 0);
        c3.addView(quotaNote);

        btSave = new MaterialButton(this);
        btSave.setText("Simpan Auto-Send");
        btSave.setAllCaps(false);
        btSave.setTextColor(0xFFFFFFFF);
        btSave.setBackgroundTintList(ColorStateList.valueOf(0xFF1565C0));   // 5,8:1 dengan teks putih
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = WaAccountsUi.dp(this, 12);
        btSave.setOnClickListener(v -> save());
        c3.addView(btSave, slp);
        formBox.addView(c3);

        buildCron();
        buildTemplates();

        svForm.setVisibility(View.VISIBLE);
        tvState.setVisibility(View.GONE);
        if (scrollY > 0) svForm.post(() -> svForm.scrollTo(0, scrollY));
    }

    /** CRON Job — baca-saja (interval hanya bisa diubah Owner di web). Ambang basi dihitung server. */
    private void buildCron() {
        JSONObject c = data != null ? data.optJSONObject("cron") : null;
        if (c == null) return;
        LinearLayout card = WaAccountsUi.card(this);
        card.addView(title("⏱️ CRON Job"));
        card.addView(WaAccountsUi.hint(this, "Follow-up & WA Perkenalan otomatis butuh scheduler Laravel dipanggil server tiap menit "
                + "(Cron Jobs di hPanel/cPanel)."));
        boolean tickStale = c.optBoolean("scheduler_stale", true);
        TextView tick = WaAccountsUi.text(this, "Scheduler (cron hosting) terakhir berdetak: " + nz(str(c, "scheduler_tick_ago")),
                13f, tickStale ? RED_FG : GREEN_FG, true);
        tick.setBackground(WaAccountsUi.rounded(this, tickStale ? RED_BG : GREEN_BG, 0, 8));
        int p = WaAccountsUi.dp(this, 8);
        tick.setPadding(p, p, p, p);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = WaAccountsUi.dp(this, 8);
        card.addView(tick, tlp);
        card.addView(WaAccountsUi.hint(this, tickStale
                ? "Detak basi → cron hosting tidak memanggil schedule:run (baris cron salah/tidak ada, atau versi PHP-nya tidak cocok)."
                : "Detak segar → cron hosting sehat. Bila \"terakhir jalan\" job di bawah tetap merah, kunci job macet."));
        cronRow(card, "wa:auto-followups terakhir jalan", str(c, "followups_last_run_ago"), c.optBoolean("followups_stale", true));
        cronRow(card, "wa:auto-intros terakhir jalan", str(c, "intros_last_run_ago"), c.optBoolean("intros_stale", true));
        cronRow(card, "Interval wa:auto-followups", str(c, "followups_interval"), null);
        cronRow(card, "Interval wa:auto-intros", str(c, "intros_interval"), null);
        card.addView(WaAccountsUi.hint(this, "🔒 Interval berlaku global (semua cabang) dan hanya bisa diubah Owner di dashboard web."));
        formBox.addView(card);
    }

    private void cronRow(LinearLayout card, String label, @Nullable String value, @Nullable Boolean stale) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, WaAccountsUi.dp(this, 6), 0, 0);
        TextView l = WaAccountsUi.text(this, label, 13f, TEXT_2, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int color = stale == null ? TEXT : stale ? RED_FG : GREEN_FG;
        row.addView(WaAccountsUi.text(this, nz(value), 13f, color, true));
        card.addView(row);
    }

    /** Template WA struk/perkenalan/follow-up — baca-saja seperti web untuk non-owner. */
    private void buildTemplates() {
        JSONArray groups = data != null ? data.optJSONArray("templates") : null;
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            LinearLayout card = WaAccountsUi.card(this);
            card.addView(title(nz(str(g, "group"))));
            card.addView(WaAccountsUi.hint(this, "🔒 Hanya owner yang dapat mengubah pengaturan ini."));
            JSONArray items = g.optJSONArray("items");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it == null) continue;
                card.addView(WaAccountsUi.label(this, nz(str(it, "label"))));
                TextView v = WaAccountsUi.text(this, nz(str(it, "value")), 14f, TEXT, false);
                v.setTextIsSelectable(true);
                card.addView(v);
            }
            formBox.addView(card);
        }
    }

    // ------------------------------------------------------------------ simpan

    private void save() {
        if (saving || data == null) return;
        JSONObject cur = data.optJSONObject("settings");
        JSONObject b = new JSONObject();
        try {
            for (Map.Entry<String, CheckBox> e : toggles.entrySet()) b.put(e.getKey(), e.getValue().isChecked());
            for (String k : new String[]{"reschedule_template", "apology_template", "selesai_template", "diantar_template", "obstacle_template"}) {
                EditText et = texts.get(k);
                b.put(k, et != null ? et.getText().toString() : JSONObject.NULL);
            }
            for (String k : new String[]{"apology_threshold_minutes", "proaktif_daily_limit", "followup_gap_days", "send_delay_seconds"}) {
                EditText et = texts.get(k);
                b.put(k, et != null ? WaAccountsUi.intOrNull(et) : JSONObject.NULL);
            }
            String sender = senderValues.get(Math.max(0, spSender.getSelectedItemPosition()));
            b.put("sender_account", sender.isEmpty() ? JSONObject.NULL : sender);
            b.put("window_start", windowStart[0]);
            b.put("window_end", windowEnd[0]);
            b.put("intro_since", introSince[0] != null ? introSince[0] : JSONObject.NULL);
            // Tak ada field-nya di web maupun di sini → kirim nilai tersimpan supaya tak ter-reset.
            if (cur != null && cur.has("intro_gap_days")) b.put("intro_gap_days", cur.optInt("intro_gap_days"));
        } catch (Exception ignored) {
        }
        saving = true;
        btSave.setEnabled(false);
        btSave.setText("Menyimpan…");
        final int y = svForm.getScrollY();
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaPost("accounts/settings", b, staffUuid), r -> {
            saving = false;
            Toast.makeText(this, WaAdmin.message(r, "Setelan auto-send WhatsApp disimpan."), Toast.LENGTH_LONG).show();
            JSONObject fresh = r.optJSONObject("settings");
            if (fresh != null && data != null) {
                try {
                    data.put("settings", fresh);
                } catch (Exception ignored) {
                }
            }
            build(y);
        }, msg -> {
            saving = false;
            btSave.setEnabled(true);
            btSave.setText("Simpan Auto-Send");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------------ util form

    private TextView title(String t) {
        TextView v = WaAccountsUi.text(this, t, 16f, TEXT, true);
        v.setPadding(0, 0, 0, WaAccountsUi.dp(this, 4));
        return v;
    }

    private LinearLayout indent() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(WaAccountsUi.dp(this, 32), 0, 0, WaAccountsUi.dp(this, 4));
        return l;
    }

    private void toggle(LinearLayout box, JSONObject s, String key, String label, String desc) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setTextSize(14f);
        cb.setChecked(s.optBoolean(key));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = WaAccountsUi.dp(this, 6);
        box.addView(cb, lp);
        TextView d = WaAccountsUi.hint(this, desc);
        d.setPadding(WaAccountsUi.dp(this, 32), 0, 0, 0);
        box.addView(d);
        toggles.put(key, cb);
    }

    private void template(LinearLayout box, JSONObject s, @Nullable JSONObject defaults, @Nullable JSONObject placeholders,
                          String key, String label, int lines, @Nullable String note) {
        box.addView(WaAccountsUi.label(this, label));
        final EditText et = WaAccountsUi.multiline(this, str(s, key), nz(str(defaults, key)), 1000, lines);
        texts.put(key, et);
        box.addView(et);
        JSONArray ph = placeholders != null ? placeholders.optJSONArray(key) : null;
        if (ph != null && ph.length() > 0) {
            box.addView(WaAccountsUi.hint(this, "Sisipkan placeholder:"));
            ChipGroup cg = new ChipGroup(this);
            for (int i = 0; i < ph.length(); i++) {
                JSONObject p = ph.optJSONObject(i);
                final String token = str(p, "token");
                if (token == null) continue;
                Chip chip = new Chip(this);
                chip.setText(nz(str(p, "label")) + "  " + token);
                chip.setTextColor(TEXT);
                chip.setChipBackgroundColor(ColorStateList.valueOf(0xFFE3E8EC));   // #212121 di atasnya ≈ 13:1
                chip.setTextSize(12f);
                chip.setOnClickListener(v -> insertToken(et, token));
                cg.addView(chip);
            }
            box.addView(cg);
        }
        if (note != null) box.addView(WaAccountsUi.hint(this, note));
    }

    /** Sisipkan token di posisi kursor; belum pernah difokus → di akhir teks (sama dengan web). */
    private static void insertToken(EditText et, String token) {
        int pos = et.hasFocus() ? et.getSelectionStart() : -1;
        if (pos < 0 || pos > et.length()) pos = et.length();
        et.getText().insert(pos, token);
        et.requestFocus();
        et.setSelection(Math.min(et.length(), pos + token.length()));
    }

    private void numberField(LinearLayout box, JSONObject s, String key, String label, int maxLen, @Nullable String note) {
        box.addView(WaAccountsUi.label(this, label));
        EditText et = WaAccountsUi.number(this, s.has(key) && !s.isNull(key) ? String.valueOf(s.optInt(key)) : null, "", maxLen);
        texts.put(key, et);
        box.addView(et);
        if (note != null) box.addView(WaAccountsUi.hint(this, note));
    }

    private MaterialButton outlined(String text) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFF0D47A1);
        b.setStrokeColor(ColorStateList.valueOf(0xFF90A4AE));
        return b;
    }

    private String sinceLabel() {
        if (introSince[0] == null) return "📅 Semua pelanggan";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            return "📅 " + new SimpleDateFormat("d MMM yyyy HH:mm", new Locale("id", "ID")).format(in.parse(introSince[0]));
        } catch (Exception e) {
            return "📅 " + introSince[0];
        }
    }

    private void pickSince(MaterialButton bt) {
        final Calendar c = Calendar.getInstance();
        if (introSince[0] != null) {
            try {
                c.setTime(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(introSince[0]));
            } catch (Exception ignored) {
            }
        }
        new DatePickerDialog(this, (v, y, m, d) -> new TimePickerDialog(this, (tp, hh, mm) -> {
            introSince[0] = String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", y, m + 1, d, hh, mm);
            bt.setText(sinceLabel());
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(String[] target, MaterialButton bt) {
        int h = 8, mi = 0;
        try {
            String[] hm = target[0].split(":");
            h = Integer.parseInt(hm[0]);
            mi = Integer.parseInt(hm[1]);
        } catch (Exception ignored) {
        }
        new TimePickerDialog(this, (tp, hh, mm) -> {
            target[0] = String.format(Locale.US, "%02d:%02d", hh, mm);
            bt.setText("🕗 " + target[0]);
        }, h, mi, true).show();
    }
}
