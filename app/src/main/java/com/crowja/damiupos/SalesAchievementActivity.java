package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Pencapaian Penjualan" — rekap penjualan per KARYAWAN se-cabang untuk peran Admin & Marketing,
 * hidup dan selalu sama dengan dashboard.
 *
 * <p>SEMUA ANGKA DIHITUNG DI SERVER ({@code /api/sales/achievement}). Itu bukan pilihan gaya:
 * transaksi device-isolated di lapisan sync — sebuah HP hanya memegang barisnya sendiri — jadi
 * "pencapaian se-cabang" mustahil dihitung benar dari DB lokal. Layar ini sengaja TIDAK menjumlah
 * apa pun sendiri; kalau butuh angka baru, tambahkan di server (App\Support\StaffSales) supaya HP
 * dan dashboard tak pernah menampilkan angka berbeda.</p>
 *
 * <p>FILTER: rentang waktu (preset harian/pekanan + PERIODE CUT-OFF gaji + rentang tanggal kustom)
 * dan perangkat pelaksana penjualan (pilih banyak). Aturan cut-off hidup di server & bisa berbeda
 * per cabang, jadi HP tak pernah menghitungnya sendiri — ia cukup mengirim {@code preset=cutoff}.</p>
 *
 * <p>LIVE: muat ulang otomatis tiap {@link #AUTO_REFRESH_MS} selama layar di depan, plus tarik-untuk-
 * segarkan. Muat-ulang otomatis TIDAK menampilkan spinner (hanya yang manual) supaya angka yang
 * sedang dibaca tak berkedip tiap setengah menit.</p>
 */
public class SalesAchievementActivity extends AppCompatActivity {

    /** Selang muat-ulang otomatis. Cukup rapat untuk terasa "live", cukup jarang untuk tidak
     *  membebani server maupun kuota HP yang dibiarkan terbuka seharian di meja. */
    private static final long AUTO_REFRESH_MS = 30_000L;

    /** Preset rentang — nilai kunci HARUS sama dengan yang dikenali
     *  {@code SalesAchievementController::resolveRange} di server. */
    private static final String[][] PRESETS = {
            {"today", "Hari Ini"},
            {"yesterday", "Kemarin"},
            {"week", "Pekan Ini"},
            {"week_prev", "Pekan Lalu"},
            {"cutoff", "Periode Cut-off"},
            {"last_cutoff", "Cut-off Lalu"},
            {"last_3m", "3 Bulan"},
    };

    private ChipGroup chips;
    private TextView tvRange, tvTotalGalon, tvDelta, tvTotalMeta, tvEmpty, tvStaffHeader;
    private MaterialButton btnDevices;
    private MaterialCardView cardChart;
    private LinearLayout chartBars;
    private SwipeRefreshLayout swipe;
    private RecyclerView rv;
    private final Adapter adapter = new Adapter();

    private String preset = "cutoff";
    private String customStart, customEnd;
    /** uuid perangkat terpilih; KOSONG = semua perangkat. */
    private final List<String> selectedDevices = new ArrayList<>();
    /** uuid → nama, dari server (dipakai label tombol + dialog pilih perangkat). */
    private final Map<String, String> deviceNames = new LinkedHashMap<>();

    private final Handler auto = new Handler(Looper.getMainLooper());
    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            load(false);
            auto.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sales_achievement);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Pencapaian Penjualan");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        chips = findViewById(R.id.chipsPeriod);
        tvRange = findViewById(R.id.tvRange);
        tvTotalGalon = findViewById(R.id.tvTotalGalon);
        tvDelta = findViewById(R.id.tvDelta);
        tvTotalMeta = findViewById(R.id.tvTotalMeta);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvStaffHeader = findViewById(R.id.tvStaffHeader);
        btnDevices = findViewById(R.id.btnDevices);
        cardChart = findViewById(R.id.cardChart);
        chartBars = findViewById(R.id.chartBars);
        swipe = findViewById(R.id.swipe);
        rv = findViewById(R.id.rvStaff);

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        swipe.setOnRefreshListener(() -> load(true));
        btnDevices.setOnClickListener(v -> showDevicePicker());

        buildPresetChips();
        load(true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Segarkan begitu layar kembali ke depan, lalu lanjut berkala.
        auto.removeCallbacks(autoTick);
        auto.postDelayed(autoTick, AUTO_REFRESH_MS);
        load(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        auto.removeCallbacks(autoTick);   // jangan memanggil server saat layar tak terlihat
    }

    // ------------------------------------------------------------------ filter

    private void buildPresetChips() {
        chips.removeAllViews();
        for (String[] p : PRESETS) {
            Chip c = new Chip(this);
            c.setText(p[1]);
            c.setCheckable(true);
            c.setTag(p[0]);
            c.setChecked(p[0].equals(preset));
            c.setOnClickListener(v -> {
                preset = (String) v.getTag();
                customStart = customEnd = null;
                load(true);
            });
            chips.addView(c);
        }
        // Chip terakhir: rentang tanggal kustom (membuka dua pemilih tanggal berurutan).
        Chip custom = new Chip(this);
        custom.setText(customStart != null ? "📅 " + customStart + " → " + customEnd : "📅 Pilih Tanggal");
        custom.setCheckable(true);
        custom.setTag("custom");
        custom.setChecked("custom".equals(preset));
        custom.setOnClickListener(v -> pickCustomRange());
        chips.addView(custom);
    }

    /** Dua pemilih tanggal berurutan: mulai lalu akhir. Batal di salah satunya = filter tak berubah. */
    private void pickCustomRange() {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (view, y, m, d) -> {
            String start = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
            Calendar c2 = Calendar.getInstance();
            c2.set(y, m, d);
            DatePickerDialog end = new DatePickerDialog(this, (v2, y2, m2, d2) -> {
                customStart = start;
                customEnd = String.format(Locale.US, "%04d-%02d-%02d", y2, m2 + 1, d2);
                preset = "custom";
                buildPresetChips();
                load(true);
            }, y, m, d);
            end.setTitle("Sampai tanggal");
            // Tanggal akhir tak boleh mendahului tanggal mulai — server memang membetulkannya sendiri,
            // tapi memblokirnya di sini lebih jujur daripada diam-diam menukar pilihan pengguna.
            end.getDatePicker().setMinDate(c2.getTimeInMillis());
            end.show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
                .show();
    }

    /** Pilih-banyak perangkat pelaksana penjualan. Tak ada yang dicentang = SEMUA perangkat. */
    private void showDevicePicker() {
        if (deviceNames.isEmpty()) {
            Toast.makeText(this, "Daftar perangkat belum termuat. Coba segarkan dulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> uuids = new ArrayList<>(deviceNames.keySet());
        final CharSequence[] labels = new CharSequence[uuids.size()];
        final boolean[] checked = new boolean[uuids.size()];
        for (int i = 0; i < uuids.size(); i++) {
            labels[i] = deviceNames.get(uuids.get(i));
            checked[i] = selectedDevices.contains(uuids.get(i));
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Perangkat pelaksana penjualan")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNeutralButton("Semua", (d, w) -> {
                    selectedDevices.clear();
                    updateDeviceButton();
                    load(true);
                })
                .setPositiveButton("Terapkan", (d, w) -> {
                    selectedDevices.clear();
                    for (int i = 0; i < uuids.size(); i++) {
                        if (checked[i]) selectedDevices.add(uuids.get(i));
                    }
                    updateDeviceButton();
                    load(true);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateDeviceButton() {
        if (selectedDevices.isEmpty()) {
            btnDevices.setText("📱 Semua Perangkat");
        } else if (selectedDevices.size() == 1) {
            btnDevices.setText("📱 " + deviceNames.get(selectedDevices.get(0)));
        } else {
            btnDevices.setText("📱 " + selectedDevices.size() + " Perangkat");
        }
    }

    // -------------------------------------------------------------------- muat

    /** @param manual true = tarik-untuk-segarkan / ganti filter (tampilkan spinner);
     *                false = muat-ulang otomatis (senyap, supaya angka tak berkedip saat dibaca). */
    private void load(boolean manual) {
        if (manual) swipe.setRefreshing(true);
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        final String p = preset;
        final String s = customStart;
        final String e = customEnd;
        final String devs = android.text.TextUtils.join(",", selectedDevices);

        new Thread(() -> {
            JSONObject res = null;
            String error = null;
            try {
                res = new SyncApi(cfg).salesAchievement(p, s, e, devs);
            } catch (Exception ex) {
                error = "Gagal memuat pencapaian. Pastikan perangkat online lalu coba lagi.";
            }
            final JSONObject fRes = res;
            final String fErr = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                swipe.setRefreshing(false);
                if (fErr != null) {
                    // Muat-ulang otomatis yang gagal TIDAK boleh menimpa angka yang sudah tampil
                    // dengan pesan galat — jaringan kurir kerap putus-nyambung.
                    if (manual) {
                        Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                        if (adapter.getItemCount() == 0) showEmpty(fErr);
                    }
                    return;
                }
                render(fRes);
            });
        }).start();
    }

    private void showEmpty(String msg) {
        tvEmpty.setText(msg);
        tvEmpty.setVisibility(View.VISIBLE);
        tvStaffHeader.setVisibility(View.GONE);
    }

    private void render(JSONObject res) {
        if (res == null) return;

        tvRange.setText(res.optString("label", ""));

        JSONObject t = res.optJSONObject("totals");
        int galon = t != null ? t.optInt("galon", 0) : 0;
        tvTotalGalon.setText(num(galon));

        StringBuilder meta = new StringBuilder();
        if (t != null) {
            meta.append("Rp ").append(num((long) t.optDouble("revenue", 0)))
                    .append("  ·  ").append(num(t.optInt("trx", 0))).append(" transaksi")
                    .append("  ·  ").append(num(t.optInt("staff", 0))).append(" karyawan");
            if (t.optInt("galon_promo", 0) > 0) {
                meta.append("\n").append(num(t.optInt("galon_promo", 0))).append(" galon promosi (gratis)");
            }
        }
        tvTotalMeta.setText(meta.toString());

        // Pembanding periode sepanjang-yang-sama sebelumnya — null artinya tak ada pembanding
        // (periode sebelumnya nol), yang BUKAN berarti 0%.
        if (t != null && !t.isNull("delta_pct")) {
            double d = t.optDouble("delta_pct", 0);
            boolean up = d >= 0;
            tvDelta.setText((up ? "▲ " : "▼ ") + String.format(Locale.US, "%.1f", Math.abs(d)).replace('.', ',')
                    + "% dibanding periode sebelumnya (" + num(t.optInt("prev_galon", 0)) + " galon)");
            tvDelta.setTextColor(up ? 0xFF2E7D32 : 0xFFC62828);
            tvDelta.setVisibility(View.VISIBLE);
        } else {
            tvDelta.setVisibility(View.GONE);
        }

        // Daftar perangkat untuk pemilih filter (dikirim bersama data — tanpa panggilan kedua).
        JSONArray devs = res.optJSONArray("devices");
        if (devs != null) {
            deviceNames.clear();
            for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.optJSONObject(i);
                if (d == null) continue;
                deviceNames.put(d.optString("uuid"),
                        d.optString("icon", "📱") + " " + d.optString("name", "Perangkat"));
            }
            updateDeviceButton();
        }

        renderChart(res.optJSONArray("series"));

        List<JSONObject> rows = new ArrayList<>();
        JSONArray arr = res.optJSONArray("rows");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) rows.add(r);
            }
        }
        adapter.setData(rows, galon);

        boolean empty = rows.isEmpty();
        tvStaffHeader.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            // Periode cut-off yang BARU MULAI wajar kosong (hari pertama periode belum ada
            // penjualan) — sebutkan itu, kalau tidak layarnya terbaca seperti gagal memuat.
            String range = res.optString("label", "rentang ini");
            tvEmpty.setText("cutoff".equals(res.optString("preset"))
                    ? "Periode berjalan (" + range + ") belum mencatat penjualan.\n"
                      + "Pilih \"Cut-off Lalu\" untuk melihat periode sebelumnya."
                    : "Belum ada penjualan pada " + range
                      + (selectedDevices.isEmpty() ? "." : " untuk perangkat yang dipilih."));
        }
    }

    /** Batang harian: satu View per hari, tinggi relatif terhadap hari terbaik. Tanpa pustaka chart
     *  (cermin "Grafik Penjualan" di web yang memakai batang CSS). */
    private void renderChart(JSONArray series) {
        chartBars.removeAllViews();
        if (series == null || series.length() < 2) {
            cardChart.setVisibility(View.GONE);
            return;
        }
        int max = 1;
        for (int i = 0; i < series.length(); i++) {
            JSONObject d = series.optJSONObject(i);
            if (d != null) max = Math.max(max, d.optInt("galon", 0) + d.optInt("galon_promo", 0));
        }
        int barW = dp(series.length() > 40 ? 6 : 14);
        int gap = dp(3);
        int maxH = dp(90);
        for (int i = 0; i < series.length(); i++) {
            JSONObject d = series.optJSONObject(i);
            if (d == null) continue;
            int v = d.optInt("galon", 0) + d.optInt("galon_promo", 0);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(barW, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginEnd(gap);
            col.setLayoutParams(cp);

            View bar = new View(this);
            int h = v > 0 ? Math.max(dp(3), Math.round(maxH * (v / (float) max))) : dp(1);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(barW, h);
            bar.setLayoutParams(bp);
            bar.setBackgroundColor(v > 0 ? 0xFF1565C0 : 0xFFE0E0E0);
            col.addView(bar);

            // Label tanggal hanya sesekali kalau rentangnya panjang — kalau tidak, teksnya tumpang tindih.
            TextView lbl = new TextView(this);
            int every = series.length() > 31 ? 7 : (series.length() > 14 ? 3 : 1);
            lbl.setText(i % every == 0 ? d.optString("label", "") : "");
            lbl.setTextSize(8f);
            lbl.setTextColor(0xFF9E9E9E);
            lbl.setMaxLines(1);
            col.addView(lbl);

            chartBars.addView(col);
        }
        cardChart.setVisibility(View.VISIBLE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Ribuan bertitik, gaya Indonesia — sama dengan tampilan angka di layar lain. */
    private static String num(long n) {
        return String.format(Locale.US, "%,d", n).replace(',', '.');
    }

    // ----------------------------------------------------------------- adapter

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<JSONObject> data = new ArrayList<>();
        private int totalGalon = 0;

        void setData(List<JSONObject> rows, int total) {
            data.clear();
            data.addAll(rows);
            totalGalon = total;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_sales_achievement, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject r = data.get(position);
            h.tvRank.setText(String.valueOf(position + 1));
            h.tvName.setText(r.optString("name", "—"));

            int galon = r.optInt("galon", 0);
            h.tvGalon.setText(num(galon));

            StringBuilder meta = new StringBuilder();
            meta.append("Rp ").append(num((long) r.optDouble("revenue", 0)))
                    .append("  ·  ").append(num(r.optInt("trx", 0))).append(" trx")
                    .append("  ·  ").append(String.format(Locale.US, "%.1f", r.optDouble("galon_per_day", 0))
                            .replace('.', ',')).append(" galon/hari");
            if (r.optInt("galon_promo", 0) > 0) {
                meta.append("  ·  ").append(num(r.optInt("galon_promo", 0))).append(" promosi");
            }
            if (!r.isNull("delta_pct")) {
                double d = r.optDouble("delta_pct", 0);
                meta.append(d >= 0 ? "  ·  ▲ " : "  ·  ▼ ")
                        .append(String.format(Locale.US, "%.0f", Math.abs(d))).append('%');
            }
            h.tvMeta.setText(meta.toString());

            // Lebar bilah = pangsa terhadap total cabang. Dihitung pada saat layout siap karena
            // lebar induknya belum diketahui saat bind.
            final double share = totalGalon > 0 ? (galon / (double) totalGalon) : 0;
            h.barShare.post(() -> {
                View parent = (View) h.barShare.getParent();
                int full = parent.getWidth() - parent.getPaddingStart() - parent.getPaddingEnd();
                ViewGroup.LayoutParams lp = h.barShare.getLayoutParams();
                lp.width = Math.max(dp(2), (int) Math.round(full * share));
                h.barShare.setLayoutParams(lp);
            });

            JSONArray prods = r.optJSONArray("products");
            if (prods != null && prods.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < prods.length() && i < 4; i++) {
                    JSONObject p = prods.optJSONObject(i);
                    if (p == null) continue;
                    if (sb.length() > 0) sb.append("  ·  ");
                    sb.append(p.optString("name", "")).append(" ×")
                            .append(trimNum(p.optDouble("galon", 0)));
                }
                if (prods.length() > 4) sb.append("  +").append(prods.length() - 4).append(" lagi");
                h.tvProducts.setText(sb.toString());
                h.tvProducts.setVisibility(View.VISIBLE);
            } else {
                h.tvProducts.setVisibility(View.GONE);
            }
        }

        /** Galon per produk bisa pecahan (transaksi teralokasi dibagi proporsional di server) —
         *  tampilkan desimal hanya bila memang ada, supaya angka bulat tidak jadi "12,0". */
        private String trimNum(double v) {
            String s = String.format(Locale.US, "%.1f", v);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s.replace('.', ',');
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvRank, tvName, tvGalon, tvMeta, tvProducts;
            View barShare;

            VH(View v) {
                super(v);
                tvRank = v.findViewById(R.id.tvRank);
                tvName = v.findViewById(R.id.tvName);
                tvGalon = v.findViewById(R.id.tvGalon);
                tvMeta = v.findViewById(R.id.tvMeta);
                tvProducts = v.findViewById(R.id.tvProducts);
                barShare = v.findViewById(R.id.barShare);
            }
        }
    }
}
