package com.crowja.damiupos.wa;

import static com.crowja.damiupos.wa.WaAccountsUi.AMBER_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.AMBER_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.BLUE_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.BLUE_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.CARD_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.CARD_STROKE;
import static com.crowja.damiupos.wa.WaAccountsUi.GREEN_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.GREEN_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.RED_BG;
import static com.crowja.damiupos.wa.WaAccountsUi.RED_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.TEXT;
import static com.crowja.damiupos.wa.WaAccountsUi.TEXT_2;
import static com.crowja.damiupos.wa.WaAccountsUi.nz;
import static com.crowja.damiupos.wa.WaAccountsUi.str;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 📱 Akun WhatsApp (Admin) — cermin halaman web /wa-accounts: daftar akun WA Bridge cabang dengan status
 * tautan, nomor, kuota proaktif per jam/hari, akun default, perangkat & urutan cadangan, izin jenis pesan;
 * tambah akun (pilih akun Bridge yang ada / buat baru), ubah (menu ⋯), jadikan default, buka halaman QR.
 * Ketuk kartu → {@link WaAccountsConsoleActivity}. Setelan Auto-Send → {@link WaAccountsSettingsActivity}.
 * Semua lewat {@code /api/admin/wa/accounts/*}; server memeriksa staf Admin aktif cabang ini.
 *
 * <p>Menghapus akun TIDAK tersedia: di web pun hanya Owner (aturan "aksi destruktif = owner-only").
 */
public class WaAccountsActivity extends AppCompatActivity {

    /** Singkatan jenis pesan untuk baris "🚫 …" (urutan sama dengan form ubah). */
    private static final String[][] TYPE_SHORT = {
            {"send_followup", "follow-up"}, {"send_intro", "perkenalan"}, {"send_struk", "struk"},
            {"send_obstacle", "kendala"}, {"send_diantar", "diantar"}, {"send_reschedule", "jadwal-ulang"},
            {"send_broadcast", "broadcast"}, {"send_apology", "permintaan maaf"},
    };

    private String staffUuid;
    private TextView tvBridge, tvSummary, tvEmpty;
    private ProgressBar pb;
    private final List<JSONObject> accounts = new ArrayList<>();
    private JSONArray unmapped = new JSONArray();
    private JSONArray devices = new JSONArray();
    private final Map<String, String> typeLabels = new LinkedHashMap<>();
    private boolean loading;
    private boolean loadedOnce;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setContentView(R.layout.activity_wa_accounts);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvBridge = findViewById(R.id.tvBridge);
        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        pb = findViewById(R.id.pbLoading);
        RecyclerView rv = findViewById(R.id.rvAccounts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        tvEmpty.setOnClickListener(v -> {
            if (!loadedOnce) load();
        });
        findViewById(R.id.fabAdd).setOnClickListener(v -> {
            if (!loadedOnce) {
                toast("Tunggu daftar akun selesai dimuat.");
                return;
            }
            showAdd();
        });
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, WaAccountsSettingsActivity.class)));
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 2, 1, "⚙️ Setelan Auto-Send").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 3, 2, "ℹ️ Cara pakai").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            load();
            return true;
        }
        if (item.getItemId() == 2) {
            startActivity(new Intent(this, WaAccountsSettingsActivity.class));
            return true;
        }
        if (item.getItemId() == 3) {
            showHowTo();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (loading) return;
        loading = true;
        pb.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        tvSummary.setText("Memuat…");
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaGet("accounts", null, staffUuid), r -> {
            loading = false;
            loadedOnce = true;
            pb.setVisibility(View.GONE);
            bind(r);
        }, msg -> {
            loading = false;
            pb.setVisibility(View.GONE);
            tvSummary.setText("Gagal memuat.");
            if (!loadedOnce) {
                tvEmpty.setText("✕ " + msg + "\n\nKetuk untuk coba lagi.");
                tvEmpty.setTextColor(RED_FG);
                tvEmpty.setVisibility(View.VISIBLE);
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private void bind(JSONObject r) {
        String branch = str(r, "branch_name");
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle(branch);

        boolean bridgeOk = r.optBoolean("bridge_ok");
        tvBridge.setVisibility(View.VISIBLE);
        if (bridgeOk) {
            tvBridge.setText("● Bridge terhubung — semua tombol kirim WA mengirim langsung lewat akun default di bawah.");
            tvBridge.setTextColor(GREEN_FG);
            tvBridge.setBackground(WaAccountsUi.rounded(this, GREEN_BG, 0, 10));
        } else {
            String err = str(r, "bridge_error");
            tvBridge.setText("✕ Bridge tidak terhubung." + (err != null ? " " + err : "")
                    + "\nLabel/catatan tetap bisa diatur, tapi status akun & kirim WA tidak berfungsi sampai Bridge kembali terhubung.");
            tvBridge.setTextColor(RED_FG);
            tvBridge.setBackground(WaAccountsUi.rounded(this, RED_BG, 0, 10));
        }

        accounts.clear();
        JSONArray arr = r.optJSONArray("accounts");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) accounts.add(o);
        }
        JSONArray um = r.optJSONArray("unmapped");
        unmapped = um != null ? um : new JSONArray();
        JSONArray dv = r.optJSONArray("devices");
        devices = dv != null ? dv : new JSONArray();
        typeLabels.clear();
        JSONObject tl = r.optJSONObject("type_labels");
        if (tl != null) {
            Iterator<String> it = tl.keys();
            while (it.hasNext()) {
                String k = it.next();
                typeLabels.put(k, tl.optString(k, k));
            }
        }
        adapter.notifyDataSetChanged();

        int def = 0;
        for (JSONObject a : accounts) if (a.optBoolean("is_active")) def++;
        tvSummary.setText(accounts.isEmpty() ? "Belum ada akun."
                : "Akun terdaftar: " + accounts.size() + (def == 0 ? " · belum ada akun default!" : "")
                + "\n🧪 Ketuk kartu akun untuk membuka Console Test (kirim pesan uji + lihat inbox).");
        tvEmpty.setTextColor(TEXT_2);
        tvEmpty.setText("Belum ada akun WhatsApp dipetakan.\nKetuk \"Tambah Akun\" — sebelum itu, tombol kirim WA akan menolak "
                + "dengan pesan \"belum ada akun default\".");
        tvEmpty.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ aksi

    private void openConsole(JSONObject a) {
        Intent i = new Intent(this, WaAccountsConsoleActivity.class);
        i.putExtra(WaAccountsConsoleActivity.EXTRA_ACCOUNT, str(a, "bridge_account"));
        i.putExtra(WaAccountsConsoleActivity.EXTRA_LABEL, str(a, "label"));
        startActivity(i);
    }

    private void activate(JSONObject a) {
        final long id = a.optLong("id");
        tvSummary.setText("Menyimpan…");
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaPost("accounts/" + id + "/activate", new JSONObject(), staffUuid),
                r -> {
                    toast(WaAdmin.message(r, "Akun default diperbarui."));
                    load();
                }, msg -> {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    load();
                });
    }

    private void showQr(JSONObject a) {
        final String url = str(a, "qr_url");
        if (url == null) {
            toast("Akun ini belum punya halaman QR.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("📷 Scan QR — " + nz(str(a, "label")))
                .setMessage("Halaman QR Bridge untuk akun " + nz(str(a, "bridge_account")) + " dibuka di browser. Pindai dengan HP "
                        + "pemilik nomor: WhatsApp → Perangkat tertaut → Tautkan perangkat.\n\nSetelah tertaut, muat ulang daftar ini.")
                .setPositiveButton("Buka", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        toast("Tidak ada browser untuk membuka tautan.");
                    }
                })
                .setNeutralButton("Salin tautan", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("QR WhatsApp", url));
                    toast("Tautan QR disalin.");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showHowTo() {
        new AlertDialog.Builder(this)
                .setTitle("Cara pakai")
                .setMessage("1. Tambahkan akun — pilih akun Bridge yang sudah ada, atau buat baru.\n"
                        + "2. Kalau belum pernah discan, ketuk 📷 Scan QR lalu pindai dengan HP pemilik nomor.\n"
                        + "3. Ketuk \"Jadikan Default\" pada akun yang ingin dipakai mengirim. Hanya SATU akun default per cabang — "
                        + "itulah nomor yang benar-benar dipakai tiap kirim.\n"
                        + "4. Setiap tombol \"kirim WhatsApp\" sejak itu mengirim lewat nomor tersebut.\n\n"
                        + "Kuota kirim proaktif: pesan follow-up/notifikasi (bukan balasan) dibatasi per akun oleh Bridge "
                        + "untuk menghindari pola blast yang dideteksi WhatsApp. Habis jatah → kirim ditolak, coba lagi nanti.")
                .setPositiveButton("Tutup", null)
                .show();
    }

    /** Dialog yang tombol positifnya TIDAK menutup otomatis — tetap terbuka saat server menolak. */
    private AlertDialog formDialog(String title, View content, String positive) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        return new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(positive, null)
                .setNegativeButton("Batal", null)
                .create();
    }

    private TextView errorLine() {
        TextView t = WaAccountsUi.text(this, "", 13f, RED_FG, false);
        t.setBackground(WaAccountsUi.rounded(this, RED_BG, 0, 8));
        int p = WaAccountsUi.dp(this, 8);
        t.setPadding(p, p, p, p);
        t.setVisibility(View.GONE);
        return t;
    }

    private void submit(AlertDialog d, TextView err, String path, JSONObject body, @Nullable OnSaved onSaved) {
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        pos.setEnabled(false);
        err.setVisibility(View.GONE);
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaPost(path, body, staffUuid), r -> {
            d.dismiss();
            toast(WaAdmin.message(r, "Tersimpan."));
            load();
            if (onSaved != null) onSaved.saved(r);
        }, msg -> {
            pos.setEnabled(true);
            err.setText("✕ " + msg);
            err.setVisibility(View.VISIBLE);
        });
    }

    private interface OnSaved {
        void saved(JSONObject r);
    }

    private void showAdd() {
        final int pad = WaAccountsUi.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, WaAccountsUi.dp(this, 8), pad, WaAccountsUi.dp(this, 4));
        final TextView err = errorLine();
        box.addView(err);

        box.addView(WaAccountsUi.label(this, "Nama akun (tampilan di dashboard)"));
        final EditText etLabel = WaAccountsUi.input(this, null, "CS Depot", 60,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(etLabel);

        final Spinner spExisting = new Spinner(this);
        final List<String> names = new ArrayList<>();
        if (unmapped.length() > 0) {
            box.addView(WaAccountsUi.label(this, "Akun Bridge yang sudah ada"));
            List<String> shown = new ArrayList<>();
            names.add(null);
            shown.add("— pilih —");
            for (int i = 0; i < unmapped.length(); i++) {
                String n = unmapped.optString(i, "");
                if (n.isEmpty()) continue;
                names.add(n);
                shown.add(n);
            }
            ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, shown);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spExisting.setAdapter(ad);
            box.addView(spExisting);
            box.addView(WaAccountsUi.hint(this, "— atau —"));
        }
        box.addView(WaAccountsUi.label(this, "Buat akun BARU di Bridge"));
        final EditText etNew = WaAccountsUi.input(this, null, "mis. ADMIN2", 40,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(etNew);
        box.addView(WaAccountsUi.hint(this, "Kosong & harus discan QR dari awal — pastikan ini nomor yang belum pernah dipakai."));
        box.addView(WaAccountsUi.label(this, "Catatan (opsional)"));
        final EditText etNote = WaAccountsUi.input(this, null, "mis. HP nomor yang dipegang siapa", 200,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(etNote);

        final AlertDialog d = formDialog("Tambah Akun", box, "Tambah");
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = etLabel.getText().toString().trim();
            String existing = unmapped.length() > 0 && spExisting.getSelectedItemPosition() > 0
                    ? names.get(spExisting.getSelectedItemPosition()) : null;
            String fresh = etNew.getText().toString().trim();
            if (label.isEmpty()) {
                err.setText("✕ Nama akun wajib diisi.");
                err.setVisibility(View.VISIBLE);
                return;
            }
            if (existing == null && fresh.isEmpty()) {
                err.setText("✕ Pilih akun Bridge yang sudah ada, atau isi nama akun baru.");
                err.setVisibility(View.VISIBLE);
                return;
            }
            JSONObject b = new JSONObject();
            try {
                b.put("label", label);
                b.put("bridge_account", existing != null ? existing : JSONObject.NULL);
                b.put("new_bridge_account", existing == null ? fresh : JSONObject.NULL);
                b.put("note", WaAccountsUi.strOrNull(etNote));
            } catch (Exception ignored) {
            }
            submit(d, err, "accounts", b, r -> {
                // Pesan web: "Kalau belum pernah discan, buka halaman QR-nya dulu." → tawarkan langsung.
                JSONObject fake = new JSONObject();
                try {
                    fake.put("label", label);
                    fake.put("bridge_account", str(r, "bridge_account"));
                    fake.put("qr_url", str(r, "qr_url"));
                } catch (Exception ignored) {
                }
                if (str(fake, "qr_url") != null) showQr(fake);
            });
        }));
        d.show();
    }

    private void showEdit(JSONObject a) {
        final long id = a.optLong("id");
        final int pad = WaAccountsUi.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, WaAccountsUi.dp(this, 8), pad, WaAccountsUi.dp(this, 4));
        final TextView err = errorLine();
        box.addView(err);

        box.addView(WaAccountsUi.label(this, "Nama akun"));
        final EditText etLabel = WaAccountsUi.input(this, str(a, "label"), "Nama akun", 60,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(etLabel);
        box.addView(WaAccountsUi.label(this, "Catatan"));
        final EditText etNote = WaAccountsUi.input(this, str(a, "note"), "Catatan", 200,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(etNote);

        box.addView(WaAccountsUi.label(this, "Ditugaskan ke perangkat"));
        final List<String> devUuids = new ArrayList<>();
        List<String> devNames = new ArrayList<>();
        devUuids.add(null);
        devNames.add("— tidak ditugaskan ke perangkat —");
        String cur = str(a, "device_uuid");
        int sel = 0;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject dv = devices.optJSONObject(i);
            if (dv == null || str(dv, "uuid") == null) continue;
            devUuids.add(str(dv, "uuid"));
            devNames.add(nz(str(dv, "name")));
            if (str(dv, "uuid").equals(cur)) sel = devUuids.size() - 1;
        }
        if (cur != null && sel == 0) {   // perangkat sudah terhapus — tetap tampil agar tak hilang diam-diam
            devUuids.add(cur);
            devNames.add("perangkat terhapus");
            sel = devUuids.size() - 1;
        }
        final Spinner spDev = new Spinner(this);
        ArrayAdapter<String> da = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devNames);
        da.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDev.setAdapter(da);
        spDev.setSelection(sel);
        box.addView(spDev);
        box.addView(WaAccountsUi.hint(this, "Dipakai mode \"Otomatis berdasarkan perangkat\" di Auto-Send."));

        box.addView(WaAccountsUi.label(this, "Urutan cadangan"));
        final EditText etPrio = WaAccountsUi.number(this, a.isNull("fallback_priority") ? null : String.valueOf(a.optInt("fallback_priority")),
                "kosongkan = tidak ikut", 3);
        box.addView(etPrio);
        box.addView(WaAccountsUi.hint(this, "Kalau akun pengirim auto-send offline, dicoba akun cadangan berangka paling kecil duluan. "
                + "Kosongkan supaya akun ini tidak pernah dipakai sebagai cadangan."));

        box.addView(WaAccountsUi.label(this, "Maks blast follow-up sekali eksekusi"));
        final EditText etBlast = WaAccountsUi.number(this, a.isNull("followup_max_blast") ? null : String.valueOf(a.optInt("followup_max_blast")),
                "kosongkan = tanpa batas", 4);
        box.addView(etBlast);
        box.addView(WaAccountsUi.hint(this, "Batas jumlah follow-up otomatis yang diantre lewat akun ini setiap kali jadwal follow-up "
                + "otomatis berjalan. Berlaku per akun ini saja, bukan per cabang."));

        box.addView(WaAccountsUi.label(this, "Akun ini boleh dipakai kirim:"));
        final Map<String, CheckBox> flags = new LinkedHashMap<>();
        for (String[] t : TYPE_SHORT) {
            CheckBox cb = new CheckBox(this);
            String lbl = typeLabels.containsKey(t[0]) ? typeLabels.get(t[0]) : t[1];
            cb.setText(lbl);
            cb.setTextColor(TEXT);
            cb.setChecked(a.optBoolean(t[0], true));
            box.addView(cb);
            flags.put(t[0], cb);
        }
        TextView lock = WaAccountsUi.hint(this, "🔒 Menghapus akun dari DAMIU POS hanya bisa dilakukan Owner lewat dashboard web.");
        lock.setPadding(0, WaAccountsUi.dp(this, 10), 0, 0);
        box.addView(lock);

        final AlertDialog d = formDialog("⋯ " + nz(str(a, "label")) + " (" + nz(str(a, "bridge_account")) + ")", box, "Simpan");
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (etLabel.getText().toString().trim().isEmpty()) {
                err.setText("✕ Nama akun wajib diisi.");
                err.setVisibility(View.VISIBLE);
                return;
            }
            JSONObject b = new JSONObject();
            try {
                b.put("label", etLabel.getText().toString().trim());
                b.put("note", WaAccountsUi.strOrNull(etNote));
                String du = devUuids.get(Math.max(0, spDev.getSelectedItemPosition()));
                b.put("device_uuid", du != null ? du : JSONObject.NULL);
                b.put("fallback_priority", WaAccountsUi.intOrNull(etPrio));
                b.put("followup_max_blast", WaAccountsUi.intOrNull(etBlast));
                for (Map.Entry<String, CheckBox> e : flags.entrySet()) b.put(e.getKey(), e.getValue().isChecked());
            } catch (Exception ignored) {
            }
            submit(d, err, "accounts/" + id + "/update", b, null);
        }));
        d.show();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ daftar

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final LinearLayout box;

            VH(LinearLayout v) {
                super(v);
                box = v;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(parent.getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = WaAccountsUi.dp(WaAccountsActivity.this, 10);
            box.setLayoutParams(lp);
            int p = WaAccountsUi.dp(WaAccountsActivity.this, 12);
            box.setPadding(p, p, p, p);
            box.setBackground(WaAccountsUi.rounded(WaAccountsActivity.this, CARD_BG, CARD_STROKE, 12));
            box.setClickable(true);
            box.setFocusable(true);
            return new VH(box);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final JSONObject a = accounts.get(position);
            final AppCompatActivity c = WaAccountsActivity.this;
            LinearLayout box = h.box;
            box.removeAllViews();
            box.setOnClickListener(v -> openConsole(a));

            // Baris 1: nama + kode akun Bridge
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = WaAccountsUi.text(c, nz(str(a, "label")), 16f, TEXT, true);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView code = WaAccountsUi.text(c, nz(str(a, "bridge_account")), 12f, TEXT_2, false);
            code.setTypeface(Typeface.MONOSPACE);
            top.addView(code);
            box.addView(top);

            // Badge: default + status tautan (persis label web)
            LinearLayout badges = new LinearLayout(c);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setPadding(0, WaAccountsUi.dp(c, 4), 0, 0);
            if (a.optBoolean("is_active")) addBadge(badges, WaAccountsUi.badge(c, "● default", BLUE_FG, BLUE_BG));
            String state = nz(str(a, "state"));
            String stLabel = str(a, "state_label");
            if ("linked".equals(state)) addBadge(badges, WaAccountsUi.badge(c, "tertaut", GREEN_FG, GREEN_BG));
            else if ("unlinked".equals(state)) addBadge(badges, WaAccountsUi.badge(c, stLabel != null ? stLabel : "belum tertaut", AMBER_FG, AMBER_BG));
            else if ("missing".equals(state)) addBadge(badges, WaAccountsUi.badge(c, "tidak ditemukan di Bridge", RED_FG, RED_BG));
            if (badges.getChildCount() > 0) box.addView(badges);

            // Detail: nomor · nama WA · catatan · 🤖 perangkat · ↩️ cadangan
            StringBuilder sb = new StringBuilder();
            dot(sb, str(a, "wa_phone"));
            dot(sb, str(a, "wa_name"));
            dot(sb, str(a, "note"));
            if (str(a, "device_uuid") != null) dot(sb, "🤖 " + nz(str(a, "device_name")));
            if (!a.isNull("fallback_priority") && a.has("fallback_priority")) dot(sb, "↩️ cadangan #" + a.optInt("fallback_priority"));
            if (sb.length() > 0) {
                TextView det = WaAccountsUi.text(c, sb.toString(), 13f, TEXT_2, false);
                det.setPadding(0, WaAccountsUi.dp(c, 4), 0, 0);
                box.addView(det);
            }
            List<String> excluded = new ArrayList<>();
            for (String[] t : TYPE_SHORT) if (a.has(t[0]) && !a.optBoolean(t[0], true)) excluded.add(t[1]);
            if (!excluded.isEmpty()) {
                TextView ex = WaAccountsUi.text(c, "🚫 tidak dipakai untuk: " + android.text.TextUtils.join(", ", excluded), 12f, AMBER_FG, false);
                ex.setPadding(0, WaAccountsUi.dp(c, 2), 0, 0);
                box.addView(ex);
            }

            // Kuota proaktif per jam / hari
            JSONObject q = a.optJSONObject("quota");
            if (q != null) {
                LinearLayout qrow = new LinearLayout(c);
                qrow.setOrientation(LinearLayout.HORIZONTAL);
                qrow.setPadding(0, WaAccountsUi.dp(c, 6), 0, 0);
                qrow.addView(quota(c, "Kuota/jam", q.optInt("hour_used"), q.optInt("hour_limit")),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                View gap = new View(c);
                qrow.addView(gap, new LinearLayout.LayoutParams(WaAccountsUi.dp(c, 16), 1));
                qrow.addView(quota(c, "Kuota/hari", q.optInt("day_used"), q.optInt("day_limit")),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                box.addView(qrow);
            }

            // Aksi: Scan QR · Jadikan Default · ⋯ Ubah (klik tombol tak membuka console)
            LinearLayout actions = new LinearLayout(c);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setPadding(0, WaAccountsUi.dp(c, 6), 0, 0);
            if (a.optBoolean("show_qr")) {
                MaterialButton qr = new MaterialButton(c);
                qr.setText("📷 Scan QR");
                qr.setAllCaps(false);
                qr.setTextColor(0xFFFFFFFF);
                qr.setBackgroundTintList(ColorStateList.valueOf(0xFF1565C0));   // 5,8:1 dengan teks putih
                qr.setOnClickListener(v -> showQr(a));
                actions.addView(qr);
            }
            if (!a.optBoolean("is_active")) {
                MaterialButton def = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                def.setText("Jadikan Default");
                def.setAllCaps(false);
                def.setTextColor(BLUE_FG);
                def.setStrokeColor(ColorStateList.valueOf(0xFF90A4AE));
                def.setOnClickListener(v -> activate(a));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = WaAccountsUi.dp(c, 8);
                actions.addView(def, lp);
            }
            MaterialButton more = new MaterialButton(c, null, com.google.android.material.R.attr.borderlessButtonStyle);
            more.setText("⋯ Ubah");
            more.setAllCaps(false);
            more.setTextColor(TEXT_2);
            more.setOnClickListener(v -> showEdit(a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = WaAccountsUi.dp(c, 4);
            actions.addView(more, lp);
            box.addView(actions);
        }

        @Override
        public int getItemCount() {
            return accounts.size();
        }
    }

    private void addBadge(LinearLayout row, TextView b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = WaAccountsUi.dp(this, 6);
        row.addView(b, lp);
    }

    private static void dot(StringBuilder sb, @Nullable String v) {
        if (v == null || v.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(v.trim());
    }

    /** "Kuota/jam 3/20" + bilah: hijau < 70%, kuning ≥ 70%, merah ≥ 90% (ambang sama dengan web). */
    private View quota(AppCompatActivity c, String label, int used, int limit) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = WaAccountsUi.text(c, label, 11f, TEXT_2, false);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(WaAccountsUi.text(c, used + "/" + limit, 11f, TEXT_2, true));
        col.addView(head);
        int pct = limit > 0 ? Math.min(100, Math.round(used * 100f / limit)) : 0;
        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(pct);
        int color = pct >= 90 ? 0xFFC62828 : pct >= 70 ? 0xFFF9A825 : 0xFF2E7D32;
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCFD8DC));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WaAccountsUi.dp(c, 8)));
        return col;
    }
}
