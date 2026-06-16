package com.crowja.damiupos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import java.util.List;

/**
 * Peta semua pelanggan follow-up dalam satu layar (OSM/Leaflet, tanpa API key).
 * Tiap pin bernama pelanggan; tap pin → popup nama + tombol "Navigasi" yang
 * membuka Google Maps intent ke pelanggan tersebut.
 *
 * <p>Juga menampilkan posisi live device sebagai ikon pengendara motor
 * (top-down) yang bergerak & berotasi mengikuti GPS.
 *
 * <p>Dipakai dari {@link FollowUpActivity} via tombol "Peta" (langsung dibuka,
 * tanpa dialog pilihan).
 */
public class FollowUpMapActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_LOCATION = 401;

    private WebView webView;
    private final List<Customer> mapCustomers = new ArrayList<>();
    private CustomerDao customerDao;
    private SettingsDao settingsDao;

    // Live location: posisi device ditampilkan sebagai ikon pengendara motor
    // (top-down) yang bergerak/berotasi mengikuti GPS.
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean mapActive = false;   // false saat empty-state (WebView GONE)
    private boolean pageReady = false;   // true setelah onPageFinished
    private Location pendingLocation;    // fix yang datang sebelum page siap

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_followup_map);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        TextView tvEmpty = findViewById(R.id.tvEmpty);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);
        int days = settingsDao.getFollowupDays();

        // Kumpulkan pelanggan follow-up yang punya koordinat.
        for (Customer c : customerDao.getFollowUpCandidates(days)) {
            if (c.getLatitude() != 0 || c.getLongitude() != 0) {
                mapCustomers.add(c);
            }
        }

        if (mapCustomers.isEmpty()) {
            webView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        mapActive = true;

        toolbar.setTitle("Peta Follow Up (" + mapCustomers.size() + ")");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // UA pengenal aplikasi supaya penyedia tile tidak memblokir request.
        settings.setUserAgentString(MapTiles.userAgent());
        webView.addJavascriptInterface(new MapBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingLocation != null) {
                    pushLocationToMap(pendingLocation);
                    pendingLocation = null;
                }
            }
        });

        String html = buildMapHtml();
        webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensureLocationPermissionThenTrack();
    }

    // ---------------------------------------------------------------- lokasi

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
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this,
                        "Izin lokasi ditolak — posisi Anda tidak ditampilkan di peta",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission") // dipanggil hanya setelah permission granted
    private void startLocationUpdates() {
        if (!mapActive || locationManager == null || locationListener != null) return;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                pushLocationToMap(location);
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };

        boolean any = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 2000L, 3f, locationListener);
                any = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 3f, locationListener);
                any = true;
            }
            // Tampilkan posisi terakhir yang diketahui dulu supaya ikon langsung
            // muncul tanpa menunggu fix GPS pertama.
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) pushLocationToMap(last);
        } catch (Exception ignored) {}

        if (!any) {
            Toast.makeText(this,
                    "GPS tidak aktif — nyalakan lokasi untuk melihat posisi Anda",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {}
            locationListener = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-attach listener kalau sebelumnya dilepas onPause (dan izin sudah ada).
        if (mapActive && locationListener == null
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates(); // hemat baterai saat layar peta tidak terlihat
    }

    /** Kirim posisi device ke peta (marker pengendara motor) via JS. */
    private void pushLocationToMap(Location loc) {
        if (loc == null || webView == null) return;
        if (!pageReady) {
            pendingLocation = loc;
            return;
        }
        float bearing = loc.hasBearing() ? loc.getBearing() : -1f;
        String js = "updateMe(" + loc.getLatitude() + "," + loc.getLongitude()
                + "," + bearing + ");";
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        super.onDestroy();
    }

    /** JS bridge: dipanggil dari popup pin untuk Navigasi / Chat Follow-up. */
    private class MapBridge {
        @JavascriptInterface
        public void navigate(double lat, double lng, String name) {
            runOnUiThread(() -> openInGoogleMaps(lat, lng, name));
        }

        @JavascriptInterface
        public void chat(long customerId) {
            runOnUiThread(() -> openChatForFollowUp(customerId));
        }
    }

    /** Buka WhatsApp follow-up untuk pelanggan pin (cari dari id). */
    private void openChatForFollowUp(long customerId) {
        Customer target = null;
        for (Customer c : mapCustomers) {
            if (c.getId() == customerId) { target = c; break; }
        }
        if (target == null) return;
        WhatsAppFollowUp.open(this, target, settingsDao, customerDao);
    }

    private void openInGoogleMaps(double lat, double lng, String name) {
        String label = name != null && !name.isEmpty() ? name : "Pelanggan";
        String uri = "geo:" + lat + "," + lng + "?q=" + lat + "," + lng
                + "(" + Uri.encode(label) + ")";
        Intent gmaps = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        gmaps.setPackage("com.google.android.apps.maps");
        if (gmaps.resolveActivity(getPackageManager()) != null) {
            startActivity(gmaps);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query="
                            + lat + "," + lng)));
        }
    }

    /** Build Leaflet HTML dengan satu marker per pelanggan (popup: nama + Navigasi). */
    private String buildMapHtml() {
        // Bangun array JSON [{lat,lng,name}] untuk di-inject ke JS.
        JSONArray arr = new JSONArray();
        double sumLat = 0, sumLng = 0;
        for (Customer c : mapCustomers) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", c.getId());
                o.put("lat", c.getLatitude());
                o.put("lng", c.getLongitude());
                o.put("name", c.getName() != null ? c.getName() : "Pelanggan");
                o.put("phone", c.getPhone() != null ? c.getPhone() : "");
                arr.put(o);
                sumLat += c.getLatitude();
                sumLng += c.getLongitude();
            } catch (Exception ignored) {}
        }
        double centerLat = sumLat / mapCustomers.size();
        double centerLng = sumLng / mapCustomers.size();
        String pointsJson = arr.toString();

        return "<!DOCTYPE html>\n" +
                "<html><head>\n" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n" +
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n" +
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n" +
                "<style>\n" +
                "  body{margin:0;padding:0;font-family:sans-serif;}\n" +
                "  #map{width:100%;height:100vh;}\n" +
                // color:#fff !important — Leaflet's '.leaflet-container a{color:#0078A8}'
                // lebih spesifik dari '.navbtn', tanpa !important teks jadi biru
                // di atas tombol biru (kontras buruk).
                "  .leaflet-popup-content a.navbtn{display:block;margin-top:8px;padding:9px 12px;background:#1565C0;color:#ffffff !important;border-radius:6px;text-decoration:none;font-size:13px;font-weight:bold;text-align:center;}\n" +
                "  .leaflet-popup-content a.chatbtn{display:block;margin-top:8px;padding:9px 12px;background:#25D366;color:#ffffff !important;border-radius:6px;text-decoration:none;font-size:13px;font-weight:bold;text-align:center;}\n" +
                "  .pname{font-size:14px;font-weight:bold;color:#222;}\n" +
                "  .pphone{font-size:12px;color:#666;margin-top:2px;}\n" +
                // Marker posisi device: divIcon polos (tanpa kotak putih default),
                // inner div dirotasi sesuai bearing GPS.
                "  .meicon{background:none !important;border:none !important;}\n" +
                "  #merot{width:44px;height:44px;transform-origin:50% 50%;transition:transform .4s linear;}\n" +
                "  #btnme{position:fixed;right:14px;bottom:22px;width:48px;height:48px;border-radius:24px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center;font-size:24px;z-index:1000;cursor:pointer;}\n" +
                "</style>\n" +
                "</head><body>\n" +
                "<div id='map'></div>\n" +
                "<div id='btnme' onclick='goMe()' title='Posisi Saya'>&#127949;</div>\n" +
                "<script>\n" +
                "var pts = " + pointsJson + ";\n" +
                "var map = L.map('map',{zoomControl:true}).setView([" + centerLat + "," + centerLng + "], 14);\n" +
                "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{subdomains:'" + MapTiles.SUBDOMAINS + "',maxZoom:19,attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);\n" +
                "var bounds=[];\n" +
                "pts.forEach(function(p){\n" +
                "  var m=L.marker([p.lat,p.lng]).addTo(map);\n" +
                "  bounds.push([p.lat,p.lng]);\n" +
                "  var phoneHtml = p.phone ? '<div class=\"pphone\">'+p.phone+'</div>' : '';\n" +
                "  var safeName=(p.name||'').replace(/'/g,\"\\\\'\");\n" +
                // Tombol Chat Follow-up hanya kalau pelanggan punya nomor telepon.
                "  var chatHtml = p.phone ? '<a class=\"chatbtn\" href=\"#\" onclick=\"chat('+p.id+');return false;\">Chat Follow-up (WhatsApp)</a>' : '';\n" +
                "  var html='<div class=\"pname\">'+p.name+'</div>'+phoneHtml+chatHtml+\n" +
                "    '<a class=\"navbtn\" href=\"#\" onclick=\"nav('+p.lat+','+p.lng+',\\''+safeName+'\\');return false;\">Navigasi (Google Maps)</a>';\n" +
                "  m.bindPopup(html);\n" +
                "});\n" +
                "if(bounds.length>1){map.fitBounds(bounds,{padding:[40,40]});}\n" +
                "else if(bounds.length===1){map.setView(bounds[0],16);}\n" +
                "function nav(lat,lng,name){\n" +
                "  if(window.Android&&Android.navigate){Android.navigate(lat,lng,name);}\n" +
                "}\n" +
                "function chat(id){\n" +
                "  if(window.Android&&Android.chat){Android.chat(id);}\n" +
                "}\n" +
                // ---- posisi live device: ikon pengendara motor (top-down) ----
                "var RIDER='<svg width=\"44\" height=\"44\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">'+\n" +
                "  '<circle cx=\"24\" cy=\"24\" r=\"22\" fill=\"#ffffff\" fill-opacity=\"0.92\" stroke=\"#1565C0\" stroke-width=\"2\"/>'+\n" +
                "  '<rect x=\"21\" y=\"4\" width=\"6\" height=\"9\" rx=\"3\" fill=\"#263238\"/>'+\n" +          // roda depan
                "  '<rect x=\"21\" y=\"35\" width=\"6\" height=\"9\" rx=\"3\" fill=\"#263238\"/>'+\n" +         // roda belakang
                "  '<rect x=\"19\" y=\"10\" width=\"10\" height=\"28\" rx=\"5\" fill=\"#1565C0\"/>'+\n" +       // bodi motor
                "  '<rect x=\"11\" y=\"12\" width=\"26\" height=\"3.5\" rx=\"1.75\" fill=\"#37474F\"/>'+\n" +   // stang
                "  '<line x1=\"17\" y1=\"24\" x2=\"13\" y2=\"15\" stroke=\"#2E7D32\" stroke-width=\"3.5\" stroke-linecap=\"round\"/>'+\n" +
                "  '<line x1=\"31\" y1=\"24\" x2=\"35\" y2=\"15\" stroke=\"#2E7D32\" stroke-width=\"3.5\" stroke-linecap=\"round\"/>'+\n" +
                "  '<ellipse cx=\"24\" cy=\"26\" rx=\"9.5\" ry=\"7\" fill=\"#43A047\"/>'+\n" +                  // bahu/badan
                "  '<circle cx=\"24\" cy=\"24\" r=\"5.5\" fill=\"#F44336\"/>'+\n" +                             // helm
                "  '<rect x=\"21\" y=\"19.5\" width=\"6\" height=\"2\" rx=\"1\" fill=\"#ffffff\" opacity=\"0.9\"/>'+\n" + // visor (penanda arah depan)
                "  '</svg>';\n" +
                "var meIcon=L.divIcon({className:'meicon',html:'<div id=\"merot\">'+RIDER+'</div>',iconSize:[44,44],iconAnchor:[22,22]});\n" +
                "var meMarker=null;\n" +
                // Dipanggil dari Java (evaluateJavascript) tiap update GPS.
                // bearing<0 = tidak ada data arah → pertahankan rotasi terakhir.
                "function updateMe(lat,lng,bearing){\n" +
                "  if(!meMarker){\n" +
                "    meMarker=L.marker([lat,lng],{icon:meIcon,zIndexOffset:1000,interactive:false}).addTo(map);\n" +
                "  }else{meMarker.setLatLng([lat,lng]);}\n" +
                "  if(bearing>=0){\n" +
                "    var el=document.getElementById('merot');\n" +
                "    if(el){el.style.transform='rotate('+bearing+'deg)';}\n" +
                "  }\n" +
                "}\n" +
                "function goMe(){if(meMarker){map.setView(meMarker.getLatLng(),16);}}\n" +
                "</script></body></html>";
    }
}
