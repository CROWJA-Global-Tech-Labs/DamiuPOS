package com.crowja.damiupos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.IderRanking;
import com.crowja.damiupos.util.MapThumbs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🛵 MODUS IDER — pendamping jualan keliling ("ider"). Layar penuh, layar tetap menyala:
 * <ul>
 *   <li>2/3 ATAS — maksimal 10 pelanggan TERDEKAT dari posisi sekarang, diurut ulang otomatis
 *   mengikuti gerak (DiffUtil → kartu bergeser beranimasi, bukan berkedip). Kartu: miniatur peta
 *   ({@link MapThumbs}), nama + jarak, badge terakhir beli, tombol "Tambahkan Transaksi".</li>
 *   <li>1/3 BAWAH — peta live Leaflet + tile ESRI ({@link MapTiles}, sama dengan peta lain di app,
 *   tanpa Google Maps): pin posisi berikon kendaraan perangkat + kerucut arah hadap, pin bernomor
 *   untuk 10 kartu dan titik kecil untuk lokasi pelanggan lain di sekitar.</li>
 * </ul>
 *
 * <p>Data = kandidat Daftar Kunjungan ({@link CustomerDao#getVisitCandidates}: semua pelanggan cabang
 * kecuali "Umum" & yang diserahterimakan perangkat ini) digabung per ORANG ({@link CustomerDao#dedupKey});
 * "terakhir beli" = MAX(lokal, server, serah-terima) — aturan yang sama dengan {@link VisitListActivity}.
 * Multi-lokasi: semua titik dihitung, kartu memakai titik TERDEKAT, dan "Tambahkan Transaksi" langsung
 * memilih titik itu sebagai tujuan "Kirim ke" ({@link TransactionActivity#EXTRA_DEST_LAT}).
 *
 * <p>Arah hadap: arah GPS saat bergerak (≥ 5 km/jam — kompas terganggu logam motor), selain itu
 * kompas (rotation vector, dikoreksi deklinasi magnet → utara sejati, sama dengan arah GPS).
 */
public class IderModeActivity extends AppCompatActivity {

    private static final int REQ_LOCATION = 7312;
    /** Titik lokasi terdekat yang dipasang sebagai pin peta (lebih dari 10 kartu → konteks sekitar). */
    private static final int MAP_SPOTS = 40;
    /** m/dtk (≈ 5,4 km/jam): di atas ini arah GPS dipercaya, di bawahnya kompas. */
    private static final float MOVING_SPEED = 1.5f;

    private RecyclerView rv;
    private TextView tvStatus;
    private TextView tvEmpty;
    private WebView web;
    private IderAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<IderRanking.Person> people = new ArrayList<>();
    private boolean dataLoaded;
    private final AtomicInteger loadGen = new AtomicInteger();
    private boolean firstResume = true;
    /** Kartu yang sedang tampil (bridge peta → kartu). */
    private List<Row> shown = new ArrayList<>();
    /** Pin terakhir di peta: kunci pin → titik (untuk "Tambahkan Transaksi" dari popup pin mana pun). */
    private final Map<String, IderRanking.Spot> pinSpots = new HashMap<>();

    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastFix;
    private long lastFixElapsed;
    private long lastGpsFixElapsed;
    private GeomagneticField geomagnetic;

    private double rankedLat = Double.NaN;
    private double rankedLng = Double.NaN;
    private long rankedAt;

    private SensorManager sensorManager;
    private SensorEventListener sensorListener;
    private float compassDeg = Float.NaN;
    private float sentHeading = Float.NaN;
    private long headingSentAt;

    private boolean pageReady;

    // ---- tampilan daftar (📏 di kepala): radius + jumlah maks, disimpan per HP ----
    private static final String PREFS = "mode_keliling";
    /** Langkah slider radius 10–500 m. Default 100 m. */
    private static final int[] RADII = {10, 20, 30, 40, 50, 75, 100, 150, 200, 250, 300, 400, 500};
    private static final int DEFAULT_RADIUS_IDX = 6;
    private static final int MIN_LIST = 3;
    private static final int MAX_LIST_CAP = 50;
    private TextView tvView;
    private int radiusIdx = DEFAULT_RADIUS_IDX;
    private int maxList = IderRanking.MAX_LIST;

    private void loadViewPrefs() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        radiusIdx = Math.max(0, Math.min(RADII.length - 1, p.getInt("radius_idx", DEFAULT_RADIUS_IDX)));
        maxList = Math.max(MIN_LIST, Math.min(MAX_LIST_CAP, p.getInt("max_list", IderRanking.MAX_LIST)));
        renderViewChip();
    }

    private void saveViewPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("radius_idx", radiusIdx).putInt("max_list", maxList).apply();
    }

    private int radiusMeters() {
        return RADII[radiusIdx];
    }

    private String radiusLabel() {
        int m = radiusMeters();
        if (m <= 0) return "tanpa batas";
        return m < 1000 ? m + " m" : IderRanking.formatDistance(m);
    }

    private void renderViewChip() {
        int m = radiusMeters();
        String r = m <= 0 ? "∞" : (m < 1000 ? m + " m" : IderRanking.formatDistance(m));
        tvView.setText("📏 " + r + " · " + maxList + " ▾");
    }

    /** Popup "Tampilan Daftar": dua slider horizontal — langsung diterapkan & disimpan saat digeser. */
    private void showViewPopup() {
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView lblR = new TextView(this);
        lblR.setTextColor(0xFF212121);
        lblR.setTextSize(15f);
        box.addView(lblR);
        android.widget.SeekBar sbR = new android.widget.SeekBar(this);
        sbR.setMax(RADII.length - 1);
        sbR.setProgress(radiusIdx);
        box.addView(sbR);
        TextView hintR = new TextView(this);
        hintR.setText("Hanya pelanggan dalam jarak ini (10–500 m) yang tampil di daftar.");
        hintR.setTextColor(0xFF455A64);
        hintR.setTextSize(12f);
        box.addView(hintR);

        final TextView lblN = new TextView(this);
        lblN.setTextColor(0xFF212121);
        lblN.setTextSize(15f);
        lblN.setPadding(0, pad, 0, 0);
        box.addView(lblN);
        android.widget.SeekBar sbN = new android.widget.SeekBar(this);
        sbN.setMax(MAX_LIST_CAP - MIN_LIST);
        sbN.setProgress(maxList - MIN_LIST);
        box.addView(sbN);

        Runnable labels = () -> {
            lblR.setText("📏 Radius: " + radiusLabel());
            lblN.setText("🔢 Maks jumlah daftar: " + maxList + " pelanggan");
        };
        labels.run();
        android.widget.SeekBar.OnSeekBarChangeListener l = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (sb == sbR) radiusIdx = progress;
                else maxList = MIN_LIST + progress;
                labels.run();
                renderViewChip();
                saveViewPrefs();
                pushRadius();
                rerank(true);
                renderEmpty();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        };
        sbR.setOnSeekBarChangeListener(l);
        sbN.setOnSeekBarChangeListener(l);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Tampilan Daftar")
                .setView(box)
                .setPositiveButton("Selesai", null)
                .setNeutralButton("Bawaan", (d, w) -> {
                    radiusIdx = DEFAULT_RADIUS_IDX;
                    maxList = IderRanking.MAX_LIST;
                    renderViewChip();
                    saveViewPrefs();
                    pushRadius();
                    rerank(true);
                    renderEmpty();
                })
                .show();
    }

    /** Lingkaran radius di sekitar posisi saya pada peta (0 = tanpa batas → disembunyikan). */
    private void pushRadius() {
        if (pageReady && web != null) web.evaluateJavascript("setRadius(" + radiusMeters() + ")", null);
    }

    /** Saat diam tak ada fix baru (minDistance) → tetap cek berkala supaya daftar tak tertinggal. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            rerank(false);
            ui.postDelayed(this, 3000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ider_mode);
        // Dipakai sambil berkendara: layar jangan padam di tengah jalan.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullscreen();

        tvStatus = findViewById(R.id.tvIderStatus);
        tvEmpty = findViewById(R.id.tvIderEmpty);
        rv = findViewById(R.id.rvIder);
        web = findViewById(R.id.webIder);
        findViewById(R.id.btnIderExit).setOnClickListener(v -> finish());
        tvStatus.setOnClickListener(v -> onStatusTapped());
        tvView = findViewById(R.id.tvIderView);
        loadViewPrefs();
        tvView.setOnClickListener(v -> showViewPopup());

        adapter = new IderAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(MapTiles.userAgent());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushMe();
                if (!Float.isNaN(compassDeg)) sendHeading(compassDeg);
                pushRadius();
                if (!Double.isNaN(rankedLat)) rerank(true);
            }
        });
        web.loadDataWithBaseURL("https://unpkg.com", buildMapHtml(), "text/html", "UTF-8", null);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        renderStatus();
        renderEmpty();
        loadData();
        if (!hasLocationPermission()) requestLocationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Kembali dari Transaksi Baru → muat ulang supaya badge "Beli hari ini" langsung benar.
        if (!firstResume) loadData();
        firstResume = false;
        startLocation();
        startCompass();
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        stopLocation();
        stopCompass();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Dialog / tarikan bilah status mengembalikan bar sistem → sembunyikan lagi.
        if (hasFocus) enterFullscreen();
    }

    private void enterFullscreen() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ---------------------------------------------------------------- data

    private void loadData() {
        final int gen = loadGen.incrementAndGet();
        new Thread(() -> {
            List<IderRanking.Person> built = buildPeople();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || gen != loadGen.get()) return;
                people.clear();
                people.addAll(built);
                dataLoaded = true;
                renderStatus();
                rerank(true);
                renderEmpty();
            });
        }, "ider-load").start();
    }

    /** Kandidat kunjungan digabung per ORANG — aturan wakil & "terakhir beli" sama dengan VisitListActivity. */
    private List<IderRanking.Person> buildPeople() {
        CustomerDao dao = new CustomerDao(DatabaseHelper.getInstance(this));
        Map<String, List<Customer>> groups = new LinkedHashMap<>();
        for (Customer c : dao.getVisitCandidates()) {
            String key = CustomerDao.dedupKey(c);
            List<Customer> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(c);
        }
        Map<Long, CustomerDao.ReorderStats> stats = dao.getReorderStats();
        java.text.SimpleDateFormat ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        List<IderRanking.Person> out = new ArrayList<>();
        for (Map.Entry<String, List<Customer>> e : groups.entrySet()) {
            List<Customer> copies = e.getValue();
            Customer rep = copies.get(0);
            long last = Long.MIN_VALUE;
            // Prediksi order kembali — model Follow Up (CustomerDao.followUpReorderDay) per ORANG:
            // galon = Σ agregat efektif tiap salinan (MAX lokal/server, cermin VisitListActivity),
            // beli pertama = paling awal (lokal/server), galon hari terakhir = dari transaksi LOKAL terbaru.
            int galon = 0;
            String first = null;
            String localLast = null;
            double lastQty = 0;
            for (Customer c : copies) {
                if (!rep.isMine() && c.isMine()) rep = c;   // utamakan salinan milik perangkat ini
                long l = Math.max(CustomerDao.parseMillisOrMin(c.getLocalLastJual()),
                        Math.max(CustomerDao.parseMillisOrMin(c.getSrvLastJual()),
                                CustomerDao.parseMillisOrMin(c.getHandedOverAt())));
                if (l > last) last = l;
                galon += c.effectiveOrdered();
                CustomerDao.ReorderStats s = stats.get(c.getId());
                String f = s != null ? s.firstJual : null;
                String sf = c.getSrvFirstJual();
                if (sf != null && sf.length() >= 10 && CustomerDao.parseMillisOrMin(sf) != Long.MIN_VALUE) {
                    String sfLocal = ts.format(new java.util.Date(CustomerDao.parseMillisOrMin(sf)));
                    if (f == null || sfLocal.compareTo(f) < 0) f = sfLocal;
                }
                if (f != null && (first == null || f.compareTo(first) < 0)) first = f;
                if (s != null && s.lastJual != null && (localLast == null || s.lastJual.compareTo(localLast) > 0)) {
                    localLast = s.lastJual;
                    lastQty = s.lastDayQty;
                }
            }
            String reorderDay = last != Long.MIN_VALUE
                    ? CustomerDao.followUpReorderDay(galon, first, ts.format(new java.util.Date(last)), lastQty)
                    : null;
            // Titik milik wakil dulu, lalu titik salinan lain yang belum ada (koordinat 5 desimal).
            List<IderRanking.Spot> spots = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            addSpots(rep, spots, seen);
            for (Customer c : copies) if (c != rep) addSpots(c, spots, seen);
            if (spots.isEmpty()) continue;
            out.add(new IderRanking.Person(e.getKey(), rep.getId(), rep.getName(), last, spots, reorderDay));
        }
        return out;
    }

    private static void addSpots(Customer c, List<IderRanking.Spot> spots, Set<String> seen) {
        List<Customer.Location> locs = c.getLocations();
        if (locs == null) return;   // DAO sudah mensintesis "Kediaman" dari koordinat lama bila perlu
        for (int i = 0; i < locs.size(); i++) {
            Customer.Location l = locs.get(i);
            if (l == null || (l.lat == 0 && l.lng == 0)) continue;   // (0,0) = belum diisi
            if (seen.add(MapThumbs.key(l.lat, l.lng))) {
                spots.add(new IderRanking.Spot(l.name, l.lat, l.lng, c.getId(), photoOf(c, l, i == 0)));
            }
        }
    }

    /**
     * Foto lokasi untuk kartu — aturan sama dengan kartu "Kirim ke" Transaksi Baru: foto lokasi itu,
     * lalu (lokasi UTAMA saja) foto rumah pelanggan; berkas lokal hanya bila benar-benar ada.
     */
    private static String photoOf(Customer c, Customer.Location l, boolean primary) {
        String p = l.photo != null ? l.photo.trim() : "";
        if (p.isEmpty() && l.photos != null && !l.photos.isEmpty() && l.photos.get(0) != null) {
            p = l.photos.get(0).trim();
        }
        if (!p.isEmpty() && (isUrl(p) || new java.io.File(p).exists())) return p;
        if (primary) {
            String path = c.getPhotoPath();
            if (path != null && !path.isEmpty() && new java.io.File(path).exists()) return path;
            String url = c.getPhotoUrl();
            if (url != null && isUrl(url.trim())) return url.trim();
        }
        return null;
    }

    private static boolean isUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * Hitung ulang 10 terdekat. Tanpa {@code force}: paling cepat tiap 1,5 dtk saat bergerak, dan saat
     * diam (geser < 8 m) cukup tiap 15 dtk — daftar tak "menari" karena GPS bergoyang beberapa meter.
     */
    private void rerank(boolean force) {
        if (!dataLoaded || lastFix == null) return;
        double lat = lastFix.getLatitude();
        double lng = lastFix.getLongitude();
        long now = SystemClock.elapsedRealtime();
        if (!force && !Double.isNaN(rankedLat)) {
            long age = now - rankedAt;
            if (age < 1500) return;
            if (IderRanking.haversineMeters(rankedLat, rankedLng, lat, lng) < 8 && age < 15000) return;
        }
        rankedLat = lat;
        rankedLng = lng;
        rankedAt = now;
        List<IderRanking.Ranked> top = IderRanking.nearest(people, lat, lng, maxList, radiusMeters());
        List<Row> rows = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) rows.add(new Row(top.get(i), i + 1));
        shown = rows;
        adapter.submitList(rows);
        renderEmpty();
        pushPins(lat, lng, top);
    }

    // ---------------------------------------------------------------- lokasi

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    private boolean providerEnabled() {
        if (locationManager == null) return false;
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        } else {
            Toast.makeText(this, "Izin lokasi ditolak — Mode Keliling tak bisa mengurutkan pelanggan terdekat.",
                    Toast.LENGTH_LONG).show();
        }
        renderStatus();
        renderEmpty();
    }

    private void onStatusTapped() {
        if (!hasLocationPermission()) {
            requestLocationPermission();
        } else if (!providerEnabled()) {
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocation() {
        renderStatus();
        renderEmpty();
        if (locationManager == null || locationListener != null || !hasLocationPermission()) return;
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                onFix(location);
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                renderStatus();
                renderEmpty();
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                renderStatus();
                renderEmpty();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };
        List<String> all = locationManager.getAllProviders();
        // Didaftarkan walau provider sedang mati: begitu GPS dinyalakan, fix langsung mengalir.
        try {
            if (all.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener);
            }
            if (all.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener);
            }
        } catch (Exception ignored) {
        }
        // Posisi terakhir yang masih segar (< 2 menit) → daftar langsung terisi tanpa menunggu fix baru.
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location l = all.contains(p) ? locationManager.getLastKnownLocation(p) : null;
                if (l == null) continue;
                long ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
                if (ageMs > 120_000L) continue;
                if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
            } catch (Exception ignored) {
            }
        }
        if (best != null && lastFix == null) onFix(best);
    }

    private void stopLocation() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {
            }
        }
        locationListener = null;
    }

    private void onFix(Location loc) {
        if (loc == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean gps = LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        if (gps) {
            lastGpsFixElapsed = now;
        } else if (lastFix != null && lastGpsFixElapsed > 0 && now - lastGpsFixElapsed < 10_000L) {
            return;   // GPS masih mengalir → abaikan fix jaringan yang kasar
        }
        lastFix = loc;
        lastFixElapsed = now;
        if (geomagnetic == null) {
            geomagnetic = new GeomagneticField((float) loc.getLatitude(), (float) loc.getLongitude(),
                    (float) loc.getAltitude(), System.currentTimeMillis());
        }
        if (isMoving()) sendHeading(loc.getBearing());
        pushMe();
        renderStatus();
        rerank(false);
        renderEmpty();
    }

    private boolean isMoving() {
        return lastFix != null && lastFix.hasBearing() && lastFix.hasSpeed()
                && lastFix.getSpeed() >= MOVING_SPEED
                && SystemClock.elapsedRealtime() - lastFixElapsed < 4000L;
    }

    // ---------------------------------------------------------------- kompas / arah hadap

    private void startCompass() {
        if (sensorManager == null || sensorListener != null) return;
        final Sensor rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        final Sensor acc = rot == null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        final Sensor mag = rot == null ? sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) : null;
        if (rot == null && (acc == null || mag == null)) return;   // tanpa kompas: arah dari GPS saja
        final float[] gravity = new float[3];
        final float[] geomag = new float[3];
        final boolean[] have = {false, false};
        sensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                float[] r = new float[9];
                boolean ok;
                if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                    // Sebagian HP (Samsung lama) menolak vektor > 4 elemen.
                    float[] v = new float[Math.min(4, e.values.length)];
                    System.arraycopy(e.values, 0, v, 0, v.length);
                    SensorManager.getRotationMatrixFromVector(r, v);
                    ok = true;
                } else {
                    if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                        System.arraycopy(e.values, 0, gravity, 0, 3);
                        have[0] = true;
                    } else {
                        System.arraycopy(e.values, 0, geomag, 0, 3);
                        have[1] = true;
                    }
                    ok = have[0] && have[1] && SensorManager.getRotationMatrix(r, null, gravity, geomag);
                }
                if (ok) onCompass(azimuthOf(r));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        if (rot != null) {
            sensorManager.registerListener(sensorListener, rot, SensorManager.SENSOR_DELAY_UI);
        } else {
            sensorManager.registerListener(sensorListener, acc, SensorManager.SENSOR_DELAY_UI);
            sensorManager.registerListener(sensorListener, mag, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /**
     * Azimut (derajat, utara magnet) arah HP menghadap. HP mendatar = arah ujung atas layar; HP tegak
     * (dipasang di setang) = arah punggung HP — tanpa remap X/Z, azimut dari getOrientation kacau
     * saat HP berdiri (pitch mendekati 90°).
     */
    private static float azimuthOf(float[] r) {
        float[] o = new float[3];
        SensorManager.getOrientation(r, o);
        if (Math.abs(Math.toDegrees(o[1])) > 45) {
            float[] remapped = new float[9];
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
            SensorManager.getOrientation(remapped, o);
        }
        return (float) ((Math.toDegrees(o[0]) + 360) % 360);
    }

    private void stopCompass() {
        if (sensorManager != null && sensorListener != null) {
            try {
                sensorManager.unregisterListener(sensorListener);
            } catch (Exception ignored) {
            }
        }
        sensorListener = null;
    }

    private void onCompass(float magneticDeg) {
        float trueDeg = norm(magneticDeg + (geomagnetic != null ? geomagnetic.getDeclination() : 0f));
        // Peredam: sensor bergetar 10-20×/dtk; ambil 20% langkah lewat jalur putaran terpendek.
        compassDeg = Float.isNaN(compassDeg) ? trueDeg : norm(compassDeg + 0.2f * delta(compassDeg, trueDeg));
        if (!isMoving()) sendHeading(compassDeg);
    }

    /** Kirim arah ke peta: hanya bila berubah ≥ 3° dan paling sering ~8×/dtk (hemat WebView). */
    private void sendHeading(float deg) {
        if (!pageReady || web == null) return;
        long now = SystemClock.uptimeMillis();
        if (!Float.isNaN(sentHeading) && (Math.abs(delta(sentHeading, deg)) < 3f || now - headingSentAt < 120)) return;
        sentHeading = norm(deg);
        headingSentAt = now;
        web.evaluateJavascript("setHeading(" + Math.round(sentHeading) + ")", null);
    }

    private static float norm(float d) {
        return ((d % 360f) + 360f) % 360f;
    }

    /** Selisih terpendek a→b (−180..180). */
    private static float delta(float a, float b) {
        return ((b - a + 540f) % 360f) - 180f;
    }

    // ---------------------------------------------------------------- status & kosong

    private void renderStatus() {
        String s;
        // Ringkas: duduk sebaris dengan judul "Mode Keliling".
        if (!hasLocationPermission()) {
            s = "⚠️ Izin lokasi";
        } else if (!providerEnabled()) {
            s = "⚠️ GPS mati";
        } else if (lastFix == null) {
            s = "📡 Mencari…";
        } else {
            StringBuilder b = new StringBuilder("📡 ");
            if (!LocationManager.GPS_PROVIDER.equals(lastFix.getProvider())) b.append("Jaringan ");
            b.append(lastFix.hasAccuracy() ? "±" + Math.round(lastFix.getAccuracy()) + " m" : "aktif");
            if (lastFix.hasSpeed() && lastFix.getSpeed() >= 0.5f) {
                b.append(" · ").append(Math.round(lastFix.getSpeed() * 3.6f)).append(" km/j");
            }
            s = b.toString();
        }
        tvStatus.setText(s);
    }

    private void renderEmpty() {
        String msg = null;
        if (!dataLoaded) {
            msg = "⏳ Memuat pelanggan…";
        } else if (people.isEmpty()) {
            msg = "Belum ada pelanggan berkoordinat.\nIsi koordinat lokasi pelanggan supaya muncul di Mode Keliling.";
        } else if (!hasLocationPermission()) {
            msg = "Mode Keliling butuh izin lokasi untuk mengurutkan pelanggan terdekat.\nKetuk status GPS di atas untuk mengizinkan.";
        } else if (lastFix == null) {
            msg = providerEnabled()
                    ? "📡 Menunggu posisi GPS…\nCari tempat terbuka supaya sinyal cepat didapat."
                    : "GPS mati.\nKetuk status GPS di atas untuk menyalakan Lokasi.";
        } else if (!Double.isNaN(rankedLat) && shown.isEmpty()) {
            msg = "Tidak ada pelanggan dalam radius " + radiusLabel() + ".\nKetuk 📏 di atas untuk memperbesar radius.";
        }
        tvEmpty.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) tvEmpty.setText(msg);
        rv.setVisibility(msg == null ? View.VISIBLE : View.INVISIBLE);
    }

    // ---------------------------------------------------------------- aksi

    private void openTransaction(long customerId, double lat, double lng) {
        // Titik terdekat langsung jadi tujuan "Kirim ke" (pelanggan multi-lokasi).
        startActivity(new Intent(this, TransactionActivity.class)
                .putExtra("type", Transaction.TYPE_JUAL)
                .putExtra("customer_id", customerId)
                .putExtra(TransactionActivity.EXTRA_DEST_LAT, lat)
                .putExtra(TransactionActivity.EXTRA_DEST_LNG, lng));
    }

    /** Ketuk kartu → menu: riwayat transaksi, tunjukkan di peta, hapus pelanggan (Admin / HP owner). */
    private void showCardMenu(Row r) {
        boolean canDelete = canDeleteCustomer();
        List<String> items = new ArrayList<>();
        items.add("📜 Riwayat Transaksi");
        items.add("📍 Tunjukkan di Peta");
        if (canDelete) items.add("🗑️ Hapus Pelanggan");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(r.name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        // Popup "Histori Transaksi" Detail Pelanggan (server, semua perangkat).
                        startActivity(new Intent(this, CustomerDetailActivity.class)
                                .putExtra("customer_id", r.customerId)
                                .putExtra(CustomerDetailActivity.EXTRA_HISTORY_ONLY, true));
                    } else if (which == 1) {
                        focusOnMap(r);
                    } else {
                        confirmDeleteCustomer(r);
                    }
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    /** Aturan sama dengan tombol Hapus di Detail Pelanggan: Admin saja; HP satu-pengguna (owner) boleh. */
    private boolean canDeleteCustomer() {
        DatabaseHelper db = DatabaseHelper.getInstance(this);
        long uid = new SettingsDao(db).getCurrentUserId();
        if (uid <= 0) return true;
        com.crowja.damiupos.model.User u = new com.crowja.damiupos.db.UserDao(db).getById(uid);
        return u == null || u.canDeleteCustomer();
    }

    /** Hapus pelanggan — konfirmasi DUA ketukan (ketuk pertama mengubah tombol jadi "Ketuk sekali lagi"). */
    private void confirmDeleteCustomer(Row r) {
        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🗑️ Hapus pelanggan?")
                .setMessage("\"" + r.name + "\" beserta datanya akan dihapus dari perangkat ini dan server "
                        + "(sama dengan Hapus di Detail Pelanggan).\n\nKetuk \"Hapus\" dua kali untuk memastikan.")
                .setPositiveButton("Hapus", null)
                .setNegativeButton("Batal", null)
                .create();
        dlg.setOnShowListener(x -> {
            android.widget.Button pos = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            pos.setTextColor(0xFFC62828);
            int[] taps = {0};
            pos.setOnClickListener(v -> {
                if (++taps[0] < 2) {
                    pos.setText("Ketuk sekali lagi");
                    return;
                }
                pos.setEnabled(false);
                new CustomerDao(DatabaseHelper.getInstance(this)).delete(r.customerId);
                Toast.makeText(this, "Pelanggan dihapus", Toast.LENGTH_SHORT).show();
                dlg.dismiss();
                loadData();
            });
        });
        dlg.show();
    }

    /** Ketuk foto → layar penuh (ketuk untuk menutup). Tanpa foto lokasi → beri tahu. */
    private void openPhoto(Row r) {
        if (r.photo == null) {
            Toast.makeText(this, "Belum ada foto lokasi untuk pelanggan ini.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String src = r.photo;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String path = src;
            if (isUrl(src)) {
                // Nama cache sama dengan thumbnail kartu → biasanya tak perlu unduh ulang.
                java.io.File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(app, src,
                        "loc_" + Integer.toHexString(src.hashCode()) + ".img");
                path = f != null ? f.getAbsolutePath() : null;
            }
            final android.graphics.Bitmap bmp = path != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeForScreen(this, path) : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (bmp == null) {
                    Toast.makeText(this, "Foto tidak dapat dimuat", Toast.LENGTH_SHORT).show();
                    return;
                }
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(0xFF000000);
                android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                d.setContentView(iv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                iv.setOnClickListener(v -> d.dismiss());
                d.show();
            });
        }).start();
    }

    private void focusOnMap(Row r) {
        if (pageReady && web != null) {
            web.evaluateJavascript("focusPin(" + JSONObject.quote(r.pinKey) + ")", null);
        }
    }

    /** Kunci pin = orang + indeks titik (unik walau satu orang punya beberapa lokasi). */
    private static String pinKey(IderRanking.Person p, int spotIndex) {
        return p.key + "#" + spotIndex;
    }

    // ---------------------------------------------------------------- peta

    private void pushMe() {
        if (!pageReady || web == null || lastFix == null) return;
        web.evaluateJavascript(String.format(Locale.US, "updateMe(%.6f,%.6f,%.0f)",
                lastFix.getLatitude(), lastFix.getLongitude(),
                lastFix.hasAccuracy() ? lastFix.getAccuracy() : 0f), null);
    }

    private void pushPins(double lat, double lng, List<IderRanking.Ranked> top) {
        if (!pageReady || web == null) return;   // onPageFinished memanggil rerank(true) lagi
        Map<IderRanking.Person, IderRanking.Ranked> rankedOf = new IdentityHashMap<>();
        Map<IderRanking.Person, Integer> rankOf = new IdentityHashMap<>();
        for (int i = 0; i < top.size(); i++) {
            rankedOf.put(top.get(i).person, top.get(i));
            rankOf.put(top.get(i).person, i + 1);
        }
        JSONArray arr = new JSONArray();
        pinSpots.clear();
        Set<String> added = new HashSet<>();
        try {
            for (IderRanking.SpotHit h : IderRanking.nearestSpots(people, lat, lng, MAP_SPOTS)) {
                addPin(arr, added, h.person, h.spotIndex, h.meters, rankedOf, rankOf);
            }
            // Titik kartu yang tak masuk 40 titik terdekat (jarang: banyak multi-lokasi) tetap dipasang.
            for (IderRanking.Ranked r : top) {
                addPin(arr, added, r.person, r.spotIndex, r.meters, rankedOf, rankOf);
            }
        } catch (Exception ignored) {
        }
        web.evaluateJavascript("setPins(" + arr + ")", null);
    }

    private void addPin(JSONArray arr, Set<String> added, IderRanking.Person p, int spotIndex, double meters,
                        Map<IderRanking.Person, IderRanking.Ranked> rankedOf,
                        Map<IderRanking.Person, Integer> rankOf) throws Exception {
        String k = pinKey(p, spotIndex);
        if (!added.add(k)) return;
        IderRanking.Spot s = p.spots.get(spotIndex);
        IderRanking.Ranked card = rankedOf.get(p);
        // Nomor hanya pada titik yang dipakai kartu; titik lain orang yang sama = titik kecil.
        int rank = card != null && card.spotIndex == spotIndex ? rankOf.get(p) : 0;
        JSONObject o = new JSONObject();
        o.put("k", k);
        o.put("p", p.key);
        o.put("lat", s.lat);
        o.put("lng", s.lng);
        o.put("n", p.name == null ? "Pelanggan" : p.name);
        o.put("s", p.spots.size() > 1 && s.name != null ? s.name : "");
        o.put("d", IderRanking.formatDistance(meters));
        o.put("r", rank);
        arr.put(o);
        pinSpots.put(k, s);
    }

    private final class Bridge {
        /** Popup pin → "Tambahkan Transaksi". */
        @JavascriptInterface
        public void addTrx(String pinKey) {
            ui.post(() -> {
                IderRanking.Spot s = pinSpots.get(pinKey);
                if (s != null && !isFinishing()) openTransaction(s.ownerId, s.lat, s.lng);
            });
        }

        /** Pin bernomor diketuk → gulir daftar ke kartunya. */
        @JavascriptInterface
        public void showCard(String personKey) {
            ui.post(() -> {
                for (int i = 0; i < shown.size(); i++) {
                    if (shown.get(i).personKey.equals(personKey)) {
                        rv.smoothScrollToPosition(i);
                        return;
                    }
                }
            });
        }
    }

    private String buildMapHtml() {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        String vehicle = cfg.getDeviceVehicle();
        if (vehicle == null || vehicle.trim().isEmpty()) vehicle = cfg.getDeviceIcon();
        if (vehicle == null || vehicle.trim().isEmpty()) vehicle = "🛵";
        String color = cfg.getDeviceColor();
        if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) color = "#1565C0";
        double[] center = Wilayah.parseCenter(cfg.getBranchCenter());
        double cLat = center != null ? center[0] : -7.55;
        double cLng = center != null ? center[1] : 110.83;

        return "<!DOCTYPE html>\n<html><head>\n"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n"
                + "<style>\n"
                + "html,body{height:100%;margin:0;padding:0;font-family:sans-serif;}\n"
                + "#map{width:100%;height:100%;}\n"
                + MapTiles.BRIGHT_TILE_CSS + "\n"
                + ".me{position:relative;width:72px;height:72px;}\n"
                + ".beam{position:absolute;left:0;top:0;width:72px;height:72px;transform-origin:50% 50%;transition:transform .3s linear;display:none;}\n"
                + ".veh{position:absolute;left:20px;top:20px;width:32px;height:32px;box-sizing:border-box;border-radius:50%;border:3px solid #fff;"
                + "box-shadow:0 1px 6px rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;font-size:17px;line-height:1;}\n"
                + ".rk{width:26px;height:26px;box-sizing:border-box;border-radius:50%;background:#BF360C;color:#fff;font-weight:bold;font-size:13px;"
                + "display:flex;align-items:center;justify-content:center;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.45);}\n"
                + ".rk.on{background:#0D47A1;transform:scale(1.3);}\n"
                + ".dot{width:12px;height:12px;box-sizing:border-box;border-radius:50%;background:#546E7A;border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,.4);}\n"
                + "#follow{position:fixed;right:10px;bottom:24px;z-index:1000;display:none;background:#fff;color:#0D47A1;font-weight:bold;"
                + "font-size:13px;padding:9px 13px;border-radius:18px;box-shadow:0 1px 6px rgba(0,0,0,.4);}\n"
                + ".pp b{font-size:14px;color:#212121;} .pp .s{font-size:12px;color:#455A64;margin-top:2px;}\n"
                + ".pp a{display:block;margin-top:8px;padding:9px 10px;background:#2E7D32;color:#fff !important;border-radius:8px;"
                + "text-align:center;font-weight:bold;text-decoration:none;font-size:13px;}\n"
                + "</style>\n</head><body>\n"
                + "<div id='map'></div>\n"
                + "<div id='follow' onclick='followMe()'>🎯 Ikuti posisi saya</div>\n"
                + "<script>\n"
                + "var map=L.map('map',{zoomControl:true}).setView([" + cLat + "," + cLng + "],14);\n"
                + "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{maxZoom:19,attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);\n"
                + "var VEH=" + JSONObject.quote(vehicle) + ", COLOR=" + JSONObject.quote(color) + ";\n"
                // Kerucut arah hadap (menunjuk ke atas = utara sebelum diputar) + segitiga penunjuk di ujungnya.
                + "var BEAM='<svg width=\"72\" height=\"72\" viewBox=\"0 0 72 72\">'+\n"
                + "  '<defs><radialGradient id=\"g\" cx=\"50%\" cy=\"50%\" r=\"50%\"><stop offset=\"0\" stop-color=\"#1E88E5\" stop-opacity=\".6\"/>'+\n"
                + "  '<stop offset=\"1\" stop-color=\"#1E88E5\" stop-opacity=\"0\"/></radialGradient></defs>'+\n"
                + "  '<path d=\"M36 36 L15 2 A40 40 0 0 1 57 2 Z\" fill=\"url(#g)\"/>'+\n"
                + "  '<path d=\"M36 6 L43 18 L29 18 Z\" fill=\"#0D47A1\" stroke=\"#fff\" stroke-width=\"1.5\"/></svg>';\n"
                + "var meIcon=L.divIcon({className:'',html:'<div class=\"me\"><div id=\"beam\" class=\"beam\">'+BEAM+'</div>'+\n"
                + "  '<div class=\"veh\" style=\"background:'+COLOR+'\">'+VEH+'</div></div>',iconSize:[72,72],iconAnchor:[36,36]});\n"
                + "var follow=true, meMarker=null, accCircle=null, rot=null, curHead=null, firstFix=true, pins={}, focusKey=null;\n"
                + "function updateMe(lat,lng,acc){\n"
                + "  var ll=[lat,lng];\n"
                + "  if(!meMarker){meMarker=L.marker(ll,{icon:meIcon,zIndexOffset:3000,interactive:false}).addTo(map);applyHead();}\n"
                + "  else meMarker.setLatLng(ll);\n"
                + "  if(acc>0){if(!accCircle){accCircle=L.circle(ll,{radius:acc,color:'#1E88E5',weight:1,fillColor:'#1E88E5',fillOpacity:.08,interactive:false}).addTo(map);}\n"
                + "    else{accCircle.setLatLng(ll);accCircle.setRadius(acc);}}\n"
                + "  if(firstFix){firstFix=false;map.setView(ll,17,{animate:false});}\n"
                + "  else if(follow){map.panTo(ll,{animate:true,duration:.6});}\n"
                + "  drawRad();\n"
                + "}\n"
                // Lingkaran radius daftar (📏) mengikuti posisi saya.
                + "var radC=null, radM=0;\n"
                + "function setRadius(m){radM=m;drawRad();}\n"
                + "function drawRad(){\n"
                + "  if(!meMarker||radM<=0){if(radC){map.removeLayer(radC);radC=null;}return;}\n"
                + "  var ll=meMarker.getLatLng();\n"
                + "  if(!radC){radC=L.circle(ll,{radius:radM,color:'#BF360C',weight:2,dashArray:'6 6',fill:false,interactive:false}).addTo(map);}\n"
                + "  else{radC.setLatLng(ll);radC.setRadius(radM);}\n"
                + "}\n"
                // Putaran kumulatif: 350°→10° berputar +20°, bukan mundur −340°.
                + "function setHeading(h){\n"
                + "  if(rot===null){rot=h;}else{rot+=((h-curHead)%360+540)%360-180;}\n"
                + "  curHead=h;applyHead();\n"
                + "}\n"
                + "function applyHead(){var b=document.getElementById('beam');if(b&&rot!==null){b.style.display='block';b.style.transform='rotate('+rot+'deg)';}}\n"
                + "function showFollow(){document.getElementById('follow').style.display='block';}\n"
                + "function followMe(){follow=true;document.getElementById('follow').style.display='none';\n"
                + "  var prev=focusKey;focusKey=null;if(prev&&pins[prev]){pins[prev].setIcon(pinIcon(pins[prev]._p));}\n"
                + "  map.closePopup();if(meMarker){map.setView(meMarker.getLatLng(),Math.max(map.getZoom(),16));}}\n"
                + "map.on('dragstart',function(){follow=false;showFollow();});\n"
                + "function pinIcon(p){\n"
                + "  if(p.r>0) return L.divIcon({className:'',html:'<div class=\"rk'+(p.k===focusKey?' on':'')+'\">'+p.r+'</div>',iconSize:[26,26],iconAnchor:[13,13],popupAnchor:[0,-12]});\n"
                + "  return L.divIcon({className:'',html:'<div class=\"dot\"></div>',iconSize:[12,12],iconAnchor:[6,6],popupAnchor:[0,-6]});\n"
                + "}\n"
                // Popup dirakit lewat DOM (bukan string HTML): nama pelanggan bisa memuat kutip apa pun.
                + "function popupEl(p){\n"
                + "  var d=document.createElement('div');d.className='pp';\n"
                + "  var b=document.createElement('b');b.textContent=p.n;d.appendChild(b);\n"
                + "  if(p.s){var s=document.createElement('div');s.className='s';s.textContent='🏠 '+p.s;d.appendChild(s);}\n"
                + "  var ds=document.createElement('div');ds.className='s';ds.textContent='📍 '+p.d;d.appendChild(ds);\n"
                + "  var a=document.createElement('a');a.href='#';a.textContent='➕ Tambahkan Transaksi';\n"
                + "  a.onclick=function(e){e.preventDefault();if(window.Android)Android.addTrx(p.k);return false;};\n"
                + "  d.appendChild(a);return d;\n"
                + "}\n"
                + "function setPins(list){\n"
                + "  var seen={};\n"
                + "  list.forEach(function(p){\n"
                + "    seen[p.k]=1;var m=pins[p.k];\n"
                + "    if(!m){\n"
                + "      m=L.marker([p.lat,p.lng],{icon:pinIcon(p),zIndexOffset:p.r>0?(1000-p.r):0}).addTo(map);\n"
                + "      m.bindPopup(popupEl(p));\n"
                + "      m.on('click',function(){if(m._p.r>0&&window.Android)Android.showCard(m._p.p);});\n"
                + "      pins[p.k]=m;\n"
                + "    }else{\n"
                + "      var o=m._p;\n"
                + "      if(o.r!==p.r){m.setIcon(pinIcon(p));m.setZIndexOffset(p.r>0?(1000-p.r):0);}\n"
                + "      if(o.d!==p.d||o.r!==p.r){m.setPopupContent(popupEl(p));}\n"
                + "    }\n"
                + "    m._p=p;\n"
                + "  });\n"
                + "  Object.keys(pins).forEach(function(k){if(!seen[k]){map.removeLayer(pins[k]);delete pins[k];if(focusKey===k)focusKey=null;}});\n"
                + "}\n"
                + "function focusPin(k){\n"
                + "  var m=pins[k];if(!m)return;\n"
                + "  follow=false;showFollow();\n"
                + "  var prev=focusKey;focusKey=k;\n"
                + "  if(prev&&pins[prev]&&prev!==k){pins[prev].setIcon(pinIcon(pins[prev]._p));}\n"
                + "  m.setIcon(pinIcon(m._p));\n"
                + "  map.setView(m.getLatLng(),Math.max(map.getZoom(),17),{animate:true});\n"
                + "  m.openPopup();\n"
                + "}\n"
                + "</script></body></html>";
    }

    // ---------------------------------------------------------------- daftar

    /** Satu kartu — nilai tampilan beku; DiffUtil membandingkan ini. */
    private static final class Row {
        final String personKey;
        final String pinKey;
        final long ownerId;
        final String name;
        final String spotName;
        final int spotCount;
        final double lat;
        final double lng;
        final String dist;
        final int rank;
        final long lastOrderMillis;
        final String photo;
        final String reorderDay;
        /** Wakil orang ini (salinan milik perangkat bila ada) — untuk riwayat & hapus. */
        final long customerId;

        Row(IderRanking.Ranked r, int rank) {
            IderRanking.Spot s = r.spot();
            this.personKey = r.person.key;
            this.pinKey = pinKey(r.person, r.spotIndex);
            this.ownerId = s.ownerId;
            this.name = r.person.name == null ? "Pelanggan" : r.person.name;
            this.spotName = s.name;
            this.spotCount = r.person.spots.size();
            this.lat = s.lat;
            this.lng = s.lng;
            this.dist = IderRanking.formatDistance(r.meters);
            this.rank = rank;
            this.lastOrderMillis = r.person.lastOrderMillis;
            this.photo = s.photo;
            this.reorderDay = r.person.reorderDay;
            this.customerId = r.person.customerId;
        }

        boolean sameContent(Row o) {
            return rank == o.rank && dist.equals(o.dist) && pinKey.equals(o.pinKey) && ownerId == o.ownerId
                    && name.equals(o.name) && lastOrderMillis == o.lastOrderMillis && spotCount == o.spotCount
                    && java.util.Objects.equals(photo, o.photo) && java.util.Objects.equals(reorderDay, o.reorderDay);
        }
    }

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override
        public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
            return a.personKey.equals(b.personKey);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
            return a.sameContent(b);
        }

        @Override
        public Object getChangePayload(@NonNull Row oldItem, @NonNull Row newItem) {
            return Boolean.TRUE;   // bind parsial: tanpa animasi pudar-silang tiap jarak berubah
        }
    };

    private final class IderAdapter extends ListAdapter<Row, VH> {
        IderAdapter() {
            super(DIFF);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ider_customer, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            h.bind(getItem(position));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
            h.bind(getItem(position));
        }
    }

    private final class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView rank;
        final TextView name;
        final TextView dist;
        final TextView spot;
        final TextView buy;
        final TextView reorder;
        Row row;
        /** Sumber gambar yang sedang terpasang (foto / "map:lat,lng") — muat ulang hanya bila berganti. */
        String boundImage;

        VH(View v) {
            super(v);
            thumb = v.findViewById(R.id.ivIderThumb);
            rank = v.findViewById(R.id.tvIderRank);
            name = v.findViewById(R.id.tvIderName);
            dist = v.findViewById(R.id.tvIderDist);
            spot = v.findViewById(R.id.tvIderSpot);
            buy = v.findViewById(R.id.tvIderBuy);
            reorder = v.findViewById(R.id.tvIderReorder);
            v.setOnClickListener(x -> {
                if (row != null) showCardMenu(row);
            });
            thumb.setOnClickListener(x -> {
                if (row != null) openPhoto(row);
            });
            v.findViewById(R.id.btnIderTrx).setOnClickListener(x -> {
                if (row != null) openTransaction(row.ownerId, row.lat, row.lng);
            });
        }

        void bind(Row r) {
            row = r;
            rank.setText(String.valueOf(r.rank));
            name.setText(r.name);
            dist.setText("📍 " + r.dist);
            if (r.spotCount > 1) {
                String sn = r.spotName == null || r.spotName.trim().isEmpty() ? "Lokasi" : r.spotName.trim();
                spot.setText("🏠 " + sn + " · " + r.spotCount + " lokasi");
                spot.setVisibility(View.VISIBLE);
            } else {
                spot.setVisibility(View.GONE);
            }
            IderRanking.BuyBadge b = IderRanking.lastBuyBadge(r.lastOrderMillis, System.currentTimeMillis(),
                    TimeZone.getDefault());
            buy.setText(b.text);
            styleBadge(buy, b.level);
            IderRanking.BuyBadge ro = IderRanking.reorderBadge(r.reorderDay, System.currentTimeMillis(),
                    TimeZone.getDefault());
            if (ro != null) {
                reorder.setText(ro.text);
                styleBadge(reorder, ro.level);
                reorder.setVisibility(View.VISIBLE);
            } else {
                reorder.setVisibility(View.GONE);
            }
            // Foto lokasi; tanpa foto → miniatur peta. Dimuat ulang hanya bila sumbernya berganti
            // (bind dipanggil tiap jarak berubah).
            String src = r.photo != null ? r.photo : "map:" + MapThumbs.key(r.lat, r.lng);
            if (!src.equals(boundImage)) {
                boundImage = src;
                if (r.photo == null) {
                    MapThumbs.load(thumb, r.lat, r.lng);
                } else if (isUrl(r.photo)) {
                    com.crowja.damiupos.util.BitmapUtils.loadIntoView(thumb, r.photo,
                            "loc_" + Integer.toHexString(r.photo.hashCode()));
                } else {
                    com.crowja.damiupos.util.BitmapUtils.loadLocalIntoView(thumb, r.photo, 256, 256);
                }
            }
        }
    }

    /** Warna badge per tingkat — pasangan latar muda + teks gelap, semuanya ≥ 5,7:1. */
    private void styleBadge(TextView tv, IderRanking.BuyLevel level) {
        int bg;
        int fg;
        switch (level) {
            case FRESH:
                bg = 0xFFE8F5E9;
                fg = 0xFF1B5E20;   // 7,0:1
                break;
            case WARN:
                bg = 0xFFFFF3E0;
                fg = 0xFF7A4100;   // 7,4:1
                break;
            case STALE:
                bg = 0xFFFFEBEE;
                fg = 0xFFB71C1C;   // 5,7:1
                break;
            default:
                bg = 0xFFE3F2FD;
                fg = 0xFF0D47A1;   // 7,5:1
                break;
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        tv.setBackground(d);
        tv.setTextColor(fg);
    }
}
