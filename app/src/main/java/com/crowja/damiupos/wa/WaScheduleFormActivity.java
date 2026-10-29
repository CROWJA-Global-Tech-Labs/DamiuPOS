package com.crowja.damiupos.wa;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Form Broadcast Terjadwal (buat / ubah) — cermin form web /wa-schedules/create|edit: nama, isi pesan ({nama} +
 * pratinjau), waktu (sekali / hari tertentu tiap minggu / tiap N hari, pemilih tanggal & jam), dan penerima lewat
 * 🔍 cari, 📍 massal per domisili, 🗺️ peta (titik, kotak, batas wilayah, gambar wilayah —
 * {@link WaScheduleMapActivity}) dan 🚚 Aturan Trip; tiap wilayah bisa dijadikan 🔄 wilayah otomatis. Validasi akhir
 * oleh server ({@code POST /api/admin/wa/schedules[/{id}/update]}), pesannya ditampilkan apa adanya.
 */
public class WaScheduleFormActivity extends AppCompatActivity {

    static final String EXTRA_ID = "wa_schedule_id";
    private static final String[] DAY_NAMES = {"Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu"};
    private static final int MAX_RECIPIENTS = 5000;

    private String staffUuid;
    private long id;
    private WaScheduleDraft draft;
    private long formLoadedAt;
    private String timezone = "Asia/Jakarta";
    @Nullable private String sendAt;
    @Nullable private String repeatTime;
    private boolean loaded, saving, filling, textChanged;
    @Nullable private JSONArray areasCache, tripsCache;
    @Nullable private AlertDialog confirmDialog;
    private final CheckBox[] dayBoxes = new CheckBox[7];
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Toolbar toolbar;
    private EditText etName, etMessage, etEvery;
    private TextView tvPreview, tvSendAtLabel, tvTz, tvRepeatHint, tvSelCount, tvAreaNote, tvAutoTitle, tvLoading;
    private RadioGroup rgRepeat;
    private View boxSendAt, boxInterval, boxWeekdays, boxAuto;
    private Button btnSendAt, btnRepeatTime, btnRecipients, btnSchedule, btnDraft;
    private CheckBox cbAuto;
    private ChipGroup cgAuto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        id = getIntent().getLongExtra(EXTRA_ID, 0);
        draft = new WaScheduleDraft();
        WaScheduleDraft.current = draft;
        setContentView(R.layout.activity_wa_schedule_form);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(id > 0 ? "Ubah Broadcast" : "Broadcast Baru");
        toolbar.setNavigationOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });
        bindViews();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (draft == null) return;   // gerbang menolak (activity sudah ditutup)
        // Form yang TAMPIL pemilik draf bersama — form kedua (mis. "Buat Broadcast" diketuk dua kali) yang ditutup
        // tak boleh membuat peta form ini kehilangan drafnya.
        WaScheduleDraft.current = draft;
        draft.onChange = this::renderSel;   // hasil muat wilayah dari peta yang tiba belakangan
        if (loaded) {
            renderSel();   // kembali dari peta: draf mungkin berubah
            for (JSONObject a : draft.unloadedAutoAreas()) loadArea(a, false, WaScheduleDraft.areaLabel(a));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (draft != null) draft.onChange = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (draft != null && WaScheduleDraft.current == draft) WaScheduleDraft.current = null;
    }

    private void bindViews() {
        etName = findViewById(R.id.etName);
        etMessage = findViewById(R.id.etMessage);
        etEvery = findViewById(R.id.etEvery);
        for (EditText e : new EditText[]{etName, etMessage, etEvery}) e.setHintTextColor(WaScheduleUi.TEXT2);
        tvPreview = findViewById(R.id.tvPreview);
        tvSendAtLabel = findViewById(R.id.tvSendAtLabel);
        tvTz = findViewById(R.id.tvTz);
        tvRepeatHint = findViewById(R.id.tvRepeatHint);
        tvSelCount = findViewById(R.id.tvSelCount);
        tvAreaNote = findViewById(R.id.tvAreaNote);
        tvAutoTitle = findViewById(R.id.tvAutoTitle);
        tvLoading = findViewById(R.id.tvLoading);
        rgRepeat = findViewById(R.id.rgRepeat);
        boxSendAt = findViewById(R.id.boxSendAt);
        boxInterval = findViewById(R.id.boxInterval);
        boxWeekdays = findViewById(R.id.boxWeekdays);
        boxAuto = findViewById(R.id.boxAuto);
        btnSendAt = findViewById(R.id.btnSendAt);
        btnRepeatTime = findViewById(R.id.btnRepeatTime);
        btnRecipients = findViewById(R.id.btnRecipients);
        btnSchedule = findViewById(R.id.btnSchedule);
        btnDraft = findViewById(R.id.btnDraft);
        cbAuto = findViewById(R.id.cbAuto);
        cgAuto = findViewById(R.id.cgAuto);

        ChipGroup cgDays = findViewById(R.id.cgDays);
        for (int i = 0; i < 7; i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(DAY_NAMES[i]);
            cb.setTextColor(WaScheduleUi.TEXT);
            cb.setOnCheckedChangeListener((b, c) -> markChanged());
            dayBoxes[i] = cb;
            cgDays.addView(cb);
        }

        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                markChanged();
                preview();
            }
        };
        etName.addTextChangedListener(tw);
        etMessage.addTextChangedListener(tw);
        etEvery.addTextChangedListener(tw);
        rgRepeat.setOnCheckedChangeListener((g, c) -> {
            markChanged();
            renderRepeat();
        });
        findViewById(R.id.btnInsNama).setOnClickListener(v -> {
            String t = etMessage.getText().toString();
            int s = Math.max(0, etMessage.getSelectionStart()), e = Math.max(s, etMessage.getSelectionEnd());
            etMessage.setText(WaScheduleText.insertNama(t, s, e));
            etMessage.requestFocus();
            etMessage.setSelection(Math.min(etMessage.length(), s + 6));
        });
        btnSendAt.setOnClickListener(v -> pickSendAt());
        btnRepeatTime.setOnClickListener(v -> pickRepeatTime());
        cbAuto.setOnCheckedChangeListener((b, c) -> draft.auto = c);
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.btnSearch).setOnClickListener(v -> showSearch());
        findViewById(R.id.btnArea).setOnClickListener(v -> showAreas());
        findViewById(R.id.btnMap).setOnClickListener(v -> startActivity(new Intent(this, WaScheduleMapActivity.class)));
        findViewById(R.id.btnTrip).setOnClickListener(v -> showTrips());
        btnRecipients.setOnClickListener(v -> showRecipients());
        btnSchedule.setOnClickListener(v -> save("schedule"));
        btnDraft.setOnClickListener(v -> save("draft"));
    }

    private void markChanged() {
        if (!filling) textChanged = true;
    }

    // ------------------------------------------------------------------ muat

    private void load() {
        tvLoading.setVisibility(View.VISIBLE);
        tvLoading.setText("Memuat…");
        tvLoading.setOnClickListener(null);
        String path = id > 0 ? "schedules/" + id + "/edit" : "schedules/new";
        WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaGet(path, null, staffUuid), this::fill, (msg, code) -> {
            if (code == 404 || code == 409) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            tvLoading.setText(msg + "\nKetuk untuk mencoba lagi.");
            tvLoading.setOnClickListener(v -> load());
        });
    }

    private void fill(JSONObject r) {
        filling = true;
        formLoadedAt = r.optLong("form_loaded_at", System.currentTimeMillis() / 1000);
        timezone = r.optString("timezone", timezone);
        draft.maxAutoAreas = r.optInt("max_auto_areas", 50);
        draft.maxDrawnVertices = r.optInt("max_drawn_vertices", 300);
        tvTz.setText("zona waktu " + timezone);
        JSONObject b = r.optJSONObject("broadcast");
        if (b == null) b = new JSONObject();
        etName.setText(b.optString("name", ""));
        etMessage.setText(b.optString("message", ""));
        String type = b.optString("repeat_type", "none");
        rgRepeat.check("weekdays".equals(type) ? R.id.rbWeekdays : "interval".equals(type) ? R.id.rbInterval : R.id.rbNone);
        JSONArray days = b.optJSONArray("repeat_days");
        for (int i = 0; days != null && i < days.length(); i++) {
            int d = days.optInt(i);
            if (d >= 1 && d <= 7) dayBoxes[d - 1].setChecked(true);
        }
        if (!b.isNull("repeat_every") && b.optInt("repeat_every") > 0) etEvery.setText(String.valueOf(b.optInt("repeat_every")));
        repeatTime = WaScheduleUi.str(b, "repeat_time");
        sendAt = WaScheduleUi.str(b, "send_at");
        draft.addAll(r.optJSONArray("selected"));
        JSONArray areas = r.optJSONArray("auto_areas");
        for (int i = 0; areas != null && i < areas.length(); i++) {
            JSONObject a = areas.optJSONObject(i);
            if (a != null) draft.autoAreas.add(a);
        }
        draft.dirty = false;
        filling = false;
        textChanged = false;
        loaded = true;
        tvLoading.setVisibility(View.GONE);
        renderRepeat();
        renderTimes();
        renderSel();
        // Seperti form web: pelanggan wilayah otomatis yang tersimpan langsung dimuat ke daftar.
        for (JSONObject a : new ArrayList<>(draft.autoAreas)) loadArea(a, true, null);
    }

    /** Muat pelanggan satu aturan wilayah → masuk daftar + jumlah di chip (draf diperbarui oleh {@link WaScheduleUi#loadRule}). */
    private void loadArea(JSONObject a, boolean initial, @Nullable String noteLabel) {
        WaScheduleUi.loadRule(this, staffUuid, draft, a, initial, (rows, added, err) -> {
            if (rows == null) {
                tvAreaNote.setText(err != null ? err : "Gagal memuat pelanggan wilayah.");
            } else if (noteLabel != null) {
                tvAreaNote.setText(WaScheduleUi.addedNote(noteLabel, rows.length(), added, draft.hasArea(a)));
            }
            renderSel();
        });
    }

    // ------------------------------------------------------------------ tampilan

    private String repeatType() {
        int c = rgRepeat.getCheckedRadioButtonId();
        return c == R.id.rbWeekdays ? "weekdays" : c == R.id.rbInterval ? "interval" : "none";
    }

    private void renderRepeat() {
        String t = repeatType();
        boxSendAt.setVisibility("weekdays".equals(t) ? View.GONE : View.VISIBLE);
        boxInterval.setVisibility("interval".equals(t) ? View.VISIBLE : View.GONE);
        boxWeekdays.setVisibility("weekdays".equals(t) ? View.VISIBLE : View.GONE);
        tvRepeatHint.setVisibility("none".equals(t) ? View.GONE : View.VISIBLE);
        tvSendAtLabel.setText("interval".equals(t) ? "Mulai pada (jam ini dipakai tiap pengulangan)" : "Kirim pada");
    }

    private void renderTimes() {
        btnSendAt.setText(sendAt != null ? "📅 " + WaScheduleText.display(sendAt) : "📅 Pilih tanggal & jam");
        btnRepeatTime.setText(repeatTime != null ? "🕗 Pukul " + repeatTime : "🕗 Pukul: pilih jam");
    }

    private void preview() {
        String first = null;
        for (JSONObject c : draft.sel.values()) {
            first = c.optString("name", "");
            break;
        }
        tvPreview.setText(WaScheduleText.preview(etMessage.getText().toString(), first));
    }

    private void renderSel() {
        int n = draft.sel.size();
        tvSelCount.setText("Penerima (" + n + " pelanggan)");
        btnRecipients.setText("👥 Lihat / keluarkan penerima (" + n + ")");
        if (cbAuto.isChecked() != draft.auto) cbAuto.setChecked(draft.auto);
        preview();
        renderAuto();
    }

    private void renderAuto() {
        cgAuto.removeAllViews();
        for (int i = 0; i < draft.autoAreas.size(); i++) {
            final int idx = i;
            JSONObject a = draft.autoAreas.get(i);
            Integer cnt = draft.areaCounts.get(WaScheduleDraft.areaKey(a));
            Chip chip = new Chip(this);
            chip.setText(WaScheduleDraft.areaLabel(a) + " · " + (cnt == null ? "…" : String.valueOf(cnt)) + " pelanggan");
            chip.setTextColor(WaScheduleUi.GREEN_TXT);
            chip.setChipBackgroundColor(ColorStateList.valueOf(WaScheduleUi.GREEN_BG));
            chip.setChipStrokeColor(ColorStateList.valueOf(0xFFA5D6A7));
            chip.setChipStrokeWidth(WaScheduleUi.dp(this, 1));
            chip.setCloseIconVisible(true);
            chip.setCloseIconTint(ColorStateList.valueOf(WaScheduleUi.GREEN_TXT));
            chip.setCloseIconContentDescription("Hentikan otomatis untuk wilayah ini");
            chip.setOnCloseIconClickListener(v -> {
                draft.removeArea(idx);
                renderSel();
            });
            cgAuto.addView(chip);
        }
        boxAuto.setVisibility(draft.autoAreas.isEmpty() ? View.GONE : View.VISIBLE);
        tvAutoTitle.setText("🔄 Wilayah otomatis — " + draft.affectedTotal() + " pelanggan terdampak");
    }

    // ------------------------------------------------------------------ waktu

    private void pickSendAt() {
        Calendar c = WaScheduleText.parse(sendAt);
        if (c == null) {
            c = Calendar.getInstance();
            c.add(Calendar.HOUR_OF_DAY, 1);
            c.set(Calendar.MINUTE, 0);
        }
        final Calendar cal = c;
        DatePickerDialog dp = new DatePickerDialog(this, (v, y, m, d) -> {
            cal.set(Calendar.YEAR, y);
            cal.set(Calendar.MONTH, m);
            cal.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tv, h, mi) -> {
                cal.set(Calendar.HOUR_OF_DAY, h);
                cal.set(Calendar.MINUTE, mi);
                cal.set(Calendar.SECOND, 0);
                sendAt = WaScheduleText.wire(cal);
                markChanged();
                renderTimes();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        dp.getDatePicker().setMinDate(today.getTimeInMillis());
        dp.show();
    }

    private void pickRepeatTime() {
        int h = 8, m = 0;
        if (repeatTime != null && repeatTime.matches("\\d{2}:\\d{2}")) {
            h = Integer.parseInt(repeatTime.substring(0, 2));
            m = Integer.parseInt(repeatTime.substring(3, 5));
        }
        new TimePickerDialog(this, (tv, hh, mm) -> {
            repeatTime = WaScheduleText.hhmm(hh, mm);
            markChanged();
            renderTimes();
        }, h, m, true).show();
    }

    // ------------------------------------------------------------------ 🔍 cari

    private void showSearch() {
        LinearLayout box = dialogBox();
        EditText q = new EditText(this);
        q.setHint("Nama atau nomor HP…");
        q.setHintTextColor(WaScheduleUi.TEXT2);
        q.setTextColor(WaScheduleUi.TEXT);
        q.setSingleLine(true);
        box.addView(q);
        TextView note = WaScheduleUi.text(this, "Ketik minimal 2 huruf.", 12, WaScheduleUi.TEXT2, false);
        box.addView(note);
        List<JSONObject> rows = new ArrayList<>();
        RowAdapter ad = new RowAdapter(rows, false);
        ListView lv = new ListView(this);
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WaScheduleUi.dp(this, 340)));
        final int[] seq = {0};
        Runnable run = () -> {
            String s = q.getText().toString().trim();
            final int my = ++seq[0];
            if (s.length() < 2) {
                rows.clear();
                ad.notifyDataSetChanged();
                note.setText("Ketik minimal 2 huruf.");
                return;
            }
            note.setText("Mencari…");
            Map<String, String> p = new HashMap<>();
            p.put("q", s);
            WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaGet("schedules/search", p, staffUuid), r -> {
                if (my != seq[0]) return;
                rows.clear();
                JSONArray arr = r.optJSONArray("customers");
                for (int i = 0; arr != null && i < arr.length(); i++) if (arr.optJSONObject(i) != null) rows.add(arr.optJSONObject(i));
                ad.notifyDataSetChanged();
                note.setText(rows.isEmpty() ? "Tidak ada pelanggan yang cocok." : "Ketuk untuk menambahkan ke penerima.");
            }, (msg, code) -> {
                if (my == seq[0]) note.setText(msg);
            });
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(run);
                ui.postDelayed(run, 300);
            }
        });
        lv.setOnItemClickListener((p, v, pos, idd) -> {
            JSONObject c = rows.get(pos);
            if (draft.add(c)) note.setText("✓ " + c.optString("name") + " ditambahkan.");
            else note.setText(c.optString("name") + " sudah termasuk.");
            ad.notifyDataSetChanged();
            renderSel();
        });
        AlertDialog d = new AlertDialog.Builder(this).setTitle("🔍 Cari pelanggan").setView(box)
                .setPositiveButton("Selesai", null).create();
        d.setOnDismissListener(x -> ui.removeCallbacks(run));
        d.show();
    }

    // ------------------------------------------------------------------ 📍 massal per domisili

    private void showAreas() {
        if (areasCache == null) {
            tvAreaNote.setText("Memuat daftar kecamatan…");
            WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaGet("schedules/areas", null, staffUuid), r -> {
                areasCache = r.optJSONArray("areas") != null ? r.optJSONArray("areas") : new JSONArray();
                tvAreaNote.setText("Hanya pelanggan yang punya nomor HP yang ditambahkan massal.");
                showAreas();
            }, (msg, code) -> tvAreaNote.setText(msg));
            return;
        }
        if (areasCache.length() == 0) {
            Toast.makeText(this, "Belum ada pelanggan ber-nomor dengan kecamatan terisi.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = dialogBox();
        box.addView(WaScheduleUi.text(this, "Kecamatan", 13, WaScheduleUi.TEXT, true));
        Spinner spKec = new Spinner(this);
        List<String> kecLabels = new ArrayList<>();
        kecLabels.add("Kecamatan…");
        for (int i = 0; i < areasCache.length(); i++) {
            JSONObject k = areasCache.optJSONObject(i);
            kecLabels.add(k.optString("kecamatan") + " (" + k.optInt("count") + ")");
        }
        spKec.setAdapter(spinnerAdapter(kecLabels));
        box.addView(spKec);
        box.addView(WaScheduleUi.text(this, "Desa", 13, WaScheduleUi.TEXT, true));
        Spinner spDesa = new Spinner(this);
        final List<String> desaValues = new ArrayList<>();
        final List<String> desaLabels = new ArrayList<>();
        desaValues.add("");
        desaLabels.add("Semua desa");
        spDesa.setAdapter(spinnerAdapter(desaLabels));
        box.addView(spDesa);
        spKec.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long idd) {
                desaValues.clear();
                desaLabels.clear();
                desaValues.add("");
                desaLabels.add("Semua desa");
                JSONObject k = pos > 0 ? areasCache.optJSONObject(pos - 1) : null;
                JSONArray ds = k != null ? k.optJSONArray("desa") : null;
                for (int i = 0; ds != null && i < ds.length(); i++) {
                    JSONObject d = ds.optJSONObject(i);
                    String name = d.optString("desa", "");
                    desaValues.add(name);
                    desaLabels.add((name.isEmpty() ? "(tanpa desa)" : name) + " (" + d.optInt("count") + ")");
                }
                spDesa.setAdapter(spinnerAdapter(desaLabels));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) { }
        });
        CheckBox auto = autoBox();
        box.addView(auto);
        box.addView(WaScheduleUi.text(this, "Hanya pelanggan yang punya nomor HP.", 12, WaScheduleUi.TEXT2, false));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("📍 Massal per domisili").setView(box)
                .setPositiveButton("Tambahkan", null).setNegativeButton("Batal", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int kp = spKec.getSelectedItemPosition();
            if (kp <= 0) {
                Toast.makeText(this, "Pilih kecamatan dulu.", Toast.LENGTH_SHORT).show();
                return;
            }
            String kec = areasCache.optJSONObject(kp - 1).optString("kecamatan");
            int dpos = spDesa.getSelectedItemPosition();
            String desa = dpos > 0 && dpos < desaValues.size() ? desaValues.get(dpos) : "";
            d.dismiss();
            JSONObject a = new JSONObject();
            try {
                a.put("kecamatan", kec).put("desa", desa);
            } catch (Exception ignored) {
            }
            addAreaRule(a, (desa.isEmpty() ? "" : desa + ", ") + kec);
        }));
        d.show();
    }

    /** Wilayah otomatis bila dicentang, lalu pelanggannya masuk daftar. */
    private void addAreaRule(JSONObject a, String label) {
        if (draft.auto && !draft.addArea(a)) {
            Toast.makeText(this, "Wilayah otomatis maksimal " + draft.maxAutoAreas + ".", Toast.LENGTH_LONG).show();
        }
        tvAreaNote.setText("Memuat pelanggan " + label + "…");
        renderSel();
        loadArea(a, false, label);
    }

    // ------------------------------------------------------------------ 🚚 Aturan Trip

    private void showTrips() {
        if (tripsCache == null) {
            tvAreaNote.setText("Memuat Aturan Trip…");
            WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaGet("schedules/trip-areas", null, staffUuid), r -> {
                tripsCache = r.optJSONArray("rules") != null ? r.optJSONArray("rules") : new JSONArray();
                tvAreaNote.setText("");
                showTrips();
            }, (msg, code) -> tvAreaNote.setText(msg));
            return;
        }
        if (tripsCache.length() == 0) {
            Toast.makeText(this, "Belum ada Aturan Trip.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[tripsCache.length()];
        for (int i = 0; i < tripsCache.length(); i++) {
            JSONObject t = tripsCache.optJSONObject(i);
            JSONArray ar = t.optJSONArray("areas");
            labels[i] = t.optString("name") + " — " + (ar != null ? ar.length() : 0) + " wilayah, " + t.optInt("count")
                    + " pelanggan" + (t.optBoolean("active") ? "" : " (nonaktif)");
        }
        new AlertDialog.Builder(this).setTitle("🚚 Impor dari Aturan Trip")
                .setItems(labels, (d, w) -> showTripRule(tripsCache.optJSONObject(w)))
                .setNegativeButton("Batal", null).show();
    }

    private void showTripRule(JSONObject t) {
        LinearLayout box = dialogBox();
        box.addView(autoBox());
        box.addView(WaScheduleUi.text(this, "Ketuk semua wilayah aturan ini, atau satu wilayahnya saja.", 12, WaScheduleUi.TEXT2, false));
        JSONArray ar = t.optJSONArray("areas");
        List<String> items = new ArrayList<>();
        items.add("➕ Semua wilayahnya (" + t.optInt("count") + " pelanggan)");
        for (int i = 0; ar != null && i < ar.length(); i++) {
            JSONObject a = ar.optJSONObject(i);
            String kec = WaScheduleUi.str(a, "kecamatan");
            items.add("🚚 " + a.optString("desa") + (kec != null ? " · " + kec : "") + " — " + a.optInt("count") + " pelanggan");
        }
        ListView lv = new ListView(this);
        lv.setAdapter(spinnerAdapter(items));
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WaScheduleUi.dp(this, 300)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("🚚 " + t.optString("name")).setView(box)
                .setNegativeButton("Batal", null).create();
        lv.setOnItemClickListener((p, v, pos, idd) -> {
            d.dismiss();
            JSONObject rule = new JSONObject();
            String name = t.optString("name");
            try {
                if (pos == 0) {
                    rule.put("trip_rule_id", t.optLong("id")).put("label", WaScheduleText.label60(name));
                    addAreaRule(rule, "🚚 " + name);
                } else {
                    JSONObject a = ar.optJSONObject(pos - 1);
                    String lbl = WaScheduleText.label60(name + " › " + a.optString("desa"));
                    rule.put("trip_area_id", a.optLong("id")).put("label", lbl);
                    addAreaRule(rule, "🚚 " + lbl);
                }
            } catch (Exception ignored) {
            }
        });
        d.show();
    }

    // ------------------------------------------------------------------ 👥 daftar penerima

    private void showRecipients() {
        if (draft.sel.isEmpty()) {
            Toast.makeText(this, "Belum ada penerima.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = dialogBox();
        EditText q = new EditText(this);
        q.setHint("Saring nama / nomor…");
        q.setHintTextColor(WaScheduleUi.TEXT2);
        q.setTextColor(WaScheduleUi.TEXT);
        q.setSingleLine(true);
        box.addView(q);
        TextView note = WaScheduleUi.text(this, "Ketuk ✕ (baris) untuk mengeluarkan dari penerima.", 12, WaScheduleUi.TEXT2, false);
        box.addView(note);
        List<JSONObject> rows = new ArrayList<>(draft.sel.values());
        RowAdapter ad = new RowAdapter(rows, true);
        ListView lv = new ListView(this);
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WaScheduleUi.dp(this, 380)));
        Runnable filter = () -> {
            String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
            rows.clear();
            for (JSONObject c : draft.sel.values()) {
                if (s.isEmpty() || c.optString("name", "").toLowerCase(Locale.ROOT).contains(s)
                        || c.optString("phone", "").contains(s)) rows.add(c);
            }
            ad.notifyDataSetChanged();
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        AlertDialog d = new AlertDialog.Builder(this).setTitle("👥 Penerima (" + draft.sel.size() + ")").setView(box)
                .setPositiveButton("Selesai", null).create();
        lv.setOnItemClickListener((p, v, pos, idd) -> {
            JSONObject c = rows.get(pos);
            draft.remove(c.optLong("id"));
            note.setText("✕ " + c.optString("name") + " dikeluarkan.");
            filter.run();
            d.setTitle("👥 Penerima (" + draft.sel.size() + ")");
            renderSel();
        });
        d.show();
    }

    private void confirmClear() {
        if (draft.sel.isEmpty() && draft.autoAreas.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Kosongkan penerima?")
                .setMessage("Semua " + draft.sel.size() + " penerima" + (draft.autoAreas.isEmpty() ? "" : " DAN " + draft.autoAreas.size()
                        + " wilayah otomatis") + " dikeluarkan dari form ini (belum tersimpan sampai Anda menyimpan).")
                .setPositiveButton("Kosongkan", (x, w) -> {
                    draft.clear();
                    renderSel();
                })
                .setNegativeButton("Batal", null).show();
    }

    // ------------------------------------------------------------------ simpan

    private void save(String action) {
        if (saving || !loaded) return;
        if (confirmDialog != null && confirmDialog.isShowing()) return;   // ketuk ganda: satu konfirmasi saja
        // Jangan kirim daftar penerima yang belum lengkap: server mencatat pelanggan wilayah otomatis yang tak ada di
        // daftar sebagai "sengaja dikeluarkan" dan tak pernah mengirimi mereka.
        List<JSONObject> unloaded = draft.unloadedAutoAreas();
        String blocker = WaScheduleLoads.saveBlocker(draft.loads.busy(), unloaded.size());
        if (blocker != null) {
            for (JSONObject a : unloaded) loadArea(a, false, WaScheduleDraft.areaLabel(a));
            if (!unloaded.isEmpty()) {
                tvAreaNote.setText("Memuat ulang pelanggan wilayah otomatis… Bila disimpan sebelum termuat, mereka dianggap "
                        + "dikeluarkan dan tidak akan dikirimi.");
            }
            Toast.makeText(this, blocker, Toast.LENGTH_LONG).show();
            return;
        }
        if (draft.sel.size() > MAX_RECIPIENTS) {
            Toast.makeText(this, "Penerima maksimal " + MAX_RECIPIENTS + " pelanggan.", Toast.LENGTH_LONG).show();
            return;
        }
        final JSONObject body;
        try {
            body = body(action);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyiapkan data.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!"schedule".equals(action)) {
            send(body);
            return;
        }
        String type = repeatType();
        String when;
        if ("weekdays".equals(type)) {
            StringBuilder ds = new StringBuilder();
            for (int i = 0; i < 7; i++) if (dayBoxes[i].isChecked()) ds.append(ds.length() > 0 ? ", " : "").append(DAY_NAMES[i]);
            when = "tiap " + (ds.length() > 0 ? ds : "(hari belum dipilih)") + " pukul " + (repeatTime != null ? repeatTime : "(belum diisi)");
        } else if ("interval".equals(type)) {
            when = "mulai " + (sendAt != null ? WaScheduleText.display(sendAt) : "(waktu belum diisi)") + ", tiap "
                    + etEvery.getText().toString().trim() + " hari";
        } else {
            when = sendAt != null ? WaScheduleText.display(sendAt) : "(waktu belum diisi)";
        }
        confirmDialog = new AlertDialog.Builder(this)
                .setTitle("Jadwalkan broadcast?")
                .setMessage("Pesan akan dikirim otomatis ke " + draft.sel.size() + " pelanggan"
                        + (draft.autoAreas.isEmpty() ? "" : " (+ pelanggan baru di " + draft.autoAreas.size() + " wilayah otomatis)")
                        + " — " + when + " (" + timezone + ").")
                .setPositiveButton("⏰ Jadwalkan", (d, w) -> send(body))
                .setNegativeButton("Batal", null).show();
    }

    private JSONObject body(String action) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", etName.getText().toString().trim());
        o.put("message", etMessage.getText().toString());
        o.put("action", action);
        o.put("repeat_type", repeatType());
        JSONArray days = new JSONArray();
        for (int i = 0; i < 7; i++) if (dayBoxes[i].isChecked()) days.put(i + 1);
        o.put("repeat_days", days);
        String every = etEvery.getText().toString().trim();
        o.put("repeat_every", every.isEmpty() ? JSONObject.NULL : (Object) every);
        o.put("repeat_time", repeatTime != null ? repeatTime : JSONObject.NULL);
        o.put("send_at", sendAt != null ? sendAt : JSONObject.NULL);
        o.put("customer_ids", draft.selectedIds());
        o.put("auto_areas", draft.autoAreasPayload());
        o.put("form_loaded_at", formLoadedAt);
        return o;
    }

    private void send(JSONObject body) {
        if (saving) return;   // POST kedua = broadcast ganda (tiap pelanggan dikirimi dua kali)
        saving = true;
        btnSchedule.setEnabled(false);
        btnDraft.setEnabled(false);
        String path = id > 0 ? "schedules/" + id + "/update" : "schedules";
        WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaPost(path, body, staffUuid), r -> {
            saving = false;
            Toast.makeText(this, WaAdmin.message(r, "Tersimpan."), Toast.LENGTH_LONG).show();
            draft.dirty = false;
            textChanged = false;
            long newId = r.optLong("id", id);
            if (id <= 0 && newId > 0) {
                startActivity(new Intent(this, WaScheduleDetailActivity.class).putExtra(WaScheduleDetailActivity.EXTRA_ID, newId));
            }
            finish();
        }, (msg, code) -> {
            saving = false;
            btnSchedule.setEnabled(true);
            btnDraft.setEnabled(true);
            new AlertDialog.Builder(this).setTitle("Belum tersimpan").setMessage(msg).setPositiveButton("OK", null).show();
        });
    }

    private void leave() {
        if (saving) {
            // Permintaan simpan sudah terkirim: "Buang" di sini tak membatalkan broadcast yang sedang dibuat server.
            Toast.makeText(this, "Sedang menyimpan — tunggu sebentar.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (loaded && (draft.dirty || textChanged)) {
            new AlertDialog.Builder(this)
                    .setTitle("Buang perubahan?")
                    .setMessage("Perubahan di form ini belum disimpan.")
                    .setPositiveButton("Buang", (d, w) -> finish())
                    .setNegativeButton("Lanjut mengisi", null).show();
        } else {
            finish();
        }
    }

    // ------------------------------------------------------------------ pembantu

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = WaScheduleUi.dp(this, 18);
        box.setPadding(p, WaScheduleUi.dp(this, 8), p, 0);
        return box;
    }

    /** Centang 🔄 Otomatis di dialog — satu nilai dengan centang di form. */
    private CheckBox autoBox() {
        CheckBox cb = new CheckBox(this);
        cb.setText("🔄 Otomatis — pelanggan BARU di wilayah ini ikut jadi penerima dengan sendirinya");
        cb.setTextColor(WaScheduleUi.TEXT);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cb.setChecked(draft.auto);
        cb.setOnCheckedChangeListener((b, c) -> {
            draft.auto = c;
            cbAuto.setChecked(c);
        });
        return cb;
    }

    private ArrayAdapter<String> spinnerAdapter(List<String> items) {
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, @Nullable View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(WaScheduleUi.TEXT);
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(WaScheduleUi.TEXT);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_list_item_1);
        return ad;
    }

    /** Baris pelanggan untuk dialog cari / daftar penerima. */
    private final class RowAdapter extends BaseAdapter {
        private final List<JSONObject> rows;
        private final boolean removable;

        RowAdapter(List<JSONObject> rows, boolean removable) {
            this.rows = rows;
            this.removable = removable;
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return rows.get(i).optLong("id"); }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(WaScheduleFormActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int p = WaScheduleUi.dp(WaScheduleFormActivity.this, 8);
            row.setPadding(p / 2, p, p / 2, p);
            JSONObject c = rows.get(i);
            LinearLayout col = new LinearLayout(WaScheduleFormActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            boolean in = draft.sel.containsKey(c.optLong("id"));
            String tag = removable ? (draft.inAutoArea(c) ? "  🔄 wilayah otomatis" : "") : (in ? "  ✓ sudah termasuk" : "");
            col.addView(WaScheduleUi.text(WaScheduleFormActivity.this, c.optString("name") + tag, 14, WaScheduleUi.TEXT, true));
            String phone = WaScheduleUi.str(c, "phone");
            String desa = WaScheduleUi.str(c, "desa"), kec = WaScheduleUi.str(c, "kecamatan");
            col.addView(WaScheduleUi.text(WaScheduleFormActivity.this, (phone != null ? phone : "tanpa nomor")
                    + (desa != null ? " · " + desa : "") + (kec != null ? ", " + kec : ""), 12, WaScheduleUi.TEXT2, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (removable) row.addView(WaScheduleUi.text(WaScheduleFormActivity.this, "✕", 18, WaScheduleUi.RED_TXT, true));
            return row;
        }
    }
}
