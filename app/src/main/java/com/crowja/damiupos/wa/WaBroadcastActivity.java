package com.crowja.damiupos.wa;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.R;
import com.crowja.damiupos.sync.SyncApi;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 📣 Broadcast WhatsApp (Admin) — cermin halaman web /wa-broadcast. Admin memilih SATU dari lima audiens
 * (Antrean Pengiriman Aktif / Tertunda / Kunjungan Urgent → pratinjau nama + nomor dari server; Pelanggan per
 * Produk → pembeli produk terpilih yang masih bisa dikoreksi; Pilihan Bebas → dicari sendiri), menulis pesan
 * (maks 2000 karakter, {nama} = nama pelanggan), lalu Kirim dengan konfirmasi jumlah penerima. Mesin kirim,
 * pemilihan akun, opt-out, dan antrean sisa (yang tertahan jeda antar pesan WA → Broadcast Terjadwal) semuanya
 * di server ({@code /api/admin/wa/broadcast/*}) — sama persis dengan web. Riwayat: menu 🕒.
 *
 * <p>Light-only: semua pasangan teks/latar ≥ 4,5:1 (teks #212121/#37474F/#455A64 di atas putih/#ECEFF1/#E3F2FD).
 */
public class WaBroadcastActivity extends AppCompatActivity {

    private static final String[] DAY_LABELS = {"Pesanan aktif hari ini", "30 hari terakhir", "60 hari", "90 hari",
            "180 hari", "1 tahun", "Semua waktu"};
    private static final String[] DAY_VALUES = {"active_today", "30", "60", "90", "180", "365", "0"};
    private static final int DAY_DEFAULT = 3;   // 90 hari — sama dengan web
    /** Baris yang ditampilkan langsung; sisanya lewat dialog (pembeli produk bisa ratusan — HP lama). */
    private static final int INLINE_MAX = 10;
    private static final int MENU_HISTORY = 1, MENU_RELOAD = 2;

    private static final int C_TEXT = 0xFF212121, C_TEXT2 = 0xFF37474F, C_MUTED = 0xFF455A64;
    private static final int C_PRIMARY = 0xFF1565C0, C_SEL_BG = 0xFFE3F2FD, C_LINE = 0xFFB0BEC5;
    private static final int C_DANGER = 0xFFB71C1C;

    private String staffUuid;
    private SyncApi api;

    private SwipeRefreshLayout swipe;
    private View boxState, form;
    private ProgressBar pbState;
    private TextView tvState, tvBridgeWarn;
    private MaterialButton btnRetry;
    private LinearLayout boxAudience;
    private LinearLayout panelPreview, listPreview;
    private TextView tvPreviewTitle;
    private MaterialButton btnPreviewAll;
    private LinearLayout panelProduct;
    private MaterialButton btnPickProducts, btnLoadBuyers;
    private Spinner spDays;
    private TextView tvProdStatus;
    private LinearLayout panelPicker, listResults, listSelected;
    private EditText etSearch;
    private TextView tvSearchStatus, tvSelectedTitle, tvSelectedEmpty;
    private MaterialButton btnSelectedAll;
    private EditText etMessage;
    private TextView tvCounter;
    private MaterialButton btnSend;

    /** Satu audiens dari server; {@code preview} = daftar otomatis (bukan disusun staf). */
    private static final class Audience {
        String key, label;
        boolean preview;
        final List<JSONObject> customers = new ArrayList<>();
    }

    private final List<Audience> audiences = new ArrayList<>();
    private final Map<String, TextView> audienceCount = new HashMap<>();
    private final Map<String, LinearLayout> audienceCard = new HashMap<>();
    /** Daftar susunan staf per audiens ('custom' / 'product'): Customer.id → baris {id,name,phone,orders?,last?}. */
    private final Map<String, LinkedHashMap<Integer, JSONObject>> picks = new HashMap<>();
    private final Set<String> chosenProducts = new LinkedHashSet<>();
    private JSONArray products = new JSONArray();
    private String target = "pending";
    private int messageMax = 2000;
    private boolean bridgeConfigured = true;
    private boolean loading, loaded, sending, loadingBuyers;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchSeq;
    @Nullable private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        api = WaAdmin.api(this);
        setContentView(R.layout.activity_wa_broadcast);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        picks.put("custom", new LinkedHashMap<>());
        picks.put("product", new LinkedHashMap<>());

        swipe = findViewById(R.id.swipe);
        boxState = findViewById(R.id.boxState);
        pbState = findViewById(R.id.pbState);
        tvState = findViewById(R.id.tvState);
        btnRetry = findViewById(R.id.btnRetry);
        form = findViewById(R.id.form);
        tvBridgeWarn = findViewById(R.id.tvBridgeWarn);
        boxAudience = findViewById(R.id.boxAudience);
        panelPreview = findViewById(R.id.panelPreview);
        listPreview = findViewById(R.id.listPreview);
        tvPreviewTitle = findViewById(R.id.tvPreviewTitle);
        btnPreviewAll = findViewById(R.id.btnPreviewAll);
        panelProduct = findViewById(R.id.panelProduct);
        btnPickProducts = findViewById(R.id.btnPickProducts);
        btnLoadBuyers = findViewById(R.id.btnLoadBuyers);
        spDays = findViewById(R.id.spDays);
        tvProdStatus = findViewById(R.id.tvProdStatus);
        panelPicker = findViewById(R.id.panelPicker);
        listResults = findViewById(R.id.listResults);
        listSelected = findViewById(R.id.listSelected);
        etSearch = findViewById(R.id.etSearch);
        tvSearchStatus = findViewById(R.id.tvSearchStatus);
        tvSelectedTitle = findViewById(R.id.tvSelectedTitle);
        tvSelectedEmpty = findViewById(R.id.tvSelectedEmpty);
        btnSelectedAll = findViewById(R.id.btnSelectedAll);
        etMessage = findViewById(R.id.etMessage);
        tvCounter = findViewById(R.id.tvCounter);
        btnSend = findViewById(R.id.btnSend);

        swipe.setColorSchemeColors(C_PRIMARY);
        swipe.setOnRefreshListener(this::load);
        btnRetry.setOnClickListener(v -> load());

        ArrayAdapter<String> days = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DAY_LABELS);
        days.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDays.setAdapter(days);
        spDays.setSelection(DAY_DEFAULT);
        btnPickProducts.setOnClickListener(v -> pickProducts());
        btnLoadBuyers.setOnClickListener(v -> loadBuyers());
        btnPreviewAll.setOnClickListener(v -> showAllPreview());
        btnSelectedAll.setOnClickListener(v -> editAllSelected());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { scheduleSearch(); }
        });
        etMessage.setFilters(new InputFilter[]{new InputFilter.LengthFilter(messageMax)});
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshSend(); }
        });
        btnSend.setOnClickListener(v -> confirmSend());
        load();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_HISTORY, 0, "🕒 Riwayat").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_RELOAD, 1, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_HISTORY) {
            startActivity(new Intent(this, WaBroadcastHistoryActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_RELOAD) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ muat halaman

    private void load() {
        if (loading) {
            swipe.setRefreshing(false);
            return;
        }
        loading = true;
        if (loaded) swipe.setRefreshing(true);
        if (!loaded) {
            boxState.setVisibility(View.VISIBLE);
            pbState.setVisibility(View.VISIBLE);
            tvState.setText("Memuat…");
            btnRetry.setVisibility(View.GONE);
        }
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = api.adminWaGet("broadcast", null, staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                swipe.setRefreshing(false);
                if (fr == null) {
                    if (loaded) {
                        Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                    } else {
                        pbState.setVisibility(View.GONE);
                        tvState.setText(fe);
                        btnRetry.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                bind(fr);
            });
        }).start();
    }

    private void bind(JSONObject r) {
        audiences.clear();
        JSONArray arr = r.optJSONArray("audiences");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Audience a = new Audience();
            a.key = o.optString("key");
            a.label = o.optString("label", a.key);
            a.preview = o.optBoolean("preview");
            JSONArray cs = o.optJSONArray("customers");
            for (int j = 0; cs != null && j < cs.length(); j++) {
                JSONObject c = cs.optJSONObject(j);
                if (c != null) a.customers.add(c);
            }
            audiences.add(a);
        }
        JSONArray p = r.optJSONArray("products");
        products = p != null ? p : new JSONArray();
        // Produk yang sudah tak ada lagi di server dibuang dari pilihan.
        Set<String> valid = new LinkedHashSet<>();
        for (int i = 0; i < products.length(); i++) {
            JSONObject o = products.optJSONObject(i);
            if (o != null) valid.add(o.optString("uuid"));
        }
        chosenProducts.retainAll(valid);
        int max = r.optInt("message_max", 2000);
        if (max > 0 && max != messageMax) {
            messageMax = max;
            etMessage.setFilters(new InputFilter[]{new InputFilter.LengthFilter(messageMax)});
        }
        bridgeConfigured = r.optBoolean("bridge_configured", true);
        tvBridgeWarn.setVisibility(bridgeConfigured ? View.GONE : View.VISIBLE);
        if (audience(target) == null && !audiences.isEmpty()) target = audiences.get(0).key;

        loaded = true;
        boxState.setVisibility(View.GONE);
        form.setVisibility(View.VISIBLE);
        buildAudienceCards();
        renderPanel();
        updateProductButton();
    }

    @Nullable
    private Audience audience(String key) {
        for (Audience a : audiences) if (a.key.equals(key)) return a;
        return null;
    }

    private int countFor(String key) {
        Audience a = audience(key);
        if (a == null) return 0;
        if (a.preview) return a.customers.size();
        LinkedHashMap<Integer, JSONObject> m = picks.get(key);
        return m != null ? m.size() : 0;
    }

    private String labelOf(String key) {
        Audience a = audience(key);
        return a != null ? a.label : key;
    }

    // ------------------------------------------------------------------ kartu audiens

    private void buildAudienceCards() {
        boxAudience.removeAllViews();
        audienceCount.clear();
        audienceCard.clear();
        LinearLayout row = null;
        for (int i = 0; i < audiences.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(8);
                boxAudience.addView(row, rp);
            }
            Audience a = audiences.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setMinimumHeight(dp(56));
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> selectAudience(a.key));
            TextView t = new TextView(this);
            t.setText(a.label);
            t.setTextColor(C_TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
            card.addView(t);
            TextView n = new TextView(this);
            n.setTextColor(C_MUTED);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            card.addView(n);
            audienceCount.put(a.key, n);
            audienceCard.put(a.key, card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 1) lp.leftMargin = dp(8);
            row.addView(card, lp);
        }
        if (audiences.size() % 2 == 1 && row != null) {
            View filler = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.leftMargin = dp(8);
            row.addView(filler, lp);
        }
        refreshAudienceCards();
    }

    private void refreshAudienceCards() {
        for (Audience a : audiences) {
            TextView n = audienceCount.get(a.key);
            if (n != null) n.setText(countFor(a.key) + (a.preview ? " pelanggan" : " dipilih"));
            LinearLayout card = audienceCard.get(a.key);
            if (card == null) continue;
            boolean on = a.key.equals(target);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(on ? C_SEL_BG : 0xFFFFFFFF);
            bg.setStroke(dp(on ? 2 : 1), on ? C_PRIMARY : C_LINE);
            card.setBackground(bg);
            card.setSelected(on);
            card.setContentDescription(a.label + ", " + countFor(a.key) + (on ? ", terpilih" : ""));
        }
    }

    private void selectAudience(String key) {
        if (sending || key.equals(target)) return;
        target = key;
        etSearch.setText("");
        clearResults();
        renderPanel();
    }

    private void renderPanel() {
        Audience a = audience(target);
        boolean preview = a != null && a.preview;
        panelPreview.setVisibility(preview ? View.VISIBLE : View.GONE);
        panelProduct.setVisibility("product".equals(target) ? View.VISIBLE : View.GONE);
        panelPicker.setVisibility(preview || a == null ? View.GONE : View.VISIBLE);
        if (preview) renderPreview(a);
        else renderSelected();
        refreshAudienceCards();
        refreshSend();
    }

    // ------------------------------------------------------------------ pratinjau audiens otomatis

    private void renderPreview(Audience a) {
        listPreview.removeAllViews();
        int n = a.customers.size();
        if (n == 0) {
            tvPreviewTitle.setText("Tidak ada pelanggan di \"" + a.label + "\" saat ini.");
            btnPreviewAll.setVisibility(View.GONE);
            return;
        }
        tvPreviewTitle.setText("Akan dikirimi: " + n + " pelanggan");
        for (int i = 0; i < Math.min(n, INLINE_MAX); i++) {
            listPreview.addView(customerLine(a.customers.get(i), null));
        }
        if (n > INLINE_MAX) {
            btnPreviewAll.setText("👥 Lihat semua " + n + " pelanggan");
            btnPreviewAll.setVisibility(View.VISIBLE);
        } else {
            btnPreviewAll.setVisibility(View.GONE);
        }
    }

    private void showAllPreview() {
        Audience a = audience(target);
        if (a == null) return;
        String[] items = new String[a.customers.size()];
        for (int i = 0; i < items.length; i++) items[i] = describe(a.customers.get(i));
        new AlertDialog.Builder(this)
                .setTitle(a.label + " (" + items.length + ")")
                .setItems(items, null)
                .setPositiveButton("Tutup", null)
                .show();
    }

    // ------------------------------------------------------------------ Pelanggan per Produk

    private void updateProductButton() {
        btnPickProducts.setText(chosenProducts.isEmpty() ? "📦 Pilih produk"
                : "📦 Produk: " + chosenProducts.size() + " dipilih — ubah");
    }

    private void pickProducts() {
        if (products.length() == 0) {
            Toast.makeText(this, "Belum ada produk.", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = products.length();
        String[] names = new String[n];
        String[] uuids = new String[n];
        boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            JSONObject o = products.optJSONObject(i);
            uuids[i] = o != null ? o.optString("uuid") : "";
            names[i] = o != null ? o.optString("name", "Produk") : "Produk";
            checked[i] = chosenProducts.contains(uuids[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Centang produk")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Pakai", (d, w) -> {
                    chosenProducts.clear();
                    for (int i = 0; i < n; i++) if (checked[i]) chosenProducts.add(uuids[i]);
                    updateProductButton();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void loadBuyers() {
        if (loadingBuyers) return;
        if (chosenProducts.isEmpty()) {
            setProdStatus("Centang minimal satu produk.");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (String u : chosenProducts) arr.put(u);
            body.put("products", arr);
            String d = DAY_VALUES[Math.max(0, spDays.getSelectedItemPosition())];
            if ("active_today".equals(d)) body.put("active_today", true);
            else body.put("days", Integer.parseInt(d));
        } catch (Exception ignored) {
        }
        loadingBuyers = true;
        btnLoadBuyers.setEnabled(false);
        setProdStatus("Memuat…");
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = api.adminWaPost("broadcast/product-buyers", body, staffUuid);
            } catch (Exception e) {
                err = e instanceof SyncApi.SyncException ? WaAdmin.errorMessage(e) : "Gagal memuat — coba lagi.";
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loadingBuyers = false;
                if (isFinishing() || isDestroyed()) return;
                btnLoadBuyers.setEnabled(true);
                if (fr == null) {
                    setProdStatus(fe);
                    return;
                }
                LinkedHashMap<Integer, JSONObject> m = picks.get("product");
                m.clear();   // GANTI isi daftar (koreksi manual dilakukan sesudahnya) — sama dengan web
                JSONArray cs = fr.optJSONArray("customers");
                for (int i = 0; cs != null && i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c != null && c.optInt("id") > 0) m.put(c.optInt("id"), c);
                }
                setProdStatus(m.size() + " pelanggan ditemukan — hapus yang tak perlu dengan ✕.");
                if ("product".equals(target)) renderPanel();
                else refreshAudienceCards();
            });
        }).start();
    }

    private void setProdStatus(String s) {
        tvProdStatus.setText(s);
        tvProdStatus.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ pemilih pelanggan (custom / product)

    @Nullable
    private LinkedHashMap<Integer, JSONObject> currentPicks() {
        return picks.get(target);
    }

    private void renderSelected() {
        LinkedHashMap<Integer, JSONObject> m = currentPicks();
        listSelected.removeAllViews();
        if (m == null) return;
        int n = m.size();
        tvSelectedTitle.setText(n + " pelanggan dipilih");
        tvSelectedEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        tvSelectedEmpty.setText("product".equals(target)
                ? "Belum ada pelanggan — pilih produk lalu ketuk \"Tampilkan pelanggan\"."
                : "Belum ada pelanggan dipilih — cari lalu ketuk untuk menambah.");
        int shown = 0;
        for (Map.Entry<Integer, JSONObject> e : m.entrySet()) {
            if (shown++ >= INLINE_MAX) break;
            final int id = e.getKey();
            listSelected.addView(customerLine(e.getValue(), v -> {
                LinkedHashMap<Integer, JSONObject> cur = currentPicks();
                if (cur != null) cur.remove(id);
                renderSelected();
                refreshAudienceCards();
                refreshSend();
            }));
        }
        if (n > INLINE_MAX) {
            btnSelectedAll.setText("👥 Lihat & koreksi semua " + n + " pelanggan");
            btnSelectedAll.setVisibility(View.VISIBLE);
        } else {
            btnSelectedAll.setVisibility(View.GONE);
        }
    }

    /** Semua yang dipilih dalam satu daftar centang: hapus centang = keluarkan dari daftar. */
    private void editAllSelected() {
        LinkedHashMap<Integer, JSONObject> m = currentPicks();
        if (m == null || m.isEmpty()) return;
        List<Integer> ids = new ArrayList<>(m.keySet());
        String[] items = new String[ids.size()];
        boolean[] keep = new boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            items[i] = describe(m.get(ids.get(i)));
            keep[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hapus centang yang tak perlu")
                .setMultiChoiceItems(items, keep, (d, which, isChecked) -> keep[which] = isChecked)
                .setPositiveButton("Simpan", (d, w) -> {
                    LinkedHashMap<Integer, JSONObject> cur = currentPicks();
                    if (cur == null) return;
                    for (int i = 0; i < ids.size(); i++) if (!keep[i]) cur.remove(ids.get(i));
                    renderSelected();
                    refreshAudienceCards();
                    refreshSend();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void scheduleSearch() {
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        final String q = etSearch.getText().toString().trim();
        final int seq = ++searchSeq;
        if (q.length() < 2) {
            clearResults();
            return;
        }
        pendingSearch = () -> runSearch(q, seq);
        ui.postDelayed(pendingSearch, 300);
    }

    private void runSearch(String q, int seq) {
        tvSearchStatus.setText("Mencari…");
        tvSearchStatus.setVisibility(View.VISIBLE);
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                Map<String, String> query = new HashMap<>();
                query.put("q", q);
                r = api.adminWaGet("broadcast/customers", query, staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != searchSeq) return;   // hasil usang
                listResults.removeAllViews();
                if (fr == null) {
                    tvSearchStatus.setText(fe);
                    return;
                }
                LinkedHashMap<Integer, JSONObject> m = currentPicks();
                JSONArray cs = fr.optJSONArray("customers");
                int added = 0;
                for (int i = 0; cs != null && i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null || c.optInt("id") <= 0 || (m != null && m.containsKey(c.optInt("id")))) continue;
                    listResults.addView(resultLine(c));
                    added++;
                }
                if (added == 0) {
                    tvSearchStatus.setText("Tidak ditemukan.");
                } else {
                    tvSearchStatus.setText("Ketuk untuk menambah ke daftar:");
                }
            });
        }).start();
    }

    private void clearResults() {
        searchSeq++;
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        listResults.removeAllViews();
        tvSearchStatus.setVisibility(View.GONE);
    }

    private View resultLine(JSONObject c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.setMinimumHeight(dp(48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView t = new TextView(this);
        t.setText("＋ " + nameOf(c) + (str(c, "phone") != null ? "  ·  " + str(c, "phone") : ""));
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(t);
        String addr = str(c, "address");
        if (addr != null) {
            TextView s = new TextView(this);
            s.setText(addr);
            s.setTextColor(C_MUTED);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setMaxLines(1);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(s);
        }
        row.setOnClickListener(v -> {
            LinkedHashMap<Integer, JSONObject> m = currentPicks();
            if (m == null) return;
            m.put(c.optInt("id"), c);
            etSearch.setText("");
            clearResults();
            renderSelected();
            refreshAudienceCards();
            refreshSend();
        });
        return row;
    }

    /** Satu baris pelanggan: "Nama · nomor (n× · tgl)"; {@code onRemove} ≠ null → tombol ✕. */
    private View customerLine(JSONObject c, @Nullable View.OnClickListener onRemove) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(onRemove != null ? 2 : 4), 0, dp(onRemove != null ? 2 : 4));
        TextView t = new TextView(this);
        t.setText(describe(c));
        t.setTextColor(C_TEXT2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (onRemove != null) {
            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextColor(C_DANGER);
            x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            x.setGravity(Gravity.CENTER);
            x.setMinWidth(dp(48));
            x.setMinHeight(dp(44));
            x.setClickable(true);
            x.setFocusable(true);
            x.setContentDescription("Keluarkan " + nameOf(c) + " dari daftar");
            x.setOnClickListener(onRemove);
            row.addView(x);
        }
        return row;
    }

    private static String describe(@Nullable JSONObject c) {
        if (c == null) return "";
        StringBuilder sb = new StringBuilder(nameOf(c));
        String phone = str(c, "phone");
        sb.append("  ·  ").append(phone != null ? phone : "—");
        int orders = c.optInt("orders", 0);
        if (orders > 0) {
            sb.append("  (").append(orders).append("×");
            String last = str(c, "last");
            if (last != null) sb.append(" · ").append(last);
            sb.append(")");
        }
        return sb.toString();
    }

    private static String nameOf(JSONObject c) {
        String n = str(c, "name");
        return n != null ? n : "Tanpa nama";
    }

    @Nullable
    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String s = o.optString(k, "").trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    // ------------------------------------------------------------------ kirim

    private void refreshSend() {
        int n = countFor(target);
        String msg = etMessage.getText().toString();
        tvCounter.setText(msg.length() + "/" + messageMax);
        btnSend.setText(sending ? "Mengirim…" : "Kirim ke " + n + " Pelanggan");
        boolean ready = !sending && n > 0 && !msg.trim().isEmpty() && bridgeConfigured;
        // "Belum siap" tetap terbaca (#37474F di atas #CFD8DC = 6,7:1) — ketuk menjelaskan kenapa.
        btnSend.setBackgroundTintList(ColorStateList.valueOf(ready ? C_PRIMARY : 0xFFCFD8DC));
        btnSend.setTextColor(ready ? 0xFFFFFFFF : C_TEXT2);
        btnSend.setEnabled(!sending);
    }

    private void confirmSend() {
        if (sending || !loaded) return;
        int n = countFor(target);
        String label = labelOf(target);
        String msg = etMessage.getText().toString().trim();
        if (!bridgeConfigured) {
            Toast.makeText(this, "FREZ WA Bridge belum diatur (menu WhatsApp → Akun WhatsApp).", Toast.LENGTH_LONG).show();
            return;
        }
        if (n == 0) {
            Audience a = audience(target);
            Toast.makeText(this, a != null && a.preview
                    ? "Tidak ada pelanggan di \"" + label + "\" saat ini."
                    : "Pilih dulu setidaknya satu pelanggan.", Toast.LENGTH_LONG).show();
            return;
        }
        if (msg.isEmpty()) {
            etMessage.setError("Pesan tidak boleh kosong.");
            etMessage.requestFocus();
            return;
        }
        String snippet = msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
        new AlertDialog.Builder(this)
                .setTitle("Kirim broadcast?")
                .setMessage("Kirim pesan ini ke " + n + " pelanggan di \"" + label + "\"?\n\n" + snippet)
                .setPositiveButton("Ya, kirim ke " + n, (d, w) -> send(n))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void send(int n) {
        if (sending) return;
        JSONObject body = new JSONObject();
        final String sentTarget = target;
        try {
            body.put("target", sentTarget);
            body.put("message", etMessage.getText().toString());
            LinkedHashMap<Integer, JSONObject> m = picks.get(sentTarget);
            if (m != null) {   // hanya 'custom' / 'product' yang disusun staf
                JSONArray ids = new JSONArray();
                for (Integer id : m.keySet()) ids.put(id);
                body.put("customer_ids", ids);
            }
        } catch (Exception ignored) {
        }
        sending = true;
        refreshSend();
        progressDialog = new AlertDialog.Builder(this)
                .setTitle("Mengirim broadcast")
                .setMessage("Mengirim ke " + n + " pelanggan… jangan tutup layar ini.")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            boolean unsure = false;
            try {
                r = api.adminWaPost("broadcast/send", body, staffUuid);
            } catch (SyncApi.SyncException se) {
                err = WaAdmin.errorMessage(se);
                unsure = se.code >= 500;
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
                unsure = true;   // putus di tengah jalan: server mungkin SUDAH mengirim
            }
            final JSONObject fr = r;
            final String fe = err;
            final boolean fu = unsure;
            runOnUiThread(() -> {
                sending = false;
                if (isFinishing() || isDestroyed()) return;
                if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
                progressDialog = null;
                refreshSend();
                if (fr == null) {
                    new AlertDialog.Builder(this)
                            .setTitle("Broadcast tidak terkirim")
                            .setMessage(fu ? fe + "\n\nPeriksa 🕒 Riwayat sebelum mengirim ulang — pesan mungkin sudah terkirim."
                                    : fe)
                            .setPositiveButton("OK", null)
                            .setNeutralButton("🕒 Riwayat", (d, w) ->
                                    startActivity(new Intent(this, WaBroadcastHistoryActivity.class)))
                            .show();
                    return;
                }
                onSent(fr, sentTarget);
            });
        }).start();
    }

    private void onSent(JSONObject r, String sentTarget) {
        // Seperti web sesudah Kirim: pesan & daftar susunan dikosongkan, pratinjau dimuat ulang.
        etMessage.setText("");
        LinkedHashMap<Integer, JSONObject> m = picks.get(sentTarget);
        if (m != null) m.clear();
        if ("product".equals(sentTarget)) setProdStatus("");
        renderPanel();
        load();

        final long logId = r.optLong("log_id", 0);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("✅ Broadcast diproses")
                .setMessage(WaAdmin.message(r, "Broadcast terkirim."))
                .setPositiveButton("OK", null)
                .setNeutralButton("Lihat rincian", (d, w) -> {
                    if (logId > 0) {
                        startActivity(new Intent(this, WaBroadcastDetailActivity.class)
                                .putExtra(WaBroadcastDetailActivity.EXTRA_ID, logId));
                    } else {
                        startActivity(new Intent(this, WaBroadcastHistoryActivity.class));
                    }
                });
        if (r.optInt("deferred", 0) > 0) {
            b.setNegativeButton("🗓️ Broadcast Terjadwal", (d, w) ->
                    startActivity(new Intent(this, WaSchedulesActivity.class)));
        }
        b.show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
