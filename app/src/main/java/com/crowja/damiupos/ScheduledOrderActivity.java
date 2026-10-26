package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 🗓️ Pesanan Terjadwal (Admin) — kelola jadwal kunjungan rutin pelanggan langsung dari HP: tambah,
 * ubah, aktif/nonaktif, tunda pengingat, hapus, dan kirim pengingat sekarang. Cermin halaman web
 * /scheduled-orders. Tabel jadwal hanya ada di server (tak tersinkron), jadi semuanya lewat
 * /api/order-schedules dengan header X-Staff-Uuid — server memeriksa staf Admin aktif cabang ini.
 */
public class ScheduledOrderActivity extends AppCompatActivity {

    private static final String[] DAY_LABELS = {"Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Ahad"};
    private static final int F_ALL = 0, F_DUE = 1, F_OFF = 2;

    private SyncSettings cfg;
    private String staffUuid;
    private TextView tvSummary, tvEmpty;
    private EditText etSearch;
    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private JSONArray products = new JSONArray();
    private JSONArray devices = new JSONArray();
    private String defaultTime = "08:00";
    private int dueCount = 0;
    private int filter = F_ALL;
    private boolean loading;
    private final ScheduleAdapter adapter = new ScheduleAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scheduled_orders);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server (Pengaturan → Sinkronisasi).", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!UserDao.isCurrentUserAdmin(this)) {
            Toast.makeText(this, "Pesanan Terjadwal hanya untuk Admin.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        staffUuid = LocationReporter.currentStaffUuid(this);
        if (staffUuid == null || staffUuid.isEmpty()) {
            Toast.makeText(this, "Akun Admin ini belum tersinkron ke server — sinkronkan data dulu.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        RecyclerView rv = findViewById(R.id.rvSchedules);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        MaterialButtonToggleGroup tg = findViewById(R.id.toggleFilter);
        tg.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            filter = id == R.id.btnFDue ? F_DUE : id == R.id.btnFOff ? F_OFF : F_ALL;
            applyFilter();
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        findViewById(R.id.fabAdd).setOnClickListener(v -> showForm(null));
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "📨 Kirim Pengingat Sekarang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 2, 1, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            confirmSendNow();
            return true;
        }
        if (item.getItemId() == 2) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (loading) return;
        loading = true;
        tvSummary.setText("Memuat…");
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = new SyncApi(cfg).orderSchedules(staffUuid);
            } catch (SyncApi.SyncException se) {
                err = se.serverMessage();
                if (err == null) err = "Gagal memuat jadwal (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal memuat jadwal — periksa koneksi internet.";
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    tvSummary.setText(fe);
                    Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                    return;
                }
                all.clear();
                JSONArray arr = fr.optJSONArray("schedules");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) all.add(o);
                }
                JSONArray p = fr.optJSONArray("products");
                products = p != null ? p : new JSONArray();
                JSONArray d = fr.optJSONArray("devices");
                devices = d != null ? d : new JSONArray();
                String t = str(fr, "default_auto_tertunda_time");
                if (t != null) defaultTime = t;
                dueCount = fr.optInt("due_count");
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        int active = 0;
        for (JSONObject s : all) {
            if (s.optBoolean("active")) active++;
            boolean off = !s.optBoolean("active") || str(s, "snoozed_until") != null;
            if (filter == F_DUE && !s.optBoolean("due_today")) continue;
            if (filter == F_OFF && !off) continue;
            if (!q.isEmpty()) {
                String hay = (nz(str(s, "customer_name")) + " " + nz(str(s, "customer_phone"))).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            shown.add(s);
        }
        adapter.notifyDataSetChanged();
        tvSummary.setText(all.size() + " jadwal · " + active + " aktif · " + dueCount + " jatuh tempo hari ini"
                + "\nKetuk kartu untuk ubah / tunda / nonaktifkan / hapus.");
        tvEmpty.setText(all.isEmpty() ? "Belum ada jadwal kunjungan.\nKetuk \"Tambah Jadwal\" untuk pelanggan yang minta dikunjungi rutin."
                : "Tidak ada jadwal pada filter ini.");
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Panggilan API di latar; sukses → toast pesan server + {@code onOk}; gagal → toast galat. */
    private void call(Callable<JSONObject> req, @Nullable Runnable onOk, @Nullable Runnable onFail) {
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                JSONObject r = req.call();
                ok = r.optBoolean("ok", true);
                msg = r.optString("message", ok ? "Tersimpan." : "Ditolak server.");
            } catch (SyncApi.SyncException se) {
                msg = se.serverMessage();
                if (msg == null) msg = "Gagal (kode " + se.code + ").";
            } catch (Exception e) {
                msg = "Gagal — periksa koneksi internet.";
            }
            final String fm = msg;
            final boolean fok = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, fm, Toast.LENGTH_LONG).show();
                if (fok) {
                    if (onOk != null) onOk.run();
                    load();
                } else if (onFail != null) {
                    onFail.run();
                }
            });
        }).start();
    }

    private void action(JSONObject s, String act, @Nullable JSONObject body) {
        final long id = s.optLong("id");
        call(() -> new SyncApi(cfg).orderScheduleAction(id, act, body, staffUuid), null, null);
    }

    private void confirmSendNow() {
        if (dueCount <= 0) {
            Toast.makeText(this, "Tidak ada jadwal yang jatuh tempo hari ini.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("📨 Kirim Pengingat Sekarang")
                .setMessage("Kirim pengingat untuk " + dueCount + " jadwal yang jatuh tempo hari ini ke Pesanan Masuk, tanpa menunggu kiriman otomatis pagi?")
                .setPositiveButton("Kirim", (d, w) -> call(() -> new SyncApi(cfg).orderSchedulesSendNow(staffUuid), null, null))
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------ aksi per kartu

    private void showActions(JSONObject s) {
        final boolean active = s.optBoolean("active");
        final boolean snoozed = str(s, "snoozed_until") != null;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> runs = new ArrayList<>();
        labels.add("✏️ Ubah jadwal");
        runs.add(() -> {
            if (str(s, "customer_name") == null) {
                Toast.makeText(this, "Pelanggan jadwal ini sudah terhapus — hapus jadwalnya saja.", Toast.LENGTH_LONG).show();
                return;
            }
            showForm(s);
        });
        if (snoozed) {
            labels.add("▶️ Batal tunda (kembali sesuai jadwal)");
            runs.add(() -> {
                JSONObject b = new JSONObject();
                try {
                    b.put("until", JSONObject.NULL);
                } catch (Exception ignored) {
                }
                action(s, "snooze", b);
            });
        } else {
            labels.add("⏸ Tunda pengingat ke tanggal…");
            runs.add(() -> pickSnoozeDate(s));
        }
        labels.add(active ? "⏹ Nonaktifkan (tidak dihapus)" : "✅ Aktifkan lagi");
        runs.add(() -> action(s, "toggle", null));
        labels.add("🗑️ Hapus jadwal");
        runs.add(() -> new AlertDialog.Builder(this)
                .setTitle("Hapus jadwal?")
                .setMessage("Jadwal kunjungan \"" + nz(str(s, "customer_name"), "(pelanggan terhapus)") + "\" dihapus permanen. "
                        + "Kalau hanya ingin berhenti sementara, pilih Nonaktifkan.")
                .setPositiveButton("Hapus", (d, w) -> action(s, "delete", null))
                .setNegativeButton("Batal", null)
                .show());
        new AlertDialog.Builder(this)
                .setTitle(nz(str(s, "customer_name"), "(pelanggan terhapus)"))
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void pickSnoozeDate(JSONObject s) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 1);
        DatePickerDialog dp = new DatePickerDialog(this, (v, y, m, d) -> {
            JSONObject b = new JSONObject();
            try {
                b.put("until", String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d));
            } catch (Exception ignored) {
            }
            action(s, "snooze", b);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(c.getTimeInMillis());
        dp.setTitle("Jadwalkan ulang pengingat ke");
        dp.show();
    }

    // ------------------------------------------------------------------ form tambah / ubah

    private void showForm(@Nullable JSONObject s) {
        final boolean editing = s != null;
        final String[] custUuid = {editing ? str(s, "customer_uuid") : null};
        final int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, dp(4));

        // Pelanggan
        box.addView(label("Pelanggan"));
        final Button btCust = new Button(this);
        btCust.setAllCaps(false);
        if (editing) {
            String ph = str(s, "customer_phone");
            btCust.setText(nz(str(s, "customer_name")) + (ph != null ? "  ·  " + ph : "") + "  (tidak bisa diganti)");
            btCust.setEnabled(false);
            btCust.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        } else {
            btCust.setText("🔎 Pilih pelanggan…");
            btCust.setOnClickListener(v -> pickCustomer((uuid, name, phone) -> {
                custUuid[0] = uuid;
                btCust.setText(name + (phone != null && !phone.isEmpty() ? "  ·  " + phone : ""));
            }));
        }
        box.addView(btCust);

        // Pola kunjungan
        box.addView(label("Jadwal kunjungan"));
        final RadioGroup rg = new RadioGroup(this);
        final RadioButton rbWeekly = radio("Hari tetap tiap pekan");
        final RadioButton rbInterval = radio("Tiap N hari sejak order terakhir");
        final RadioButton rbPred = radio("Sesuai prediksi habis konsumsi galon");
        rg.addView(rbWeekly);
        rg.addView(rbInterval);
        rg.addView(rbPred);
        box.addView(rg);
        final CheckBox[] days = new CheckBox[7];
        final LinearLayout dayBox = new LinearLayout(this);
        dayBox.setOrientation(LinearLayout.VERTICAL);
        dayBox.setPadding(dp(16), 0, 0, 0);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        for (int i = 0; i < 7; i++) {
            days[i] = new CheckBox(this);
            days[i].setText(DAY_LABELS[i]);
            days[i].setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            (i < 4 ? r1 : r2).addView(days[i]);
        }
        View filler = new View(this);   // baris kedua 3 hari + pengisi → kolomnya sejajar baris pertama
        filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        r2.addView(filler);
        dayBox.addView(r1);
        dayBox.addView(r2);
        box.addView(dayBox);
        final LinearLayout intBox = new LinearLayout(this);
        intBox.setOrientation(LinearLayout.HORIZONTAL);
        intBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        intBox.setPadding(dp(16), 0, 0, 0);
        TextView tiap = new TextView(this);
        tiap.setText("tiap ");
        tiap.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        final EditText etInt = new EditText(this);
        etInt.setInputType(InputType.TYPE_CLASS_NUMBER);
        etInt.setHint("mis. 5");
        etInt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        etInt.setMinWidth(dp(90));
        TextView hari = new TextView(this);
        hari.setText(" hari");
        hari.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        intBox.addView(tiap);
        intBox.addView(etInt);
        intBox.addView(hari);
        box.addView(intBox);
        final TextView predHint = hint("Jatuh tempo otomatis dari rate konsumsi galon pelanggan. Pelanggan baru / baru sekali beli "
                + "belum terukur → tidak pernah jatuh tempo lewat mode ini.");
        predHint.setPadding(dp(16), 0, 0, 0);
        box.addView(predHint);
        rg.setOnCheckedChangeListener((g, id) -> {
            dayBox.setVisibility(id == rbWeekly.getId() ? View.VISIBLE : View.GONE);
            intBox.setVisibility(id == rbInterval.getId() ? View.VISIBLE : View.GONE);
            predHint.setVisibility(id == rbPred.getId() ? View.VISIBLE : View.GONE);
        });

        // Catatan pengingat
        box.addView(label("Catatan untuk staf (opsional)"));
        final EditText etNote = new EditText(this);
        etNote.setHint("mis. antar pagi sebelum jam 9");
        etNote.setFilters(new InputFilter[]{new InputFilter.LengthFilter(255)});
        etNote.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(etNote);

        // Produk & jumlah
        final Map<String, EditText> qty = new LinkedHashMap<>();
        if (products.length() > 0) {
            box.addView(label("Produk & jumlah yang diminta (opsional — wajib bila Pesanan Tertunda otomatis aktif)"));
            for (int i = 0; i < products.length(); i++) {
                JSONObject p = products.optJSONObject(i);
                if (p == null || str(p, "uuid") == null) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView name = new TextView(this);
                name.setText(nz(str(p, "name")));
                name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                EditText et = new EditText(this);
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                et.setHint("0");
                et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
                et.setGravity(android.view.Gravity.END);
                et.setLayoutParams(new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(name);
                row.addView(et);
                box.addView(row);
                qty.put(str(p, "uuid"), et);
            }
        }

        // Jenis galon
        box.addView(label("Jenis galon"));
        final RadioGroup rgGal = new RadioGroup(this);
        rgGal.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton rbKeran = radio("Keran");
        final RadioButton rbNon = radio("Non-keran");
        rgGal.addView(rbKeran);
        rgGal.addView(rbNon);
        box.addView(rgGal);

        // Otomatis buat Pesanan Tertunda
        final CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("🅰️ Otomatis buat Pesanan Tertunda saat jatuh tempo");
        cbAuto.setPadding(0, dp(8), 0, 0);
        box.addView(cbAuto);
        box.addView(hint("Dibuat dari produk & jumlah di atas, diparkir di Antrian Tertunda, lalu pindah sendiri ke antrian aktif tepat pada jam yang dipilih."));
        final LinearLayout autoBox = new LinearLayout(this);
        autoBox.setOrientation(LinearLayout.VERTICAL);
        autoBox.setPadding(dp(16), 0, 0, 0);
        final String[] time = {defaultTime};
        final Button btTime = new Button(this);
        btTime.setAllCaps(false);
        btTime.setText("🕗 Jam pengiriman: " + time[0]);
        btTime.setOnClickListener(v -> {
            int h = 8, mi = 0;
            try {
                String[] hm = time[0].split(":");
                h = Integer.parseInt(hm[0]);
                mi = Integer.parseInt(hm[1]);
            } catch (Exception ignored) {
            }
            new TimePickerDialog(this, (tp, hh, mm) -> {
                time[0] = String.format(Locale.US, "%02d:%02d", hh, mm);
                btTime.setText("🕗 Jam pengiriman: " + time[0]);
            }, h, mi, true).show();
        });
        autoBox.addView(btTime);
        autoBox.addView(label("Perangkat tujuan"));
        final List<String> devUuids = new ArrayList<>();
        List<String> devNames = new ArrayList<>();
        devUuids.add(null);
        devNames.add("— Otomatis (sesuai wilayah/penugasan) —");
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null || str(d, "uuid") == null) continue;
            devUuids.add(str(d, "uuid"));
            devNames.add(nz(str(d, "name"), "Perangkat"));
        }
        final Spinner spDev = new Spinner(this);
        ArrayAdapter<String> da = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devNames);
        da.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDev.setAdapter(da);
        autoBox.addView(spDev);
        final EditText etAutoNote = new EditText(this);
        etAutoNote.setHint("Catatan di struk WA pelanggan (opsional)");
        etAutoNote.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        etAutoNote.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        autoBox.addView(etAutoNote);
        box.addView(autoBox);
        cbAuto.setOnCheckedChangeListener((b, on) -> autoBox.setVisibility(on ? View.VISIBLE : View.GONE));

        // Isi awal
        String mode = editing ? nz(str(s, "mode"), "weekly") : "weekly";
        rg.check("interval".equals(mode) ? rbInterval.getId() : "prediction".equals(mode) ? rbPred.getId() : rbWeekly.getId());
        if (editing) {
            JSONArray wd = s.optJSONArray("weekdays");
            for (int i = 0; wd != null && i < wd.length(); i++) {
                int d = wd.optInt(i);
                if (d >= 1 && d <= 7) days[d - 1].setChecked(true);
            }
            if (!s.isNull("interval_days")) etInt.setText(String.valueOf(s.optInt("interval_days")));
            etNote.setText(nz(str(s, "note")));
            JSONArray items = s.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                EditText et = it != null ? qty.get(str(it, "product_uuid")) : null;
                if (et != null && it.optInt("qty") > 0) et.setText(String.valueOf(it.optInt("qty")));
            }
            String gt = str(s, "galon_type");
            if ("KERAN".equals(gt)) rgGal.check(rbKeran.getId());
            else if ("NON_KERAN".equals(gt)) rgGal.check(rbNon.getId());
            cbAuto.setChecked(s.optBoolean("auto_tertunda"));
            String at = str(s, "auto_tertunda_time");
            if (at != null) {
                time[0] = at;
                btTime.setText("🕗 Jam pengiriman: " + at);
            }
            int di = devUuids.indexOf(str(s, "auto_tertunda_device_uuid"));
            if (di > 0) spDev.setSelection(di);
            etAutoNote.setText(nz(str(s, "auto_tertunda_note")));
        } else {
            rgGal.check(rbKeran.getId());
        }
        autoBox.setVisibility(cbAuto.isChecked() ? View.VISIBLE : View.GONE);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(editing ? "✏️ Ubah Jadwal Kunjungan" : "➕ Tambah Jadwal Kunjungan")
                .setView(sv)
                .setPositiveButton("Simpan", null)
                .setNegativeButton("Batal", null)
                .create();
        dlg.setOnShowListener(di -> {
            final Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                JSONObject b = new JSONObject();
                try {
                    if (editing) {
                        b.put("id", s.optLong("id"));
                    } else {
                        if (custUuid[0] == null) {
                            toast("Pilih pelanggan dulu.");
                            return;
                        }
                        b.put("customer_uuid", custUuid[0]);
                    }
                    int checkedMode = rg.getCheckedRadioButtonId();
                    if (checkedMode == rbInterval.getId()) {
                        int n = 0;
                        try {
                            n = Integer.parseInt(etInt.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        if (n < 1 || n > 365) {
                            toast("Isi tiap berapa hari (1–365).");
                            return;
                        }
                        b.put("mode", "interval");
                        b.put("interval_days", n);
                    } else if (checkedMode == rbPred.getId()) {
                        b.put("mode", "prediction");
                    } else {
                        JSONArray wd = new JSONArray();
                        for (int i = 0; i < 7; i++) if (days[i].isChecked()) wd.put(i + 1);
                        if (wd.length() == 0) {
                            toast("Pilih minimal satu hari kunjungan.");
                            return;
                        }
                        b.put("mode", "weekly");
                        b.put("weekdays", wd);
                    }
                    String note = etNote.getText().toString().trim();
                    if (!note.isEmpty()) b.put("note", note);
                    JSONObject q = new JSONObject();
                    for (Map.Entry<String, EditText> e : qty.entrySet()) {
                        int n = 0;
                        try {
                            n = Integer.parseInt(e.getValue().getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        if (n > 0) q.put(e.getKey(), n);
                    }
                    b.put("qty", q);
                    if (rgGal.getCheckedRadioButtonId() == rbKeran.getId()) b.put("galon_type", "KERAN");
                    else if (rgGal.getCheckedRadioButtonId() == rbNon.getId()) b.put("galon_type", "NON_KERAN");
                    b.put("auto_tertunda", cbAuto.isChecked());
                    if (cbAuto.isChecked()) {
                        if (q.length() == 0) {
                            toast("Isi minimal satu produk & jumlah untuk Pesanan Tertunda otomatis.");
                            return;
                        }
                        b.put("auto_tertunda_time", time[0]);
                        String du = devUuids.get(Math.max(0, spDev.getSelectedItemPosition()));
                        if (du != null) b.put("auto_tertunda_device_uuid", du);
                        String an = etAutoNote.getText().toString().trim();
                        if (!an.isEmpty()) b.put("auto_tertunda_note", an);
                    }
                } catch (Exception e) {
                    toast("Isian tidak valid.");
                    return;
                }
                pos.setEnabled(false);
                pos.setText("Menyimpan…");
                call(() -> new SyncApi(cfg).saveOrderSchedule(b, staffUuid), dlg::dismiss, () -> {
                    pos.setEnabled(true);
                    pos.setText("Simpan");
                });
            });
        });
        dlg.show();
    }

    private interface Picked {
        void on(String uuid, String name, String phone);
    }

    /** Pemilih pelanggan dari data lokal HP (sinkron branch-wide); yang sudah terjadwal ditandai. */
    private void pickCustomer(Picked cb) {
        final Set<String> taken = new HashSet<>();
        for (JSONObject s : all) {
            String u = str(s, "customer_uuid");
            if (u != null) taken.add(u);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText etQ = new EditText(this);
        etQ.setHint("🔎 Cari nama / nomor…");
        etQ.setSingleLine(true);
        box.addView(etQ);
        final ListView lv = new ListView(this);
        lv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        box.addView(lv);
        final List<String[]> rows = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        lv.setAdapter(ad);
        final Runnable refresh = () -> {
            rows.clear();
            labels.clear();
            String q = "%" + etQ.getText().toString().trim() + "%";
            Cursor c = null;
            try {
                c = DatabaseHelper.getInstance(this).getReadableDatabase().rawQuery(
                        "SELECT " + DatabaseHelper.COL_SYNC_UUID + ", " + DatabaseHelper.COL_NAME + ", " + DatabaseHelper.COL_PHONE
                                + " FROM " + DatabaseHelper.TABLE_CUSTOMERS
                                + " WHERE " + DatabaseHelper.COL_SYNC_UUID + " IS NOT NULL AND " + DatabaseHelper.COL_SYNC_UUID + " != ''"
                                + " AND LOWER(TRIM(" + DatabaseHelper.COL_NAME + ")) != 'umum'"
                                + " AND (" + DatabaseHelper.COL_NAME + " LIKE ? OR " + DatabaseHelper.COL_PHONE + " LIKE ?)"
                                + " ORDER BY " + DatabaseHelper.COL_NAME + " COLLATE NOCASE LIMIT 200",
                        new String[]{q, q});
                while (c.moveToNext()) {
                    String u = c.getString(0), n = c.getString(1), p = c.getString(2);
                    rows.add(new String[]{u, n, p});
                    labels.add(nz(n) + (p != null && !p.isEmpty() ? "  ·  " + p : "") + (taken.contains(u) ? "  (sudah terjadwal)" : ""));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            ad.notifyDataSetChanged();
        };
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Pilih Pelanggan")
                .setView(box)
                .setNegativeButton("Batal", null)
                .create();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String[] r = rows.get(pos);
            if (taken.contains(r[0])) {
                toast("\"" + nz(r[1]) + "\" sudah punya jadwal — ubah dari daftar.");
                return;
            }
            d.dismiss();
            cb.on(r[0], nz(r[1]), r[2]);
        });
        etQ.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        refresh.run();
        d.show();
    }

    // ------------------------------------------------------------------ daftar

    private class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final TextView name, badge, phone, label, detail, meta;
            final View content;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tvName);
                badge = v.findViewById(R.id.tvBadge);
                phone = v.findViewById(R.id.tvPhone);
                label = v.findViewById(R.id.tvLabel);
                detail = v.findViewById(R.id.tvDetail);
                meta = v.findViewById(R.id.tvMeta);
                content = v.findViewById(R.id.content);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduled_order, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject s = shown.get(position);
            String name = str(s, "customer_name");
            h.name.setText(name != null ? name : "(pelanggan terhapus)");
            String phone = str(s, "customer_phone");
            h.phone.setText(phone != null ? phone : "tanpa nomor");

            boolean active = s.optBoolean("active");
            String snoozed = str(s, "snoozed_until");
            if (!active) badge(h.badge, "Nonaktif", 0xFFE5E7EB, 0xFF374151);
            else if (snoozed != null) badge(h.badge, "⏸ s/d " + fmtDate(snoozed), 0xFFFFEDD5, 0xFF9A3412);
            else if (s.optBoolean("due_today")) badge(h.badge, "Hari ini", 0xFFFEF3C7, 0xFF92400E);
            else badge(h.badge, "Aktif", 0xFFD1FAE5, 0xFF065F46);
            h.content.setAlpha(active ? 1f : 0.6f);

            h.label.setText("🗓️ " + nz(str(s, "label")));
            StringBuilder d = new StringBuilder();
            line(d, "🧾 ", str(s, "items_label"));
            line(d, "📝 ", str(s, "note"));
            String gt = str(s, "galon_type");
            if ("KERAN".equals(gt)) line(d, "🚰 ", "Galon keran");
            else if ("NON_KERAN".equals(gt)) line(d, "🚰 ", "Galon non-keran");
            if (s.optBoolean("auto_tertunda")) {
                String dev = str(s, "auto_tertunda_device_name");
                line(d, "🅰️ ", "Pesanan tertunda otomatis jam " + nz(str(s, "auto_tertunda_time"), "—")
                        + (dev != null ? " → " + dev : "")
                        + (s.optBoolean("auto_tertunda_done_today") ? " · ✓ sudah dibuat hari ini" : ""));
                line(d, "💬 ", str(s, "auto_tertunda_note"));
            }
            h.detail.setText(d.toString());
            h.detail.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
            String lo = str(s, "last_order_date"), ls = str(s, "last_sent_date");
            h.meta.setText("Order terakhir: " + (lo != null ? fmtDate(lo) : "—")
                    + "  ·  Pengingat terakhir: " + (ls != null ? fmtDate(ls) : "—"));
            h.itemView.setOnClickListener(v -> showActions(s));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    // ------------------------------------------------------------------ util

    private void badge(TextView tv, String text, int bg, int fg) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(10));
        tv.setBackground(g);
        tv.setTextColor(fg);
        tv.setText(text);
    }

    private static void line(StringBuilder sb, String prefix, @Nullable String v) {
        if (v == null || v.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(prefix).append(v.trim());
    }

    private static String fmtDate(String ymd) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID")).format(in.parse(ymd.substring(0, 10)));
        } catch (Exception e) {
            return ymd;
        }
    }

    /** Nilai string JSON; null untuk kunci hilang / JSON null / kosong (optString memberi "null"). */
    @Nullable
    private static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }

    private static String nz(@Nullable String v) {
        return v != null ? v : "";
    }

    private static String nz(@Nullable String v, String fallback) {
        return v != null ? v : fallback;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11f);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        return t;
    }

    private RadioButton radio(String text) {
        RadioButton rb = new RadioButton(this);
        rb.setText(text);
        rb.setId(View.generateViewId());
        return rb;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
