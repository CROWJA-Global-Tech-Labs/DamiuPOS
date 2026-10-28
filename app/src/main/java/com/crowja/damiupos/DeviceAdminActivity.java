package com.crowja.damiupos;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.DeviceAdminRules;
import com.crowja.damiupos.util.Ts;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * ⚙️ Pengaturan Perangkat (Admin) — bagian NON-destruktif halaman web "Perangkat" langsung dari HP:
 * identitas (nama/ikon/warna/kode struk), pengiriman (delivery, muatan, umur antrean, wilayah
 * tambahan, konversi pengisian), sakelar, Petugas WA Perkenalan, Perangkat Marketing, Boleh Login,
 * dan perintah (sinkron / minta lokasi / pesan). Data hanya di server → semua lewat
 * /api/admin/devices dengan header X-Staff-Uuid; server memeriksa staf Admin aktif cabang ini.
 * Cabut / hapus / arsip / provisi ulang / setoran / versi tetap khusus owner di web.
 *
 * <p>Satu POST = satu bagian (sama dengan bagian server). Setelah tersimpan, data dimuat ulang dan
 * rincian digambar ulang dari balasan server.
 */
public class DeviceAdminActivity extends AppCompatActivity {

    private static final int C_TEXT = 0xFF212121;
    private static final int C_TEXT2 = 0xFF455A64;
    private static final int C_HINT = 0xFF757575;
    private static final int C_PRIMARY = 0xFF1565C0;
    private static final int C_GREEN = 0xFF2E7D32;
    private static final int C_LINE = 0xFFCFD8DC;

    private SyncSettings cfg;
    private String staffUuid;
    private Toolbar toolbar;
    private TextView tvStatus;
    private ScrollView svList, svDetail;
    private LinearLayout listBox, detailBox;

    /** Balasan terakhir GET /api/admin/devices; null = belum pernah berhasil. */
    @Nullable private JSONObject data;
    /** uuid perangkat yang rinciannya sedang dibuka; null = halaman daftar. */
    @Nullable private String openUuid;
    private boolean loading;
    /** Penahan listener sakelar saat nilainya dipasang/dikembalikan dari kode. */
    private boolean suppressSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_admin);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server (Pengaturan → Sinkronisasi).", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!UserDao.isCurrentUserAdmin(this)) {
            Toast.makeText(this, "Pengaturan Perangkat hanya untuk Admin.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        staffUuid = LocationReporter.currentStaffUuid(this);
        if (staffUuid == null || staffUuid.isEmpty()) {
            Toast.makeText(this, "Akun Admin ini belum tersinkron ke server — sinkronkan data dulu.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvStatus = findViewById(R.id.tvStatus);
        svList = findViewById(R.id.svList);
        svDetail = findViewById(R.id.svDetail);
        listBox = findViewById(R.id.listBox);
        detailBox = findViewById(R.id.detailBox);
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
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

    private void goBack() {
        if (openUuid != null) showList();
        else finish();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (loading) return;
        loading = true;
        tvStatus.setText("Memuat…");
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = new SyncApi(cfg).adminDevices(staffUuid);
                if (!r.optBoolean("ok", true)) {
                    err = nz(str(r, "message"), "Ditolak server.");
                    r = null;
                }
            } catch (SyncApi.SyncException se) {
                err = se.serverMessage();
                if (err == null) err = "Gagal memuat perangkat (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal memuat perangkat — periksa koneksi internet.";
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    tvStatus.setText(fe);
                    Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                    if (data == null) renderError(fe);
                    return;
                }
                data = fr;
                renderList();
                if (openUuid != null) {
                    JSONObject d = findDevice(openUuid);
                    if (d == null) {
                        Toast.makeText(this, "Perangkat ini sudah tidak ada (dicabut?).", Toast.LENGTH_LONG).show();
                        showList();
                    } else {
                        final int y = svDetail.getScrollY();
                        renderDetail(d);
                        svDetail.post(() -> svDetail.scrollTo(0, y));
                    }
                }
            });
        }).start();
    }

    /** Panggilan simpan di latar; sukses → toast pesan server + muat ulang; gagal → toast + {@code onFail}. */
    private void call(Callable<JSONObject> req, @Nullable View trigger, @Nullable Runnable onFail) {
        if (trigger != null) trigger.setEnabled(false);
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                JSONObject r = req.call();
                ok = r.optBoolean("ok", true);
                msg = nz(str(r, "message"), ok ? "Tersimpan." : "Ditolak server.");
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
                if (trigger != null) trigger.setEnabled(true);
                if (fok) load();
                else if (onFail != null) onFail.run();
            });
        }).start();
    }

    private void save(String deviceUuid, String section, JSONObject body, @Nullable View trigger,
                      @Nullable Runnable onFail) {
        call(() -> new SyncApi(cfg).adminDeviceUpdate(deviceUuid, section, body, staffUuid), trigger, onFail);
    }

    @Nullable
    private JSONObject findDevice(String uuid) {
        JSONArray arr = data != null ? data.optJSONArray("devices") : null;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && uuid.equals(str(o, "uuid"))) return o;
        }
        return null;
    }

    private JSONArray arr(String key) {
        JSONArray a = data != null ? data.optJSONArray(key) : null;
        return a != null ? a : new JSONArray();
    }

    // ------------------------------------------------------------------ halaman daftar

    private void showList() {
        openUuid = null;
        svDetail.setVisibility(View.GONE);
        svList.setVisibility(View.VISIBLE);
        toolbar.setTitle("Pengaturan Perangkat");
        if (data != null) renderList();
    }

    private void renderError(String msg) {
        listBox.removeAllViews();
        LinearLayout card = card(listBox);
        TextView t = text("⚠️ " + msg, 14, C_TEXT, false);
        card.addView(t);
        MaterialButton retry = button("🔄 Coba lagi", C_PRIMARY);
        retry.setOnClickListener(v -> load());
        card.addView(retry, topMargin(wrap(), 10));
    }

    private void renderList() {
        listBox.removeAllViews();
        JSONArray devs = arr("devices");
        int online = 0;
        for (int i = 0; i < devs.length(); i++) {
            JSONObject o = devs.optJSONObject(i);
            if (o != null && o.optBoolean("online")) online++;
        }
        tvStatus.setText(devs.length() + " perangkat · " + online + " online (15 menit terakhir)\nKetuk kartu untuk mengatur perangkat.");

        MaterialButton refresh = button("🔄 Muat ulang", 0xFF546E7A);
        refresh.setOnClickListener(v -> load());
        listBox.addView(refresh, topMargin(wrap(), 4));

        if (devs.length() == 0) {
            LinearLayout c = card(listBox);
            c.addView(text("Belum ada perangkat aktif di cabang ini.", 14, C_TEXT2, false));
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < devs.length(); i++) {
            JSONObject d = devs.optJSONObject(i);
            if (d == null) continue;
            LinearLayout c = card(listBox);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(circle(str(d, "display_icon"), str(d, "display_color"), 46));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);
            String name = nz(str(d, "name"), "(tanpa nama)") + (d.optBoolean("is_me") ? "  (HP ini)" : "");
            col.addView(text(name, 15, C_TEXT, true));
            col.addView(statusLine(d, now));
            List<String[]> chips = flagChips(d);
            if (!chips.isEmpty()) {
                Flow f = new Flow(this, dp(4));
                for (String[] ch : chips) f.addView(chip(ch[0], ch[1], ch[2]));
                col.addView(f, topMargin(matchWrap(), 6));
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView chev = text("›", 22, C_TEXT2, true);
            row.addView(chev);
            c.addView(row);
            final String uuid = str(d, "uuid");
            c.setOnClickListener(v -> {
                if (uuid != null) openDetail(uuid);
            });
        }

        listBox.addView(footerHint(), topMargin(matchWrap(), 12));
    }

    /** "● Online · terakhir aktif 3 menit lalu · v1.4.180". */
    private TextView statusLine(JSONObject d, long now) {
        boolean on = d.optBoolean("online");
        String seen = DeviceAdminRules.lastSeen(now, Ts.millis(str(d, "last_seen_at")));
        String ver = str(d, "app_version");
        TextView t = text((on ? "● Online" : "● Offline") + " · terakhir aktif " + seen
                + (ver != null ? " · v" + ver : ""), 12, on ? C_GREEN : 0xFF616161, false);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    /** Chip bendera aktif: {teks, warna latar, warna teks} — pasangan kontras ≥ 4.5:1. */
    private List<String[]> flagChips(JSONObject d) {
        List<String[]> out = new ArrayList<>();
        if (d.optBoolean("is_delivery")) out.add(new String[]{"🚚 Delivery", "#E3F2FD", "#0D47A1"});
        if (d.optBoolean("is_guided_delivery")) out.add(new String[]{"🧭 Guided", "#EDE7F6", "#4527A0"});
        if (d.optBoolean("delivery_proof_required")) out.add(new String[]{"📸 Wajib Foto", "#FFF3E0", "#BF360C"});
        if (d.optBoolean("claim_requires_approval")) out.add(new String[]{"✋ Butuh Persetujuan", "#FCE4EC", "#880E4F"});
        if (d.optBoolean("is_marketing")) out.add(new String[]{"📣 Marketing", "#E8F5E9", "#1B5E20"});
        if (d.optBoolean("is_intro_wa")) out.add(new String[]{"👋 WA Perkenalan", "#E0F2F1", "#004D40"});
        return out;
    }

    // ------------------------------------------------------------------ halaman rincian

    private void openDetail(String uuid) {
        JSONObject d = findDevice(uuid);
        if (d == null) return;
        openUuid = uuid;
        svList.setVisibility(View.GONE);
        svDetail.setVisibility(View.VISIBLE);
        renderDetail(d);
        svDetail.post(() -> svDetail.scrollTo(0, 0));
    }

    private void renderDetail(JSONObject d) {
        detailBox.removeAllViews();
        final String uuid = str(d, "uuid");
        if (uuid == null) return;
        toolbar.setTitle(nz(str(d, "name"), "Perangkat"));
        tvStatus.setText("Tiap bagian disimpan sendiri-sendiri. Setelah tersimpan, data dimuat ulang dari server "
                + "(isian bagian lain yang belum disimpan ikut kembali ke nilai server).");

        // Kepala: identitas + status
        LinearLayout head = card(detailBox);
        LinearLayout hr = new LinearLayout(this);
        hr.setOrientation(LinearLayout.HORIZONTAL);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        hr.addView(circle(str(d, "display_icon"), str(d, "display_color"), 54));
        LinearLayout hc = new LinearLayout(this);
        hc.setOrientation(LinearLayout.VERTICAL);
        hc.setPadding(dp(12), 0, 0, 0);
        hc.addView(text(nz(str(d, "name"), "(tanpa nama)") + (d.optBoolean("is_me") ? "  (HP ini)" : ""), 17, C_TEXT, true));
        hc.addView(statusLine(d, System.currentTimeMillis()));
        String rc = str(d, "receipt_code");
        hc.addView(text("Kode struk: " + (rc != null ? rc : "otomatis"), 12, C_TEXT2, false));
        hr.addView(hc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(hr);

        buildIdentity(d, uuid);
        buildFlags(d, uuid);
        buildIntroWa(d, uuid);
        buildMarketing(d, uuid);
        buildStaffLogins(d, uuid);
        buildCommands(d, uuid);
        detailBox.addView(footerHint(), topMargin(matchWrap(), 12));
    }

    // ---- Identitas & Pengiriman (POST identity — selalu form lengkap)

    private void buildIdentity(JSONObject d, String uuid) {
        LinearLayout s = section("🪪 Identitas", null);

        s.addView(label("Nama perangkat"));
        EditText etName = edit(str(d, "name"), "mis. Motor 1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        s.addView(etName);

        // Ikon — null = otomatis (display_icon). Server tak bisa mengosongkan ikon yang sudah
        // dipilih (icon null → tetap nilai lama), jadi "Otomatis" hanya ditawarkan selama belum dipilih.
        final String[] selIcon = {str(d, "icon")};
        final String[] selColor = {str(d, "color")};
        final boolean iconWasAuto = selIcon[0] == null, colorWasAuto = selColor[0] == null;
        final TextView preview = circle(str(d, "display_icon"), str(d, "display_color"), 44);
        final String autoIcon = str(d, "display_icon"), autoColor = str(d, "display_color");
        Runnable refreshPreview = () -> {
            preview.setText(nz(selIcon[0] != null ? selIcon[0] : autoIcon, "📱"));
            ((GradientDrawable) preview.getBackground()).setColor(
                    DeviceAdminRules.parseColor(selColor[0] != null ? selColor[0] : autoColor, 0xFF90A4AE));
        };

        LinearLayout pv = new LinearLayout(this);
        pv.setOrientation(LinearLayout.HORIZONTAL);
        pv.setGravity(Gravity.CENTER_VERTICAL);
        pv.addView(preview);
        TextView pvt = text("Pratinjau ikon & warna", 12, C_TEXT2, false);
        pvt.setPadding(dp(10), 0, 0, 0);
        pv.addView(pvt);
        s.addView(pv, topMargin(matchWrap(), 8));

        s.addView(label("Ikon"));
        Flow icons = new Flow(this, dp(6));
        List<TextView> iconViews = new ArrayList<>();
        List<String> iconVals = new ArrayList<>();
        if (iconWasAuto) iconVals.add(null);
        JSONArray ia = arr("icons");
        for (int i = 0; i < ia.length(); i++) iconVals.add(ia.optString(i));
        Runnable paintIcons = () -> {
            for (int i = 0; i < iconViews.size(); i++) {
                String v = iconVals.get(i);
                boolean sel = v == null ? selIcon[0] == null : v.equals(selIcon[0]);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(10));
                g.setColor(sel ? 0xFFBBDEFB : 0xFFECEFF1);
                g.setStroke(dp(sel ? 2 : 1), sel ? C_PRIMARY : C_LINE);
                iconViews.get(i).setBackground(g);
            }
        };
        for (String v : iconVals) {
            TextView t = new TextView(this);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(C_TEXT);
            if (v == null) {
                t.setText("Otomatis");
                t.setTextSize(11f);
                t.setPadding(dp(8), 0, dp(8), 0);
                t.setHeight(dp(42));
            } else {
                t.setText(v);
                t.setTextSize(20f);
                t.setWidth(dp(42));
                t.setHeight(dp(42));
            }
            t.setOnClickListener(x -> {
                selIcon[0] = v;
                paintIcons.run();
                refreshPreview.run();
            });
            iconViews.add(t);
            icons.addView(t);
        }
        paintIcons.run();
        s.addView(icons);

        s.addView(label("Warna"));
        Flow colors = new Flow(this, dp(6));
        List<TextView> colorViews = new ArrayList<>();
        List<String> colorVals = new ArrayList<>();
        if (colorWasAuto) colorVals.add(null);
        JSONArray ca = arr("colors");
        for (int i = 0; i < ca.length(); i++) colorVals.add(ca.optString(i));
        Runnable paintColors = () -> {
            for (int i = 0; i < colorViews.size(); i++) {
                String v = colorVals.get(i);
                boolean sel = v == null ? selColor[0] == null : v.equalsIgnoreCase(nz(selColor[0]));
                GradientDrawable g = new GradientDrawable();
                if (v == null) {
                    g.setCornerRadius(dp(10));
                    g.setColor(0xFFECEFF1);
                } else {
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(DeviceAdminRules.parseColor(v, 0xFF90A4AE));
                }
                g.setStroke(dp(sel ? 3 : 1), sel ? C_TEXT : C_LINE);
                TextView tv = colorViews.get(i);
                tv.setBackground(g);
                if (v != null) tv.setText(sel ? "✓" : "");
            }
        };
        for (String v : colorVals) {
            TextView t = new TextView(this);
            t.setGravity(Gravity.CENTER);
            t.setHeight(dp(36));
            if (v == null) {
                t.setText("Otomatis");
                t.setTextSize(11f);
                t.setTextColor(C_TEXT);
                t.setPadding(dp(8), 0, dp(8), 0);
            } else {
                t.setWidth(dp(36));
                t.setTextSize(16f);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                // Tanda centang: putih/hitam mengikuti terang-gelapnya warna supaya tetap terbaca.
                t.setTextColor(isLight(DeviceAdminRules.parseColor(v, 0xFF90A4AE)) ? C_TEXT : 0xFFFFFFFF);
            }
            t.setOnClickListener(x -> {
                selColor[0] = v;
                paintColors.run();
                refreshPreview.run();
            });
            colorViews.add(t);
            colors.addView(t);
        }
        paintColors.run();
        s.addView(colors);
        if (!iconWasAuto || !colorWasAuto) {
            s.addView(hint("Ikon/warna yang sudah dipilih tidak bisa dikembalikan ke otomatis dari sini — pilih ikon/warna lain."));
        }

        s.addView(label("Kode struk (maks 3 huruf/angka — kosong = otomatis dari nama)"));
        EditText etCode = edit(str(d, "receipt_code"), "otomatis", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        etCode.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        s.addView(etCode);

        // Pengiriman — bagian dari form identitas yang sama.
        s.addView(subhead("🚚 Pengiriman"));
        SwitchMaterial swDelivery = switchView("Perangkat delivery (bisa jadi tujuan pengantaran)", d.optBoolean("is_delivery"));
        s.addView(swDelivery);

        s.addView(label("Muatan maks (galon sekali jalan) — kosong = belum diatur"));
        EditText etLoad = edit(intText(d, "max_load"), "belum diatur", InputType.TYPE_CLASS_NUMBER);
        s.addView(etLoad);

        s.addView(label("Batas umur antrean (menit) — kosong = ikut setelan cabang"));
        EditText etAge = edit(intText(d, "delivery_max_age_minutes"), "ikut setelan cabang", InputType.TYPE_CLASS_NUMBER);
        s.addView(etAge);

        s.addView(label("Wilayah tambahan (wilayah perangkat delivery lain yang juga diterima)"));
        Map<String, JSONObject> byUuid = new LinkedHashMap<>();
        JSONArray devs = arr("devices");
        List<String> deliveryUuids = new ArrayList<>();
        for (int i = 0; i < devs.length(); i++) {
            JSONObject o = devs.optJSONObject(i);
            String u = o != null ? str(o, "uuid") : null;
            if (u == null) continue;
            byUuid.put(u, o);
            if (o.optBoolean("is_delivery")) deliveryUuids.add(u);
        }
        final List<String> candidates = DeviceAdminRules.extraWilayahCandidates(uuid, deliveryUuids);
        Set<String> current = toSet(d.optJSONArray("extra_wilayah"));
        final Map<String, CheckBox> extraBoxes = new LinkedHashMap<>();
        Map<String, List<String>> zonesByDevice = zonesByDevice();
        for (String u : candidates) {
            JSONObject o = byUuid.get(u);
            List<String> zl = zonesByDevice.get(u);
            String lbl = nz(str(o, "display_icon"), "🚚") + " " + nz(str(o, "name"), "(tanpa nama)")
                    + (zl != null && !zl.isEmpty() ? " — " + join(zl) : " — belum punya wilayah");
            CheckBox cb = check(lbl, current.contains(u));
            extraBoxes.put(u, cb);
            s.addView(cb);
        }
        if (candidates.isEmpty()) s.addView(hint("Tidak ada perangkat delivery lain di cabang ini."));

        s.addView(subhead("♻️ Konversi Pengisian"));
        s.addView(label("N galon \"Isi\" di Pengisian Live dari HP ini = 1 poin galon staf — kosong/0 = mati"));
        double ratio = d.isNull("fill_delivery_ratio") ? 0 : d.optDouble("fill_delivery_ratio", 0);
        EditText etRatio = edit(ratio > 0 ? DeviceAdminRules.plain(ratio) : null, "mati",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        s.addView(etRatio);

        MaterialButton btn = button("💾 Simpan Identitas & Pengiriman", C_PRIMARY);
        btn.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("Nama wajib diisi");
                etName.requestFocus();
                return;
            }
            try {
                JSONObject b = new JSONObject();
                b.put("name", name);
                b.put("icon", selIcon[0] != null ? selIcon[0] : JSONObject.NULL);
                b.put("color", selColor[0] != null ? selColor[0] : JSONObject.NULL);
                Integer ml = DeviceAdminRules.optPositiveInt(etLoad.getText().toString());
                b.put("max_load", ml != null ? (Object) ml : JSONObject.NULL);
                Integer age = DeviceAdminRules.optPositiveInt(etAge.getText().toString());
                b.put("delivery_max_age_minutes", age != null ? (Object) age : JSONObject.NULL);
                b.put("fill_delivery_ratio", DeviceAdminRules.ratio(etRatio.getText().toString()));
                b.put("is_delivery", swDelivery.isChecked());
                b.put("receipt_code", DeviceAdminRules.receiptCode(etCode.getText().toString()));
                Set<String> checked = new HashSet<>();
                for (Map.Entry<String, CheckBox> e : extraBoxes.entrySet()) {
                    if (e.getValue().isChecked()) checked.add(e.getKey());
                }
                JSONArray ex = new JSONArray();
                for (String u : DeviceAdminRules.extraWilayahToSend(candidates, checked)) ex.put(u);
                b.put("extra_wilayah", ex);
                b.put("extra_wilayah_form", true);
                save(uuid, "identity", b, btn, null);
            } catch (Exception e) {
                toast("Gagal menyusun data: " + e.getMessage());
            }
        });
        s.addView(btn, topMargin(matchWrap(), 12));
    }

    // ---- Sakelar (POST flags — satu kunci per ketukan, dikembalikan bila gagal)

    private void buildFlags(JSONObject d, String uuid) {
        LinearLayout s = section("🎚️ Sakelar", "Tersimpan langsung saat digeser.");
        boolean isDelivery = d.optBoolean("is_delivery");
        addFlag(s, uuid, d, "push_enabled", "Notifikasi push", null, true);
        addFlag(s, uuid, d, "collect_data", "Kumpulkan data (lokasi & aktivitas)", null, true);
        boolean guided = d.optBoolean("is_guided_delivery");
        addFlag(s, uuid, d, "is_guided_delivery", "🧭 Pengiriman Terpandu (Guided Delivery)",
                isDelivery ? null : "Hanya untuk perangkat delivery — aktifkan \"Perangkat delivery\" di Identitas dulu.",
                isDelivery || guided);
        addFlag(s, uuid, d, "delivery_proof_required", "📸 Wajib foto bukti antar", null, true);
        addFlag(s, uuid, d, "claim_requires_approval", "✋ Ambil alih pesanan butuh persetujuan", null, true);
    }

    private void addFlag(LinearLayout s, String uuid, JSONObject d, String key, String title,
                         @Nullable String note, boolean enabled) {
        SwitchMaterial sw = switchView(title, d.optBoolean(key));
        sw.setEnabled(enabled);
        s.addView(sw);
        if (note != null) s.addView(hint(note));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (suppressSwitch) return;
            try {
                JSONObject body = new JSONObject().put(key, on);
                save(uuid, "flags", body, sw, () -> {
                    suppressSwitch = true;
                    sw.setChecked(!on);
                    suppressSwitch = false;
                });
            } catch (Exception ignored) {
            }
        });
    }

    // ---- Petugas WA Perkenalan (POST intro-wa)

    private void buildIntroWa(JSONObject d, String uuid) {
        LinearLayout s = section("👋 Petugas WA Perkenalan", "Perangkat ini mengirim WA perkenalan ke pelanggan baru di wilayah terpilih.");
        SwitchMaterial sw = switchView("Jadikan Petugas WA Perkenalan", d.optBoolean("is_intro_wa"));
        s.addView(sw);
        JSONArray zones = arr("zones");
        Set<Integer> cur = new HashSet<>();
        JSONArray iz = d.optJSONArray("intro_wa_zones");
        for (int i = 0; iz != null && i < iz.length(); i++) cur.add(iz.optInt(i));
        final Map<Integer, CheckBox> boxes = new LinkedHashMap<>();
        if (zones.length() > 0) {
            s.addView(label("Wilayah (tidak ada yang dicentang = semua wilayah)"));
            Map<String, String> names = deviceNames();
            for (int i = 0; i < zones.length(); i++) {
                JSONObject z = zones.optJSONObject(i);
                if (z == null) continue;
                int idx = z.optInt("index", i);
                String dev = str(z, "device");
                String devName = dev != null ? names.get(dev) : null;
                CheckBox cb = check(nz(str(z, "label"), "Wilayah " + (idx + 1))
                        + (devName != null ? " · " + devName : ""), cur.contains(idx));
                boxes.put(idx, cb);
                s.addView(cb);
            }
        } else {
            s.addView(hint("Cabang ini belum punya pembagian wilayah — berlaku untuk semua pelanggan."));
        }
        MaterialButton btn = button("💾 Simpan WA Perkenalan", C_PRIMARY);
        btn.setOnClickListener(v -> {
            try {
                JSONArray zs = new JSONArray();
                for (Map.Entry<Integer, CheckBox> e : boxes.entrySet()) {
                    if (e.getValue().isChecked()) zs.put(e.getKey().intValue());
                }
                JSONObject b = new JSONObject().put("is_intro_wa", sw.isChecked()).put("intro_wa_zones", zs);
                save(uuid, "intro-wa", b, btn, null);
            } catch (Exception e) {
                toast("Gagal menyusun data: " + e.getMessage());
            }
        });
        s.addView(btn, topMargin(matchWrap(), 10));
    }

    // ---- Perangkat Marketing (POST marketing)

    private void buildMarketing(JSONObject d, String uuid) {
        LinearLayout s = section("📣 Perangkat Marketing", "Harga khusus per produk untuk HP ini. Kosong / sama dengan standar = ikut harga standar.");
        SwitchMaterial sw = switchView("Jadikan Perangkat Marketing", d.optBoolean("is_marketing"));
        s.addView(sw);
        JSONObject mp = d.optJSONObject("marketing_prices");
        JSONArray products = arr("products");
        final Map<String, EditText> inputs = new LinkedHashMap<>();
        final Map<String, Double> standard = new HashMap<>();
        for (int i = 0; i < products.length(); i++) {
            JSONObject p = products.optJSONObject(i);
            String pu = p != null ? str(p, "uuid") : null;
            if (pu == null) continue;
            double std = p.optDouble("harga_jual", Double.NaN);
            if (!Double.isNaN(std)) standard.put(pu, std);
            s.addView(label(nz(str(p, "name"), "(produk)") + (Double.isNaN(std) ? "" : " — standar " + DeviceAdminRules.rupiah(std))));
            String pre = null;
            if (mp != null && mp.has(pu) && !mp.isNull(pu)) {
                double v = mp.optDouble(pu, Double.NaN);
                if (!Double.isNaN(v)) pre = DeviceAdminRules.plain(v);
            }
            EditText et = edit(pre, Double.isNaN(std) ? "harga standar" : DeviceAdminRules.plain(std), InputType.TYPE_CLASS_NUMBER);
            inputs.put(pu, et);
            s.addView(et);
        }
        if (products.length() == 0) s.addView(hint("Belum ada produk di cabang ini."));
        MaterialButton btn = button("💾 Simpan Marketing", C_PRIMARY);
        btn.setOnClickListener(v -> {
            try {
                Map<String, String> raw = new LinkedHashMap<>();
                for (Map.Entry<String, EditText> e : inputs.entrySet()) raw.put(e.getKey(), e.getValue().getText().toString());
                JSONObject prices = new JSONObject();
                for (Map.Entry<String, Long> e : DeviceAdminRules.marketingPrices(raw, standard).entrySet()) {
                    prices.put(e.getKey(), e.getValue().longValue());
                }
                JSONObject b = new JSONObject().put("is_marketing", sw.isChecked()).put("marketing_prices", prices);
                save(uuid, "marketing", b, btn, null);
            } catch (Exception e) {
                toast("Gagal menyusun data: " + e.getMessage());
            }
        });
        s.addView(btn, topMargin(matchWrap(), 10));
    }

    // ---- Boleh Login (POST staff-logins)

    private void buildStaffLogins(JSONObject d, String uuid) {
        LinearLayout s = section("🔐 Boleh Login", "Staf yang boleh login di HP ini. Tidak ada yang dicentang = semua staf aktif boleh.");
        Set<String> cur = toSet(d.optJSONArray("staff_logins"));
        JSONArray staff = arr("staff");
        final Map<String, CheckBox> boxes = new LinkedHashMap<>();
        for (int i = 0; i < staff.length(); i++) {
            JSONObject st = staff.optJSONObject(i);
            String su = st != null ? str(st, "uuid") : null;
            if (su == null) continue;
            String role = str(st, "role");
            CheckBox cb = check(nz(str(st, "name"), "(tanpa nama)") + (role != null ? " · " + role : ""), cur.contains(su));
            boxes.put(su, cb);
            s.addView(cb);
        }
        if (boxes.isEmpty()) s.addView(hint("Belum ada staf aktif di server."));
        MaterialButton btn = button("💾 Simpan Boleh Login", C_PRIMARY);
        btn.setOnClickListener(v -> {
            try {
                JSONArray arr = new JSONArray();
                for (Map.Entry<String, CheckBox> e : boxes.entrySet()) {
                    if (e.getValue().isChecked()) arr.put(e.getKey());
                }
                save(uuid, "staff-logins", new JSONObject().put("staff", arr), btn, null);
            } catch (Exception e) {
                toast("Gagal menyusun data: " + e.getMessage());
            }
        });
        s.addView(btn, topMargin(matchWrap(), 10));
    }

    // ---- Perintah (POST command)

    private void buildCommands(JSONObject d, String uuid) {
        LinearLayout s = section("📡 Perintah", "Diantrikan di server dan dijalankan saat HP tujuan polling.");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton bSync = button("🔄 Sinkron", C_GREEN);
        MaterialButton bLoc = button("📍 Minta Lokasi", C_GREEN);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMarginEnd(dp(6));
        row.addView(bSync, lp1);
        row.addView(bLoc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        s.addView(row);
        bSync.setOnClickListener(v -> command(uuid, "sync", null, bSync));
        bLoc.setOnClickListener(v -> command(uuid, "locate", null, bLoc));

        s.addView(label("Pesan ke HP ini (maks 500 karakter)"));
        EditText etMsg = edit(null, "Tulis pesan…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        etMsg.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        etMsg.setMinLines(2);
        s.addView(etMsg);
        MaterialButton bMsg = button("✉️ Kirim Pesan", C_PRIMARY);
        bMsg.setOnClickListener(v -> {
            String m = etMsg.getText().toString().trim();
            if (m.isEmpty()) {
                etMsg.setError("Pesan wajib diisi");
                etMsg.requestFocus();
                return;
            }
            command(uuid, "message", m, bMsg);
        });
        s.addView(bMsg, topMargin(matchWrap(), 8));
    }

    private void command(String uuid, String cmd, @Nullable String body, View trigger) {
        try {
            JSONObject b = new JSONObject().put("cmd", cmd);
            if (body != null) b.put("body", body);
            save(uuid, "command", b, trigger, null);
        } catch (Exception e) {
            toast("Gagal menyusun data: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ data bantu

    /** uuid perangkat → label wilayah miliknya (dari {@code zones[].device}). */
    private Map<String, List<String>> zonesByDevice() {
        Map<String, List<String>> out = new HashMap<>();
        JSONArray zones = arr("zones");
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            String dev = z != null ? str(z, "device") : null;
            if (dev == null) continue;
            List<String> l = out.get(dev);
            if (l == null) {
                l = new ArrayList<>();
                out.put(dev, l);
            }
            l.add(nz(str(z, "label"), "Wilayah " + (z.optInt("index", i) + 1)));
        }
        return out;
    }

    private Map<String, String> deviceNames() {
        Map<String, String> out = new HashMap<>();
        JSONArray devs = arr("devices");
        for (int i = 0; i < devs.length(); i++) {
            JSONObject o = devs.optJSONObject(i);
            String u = o != null ? str(o, "uuid") : null;
            if (u != null) out.put(u, nz(str(o, "name"), "(tanpa nama)"));
        }
        return out;
    }

    private static Set<String> toSet(@Nullable JSONArray a) {
        Set<String> s = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String v = a.optString(i, "");
            if (!v.isEmpty()) s.add(v);
        }
        return s;
    }

    @Nullable
    private static String intText(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        int v = o.optInt(k, 0);
        return v > 0 ? String.valueOf(v) : null;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** Nilai string JSON; null untuk kunci hilang / JSON null / kosong (optString memberi "null"). */
    @Nullable
    private static String str(@Nullable JSONObject o, String k) {
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

    private static boolean isLight(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160;
    }

    // ------------------------------------------------------------------ pembangun tampilan

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), 0xFFE0E0E0);
        c.setBackground(g);
        parent.addView(c, topMargin(matchWrap(), 8));
        return c;
    }

    private LinearLayout section(String title, @Nullable String sub) {
        LinearLayout c = card(detailBox);
        c.addView(text(title, 16, C_PRIMARY, true));
        if (sub != null) c.addView(hint(sub));
        return c;
    }

    private TextView subhead(String t) {
        TextView v = text(t, 14, C_PRIMARY, true);
        v.setPadding(0, dp(16), 0, dp(2));
        return v;
    }

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView label(String t) {
        TextView v = text(t, 12, C_TEXT2, false);
        v.setPadding(0, dp(10), 0, dp(2));
        return v;
    }

    private TextView hint(String t) {
        TextView v = text(t, 12, C_TEXT2, false);
        v.setPadding(0, dp(4), 0, dp(2));
        return v;
    }

    private TextView footerHint() {
        TextView t = text("ℹ️ Cabut akses, hapus, arsip, provisi ulang, setoran, dan versi aplikasi tetap khusus owner di dashboard web.",
                12, C_TEXT2, false);
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private EditText edit(@Nullable String value, String hintText, int inputType) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setText(value != null ? value : "");
        e.setHint(hintText);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_HINT);
        e.setTextSize(15f);
        return e;
    }

    private CheckBox check(String t, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(t);
        cb.setTextColor(C_TEXT);
        cb.setTextSize(14f);
        cb.setChecked(on);
        return cb;
    }

    private SwitchMaterial switchView(String t, boolean on) {
        SwitchMaterial sw = new SwitchMaterial(this);
        suppressSwitch = true;
        sw.setChecked(on);
        suppressSwitch = false;
        sw.setText(t);
        sw.setTextColor(C_TEXT);
        sw.setTextSize(14f);
        sw.setMinHeight(dp(48));
        return sw;
    }

    private MaterialButton button(String t, int bg) {
        MaterialButton b = new MaterialButton(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setCornerRadius(dp(10));
        return b;
    }

    /** Lingkaran berwarna berisi emoji identitas perangkat. */
    private TextView circle(@Nullable String icon, @Nullable String color, int sizeDp) {
        TextView t = new TextView(this);
        t.setText(nz(icon, "📱"));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(sizeDp * 0.42f);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(DeviceAdminRules.parseColor(color, 0xFF90A4AE));
        t.setBackground(g);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    private TextView chip(String t, String bg, String fg) {
        TextView v = text(t, 11, DeviceAdminRules.parseColor(fg, C_TEXT), true);
        v.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable g = new GradientDrawable();
        g.setColor(DeviceAdminRules.parseColor(bg, 0xFFECEFF1));
        g.setCornerRadius(dp(10));
        v.setBackground(g);
        return v;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams lp, int dpTop) {
        lp.topMargin = dp(dpTop);
        return lp;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Tata letak alir sederhana: anak berjajar kiri→kanan, pindah baris saat penuh. */
    private static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(widthSpec);
            int maxW = (mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE / 2 : MeasureSpec.getSize(widthSpec))
                    - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0, usedW = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                ch.measure(MeasureSpec.makeMeasureSpec(Math.max(0, maxW), MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += cw + gap;
                usedW = Math.max(usedW, x - gap);
                rowH = Math.max(rowH, chh);
            }
            int w = mode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec)
                    : usedW + getPaddingLeft() + getPaddingRight();
            if (mode == MeasureSpec.AT_MOST) w = Math.min(w, MeasureSpec.getSize(widthSpec));
            int h = y + rowH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(w, resolveSize(h, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                ch.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + chh);
                x += cw + gap;
                rowH = Math.max(rowH, chh);
            }
        }
    }
}
