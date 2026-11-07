package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.sync.ClaimRequests;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Lihat Antrian Perangkat Lain" — antrian AKTIF milik SATU perangkat lain di cabang yang sama,
 * diambil on-demand dari server (transaksi device-isolated: HP ini tak pernah menyimpan baris
 * perangkat lain secara lokal).
 *
 * <p>Selain melihat beban rekan, kurir bisa: melihat JARAK order itu dari posisinya sendiri,
 * NAVIGASI ke titik antarnya, dan MENGAMBIL ALIH order (butuh konfirmasi dua ketukan). Ambil alih
 * memindahkan rute order ke perangkat ini di server; perangkat asal mengetahuinya lewat deteksi
 * pull yang sudah ada (notifikasi "⚠ Order Dipindahkan").</p>
 */
public class OtherDeviceQueueActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_UUID = "device_uuid";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private static final SimpleDateFormat SDF_PARSE =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private RecyclerView rv;
    private TextView tvEmpty;
    private View progress;
    /** Yang tampil (hasil cari/saring/urut) dari {@link #all}, antrean perangkat terpilih. */
    private final List<JSONObject> data = new ArrayList<>();
    private final List<JSONObject> all = new ArrayList<>();
    private final java.util.Map<String, Integer> tabCounts = new java.util.HashMap<>();
    private com.google.android.material.tabs.TabLayout deviceTabs;
    private TextView tvSummary;
    private String searchText = "";
    private boolean salesOnly = false;
    private int filterMode = 0;   // 0 semua, 1 prioritas, 2 terlambat, 3 terbuka
    private int sortMode = 2;     // 0 jarak, 1 galon, 2 umur
    private Adapter adapter;
    /** Perangkat yang antriannya sedang dilihat — dipakai ulang saat memuat ulang setelah ambil alih. */
    private String peekDeviceUuid;
    private String peekDeviceName;
    /** Posisi kurir INI (GPS terakhir). NaN = belum ada fix → baris jarak tak ditampilkan. */
    private double myLat = Double.NaN, myLng = Double.NaN;
    /** Salinan LOKAL pelanggan (sinkron branch-wide) — sumber wilayah & daftar lokasinya untuk baris 📍. */
    private CustomerDao customerDao;

    // Badge umur pesanan live per detik — cermin PERSIS DeliveryQueueActivity.tick/ticker (Antrian
    // Saya sendiri). Layar ini dulunya menampilkan "45 mnt lalu" statis (dihitung sekali saat data
    // dimuat) dengan alasan "sekilas lihat" — permintaan berikutnya membalik itu: badge berkedip
    // hijau/kuning/merah, sama pentingnya di sini karena kurir memutuskan Ambil Alih dari umurnya.
    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            adapter.refreshTimers();
            tick.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lateMs = (long) new SettingsDao(DatabaseHelper.getInstance(this)).getDeliveryMaxAgeMinutes() * 60000L;
        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));
        DeliveryQueueActivity.DestAreaCache.init(this);
        setContentView(R.layout.activity_other_device_queue);

        String deviceUuid = getIntent().getStringExtra(EXTRA_DEVICE_UUID);
        String deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        peekDeviceUuid = deviceUuid;
        peekDeviceName = deviceName;
        if (deviceUuid == null || deviceUuid.isEmpty()) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle("Antrian: " + (deviceName != null && !deviceName.isEmpty() ? deviceName : "Perangkat"));

        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);
        progress = findViewById(R.id.progress);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        buildHeader();
        load(deviceUuid);

        // Jarak dihitung dari posisi kurir SEKARANG. Non-blok: daftar tampil lebih dulu, baris
        // jarak menyusul begitu GPS memberi fix (pola yang sama dengan mode "Jarak" di antrian
        // sendiri). Tanpa izin/fix, jarak sekadar tidak ditampilkan.
        LocationService.lastLocation(this, loc -> {
            if (loc == null || isFinishing() || isDestroyed()) return;
            myLat = loc.getLatitude();
            myLng = loc.getLongitude();
            applyView();
        });
    }

    /** Wilayah lokasi non-utama baru tiba (DeliveryQueueActivity.DestAreaCache) → bind ulang baris 📍. */
    private final Runnable destAreaRefresh = () -> {
        if (!isFinishing() && !isDestroyed() && adapter != null) adapter.notifyDataSetChanged();
    };

    /** Status permintaan persetujuan ambil alih berubah → bind ulang kartu (tombol ⏳ + baris status). */
    private final Runnable claimRequestRefresh = () -> {
        if (!isFinishing() && !isDestroyed() && adapter != null) adapter.notifyDataSetChanged();
    };

    @Override
    protected void onResume() {
        super.onResume();
        DeliveryQueueActivity.DestAreaCache.addListener(destAreaRefresh);
        ClaimRequests.addListener(claimRequestRefresh);
        tick.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        DeliveryQueueActivity.DestAreaCache.removeListener(destAreaRefresh);
        ClaimRequests.removeListener(claimRequestRefresh);
        tick.removeCallbacks(ticker);
    }

    /** Tab perangkat (roster cabang), kotak cari, saringan, urutan, dan ringkasan. */
    private void buildHeader() {
        android.widget.LinearLayout header = findViewById(R.id.queueHeader);
        deviceTabs = new com.google.android.material.tabs.TabLayout(this);
        deviceTabs.setTabMode(com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE);
        header.addView(deviceTabs);
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        String me = cfg.getDeviceUuid();
        boolean foundCurrent = false;
        try {
            JSONArray roster = new JSONArray(cfg.getDeviceRoster());
            for (int i = 0; i < roster.length(); i++) {
                JSONObject d = roster.optJSONObject(i);
                String u = d != null ? d.optString("uuid", "") : "";
                if (u.isEmpty() || u.equals(me)) continue;
                boolean current = u.equals(peekDeviceUuid);
                foundCurrent |= current;
                addDeviceTab(u, d.optString("name", "Perangkat"), current);
            }
        } catch (Exception ignored) {
        }
        if (!foundCurrent) addDeviceTab(peekDeviceUuid, safe(peekDeviceName), true);
        deviceTabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                String[] dev = (String[]) tab.getTag();
                peekDeviceUuid = dev[0];
                peekDeviceName = dev[1];
                ((Toolbar) findViewById(R.id.toolbar)).setTitle("Antrian: " + dev[1]);
                all.clear();
                applyView();
                load(dev[0]);
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) { load(((String[]) tab.getTag())[0]); }
        });

        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("🔍 Cari nama / no. WA / struk / catatan…");
        search.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(pad, pad / 2, pad, 0);
        header.addView(search, slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                searchText = e.toString().trim().toLowerCase(Locale.ROOT);
                applyView();
            }
        });
        android.widget.CheckBox cbSales = new android.widget.CheckBox(this);
        cbSales.setText("🛒 Penjualan saja");
        cbSales.setOnCheckedChangeListener((b, on) -> { salesOnly = on; applyView(); });
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        clp.setMarginStart(pad);
        header.addView(cbSales, clp);
        header.addView(chipRow(new String[]{"Semua", "⚡ Prioritas", "🚨 Terlambat", "🎲 Terbuka"}, filterMode, i -> { filterMode = i; applyView(); }));
        header.addView(chipRow(new String[]{"📍 Jarak", "🛢 Galon", "⏱ Umur"}, sortMode, i -> { sortMode = i; applyView(); }));
        tvSummary = new TextView(this);
        tvSummary.setTextColor(0xFF37474F);
        tvSummary.setTextSize(13f);
        tvSummary.setPadding(pad, pad / 3, pad, pad / 3);
        header.addView(tvSummary);
    }

    private void addDeviceTab(String uuid, String name, boolean select) {
        String label = name.isEmpty() ? "Perangkat" : name;
        deviceTabs.addTab(deviceTabs.newTab().setText(label).setTag(new String[]{uuid, label}), select);
    }

    private android.widget.HorizontalScrollView chipRow(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        com.google.android.material.chip.ChipGroup group = new com.google.android.material.chip.ChipGroup(this);
        group.setSingleLine(true);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        group.setPadding(pad, 0, pad, 0);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
            chip.setText(labels[i]);
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            group.addView(chip);
            if (i == selected) chip.setChecked(true);
            chip.setOnCheckedChangeListener((b, on) -> { if (on) onPick.accept(idx); });
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(group);
        return hs;
    }

    private boolean isLateOrder(JSONObject q) {
        return lateMs > 0 && elapsedMillis(q.optString("queued_at", null)) >= lateMs;
    }

    private boolean matchesView(JSONObject q) {
        if (salesOnly && q.optBoolean("pickup_only", false)) return false;
        if (filterMode == 1 && !q.optBoolean("order_priority", false)) return false;
        if (filterMode == 2 && !isLateOrder(q)) return false;
        if (filterMode == 3 && !q.optBoolean("open_dispatch", false)) return false;
        if (searchText.isEmpty()) return true;
        String hay = (str(q, "name") + " " + str(q, "phone") + " " + str(q, "receipt_no") + " " + str(q, "note") + " "
                + str(q, "address") + " " + str(q, "items")).toLowerCase(Locale.ROOT);
        return hay.contains(searchText);
    }

    /** Terapkan cari, saring, urut, lalu perbarui daftar, ringkasan, dan jumlah di tab. */
    private void applyView() {
        data.clear();
        for (JSONObject q : all) if (matchesView(q)) data.add(q);
        if (sortMode == 1) {
            data.sort((a, b) -> Integer.compare(b.optInt("galon", 0), a.optInt("galon", 0)));
        } else if (sortMode == 2) {
            data.sort((a, b) -> Long.compare(elapsedMillis(b.optString("queued_at", null)), elapsedMillis(a.optString("queued_at", null))));
        } else {
            data.sort((a, b) -> {
                double x = distanceKmTo(a), y = distanceKmTo(b);
                return Double.compare(Double.isNaN(x) ? Double.MAX_VALUE : x, Double.isNaN(y) ? Double.MAX_VALUE : y);
            });
        }
        int galon = 0, late = 0, priority = 0;
        for (JSONObject q : data) {
            galon += Math.max(0, q.optInt("galon", 0));
            if (isLateOrder(q)) late++;
            if (q.optBoolean("order_priority", false)) priority++;
        }
        if (tvSummary != null) {
            tvSummary.setText((data.size() < all.size() ? data.size() + " dari " + all.size() : String.valueOf(all.size())) + " order · " + galon + " galon"
                    + (priority > 0 ? " · ⚡ " + priority : "") + (late > 0 ? " · 🚨 " + late : ""));
        }
        adapter.notifyDataSetChanged();
    }

    private void showLoadedCount(String deviceUuid, int count) {
        for (int i = 0; i < deviceTabs.getTabCount(); i++) {
            com.google.android.material.tabs.TabLayout.Tab tab = deviceTabs.getTabAt(i);
            String[] dev = (String[]) tab.getTag();
            if (dev[0].equals(deviceUuid)) tab.setText(dev[1] + " (" + count + ")");
        }
    }

    private void load(String deviceUuid) {
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        new Thread(() -> {
            JSONArray queue = null;
            String error = null;
            try {
                JSONObject res = new SyncApi(cfg).deviceQueue(deviceUuid);
                queue = res.optJSONArray("queue");
            } catch (Exception e) {
                error = "Gagal memuat antrian. Pastikan perangkat online lalu coba lagi.";
            }
            final JSONArray finalQueue = queue;
            final String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !deviceUuid.equals(peekDeviceUuid)) return;   // tab sudah berganti
                progress.setVisibility(View.GONE);
                if (finalError != null) {
                    Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                    tvEmpty.setText(finalError);
                    tvEmpty.setVisibility(View.VISIBLE);
                    return;
                }
                all.clear();
                if (finalQueue != null) {
                    for (int i = 0; i < finalQueue.length(); i++) {
                        JSONObject o = finalQueue.optJSONObject(i);
                        if (o != null) all.add(o);
                    }
                }
                showLoadedCount(deviceUuid, all.size());
                applyView();
                tvEmpty.setText(all.isEmpty() ? "Antrian perangkat ini kosong 🎉" : "Tidak ada order yang cocok");
                tvEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /** Milidetik sejak order masuk antrian; 0 kalau tidak bisa di-parse. Cermin PERSIS
     *  DeliveryQueueActivity.elapsedMillis (sumbernya JSON queued_at, bukan kolom lokal). */
    private static long elapsedMillis(String queuedAt) {
        if (queuedAt == null || queuedAt.length() < 19) return 0;
        try {
            java.util.Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            if (d == null) return 0;
            // TIDAK di-clamp ke 0: Pesanan Terjadwal menulis queued_at di MASA DEPAN, jadi selisihnya
            // sengaja NEGATIF (hitung mundur) — lihat DeliveryQueueActivity.elapsedMillis (sumber
            // kebenaran yang dicerminkan layar ini).
            return System.currentTimeMillis() - d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Format ringkas badge timer umur pesanan — cermin PERSIS DeliveryQueueActivity.formatElapsedBadge:
     *  &lt;1 jam "mm:ss", 1–24 jam "hh:mm:ss", &gt;24 jam "N hari hh:mm"; negatif (Pesanan Terjadwal,
     *  hitung mundur) diberi awalan "-". */
    private static String formatElapsedBadge(long ms) {
        boolean future = ms < 0;
        long s = Math.abs(ms) / 1000;
        long days = s / 86400;
        String formatted;
        if (days > 0) {
            long h = (s % 86400) / 3600, m = (s % 3600) / 60;
            formatted = days + " hari " + String.format(Locale.US, "%02d:%02d", h, m);
        } else {
            long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
            formatted = h > 0 ? String.format(Locale.US, "%02d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%02d:%02d", m, sec);
        }
        return future ? "-" + formatted : formatted;
    }

    /** Prefiks emoji badge: 🗓️ Pesanan Terjadwal (hitung mundur), 🚨 telat, ⏱ normal. */
    private static String elapsedPrefix(long ms) {
        if (ms < 0) return "🗓️ ";
        return (lateMs > 0 && ms >= lateMs) ? "🚨 " : "⏱ ";
    }

    /** Ambang peringatan lama menunggu — sama dengan Antrian Saya & dashboard web. */
    private static final long QUEUE_WARN_MS = 60L * 60 * 1000;       // 1 jam → kuning berkedip
    private static final long QUEUE_LATE_MS = 2L * 60 * 60 * 1000;   // 2 jam → merah berkedip lebih cepat
    /** Batas umur "TERLAMBAT" cabang (delivery_max_age_minutes) dalam ms; 0 = fitur mati. Dibaca
     *  sekali di onCreate -- cermin field yang sama di DeliveryQueueActivity. */
    private static long lateMs = 0L;

    /** Warnai + kedipkan badge umur pesanan: &lt;1 jam HIJAU diam, ≥1 jam KUNING berkedip, ≥2 jam
     *  MERAH berkedip lebih cepat, dan >= batas umur cabang MERAH TUA = TERLAMBAT.
     *  Cermin PERSIS DeliveryQueueActivity.applyQueueTimerState (beda
     *  hanya warna netral: hijau di sini vs abu-abu di Antrian Saya — layar ini tak punya makna
     *  "baru masuk, belum perlu perhatian" netral yang sama, jadi hijau eksplisit lebih jelas). */
    private static void applyQueueTimerState(TextView tv, long elapsedMs) {
        // level -1 = Pesanan Terjadwal (elapsedMs negatif) — masih hitung mundur, bukan "menunggu",
        // jadi tak ikut ambang kuning/merah/telat. Cermin DeliveryQueueActivity.applyQueueTimerState.
        final int level = elapsedMs < 0 ? -1
                : lateMs > 0 && elapsedMs >= lateMs ? 3
                : elapsedMs >= QUEUE_LATE_MS ? 2 : (elapsedMs >= QUEUE_WARN_MS ? 1 : 0);
        Object prev = tv.getTag(R.id.tvQueued);
        boolean changed = !(prev instanceof Integer) || (Integer) prev != level;
        if (changed) {
            tv.setTag(R.id.tvQueued, level);
            tv.setBackgroundResource(level == 3 ? R.drawable.bg_queue_timer_danger
                    : level == 2 ? R.drawable.bg_pending_badge
                    : level == 1 ? R.drawable.bg_queue_timer_warn
                    : level == -1 ? R.drawable.bg_queue_timer_scheduled
                    : R.drawable.bg_queue_timer_ok_green);
        }
        if (level <= 0) {
            tv.clearAnimation();
            tv.setAlpha(1f);
            return;
        }
        if (!changed && tv.getAnimation() != null) return;   // sudah berkedip pada tingkat yang sama
        android.view.animation.AlphaAnimation blink =
                new android.view.animation.AlphaAnimation(1f, level >= 3 ? 0.15f : level == 2 ? 0.2f : 0.35f);
        blink.setDuration(level >= 3 ? 250 : level == 2 ? 350 : 650);
        blink.setRepeatMode(android.view.animation.Animation.REVERSE);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        tv.startAnimation(blink);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Nilai JSON opsional yang bisa datang sebagai literal "null" dari server. */
    private static String str(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null || v.equals("null") ? "" : v;
    }

    // ------------------------------------------------------- Jarak & navigasi

    /** Koordinat EFEKTIF order (server sudah mengirim tujuan "Kirim ke" bila ada, else pelanggan). */
    private static double lat(JSONObject q) { return q.optDouble("latitude", 0); }

    private static double lng(JSONObject q) { return q.optDouble("longitude", 0); }

    private static boolean hasGeo(JSONObject q) { return lat(q) != 0 || lng(q) != 0; }

    /** Jarak garis-lurus (km) dari posisi kurir ke order; NaN bila GPS/koordinat order tak ada. */
    private double distanceKmTo(JSONObject q) {
        if (Double.isNaN(myLat) || Double.isNaN(myLng) || !hasGeo(q)) return Double.NaN;
        return haversineKm(myLat, myLng, lat(q), lng(q));
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String formatKm(double km) {
        if (km < 1) return Math.round(km * 1000) + " m";
        return String.format(Locale.US, "%.1f km", km).replace('.', ',');
    }

    /** Buka navigasi ke titik antar order (Google Maps, fallback peta web / pencarian alamat). */
    private void navigateTo(JSONObject q) {
        Intent i;
        if (hasGeo(q)) {
            Uri nav = Uri.parse("google.navigation:q=" + lat(q) + "," + lng(q));
            i = new Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) == null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://www.google.com/maps?q=" + lat(q) + "," + lng(q)));
            }
        } else {
            String addr = str(q, "address");
            if (addr.isEmpty()) {
                Toast.makeText(this, "Lokasi pelanggan belum diset", Toast.LENGTH_SHORT).show();
                return;
            }
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(addr)));
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------- Ambil alih order

    /**
     * Gerbang AMBIL ALIH: popup peringatan (⚠) yang tombol lanjutnya harus diketuk DUA KALI —
     * memindahkan order rekan adalah keputusan yang mengubah pembagian kerja orang lain, jadi tak
     * boleh terjadi karena salah sentuh. Order yang sedang DIANTAR rekan tidak bisa diambil alih
     * sama sekali (kurirnya sudah di jalan).
     */
    private void confirmTakeOver(JSONObject q) {
        final String uuid = str(q, "uuid");   // identitas sinkron transaksi (bukan id lokal server)
        final String name = safe(q.optString("name", "Pelanggan"));
        if (q.optBoolean("in_progress", false)) {
            Toast.makeText(this, "🚚 Sedang diantar — tidak bisa diambil alih.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ClaimRequests.blocks(uuid)) {
            // Sudah minta persetujuan pemilik antrean (≤1 jam lalu) — jangan kirim ulang.
            Toast.makeText(this, ClaimRequests.blockedToast(uuid), Toast.LENGTH_LONG).show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("Order \"").append(name).append("\" akan DIPINDAHKAN dari perangkat ")
                .append(peekDeviceName != null && !peekDeviceName.isEmpty() ? "\"" + peekDeviceName + "\"" : "lain")
                .append(" ke perangkat Anda.\n\n");
        msg.append("Perangkat asal akan diberi tahu bahwa order ini dipindahkan.\n\n"
                + "Ketuk \"Ambil Alih\" dua kali untuk memastikan.");

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Ambil Alih Pengiriman?")
                .setCancelable(false)
                .setMessage(msg.toString())
                .setPositiveButton("Ambil Alih", null)
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final int[] clicks = {0};
            pos.setOnClickListener(v -> {
                // Ketukan PERTAMA hanya menegaskan; ketukan KEDUA yang benar-benar memindahkan.
                if (++clicks[0] < 2) {
                    pos.setText("Ketuk sekali lagi");
                    return;
                }
                pos.setEnabled(false);
                pos.setText("Memindahkan…");
                dialog.setCancelable(false);
                Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (neg != null) neg.setEnabled(false);
                takeOver(dialog, pos, neg, uuid, name, str(q, "routed_uuid"));
            });
        });
        dialog.show();
    }

    /** Kirim klaim ke server; sukses → notifikasi lokal + muat ulang daftar rekan + sinkron. */
    private void takeOver(final AlertDialog dialog, final Button pos, final Button neg,
                          final String trxUuid, final String custName, final String routedUuid) {
        final SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        if (trxUuid == null || trxUuid.isEmpty()) {
            Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        new Thread(() -> {
            String okMsg = null, errMsg = null;
            boolean needsApproval = false;
            try {
                JSONObject body = new JSONObject();
                body.put("transaction_uuid", trxUuid);
                // Pemilik order MENURUT layar ini — penjaga basi di server: bila di server sudah
                // berpindah, klaim ditolak (409) alih-alih menimpa keputusan yang lebih baru.
                body.put("expected_device_uuid", routedUuid != null ? routedUuid : "");
                JSONObject r = new SyncApi(cfg).claimDelivery(body);
                okMsg = r.optString("message", "Order diambil alih ke perangkat ini.");
            } catch (SyncApi.SyncException se) {
                needsApproval = ClaimRequests.isNeedsApproval(se);
                errMsg = extractMessage(se.body);
                if (errMsg == null) errMsg = "Gagal mengambil alih (kode " + se.code + ").";
            } catch (Exception e) {
                errMsg = "Gagal mengambil alih — periksa koneksi internet.";
            }
            final String fOk = okMsg, fErr = errMsg;
            final boolean fAwaiting = needsApproval;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fAwaiting) {
                    // Pemilik antrean wajib menyetujui — permintaan terkirim, kartu kini ⏳ terkunci.
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    adapter.notifyDataSetChanged();
                    return;
                }
                if (fOk != null) {
                    Toast.makeText(this, fOk, Toast.LENGTH_LONG).show();
                    // Notifikasi di perangkat TUJUAN (perangkat ini). Perangkat ASAL mendapat
                    // notifikasinya sendiri saat sinkron berikutnya — SyncEngine mendeteksi order
                    // yang keluar dari antriannya ("⚠ Order Dipindahkan").
                    try {
                        com.crowja.damiupos.sync.OnlineNotifier.postNotif(getApplicationContext(),
                                "📥 Order Diambil Alih",
                                "Order \"" + custName + "\" dipindahkan ke perangkat ini dan masuk antrian Anda.",
                                7950);
                    } catch (Throwable ignored) {}
                    dialog.dismiss();
                    // Tarik order-nya ke HP ini sekarang, lalu segarkan daftar antrian rekan.
                    SyncScheduler.syncNow(getApplicationContext());
                    if (peekDeviceUuid != null) load(peekDeviceUuid);
                } else {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Ambil Alih");
                    dialog.setCancelable(true);
                    if (neg != null) neg.setEnabled(true);
                    // Daftar kemungkinan sudah basi (order berpindah/selesai) → muat ulang.
                    if (peekDeviceUuid != null) load(peekDeviceUuid);
                }
            });
        }).start();
    }

    /** Pesan ramah dari body JSON error server ({"message":"..."}); null bila gagal dibaca. */
    private static String extractMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return new JSONObject(body).optString("message", null);
        } catch (Exception e) {
            return null;
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_other_device_compact, parent, false);
            return new VH(v);
        }

        /** Perbarui HANYA badge umur pesanan pada baris yang sedang TERLIHAT — dipanggil tiap detik
         *  oleh ticker, bukan notifyDataSetChanged() (itu akan mereset scroll & re-inflate semua
         *  baris tiap detik). Cermin PERSIS DeliveryQueueActivity.Adapter.refreshTimers. */
        void refreshTimers() {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
                int pos = vh.getAdapterPosition();
                if (pos >= 0 && pos < data.size() && vh instanceof VH) {
                    long ms = elapsedMillis(data.get(pos).optString("queued_at", null));
                    DeliveryQueueActivity.bindElapsedBadge(((VH) vh).tvElapsed, ms);
                }
            }
        }

        /** Kartu sama dengan tab Perangkat Lain di Antrian Delivery. */
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject q = data.get(position);
            DeliveryQueueActivity.bindOrderNote(h.tvOrderNote, str(q, "note"));
            String custUuid = str(q, "customer_uuid");
            Customer cust = custUuid.isEmpty() ? null : customerDao.getBySyncUuid(custUuid);
            boolean orderPriority = q.optBoolean("order_priority", false);
            boolean incomplete = cust != null && cust.isIncomplete();
            h.tvCustomer.setText((orderPriority ? "⚡ " : "") + (q.optBoolean("is_priority", false) ? "⭐ " : "")
                    + (incomplete ? "❗ " : "") + str(q, "name"));
            bindPriority(h.tvPriorityBig, orderPriority, str(q, "order_priority_reason"));
            DeliveryQueueActivity.bindElapsedBadge(h.tvElapsed, elapsedMillis(q.optString("queued_at", null)));

            boolean delivering = q.optBoolean("in_progress", false);
            StringBuilder meta = new StringBuilder();
            if (delivering) meta.append("🚚 Sedang diantar").append(DeliveryQueueActivity.headingSuffix(q));
            String dev = str(q, "device_group_label");
            if (!dev.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("📱 ").append(dev);
            String phone = str(q, "phone");
            if (!phone.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("📞 ").append(phone);
            meta.append(meta.length() > 0 ? " · " : "").append(q.optInt("galon", 0)).append(" galon");
            meta.append(DeliveryQueueActivity.checkoutLegSuffix(q));
            meta.append(DeliveryQueueActivity.receiptSuffix(str(q, "receipt_no")));
            h.tvMeta.setText(meta.toString());
            h.tvOngkir.setVisibility(q.optDouble("ongkir", 0) > 0 ? View.VISIBLE : View.GONE);
            boolean pickup = q.optBoolean("pickup_only", false);
            DeliveryQueueActivity.bindPickupOnlyBadge(h.tvPickupOnly, pickup);
            bindChips(h.productChips, q, pickup);
            double km = distanceKmTo(q);
            DeliveryQueueActivity.bindLocationLine(h.tvAdminArea, DeliveryQueueActivity.queueLocationLine(
                    cust, q, Double.isNaN(km) ? null : formatKm(km)));

            h.btnMore.setOnClickListener(v -> {
                android.widget.PopupMenu menu = new android.widget.PopupMenu(v.getContext(), v);
                menu.getMenu().add("🧭 Navigasi");
                menu.setOnMenuItemClickListener(i -> {
                    navigateTo(q);
                    return true;
                });
                menu.show();
            });
            h.btnTakeOver.setText(delivering ? "🚚" : "📥");
            h.btnTakeOver.setAlpha(delivering ? 0.5F : 1.0F);
            h.btnTakeOver.setOnClickListener(v -> confirmTakeOver(q));
            DeliveryQueueActivity.bindClaimRequest(h.tvClaimRequest, h.btnTakeOver, str(q, "uuid"), delivering);
            h.btnWaChat.setVisibility(phone.isEmpty() ? View.GONE : View.VISIBLE);
            h.btnWaChat.setOnClickListener(v -> DeliveryQueueActivity.openWhatsApp(v.getContext(), phone, ""));
        }

        private void bindPriority(TextView tv, boolean priority, String why) {
            tv.clearAnimation();
            tv.setAlpha(1f);
            tv.setVisibility(priority ? View.VISIBLE : View.GONE);
            if (!priority) return;
            tv.setText("⚡ PRIORITAS" + (why.isEmpty() ? "" : ": " + why));
            android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1f, 0.35f);
            blink.setDuration(650);
            blink.setRepeatMode(android.view.animation.Animation.REVERSE);
            blink.setRepeatCount(android.view.animation.Animation.INFINITE);
            tv.startAnimation(blink);
        }

        private void bindChips(android.widget.LinearLayout box, JSONObject q, boolean pickup) {
            box.removeAllViews();
            JSONArray slugs = q.optJSONArray("item_slugs");
            String type = str(q, "galon_type");
            boolean typed = !pickup && GalonTypeUi.known(type);
            boolean assumed = GalonTypeUi.SOURCE_DEFAULT.equals(str(q, "galon_type_source"));
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; slugs != null && i < slugs.length(); i++) {
                JSONObject it = slugs.optJSONObject(i);
                if (it == null) continue;
                int bg = 0xFF1565C0;
                try {
                    if (!str(it, "color").isEmpty()) bg = android.graphics.Color.parseColor(str(it, "color"));
                } catch (IllegalArgumentException ignored) {
                }
                int fg = DeliveryQueueActivity.chipTextColor(bg);
                String label = str(it, "slug") + " ×" + Math.round(it.optDouble("qty", 0));
                TextView chip = new TextView(box.getContext());
                chip.setText(typed ? GalonTypeUi.tagged(label, type, assumed, fg) : label);
                chip.setTextColor(fg);
                chip.setTextSize(12f);
                chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
                chip.setPadding(Math.round(8 * d), Math.round(3 * d), Math.round(8 * d), Math.round(3 * d));
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(10 * d);
                g.setColor(bg);
                chip.setBackground(g);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
                lp.setMarginEnd(Math.round(6 * d));
                box.addView(chip, lp);
            }
            box.setVisibility(box.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvCustomer, tvElapsed, tvMeta, tvOngkir, tvPickupOnly, tvAdminArea, tvOrderNote, tvPriorityBig, tvClaimRequest;
            final android.widget.LinearLayout productChips;
            final MaterialButton btnMore, btnTakeOver, btnWaChat;

            VH(View v) {
                super(v);
                tvCustomer = v.findViewById(R.id.tvCustomer);
                tvElapsed = v.findViewById(R.id.tvElapsed);
                tvMeta = v.findViewById(R.id.tvMeta);
                tvOngkir = v.findViewById(R.id.tvOngkir);
                tvPickupOnly = v.findViewById(R.id.tvPickupOnly);
                tvAdminArea = v.findViewById(R.id.tvAdminArea);
                tvOrderNote = v.findViewById(R.id.tvOrderNote);
                tvPriorityBig = v.findViewById(R.id.tvPriorityBig);
                tvClaimRequest = v.findViewById(R.id.tvClaimRequest);
                productChips = v.findViewById(R.id.productChips);
                btnMore = v.findViewById(R.id.btnMore);
                btnTakeOver = v.findViewById(R.id.btnTakeOver);
                btnWaChat = v.findViewById(R.id.btnWaChat);
            }
        }
    }
}
