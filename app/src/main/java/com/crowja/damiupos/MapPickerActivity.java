package com.crowja.damiupos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MapPickerActivity extends AppCompatActivity {

    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";

    private static final int REQUEST_PERMISSION_LOCATION = 401;

    // Default fallback: Boyolali area (matches depot location)
    private static final double FALLBACK_LAT = -7.5364;
    private static final double FALLBACK_LNG = 110.5951;

    private double selectedLat;
    private double selectedLng;
    private WebView webView;
    private boolean hasExplicitInitial;
    private LocationManager locationManager;
    private LocationListener locationListener;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_picker);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        hasExplicitInitial = getIntent().hasExtra(EXTRA_LATITUDE)
                && getIntent().hasExtra(EXTRA_LONGITUDE);
        double initLat = getIntent().getDoubleExtra(EXTRA_LATITUDE, FALLBACK_LAT);
        double initLng = getIntent().getDoubleExtra(EXTRA_LONGITUDE, FALLBACK_LNG);
        selectedLat = initLat;
        selectedLng = initLng;

        webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // UA pengenal aplikasi supaya penyedia tile tidak memblokir request.
        settings.setUserAgentString(MapTiles.userAgent());

        webView.addJavascriptInterface(new MapBridge(), "Android");
        webView.setWebViewClient(new WebViewClient());

        String html = buildMapHtml(initLat, initLng);
        webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null);

        findViewById(R.id.btnPilih).setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_LATITUDE, selectedLat);
            result.putExtra(EXTRA_LONGITUDE, selectedLng);
            setResult(RESULT_OK, result);
            finish();
        });

        findViewById(R.id.btnMyLocation).setOnClickListener(v -> requestCurrentLocation(true));

        // If no explicit initial coord, auto-center on current GPS
        if (!hasExplicitInitial) {
            requestCurrentLocation(false);
        }
    }

    private void requestCurrentLocation(boolean showFeedback) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_PERMISSION_LOCATION);
            return;
        }

        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) {
            if (showFeedback) Toast.makeText(this, "Layanan lokasi tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }

        // Try last known first for instant centering
        Location last = null;
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null && net != null) last = gps.getTime() > net.getTime() ? gps : net;
            else last = gps != null ? gps : net;
        } catch (SecurityException ignored) {}

        if (last != null) {
            panTo(last.getLatitude(), last.getLongitude());
        } else if (showFeedback) {
            Toast.makeText(this, "Mencari lokasi...", Toast.LENGTH_SHORT).show();
        }

        // Request a single fresh update
        if (locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) {
                panTo(location.getLatitude(), location.getLongitude());
                try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
            }
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, getMainLooper());
            }
        } catch (SecurityException ignored) {}
    }

    private void panTo(double lat, double lng) {
        selectedLat = lat;
        selectedLng = lng;
        if (webView != null) {
            webView.evaluateJavascript(
                    "if(typeof map!=='undefined'){map.setView([" + lat + "," + lng + "],16);}",
                    null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) { granted = true; break; }
            }
            if (granted) {
                requestCurrentLocation(true);
            } else {
                Toast.makeText(this, "Izin lokasi ditolak", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
    }

    private class MapBridge {
        @JavascriptInterface
        public void onLocationSelected(double lat, double lng) {
            selectedLat = lat;
            selectedLng = lng;
        }
    }

    private String buildMapHtml(double lat, double lng) {
        return "<!DOCTYPE html>\n" +
                "<html><head>\n" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n" +
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n" +
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n" +
                "<style>\n" +
                "  body{margin:0;padding:0;font-family:sans-serif;}\n" +
                "  #map{width:100%;height:100vh;}\n" +
                "  #searchBar{position:fixed;top:8px;left:8px;right:8px;z-index:1001;display:flex;gap:6px;}\n" +
                "  #searchInput{flex:1;padding:10px 12px;border:none;border-radius:24px;box-shadow:0 2px 6px rgba(0,0,0,0.3);font-size:14px;outline:none;}\n" +
                "  #searchBtn{padding:0 14px;border:none;border-radius:24px;background:#1565C0;color:white;font-size:14px;box-shadow:0 2px 6px rgba(0,0,0,0.3);cursor:pointer;}\n" +
                "  #results{position:fixed;top:52px;left:8px;right:8px;z-index:1001;background:white;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,0.3);max-height:50vh;overflow-y:auto;display:none;}\n" +
                "  .result-item{padding:10px 12px;border-bottom:1px solid #eee;cursor:pointer;font-size:13px;}\n" +
                "  .result-item:last-child{border-bottom:none;}\n" +
                "  .result-item:active{background:#f0f0f0;}\n" +
                "  #searchStatus{padding:10px 12px;color:#888;font-size:13px;}\n" +
                "  #crosshair{position:fixed;top:50%;left:50%;transform:translate(-50%,-100%);z-index:1000;font-size:36px;pointer-events:none;text-shadow:0 0 4px white;}\n" +
                "  #coords{position:fixed;bottom:10px;left:10px;right:10px;background:rgba(255,255,255,0.9);padding:8px 12px;border-radius:8px;z-index:1000;font-size:13px;text-align:center;}\n" +
                "</style>\n" +
                "</head><body>\n" +
                "<div id='searchBar'>\n" +
                "  <input id='searchInput' type='text' placeholder='Cari alamat atau tempat...' autocomplete='off'/>\n" +
                "  <button id='searchBtn'>Cari</button>\n" +
                "</div>\n" +
                "<div id='results'></div>\n" +
                "<div id='map'></div>\n" +
                "<div id='crosshair'>\uD83D\uDCCD</div>\n" +
                "<div id='coords'>Geser peta untuk memilih lokasi</div>\n" +
                "<script>\n" +
                "var map = L.map('map',{zoomControl:true}).setView([" + lat + "," + lng + "], 15);\n" +
                "L.control.zoom({position:'bottomright'}).remove();\n" +
                "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{\n" +
                "  subdomains:'" + MapTiles.SUBDOMAINS + "',attribution:'" + MapTiles.ATTRIBUTION + "',maxZoom:19\n" +
                "}).addTo(map);\n" +
                "function updateCoords(){\n" +
                "  var c=map.getCenter();\n" +
                "  document.getElementById('coords').innerHTML=\n" +
                "    'Lat: '+c.lat.toFixed(6)+' | Lng: '+c.lng.toFixed(6);\n" +
                "  if(window.Android&&Android.onLocationSelected) Android.onLocationSelected(c.lat,c.lng);\n" +
                "}\n" +
                "map.on('moveend',updateCoords);\n" +
                "updateCoords();\n" +
                "\n" +
                "var results=document.getElementById('results');\n" +
                "var input=document.getElementById('searchInput');\n" +
                "var btn=document.getElementById('searchBtn');\n" +
                "function hideResults(){results.style.display='none';results.innerHTML='';}\n" +
                "function showStatus(msg){results.style.display='block';results.innerHTML='<div id=\"searchStatus\">'+msg+'</div>';}\n" +
                "function doSearch(){\n" +
                "  var q=input.value.trim();\n" +
                "  if(!q){hideResults();return;}\n" +
                "  showStatus('Mencari...');\n" +
                "  var url='https://nominatim.openstreetmap.org/search?format=json&limit=8&accept-language=id&q='+encodeURIComponent(q);\n" +
                "  fetch(url,{headers:{'Accept':'application/json'}})\n" +
                "    .then(function(r){return r.json();})\n" +
                "    .then(function(data){\n" +
                "      if(!data||data.length===0){showStatus('Tidak ditemukan');return;}\n" +
                "      results.innerHTML='';\n" +
                "      data.forEach(function(item){\n" +
                "        var d=document.createElement('div');\n" +
                "        d.className='result-item';\n" +
                "        d.textContent=item.display_name;\n" +
                "        d.onclick=function(){\n" +
                "          map.setView([parseFloat(item.lat),parseFloat(item.lon)],17);\n" +
                "          hideResults();\n" +
                "          input.blur();\n" +
                "        };\n" +
                "        results.appendChild(d);\n" +
                "      });\n" +
                "      results.style.display='block';\n" +
                "    })\n" +
                "    .catch(function(e){showStatus('Gagal: '+e.message);});\n" +
                "}\n" +
                "btn.onclick=doSearch;\n" +
                "input.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();doSearch();}});\n" +
                "map.on('click',hideResults);\n" +
                "map.on('dragstart',hideResults);\n" +
                "</script></body></html>";
    }
}
