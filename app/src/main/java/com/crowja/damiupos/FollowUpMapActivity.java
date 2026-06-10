package com.crowja.damiupos;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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
 * <p>Dipakai dari {@link FollowUpActivity} via tombol "Peta" → pilihan
 * "Lihat Semua di Peta".
 */
public class FollowUpMapActivity extends AppCompatActivity {

    private WebView webView;
    private final List<Customer> mapCustomers = new ArrayList<>();

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
        CustomerDao customerDao = new CustomerDao(dbHelper);
        SettingsDao settingsDao = new SettingsDao(dbHelper);
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

        toolbar.setTitle("Peta Follow Up (" + mapCustomers.size() + ")");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new MapBridge(), "Android");
        webView.setWebViewClient(new WebViewClient());

        String html = buildMapHtml();
        webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null);
    }

    /** JS bridge: dipanggil dari popup "Navigasi" untuk buka Google Maps. */
    private class MapBridge {
        @JavascriptInterface
        public void navigate(double lat, double lng, String name) {
            runOnUiThread(() -> openInGoogleMaps(lat, lng, name));
        }
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
                "  .pname{font-size:14px;font-weight:bold;color:#222;}\n" +
                "  .pphone{font-size:12px;color:#666;margin-top:2px;}\n" +
                "</style>\n" +
                "</head><body>\n" +
                "<div id='map'></div>\n" +
                "<script>\n" +
                "var pts = " + pointsJson + ";\n" +
                "var map = L.map('map',{zoomControl:true}).setView([" + centerLat + "," + centerLng + "], 14);\n" +
                "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'OSM',maxZoom:19}).addTo(map);\n" +
                "var bounds=[];\n" +
                "pts.forEach(function(p){\n" +
                "  var m=L.marker([p.lat,p.lng]).addTo(map);\n" +
                "  bounds.push([p.lat,p.lng]);\n" +
                "  var phoneHtml = p.phone ? '<div class=\"pphone\">'+p.phone+'</div>' : '';\n" +
                "  var safeName=(p.name||'').replace(/'/g,\"\\\\'\");\n" +
                "  var html='<div class=\"pname\">'+p.name+'</div>'+phoneHtml+\n" +
                "    '<a class=\"navbtn\" href=\"#\" onclick=\"nav('+p.lat+','+p.lng+',\\''+safeName+'\\');return false;\">Navigasi (Google Maps)</a>';\n" +
                "  m.bindPopup(html);\n" +
                "});\n" +
                "if(bounds.length>1){map.fitBounds(bounds,{padding:[40,40]});}\n" +
                "else if(bounds.length===1){map.setView(bounds[0],16);}\n" +
                "function nav(lat,lng,name){\n" +
                "  if(window.Android&&Android.navigate){Android.navigate(lat,lng,name);}\n" +
                "}\n" +
                "</script></body></html>";
    }
}
