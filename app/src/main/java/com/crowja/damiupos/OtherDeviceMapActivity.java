package com.crowja.damiupos;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 🗺 Peta REAL-TIME satu perangkat dari layar "Antrian: perangkat": posisi kendaraannya (disegarkan tiap 5 detik), pin
 * bernomor untuk tiap order antreannya (nomor = urutan di HP perangkat itu), dan GARIS BERANIMASI dari posisi perangkat
 * ke order yang sedang DITUJU (order yang ditandai "sekarang menuju", bila tak ada = urutan pertama).
 */
public class OtherDeviceMapActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_UUID = "device_uuid";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private static final long LIVE_MS = 5000L;

    private WebView web;
    private ProgressBar progress;
    private TextView tvEmpty;
    private SyncSettings cfg;
    private String deviceUuid;
    private String deviceName;
    private boolean pageReady;
    private boolean loading;
    private String pendingJson;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, LIVE_MS);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_map);
        deviceUuid = getIntent().getStringExtra(EXTRA_DEVICE_UUID);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle("🗺 Peta: " + (deviceName != null && !deviceName.isEmpty() ? deviceName : "Perangkat"));
        web = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tvEmpty);
        cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (deviceUuid == null || deviceUuid.isEmpty() || !cfg.isEnrolled()) {
            progress.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Perangkat belum terhubung ke server.");
            return;
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(MapTiles.userAgent());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                progress.setVisibility(View.GONE);
                if (pendingJson != null) push(pendingJson);
            }
        });
        web.loadDataWithBaseURL("https://unpkg.com", html(), "text/html", "UTF-8", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (deviceUuid != null && !deviceUuid.isEmpty() && cfg.isEnrolled()) handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    /** Ambil antrean perangkat + posisinya dari server lalu dorong ke peta (gagal = peta tetap seperti terakhir). */
    private void refresh() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            JSONObject out = null;
            try {
                SyncApi api = new SyncApi(cfg);
                JSONArray queue = api.deviceQueue(deviceUuid).optJSONArray("queue");
                JSONObject pos = null;
                JSONArray positions = api.deliveryMap().optJSONArray("positions");
                for (int i = 0; positions != null && i < positions.length(); i++) {
                    JSONObject p = positions.optJSONObject(i);
                    if (p != null && deviceUuid.equals(p.optString("device_uuid"))) {
                        pos = p;
                        break;
                    }
                }
                JSONArray orders = new JSONArray();
                for (int i = 0; queue != null && i < queue.length(); i++) {
                    JSONObject q = queue.optJSONObject(i);
                    if (q == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("n", i + 1);
                    o.put("name", q.optString("name", "Pelanggan"));
                    o.put("lat", q.isNull("latitude") ? JSONObject.NULL : q.optDouble("latitude"));
                    o.put("lng", q.isNull("longitude") ? JSONObject.NULL : q.optDouble("longitude"));
                    o.put("galon", q.optInt("galon", 0));
                    o.put("pickup", q.optBoolean("pickup_only", false));
                    o.put("prio", q.optBoolean("order_priority", false) || q.optBoolean("is_priority", false));
                    o.put("head", q.optBoolean("heading_here", false));
                    o.put("run", q.optBoolean("in_progress", false));
                    orders.put(o);
                }
                out = new JSONObject();
                out.put("name", deviceName == null ? "" : deviceName);
                out.put("pos", pos == null ? JSONObject.NULL : new JSONObject()
                        .put("lat", pos.optDouble("lat")).put("lng", pos.optDouble("lng"))
                        .put("at", pos.optString("at", "")).put("vehicle", pos.optString("vehicle", pos.optString("icon", "🚚"))));
                out.put("orders", orders);
            } catch (Exception ignored) {
            }
            final JSONObject fOut = out;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed() || fOut == null) return;
                pendingJson = fOut.toString();
                if (pageReady) push(pendingJson);
            });
        }).start();
    }

    private void push(String json) {
        web.evaluateJavascript("window.update(" + json + ");", null);
    }

    private static String html() {
        return "<!DOCTYPE html>\n<html><head>\n"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n"
                + "<style>\n"
                + "  body{margin:0;padding:0;font-family:sans-serif;}\n"
                + "  #map{width:100%;height:100vh;}\n"
                + MapTiles.BRIGHT_TILE_CSS + "\n"
                + "  .pin{width:30px;height:30px;border-radius:50%;background:#1565c0;color:#fff;font-weight:bold;font-size:14px;"
                + "display:flex;align-items:center;justify-content:center;border:3px solid #fff;box-shadow:0 1px 5px rgba(0,0,0,.5);}\n"
                + "  .pin.prio{background:#e65100;}\n"
                + "  .pin.pickup{background:#6a1b9a;}\n"
                + "  .pin.target{background:#c62828;width:36px;height:36px;font-size:16px;animation:pulse 1.2s ease-out infinite;}\n"
                + "  @keyframes pulse{0%{box-shadow:0 0 0 0 rgba(198,40,40,.7);}100%{box-shadow:0 0 0 18px rgba(198,40,40,0);}}\n"
                + "  .veh{font-size:30px;line-height:30px;text-align:center;filter:drop-shadow(0 1px 3px rgba(0,0,0,.6));}\n"
                + "  .vname{background:#0d47a1;color:#fff;font-size:11px;font-weight:bold;padding:1px 6px;border-radius:8px;white-space:nowrap;"
                + "margin-top:-2px;text-align:center;}\n"
                + "  .flow{stroke-dasharray:10 14;animation:dash .7s linear infinite;}\n"
                + "  @keyframes dash{to{stroke-dashoffset:-24;}}\n"
                + "  #info{position:absolute;top:8px;left:8px;right:8px;z-index:1000;background:rgba(255,255,255,.95);color:#212121;"
                + "border-radius:8px;padding:6px 10px;font-size:13px;box-shadow:0 1px 4px rgba(0,0,0,.3);}\n"
                + "</style></head><body>\n"
                + "<div id='map'></div><div id='info'>Memuat…</div>\n"
                + "<script>\n"
                + "var map=L.map('map',{zoomControl:true}).setView([-7.55,110.83],13);\n"
                + "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{subdomains:'" + MapTiles.SUBDOMAINS + "',maxZoom:19,attribution:'"
                + MapTiles.ATTRIBUTION + "'}).addTo(map);\n"
                + "var pins=L.layerGroup().addTo(map), veh=null, line=null, fitted=false;\n"
                + "function km(a,b,c,d){var R=6371,p=Math.PI/180,x=(c-a)*p,y=(d-b)*p;"
                + "var h=Math.sin(x/2)*Math.sin(x/2)+Math.cos(a*p)*Math.cos(c*p)*Math.sin(y/2)*Math.sin(y/2);return 2*R*Math.asin(Math.sqrt(h));}\n"
                + "function esc(s){return String(s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}\n"
                + "window.update=function(d){\n"
                + "  pins.clearLayers();\n"
                + "  var orders=d.orders||[], withGeo=orders.filter(function(o){return o.lat!=null&&o.lng!=null&&!(o.lat==0&&o.lng==0);});\n"
                + "  var target=null; for(var i=0;i<withGeo.length;i++){ if(withGeo[i].head){target=withGeo[i];break;} }\n"
                + "  if(!target && withGeo.length) target=withGeo[0];\n"
                + "  var pts=[];\n"
                + "  withGeo.forEach(function(o){\n"
                + "    var cls='pin'+(o.prio?' prio':'')+(o.pickup?' pickup':'')+(target&&o.n===target.n?' target':'');\n"
                + "    var ic=L.divIcon({className:'',html:\"<div class='\"+cls+\"'>\"+o.n+\"</div>\",iconSize:[36,36],iconAnchor:[18,18]});\n"
                + "    L.marker([o.lat,o.lng],{icon:ic,zIndexOffset:(target&&o.n===target.n)?1000:0}).addTo(pins)\n"
                + "     .bindPopup('<b>#'+o.n+' '+esc(o.name)+'</b><br>'+o.galon+' galon'+(o.pickup?' · ↩ ambil':'')+(o.run?' · 🚚 sedang diantar':'')+(o.head?'<br>🧭 sedang dituju':''));\n"
                + "    pts.push([o.lat,o.lng]);\n"
                + "  });\n"
                + "  var info='';\n"
                + "  if(d.pos&&d.pos.lat&&d.pos.lng){\n"
                + "    var ll=[d.pos.lat,d.pos.lng];\n"
                + "    var vi=L.divIcon({className:'',html:\"<div class='veh'>\"+esc(d.pos.vehicle||'🚚')+\"</div><div class='vname'>\"+esc(d.name||'')+\"</div>\","
                + "iconSize:[80,48],iconAnchor:[40,20]});\n"
                + "    if(!veh){veh=L.marker(ll,{icon:vi,zIndexOffset:2000}).addTo(map);} else {veh.setLatLng(ll);veh.setIcon(vi);}\n"
                + "    pts.push(ll);\n"
                + "    if(line){map.removeLayer(line);line=null;}\n"
                + "    if(target){ line=L.polyline([ll,[target.lat,target.lng]],{color:'#c62828',weight:5,opacity:.9,className:'flow'}).addTo(map);\n"
                + "      info='🚚 <b>'+esc(d.name||'')+'</b> → <b>#'+target.n+' '+esc(target.name)+'</b> · '+km(ll[0],ll[1],target.lat,target.lng).toFixed(1).replace('.',',')+' km (garis lurus)'; }\n"
                + "    else info='🚚 <b>'+esc(d.name||'')+'</b> · tidak ada order berkoordinat';\n"
                + "    if(d.pos.at) info+='<br><small>posisi '+esc(d.pos.at)+'</small>';\n"
                + "  } else {\n"
                + "    if(veh){map.removeLayer(veh);veh=null;} if(line){map.removeLayer(line);line=null;}\n"
                + "    info='Posisi perangkat belum diketahui (GPS belum mengirim).';\n"
                + "  }\n"
                + "  var noGeo=orders.length-withGeo.length; if(noGeo>0) info+='<br><small>'+noGeo+' order tanpa koordinat tidak tampil di peta</small>';\n"
                + "  document.getElementById('info').innerHTML=info+'<br><small>🔄 live · '+orders.length+' order</small>';\n"
                + "  if(!fitted&&pts.length){ map.fitBounds(L.latLngBounds(pts).pad(0.2)); fitted=true; }\n"
                + "};\n"
                + "</script></body></html>";
    }
}
