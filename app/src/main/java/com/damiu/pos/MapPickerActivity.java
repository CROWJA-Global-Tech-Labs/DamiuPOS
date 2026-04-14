package com.damiu.pos;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MapPickerActivity extends AppCompatActivity {

    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";

    private double selectedLat;
    private double selectedLng;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_picker);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        double initLat = getIntent().getDoubleExtra(EXTRA_LATITUDE, -6.2);
        double initLng = getIntent().getDoubleExtra(EXTRA_LONGITUDE, 106.8);
        selectedLat = initLat;
        selectedLng = initLng;

        WebView webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

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
                "  body{margin:0;padding:0;}\n" +
                "  #map{width:100%;height:100vh;}\n" +
                "  #crosshair{position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);z-index:1000;font-size:32px;pointer-events:none;text-shadow:0 0 4px white;}\n" +
                "  #coords{position:fixed;bottom:10px;left:10px;right:10px;background:rgba(255,255,255,0.9);padding:8px 12px;border-radius:8px;z-index:1000;font-size:14px;text-align:center;font-family:sans-serif;}\n" +
                "</style>\n" +
                "</head><body>\n" +
                "<div id='map'></div>\n" +
                "<div id='crosshair'>\uD83D\uDCCD</div>\n" +
                "<div id='coords'>Geser peta untuk memilih lokasi</div>\n" +
                "<script>\n" +
                "var map = L.map('map').setView([" + lat + "," + lng + "], 15);\n" +
                "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{\n" +
                "  attribution:'OSM',maxZoom:19\n" +
                "}).addTo(map);\n" +
                "function updateCoords(){\n" +
                "  var c=map.getCenter();\n" +
                "  document.getElementById('coords').innerHTML=\n" +
                "    'Lat: '+c.lat.toFixed(6)+' | Lng: '+c.lng.toFixed(6);\n" +
                "  Android.onLocationSelected(c.lat,c.lng);\n" +
                "}\n" +
                "map.on('moveend',updateCoords);\n" +
                "updateCoords();\n" +
                "</script></body></html>";
    }
}
