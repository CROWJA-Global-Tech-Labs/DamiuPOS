package com.crowja.damiupos;

import com.crowja.damiupos.map.LiveDeviceOverlay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * "Persebaran Pelanggan" — peta semua pelanggan yang punya koordinat (OSM/Leaflet, tanpa API key),
 * cermin fitur dashboard web. Ringan: WebView + Leaflet + MarkerCluster dari CDN (tanpa Maps SDK),
 * pin dikelompokkan (cluster) supaya lancar di HP lama walau ratusan titik.
 *
 * <p>Fitur:
 * <ul>
 *   <li>Satu pin per LOKASI pelanggan (mendukung multi-lokasi {@code locations}), diwarnai per
 *       perangkat asal ({@code originLabel}) dengan legenda yang bisa di-toggle.</li>
 *   <li>"Pilih Pelanggan" — dialog multi-pilih (dengan pencarian) untuk memilih pelanggan mana
 *       yang ditampilkan di peta.</li>
 *   <li>"Pelanggan Saya" — cepat batasi ke pelanggan milik perangkat ini.</li>
 *   <li>Posisi Saya — pinpoint koordinat kita saat ini (marker pengendara live mengikuti GPS).</li>
 *   <li>Popup pin: nama, telepon, badge Reseller/Baru, tombol Detail & Navigasi (Google Maps).</li>
 * </ul>
 *
 * <p>Online-only (Leaflet + tile CDN), sama seperti semua peta lain di aplikasi.
 */
public class CustomerMapActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_LOCATION = 401;

    // Palet ikon/warna per-perangkat — cermin App\Support\DeviceIcon di web (urutan sama →
    // pemetaan mirip). Perangkat "Web"/tanpa-label memakai fallback abu-abu.
    private static final String[] ICONS = {
            "🛵", "🏍", "🚚", "🚗", "🚙",
            "🛺", "🚲", "🏪", "💧", "📦",
            "⭐", "🚩"};
    private static final String[] COLORS = {
            "#e11d48", "#ea580c", "#ca8a04", "#16a34a", "#0891b2", "#2563eb",
            "#7c3aed", "#db2777", "#0d9488", "#4d7c0f", "#475569", "#b91c1c"};
    private static final String WEB_ICON = "🖥";   // 🖥️
    private static final String WEB_COLOR = "#64748b";

    private WebView webView;
    private CustomerDao customerDao;
    private SettingsDao settingsDao;

    /** Pelanggan (sudah dedup lintas-perangkat) yang punya minimal satu koordinat. */
    private final List<Customer> mapCustomers = new ArrayList<>();

    /** Live location marker (pengendara) mengikuti GPS. */
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean mapActive = false;
    private boolean pageReady = false;
    private Location pendingLocation;
    private Location lastLoc;          // fix GPS terakhir — dasar filter "Pelanggan Sekitar"

    @SuppressLint("SetJavaScriptEnabled")
    /** Pin posisi LIVE perangkat lain — dipasang di semua peta aplikasi. */
    private LiveDeviceOverlay liveDev;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_map);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        TextView tvEmpty = findViewById(R.id.tvEmpty);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        // Ambil pelanggan (dedup lintas-perangkat → satu wakil per orang, sekaligus mengisi
        // originLabels) lalu simpan yang punya koordinat.
        List<Customer> all = CustomerDao.dedupeForDisplay(customerDao.getAll(CustomerDao.SORT_NAME));
        for (Customer c : all) {
            if (hasAnyCoordinate(c)) {
                mapCustomers.add(c);
            }
        }

        if (mapCustomers.isEmpty()) {
            webView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        mapActive = true;
        toolbar.setTitle("Persebaran Pelanggan (" + mapCustomers.size() + ")");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(MapTiles.userAgent());
        webView.addJavascriptInterface(new MapBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                // Pin perangkat lain disuntikkan SETELAH halaman siap (variabel global `map` sudah ada).
                if (liveDev == null) {
                    liveDev = new LiveDeviceOverlay(CustomerMapActivity.this, webView);
                }
                liveDev.start();
                if (pendingLocation != null) {
                    pushLocationToMap(pendingLocation);
                    pendingLocation = null;
                }
            }
        });

        webView.loadDataWithBaseURL("https://unpkg.com", buildMapHtml(), "text/html", "UTF-8", null);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensureLocationPermissionThenTrack();
    }

    private static boolean hasAnyCoordinate(Customer c) {
        if (c.getLocations() != null) {
            for (Customer.Location l : c.getLocations()) {
                if (l.lat != 0 || l.lng != 0) {
                    return true;
                }
            }
        }
        return c.getLatitude() != 0 || c.getLongitude() != 0;
    }

    // ---------------------------------------------------------------- menu / filter

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Pilih Pelanggan").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 4, 1, "Pelanggan Sekitar Saya").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 2, 2, "Hanya Pelanggan Saya").setCheckable(true)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 3, 3, "Tampilkan Semua").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    private boolean onlyMine = false;

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem mine = menu.findItem(2);
        if (mine != null) {
            mine.setChecked(onlyMine);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1) {
            showSelectCustomersDialog();
            return true;
        }
        if (id == 2) {
            onlyMine = !onlyMine;
            invalidateOptionsMenu();
            applyMineFilter();
            return true;
        }
        if (id == 4) {
            showRadiusDialog();
            return true;
        }
        if (id == 3) {
            onlyMine = false;
            invalidateOptionsMenu();
            evalJs("showAll();");
            Toast.makeText(this, "Menampilkan semua pelanggan", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Batasi peta ke pelanggan milik perangkat ini (is_mine), atau kembalikan ke semua. */
    private void applyMineFilter() {
        if (!onlyMine) {
            evalJs("showAll();");
            return;
        }
        JSONArray ids = new JSONArray();
        for (Customer c : mapCustomers) {
            if (c.isMine()) {
                ids.put(c.getId());
            }
        }
        evalJs("setSelected(" + ids + ");");
        Toast.makeText(this, "Hanya pelanggan perangkat ini", Toast.LENGTH_SHORT).show();
    }

    /** Tampilkan hanya pelanggan dalam radius tertentu dari lokasi GPS saat ini. */
    private void showRadiusDialog() {
        if (lastLoc == null) {
            Toast.makeText(this, "Menunggu lokasi GPS… nyalakan lokasi lalu coba lagi",
                    Toast.LENGTH_LONG).show();
            ensureLocationPermissionThenTrack();
            return;
        }
        final String[] labels = {"500 m", "1 km", "2 km", "5 km", "10 km"};
        final int[] meters = {500, 1000, 2000, 5000, 10000};
        new AlertDialog.Builder(this)
                .setTitle("Pelanggan dalam radius")
                .setItems(labels, (d, which) -> {
                    onlyMine = false;
                    invalidateOptionsMenu();
                    Location here = lastLoc;
                    evalJs("setRadius(" + here.getLatitude() + "," + here.getLongitude()
                            + "," + meters[which] + ");");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Dialog multi-pilih pelanggan (dengan pencarian) → peta hanya menampilkan yang dicentang. */
    private void showSelectCustomersDialog() {
        final SelectAdapter adapter = new SelectAdapter();

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final EditText search = new EditText(this);
        search.setHint("Cari nama / nomor…");
        search.setSingleLine(true);
        search.setPadding(pad, pad, pad, pad);
        box.addView(search);

        final ListView list = new ListView(this);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.addView(list, lp);

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                adapter.filter(s.toString());
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Pilih Pelanggan di Peta")
                .setView(box)
                .setPositiveButton("Terapkan", (d, w) -> {
                    onlyMine = false;
                    invalidateOptionsMenu();
                    JSONArray ids = new JSONArray();
                    for (Customer c : mapCustomers) {
                        if (adapter.checked.contains(c.getId())) {
                            ids.put(c.getId());
                        }
                    }
                    if (ids.length() == 0) {
                        Toast.makeText(this, "Tidak ada pelanggan dipilih — menampilkan semua",
                                Toast.LENGTH_SHORT).show();
                        evalJs("showAll();");
                    } else {
                        evalJs("setSelected(" + ids + ");");
                        Toast.makeText(this, ids.length() + " pelanggan ditampilkan",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Pilih Semua", (d, w) -> {
                    for (Customer c : mapCustomers) {
                        adapter.checked.add(c.getId());
                    }
                    evalJs("showAll();");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Adapter list multi-pilih dengan filter teks; state centang disimpan di {@link #checked}. */
    private class SelectAdapter extends BaseAdapter {
        final java.util.HashSet<Long> checked = new java.util.HashSet<>();
        final List<Customer> shown = new ArrayList<>(mapCustomers);

        SelectAdapter() {
            for (Customer c : mapCustomers) {
                checked.add(c.getId());   // default: semua tercentang
            }
        }

        void filter(String q) {
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (Customer c : mapCustomers) {
                String name = c.getName() != null ? c.getName().toLowerCase(Locale.ROOT) : "";
                String phone = c.getPhone() != null ? c.getPhone().toLowerCase(Locale.ROOT) : "";
                if (needle.isEmpty() || name.contains(needle) || phone.contains(needle)) {
                    shown.add(c);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).getId(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CheckBox cb = convertView instanceof CheckBox ? (CheckBox) convertView : new CheckBox(CustomerMapActivity.this);
            Customer c = shown.get(position);
            String label = c.getName() != null ? c.getName() : "Pelanggan";
            if (c.getPhone() != null && !c.getPhone().isEmpty()) {
                label += "  ·  " + c.getPhone();
            }
            cb.setText(label);
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(checked.contains(c.getId()));
            final long cid = c.getId();
            cb.setOnCheckedChangeListener((b, isChecked) -> {
                if (isChecked) {
                    checked.add(cid);
                } else {
                    checked.remove(cid);
                }
            });
            return cb;
        }
    }

    // ---------------------------------------------------------------- lokasi (GPS)

    private void ensureLocationPermissionThenTrack() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_PERMISSION_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "Izin lokasi ditolak — posisi Anda tidak ditampilkan di peta",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (!mapActive || locationManager == null || locationListener != null) {
            return;
        }
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) {
                pushLocationToMap(location);
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };
        boolean any = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, locationListener);
                any = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, locationListener);
                any = true;
            }
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) {
                pushLocationToMap(last);
            }
        } catch (Exception ignored) {
        }
        if (!any) {
            Toast.makeText(this, "GPS tidak aktif — nyalakan lokasi untuk melihat posisi Anda",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {
            }
            locationListener = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapActive && locationListener == null
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        if (liveDev != null) liveDev.stop();
        stopLocationUpdates();
        super.onDestroy();
    }

    private void pushLocationToMap(Location loc) {
        if (loc == null || webView == null) {
            return;
        }
        lastLoc = loc;
        if (!pageReady) {
            pendingLocation = loc;
            return;
        }
        float bearing = loc.hasBearing() ? loc.getBearing() : -1f;
        evalJs("updateMe(" + loc.getLatitude() + "," + loc.getLongitude() + "," + bearing + ");");
    }

    private void evalJs(String js) {
        if (webView != null) {
            webView.evaluateJavascript(js, null);
        }
    }

    // ---------------------------------------------------------------- bridge

    private class MapBridge {
        @JavascriptInterface
        public void navigate(double lat, double lng, String name) {
            runOnUiThread(() -> openInGoogleMaps(lat, lng, name));
        }

        @JavascriptInterface
        public void openDetail(long customerId) {
            runOnUiThread(() -> {
                Intent i = new Intent(CustomerMapActivity.this, CustomerDetailActivity.class);
                i.putExtra("customer_id", customerId);
                startActivity(i);
            });
        }
    }

    private void openInGoogleMaps(double lat, double lng, String name) {
        String label = name != null && !name.isEmpty() ? name : "Pelanggan";
        String uri = "geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(" + Uri.encode(label) + ")";
        Intent gmaps = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        gmaps.setPackage("com.google.android.apps.maps");
        if (gmaps.resolveActivity(getPackageManager()) != null) {
            startActivity(gmaps);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng)));
        }
    }

    // ---------------------------------------------------------------- HTML

    /** Warna+ikon stabil untuk sebuah label perangkat asal (cermin DeviceIcon; "Web" = fallback). */
    private static String[] identityFor(String originLabel) {
        if (originLabel == null || originLabel.trim().isEmpty()
                || originLabel.equalsIgnoreCase("Web")) {
            return new String[]{WEB_ICON, WEB_COLOR};
        }
        CRC32 crc = new CRC32();
        crc.update(originLabel.getBytes());
        int idx = (int) (Math.abs(crc.getValue()) % ICONS.length);
        return new String[]{ICONS[idx], COLORS[idx]};
    }

    private String buildMapHtml() {
        JSONArray points = new JSONArray();
        // origin → {icon,color,count} (count = jumlah PELANGGAN unik) untuk legenda.
        Map<String, int[]> legendCount = new LinkedHashMap<>();     // origin → [count]
        Map<String, String[]> legendIcon = new LinkedHashMap<>();   // origin → [icon,color]
        double sumLat = 0, sumLng = 0;
        int nCoord = 0;

        for (Customer c : mapCustomers) {
            String origin = c.getOriginLabel() != null && !c.getOriginLabel().isEmpty()
                    ? c.getOriginLabel() : "Web";
            String[] id = identityFor(origin);
            if (!legendCount.containsKey(origin)) {
                legendCount.put(origin, new int[]{0});
                legendIcon.put(origin, id);
            }
            legendCount.get(origin)[0]++;

            // Satu pin per lokasi (multi-lokasi didukung); fallback ke koordinat lama.
            List<double[]> coords = new ArrayList<>();
            List<String> locNames = new ArrayList<>();
            if (c.getLocations() != null) {
                for (Customer.Location l : c.getLocations()) {
                    if (l.lat != 0 || l.lng != 0) {
                        coords.add(new double[]{l.lat, l.lng});
                        locNames.add(l.name != null ? l.name : "");
                    }
                }
            }
            if (coords.isEmpty() && (c.getLatitude() != 0 || c.getLongitude() != 0)) {
                coords.add(new double[]{c.getLatitude(), c.getLongitude()});
                locNames.add("");
            }

            for (int i = 0; i < coords.size(); i++) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("cid", c.getId());
                    o.put("lat", coords.get(i)[0]);
                    o.put("lng", coords.get(i)[1]);
                    o.put("name", c.getName() != null ? c.getName() : "Pelanggan");
                    o.put("phone", c.getPhone() != null ? c.getPhone() : "");
                    o.put("loc", locNames.get(i));
                    o.put("origin", origin);
                    o.put("icon", id[0]);
                    o.put("color", id[1]);
                    o.put("reseller", c.isReseller());
                    o.put("mine", c.isMine());
                    points.put(o);
                    sumLat += coords.get(i)[0];
                    sumLng += coords.get(i)[1];
                    nCoord++;
                } catch (Exception ignored) {
                }
            }
        }

        JSONArray legend = new JSONArray();
        for (Map.Entry<String, int[]> e : legendCount.entrySet()) {
            try {
                String[] id = legendIcon.get(e.getKey());
                JSONObject o = new JSONObject();
                o.put("origin", e.getKey());
                o.put("icon", id[0]);
                o.put("color", id[1]);
                o.put("count", e.getValue()[0]);
                legend.put(o);
            } catch (Exception ignored) {
            }
        }

        double centerLat = nCoord > 0 ? sumLat / nCoord : -7.55;
        double centerLng = nCoord > 0 ? sumLng / nCoord : 110.83;

        return "<!DOCTYPE html>\n<html><head>\n" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n" +
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n" +
                "<link rel='stylesheet' href='https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css'/>\n" +
                "<link rel='stylesheet' href='https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.Default.css'/>\n" +
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n" +
                "<script src='https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster.js'></script>\n" +
                "<style>\n" +
                "  body{margin:0;padding:0;font-family:sans-serif;}\n" +
                "  #map{width:100%;height:100vh;}\n" +
                "  .leaflet-popup-content a.navbtn{display:block;margin-top:8px;padding:9px 12px;background:#1565C0;color:#fff !important;border-radius:6px;text-decoration:none;font-size:13px;font-weight:bold;text-align:center;}\n" +
                "  .leaflet-popup-content a.detbtn{display:block;margin-top:8px;padding:9px 12px;background:#0d9488;color:#fff !important;border-radius:6px;text-decoration:none;font-size:13px;font-weight:bold;text-align:center;}\n" +
                "  .pname{font-size:14px;font-weight:bold;color:#222;}\n" +
                "  .pphone{font-size:12px;color:#666;margin-top:2px;}\n" +
                "  .badge{display:inline-block;font-size:10px;font-weight:bold;padding:1px 6px;border-radius:8px;margin-left:4px;vertical-align:middle;}\n" +
                "  .pin{position:relative;width:30px;height:38px;}\n" +
                "  .pin svg{position:absolute;top:0;left:0;}\n" +
                "  .pin .em{position:absolute;top:3px;left:0;width:30px;text-align:center;font-size:14px;line-height:20px;}\n" +
                "  .meicon{background:none !important;border:none !important;}\n" +
                "  #merot{width:44px;height:44px;transform-origin:50% 50%;transition:transform .4s linear;}\n" +
                "  #btnme{position:fixed;right:14px;bottom:22px;width:48px;height:48px;border-radius:24px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center;font-size:24px;z-index:1000;cursor:pointer;}\n" +
                "  #legend{position:fixed;left:8px;top:8px;max-width:62%;max-height:40%;overflow:auto;z-index:1000;}\n" +
                "  .lchip{display:inline-flex;align-items:center;gap:4px;background:#fff;border-radius:14px;padding:3px 9px;margin:2px;box-shadow:0 1px 4px rgba(0,0,0,.25);font-size:12px;cursor:pointer;border:2px solid transparent;}\n" +
                "  .lchip.off{opacity:.4;}\n" +
                "  .ldot{width:10px;height:10px;border-radius:5px;display:inline-block;}\n" +
                "</style>\n</head><body>\n" +
                "<div id='map'></div>\n" +
                "<div id='legend'></div>\n" +
                "<div id='radinfo' style='display:none;position:fixed;left:50%;top:8px;transform:translateX(-50%);z-index:1000;background:#1565C0;color:#fff;font-size:12px;font-weight:bold;padding:5px 12px;border-radius:14px;box-shadow:0 1px 4px rgba(0,0,0,.3);'></div>\n" +
                "<div id='btnme' onclick='goMe()' title='Posisi Saya'>&#128205;</div>\n" +
                "<script>\n" +
                "var pts = " + points + ";\n" +
                "var legend = " + legend + ";\n" +
                "var map = L.map('map',{zoomControl:true}).setView([" + centerLat + "," + centerLng + "], 13);\n" +
                "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{subdomains:'" + MapTiles.SUBDOMAINS + "',maxZoom:19,attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);\n" +
                "var cluster = L.markerClusterGroup({maxClusterRadius:50,spiderfyOnMaxZoom:true});\n" +
                "map.addLayer(cluster);\n" +
                "var hiddenOrigins = {};\n" +
                "var selectedIds = null;\n" +   // null = semua
                "var radCenter=null, radMeters=0, radCircle=null;\n" +
                "function haversine(aLat,aLng,bLat,bLng){\n" +
                "  var R=6371000,toRad=Math.PI/180;\n" +
                "  var dLat=(bLat-aLat)*toRad, dLng=(bLng-aLng)*toRad;\n" +
                "  var s=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(aLat*toRad)*Math.cos(bLat*toRad)*Math.sin(dLng/2)*Math.sin(dLng/2);\n" +
                "  return 2*R*Math.asin(Math.min(1,Math.sqrt(s)));\n" +
                "}\n" +
                "function pinIcon(color,emoji){\n" +
                "  var svg='<svg width=\"30\" height=\"38\" viewBox=\"0 0 30 38\" xmlns=\"http://www.w3.org/2000/svg\">'+\n" +
                "    '<path d=\"M15 0C6.7 0 0 6.7 0 15c0 10 15 23 15 23s15-13 15-23C30 6.7 23.3 0 15 0z\" fill=\"'+color+'\"/>'+\n" +
                "    '<circle cx=\"15\" cy=\"14\" r=\"10\" fill=\"#fff\"/></svg>';\n" +
                "  return L.divIcon({className:'',html:'<div class=\"pin\">'+svg+'<div class=\"em\">'+emoji+'</div></div>',iconSize:[30,38],iconAnchor:[15,38],popupAnchor:[0,-34]});\n" +
                "}\n" +
                "function esc(s){return (s||'').replace(/'/g,\"\\\\'\");}\n" +
                // KEAMANAN: popup dirakit sebagai STRING lalu diserahkan ke bindPopup() yang
                // merendernya lewat innerHTML. Nama/nomor/lokasi pelanggan bisa diisi SIAPA SAJA —
                // checkout online di landing publik bisa dipakai orang luar tanpa akun. Tanpa
                // escape, "<img src=x onerror=…>" berjalan DI DALAM halaman peta ini, dan halaman
                // ini memegang variabel `pts` berisi nama+nomor+titik rumah SELURUH pelanggan
                // cabang → satu nama pelanggan jahat = kebocoran basis data dari tiap HP staf.
                // esc() di atas hanya untuk konteks string JS (tanda kutip), bukan HTML.
                "function escHtml(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){\n" +
                "  return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}\n" +
                "var markers=[];\n" +
                "pts.forEach(function(p){\n" +
                "  var m=L.marker([p.lat,p.lng],{icon:pinIcon(p.color,p.icon)});\n" +
                "  m._p=p;\n" +
                "  var phoneHtml = p.phone ? '<div class=\"pphone\">'+escHtml(p.phone)+'</div>' : '';\n" +
                "  var badges = (p.reseller?'<span class=\"badge\" style=\"background:#fef3c7;color:#b45309\">Reseller</span>':'');\n" +
                "  var locHtml = (p.loc && p.loc!=='Kediaman') ? '<div class=\"pphone\">📍 '+escHtml(p.loc)+'</div>' : '';\n" +
                "  var det='<a class=\"detbtn\" href=\"#\" onclick=\"det('+(+p.cid)+');return false;\">👤 Detail Pelanggan</a>';\n" +
                "  var html='<div class=\"pname\">'+escHtml(p.name)+badges+'</div>'+phoneHtml+locHtml+det+\n" +
                // Nama TIDAK lagi disisipkan ke atribut onclick: nilai di sana melewati DUA lapis
                // parser (atribut HTML → string JS), dan nama bertanda kutip ganda cukup untuk
                // keluar dari atributnya. Cukup kirim id numerik; namanya diambil dari objek titik.
                "    '<a class=\"navbtn\" href=\"#\" onclick=\"navById('+(+p.cid)+');return false;\">🧭 Navigasi (Google Maps)</a>';\n" +
                "  m.bindPopup(html);\n" +
                "  markers.push(m);\n" +
                "});\n" +
                "function visible(p){\n" +
                "  if(hiddenOrigins[p.origin])return false;\n" +
                "  if(selectedIds!==null && selectedIds.indexOf(p.cid)<0)return false;\n" +
                "  if(radMeters>0 && radCenter && haversine(radCenter[0],radCenter[1],p.lat,p.lng)>radMeters)return false;\n" +
                "  return true;\n" +
                "}\n" +
                "function applyFilter(){\n" +
                "  cluster.clearLayers();\n" +
                "  var b=[],n=0;\n" +
                "  markers.forEach(function(m){ if(visible(m._p)){cluster.addLayer(m); b.push([m._p.lat,m._p.lng]); n++;} });\n" +
                "  var info=document.getElementById('radinfo');\n" +
                "  if(radMeters>0 && radCenter){\n" +
                "    info.style.display='block';\n" +
                "    var r=radMeters>=1000?(radMeters/1000)+' km':radMeters+' m';\n" +
                "    info.textContent='📍 '+n+' pelanggan dalam '+r;\n" +
                "    b.push([radCenter[0],radCenter[1]]);\n" +
                "  } else { info.style.display='none'; }\n" +
                "  if(b.length>1){map.fitBounds(b,{padding:[40,40],maxZoom:16});}\n" +
                "  else if(b.length===1){map.setView(b[0],16);}\n" +
                "}\n" +
                "function setSelected(ids){selectedIds=ids;clearRadiusState();applyFilter();}\n" +
                "function clearRadiusState(){radMeters=0;radCenter=null;if(radCircle){map.removeLayer(radCircle);radCircle=null;}}\n" +
                "function setRadius(lat,lng,m){\n" +
                "  selectedIds=null;hiddenOrigins={};renderLegend();\n" +
                "  radCenter=[lat,lng];radMeters=m;\n" +
                "  if(radCircle){map.removeLayer(radCircle);}\n" +
                "  radCircle=L.circle([lat,lng],{radius:m,color:'#1565C0',weight:2,fillColor:'#1565C0',fillOpacity:0.08}).addTo(map);\n" +
                "  applyFilter();\n" +
                "}\n" +
                "function showAll(){selectedIds=null;hiddenOrigins={};clearRadiusState();renderLegend();applyFilter();}\n" +
                "function nav(lat,lng,name){if(window.Android&&Android.navigate){Android.navigate(lat,lng,name);}}\n" +
                // Cari titiknya dari daftar yang sudah ada — nama tak pernah melewati HTML sama sekali.
                "function navById(cid){for(var i=0;i<pts.length;i++){if(pts[i].cid===cid){\n" +
                "  nav(pts[i].lat,pts[i].lng,pts[i].name);return;}}}\n" +
                "function det(cid){if(window.Android&&Android.openDetail){Android.openDetail(cid);}}\n" +
                "function renderLegend(){\n" +
                "  var el=document.getElementById('legend');el.innerHTML='';\n" +
                "  legend.forEach(function(g){\n" +
                "    var c=document.createElement('div');c.className='lchip'+(hiddenOrigins[g.origin]?' off':'');\n" +
                "    c.innerHTML='<span class=\"ldot\" style=\"background:'+g.color+'\"></span>'+g.icon+' '+g.origin+' ('+g.count+')';\n" +
                "    c.onclick=function(){hiddenOrigins[g.origin]=!hiddenOrigins[g.origin];renderLegend();applyFilter();};\n" +
                "    el.appendChild(c);\n" +
                "  });\n" +
                "}\n" +
                "renderLegend();applyFilter();\n" +
                // ---- posisi live device (pengendara motor top-down) ----
                "var RIDER='<svg width=\"44\" height=\"44\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">'+\n" +
                "  '<circle cx=\"24\" cy=\"24\" r=\"22\" fill=\"#ffffff\" fill-opacity=\"0.92\" stroke=\"#1565C0\" stroke-width=\"2\"/>'+\n" +
                "  '<rect x=\"21\" y=\"4\" width=\"6\" height=\"9\" rx=\"3\" fill=\"#263238\"/>'+\n" +
                "  '<rect x=\"21\" y=\"35\" width=\"6\" height=\"9\" rx=\"3\" fill=\"#263238\"/>'+\n" +
                "  '<rect x=\"19\" y=\"10\" width=\"10\" height=\"28\" rx=\"5\" fill=\"#1565C0\"/>'+\n" +
                "  '<rect x=\"11\" y=\"12\" width=\"26\" height=\"3.5\" rx=\"1.75\" fill=\"#37474F\"/>'+\n" +
                "  '<ellipse cx=\"24\" cy=\"26\" rx=\"9.5\" ry=\"7\" fill=\"#43A047\"/>'+\n" +
                "  '<circle cx=\"24\" cy=\"24\" r=\"5.5\" fill=\"#F44336\"/>'+\n" +
                "  '<rect x=\"21\" y=\"19.5\" width=\"6\" height=\"2\" rx=\"1\" fill=\"#ffffff\" opacity=\"0.9\"/>'+\n" +
                "  '</svg>';\n" +
                "var meIcon=L.divIcon({className:'meicon',html:'<div id=\"merot\">'+RIDER+'</div>',iconSize:[44,44],iconAnchor:[22,22]});\n" +
                "var meMarker=null;\n" +
                "function updateMe(lat,lng,bearing){\n" +
                "  if(!meMarker){meMarker=L.marker([lat,lng],{icon:meIcon,zIndexOffset:1000,interactive:false}).addTo(map);}\n" +
                "  else{meMarker.setLatLng([lat,lng]);}\n" +
                "  if(bearing>=0){var el=document.getElementById('merot');if(el){el.style.transform='rotate('+bearing+'deg)';}}\n" +
                "}\n" +
                "function goMe(){if(meMarker){map.setView(meMarker.getLatLng(),16);}else{if(window.Android){}}}\n" +
                "</script></body></html>";
    }
}
