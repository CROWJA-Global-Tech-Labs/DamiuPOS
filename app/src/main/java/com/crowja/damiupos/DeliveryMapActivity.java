package com.crowja.damiupos;

import com.crowja.damiupos.map.LiveDeviceOverlay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Peta Antrian Delivery" — persebaran SEMUA order aktif se-cabang (pin per order, warna per
 * perangkat penanggung jawab, cermin "Peta Delivery" web) + posisi terakhir tiap perangkat +
 * posisi GPS perangkat ini sendiri. Data diambil on-demand dari server (order lintas-perangkat
 * device-isolated di sinkron lokal — sama seperti "Lihat Antrian Perangkat Lain").
 *
 * <p>Fitur:
 * <ul>
 *   <li>Ceklis perangkat mana yang ditampilkan (default semua tercentang) — legenda dobel fungsi
 *       sebagai filter, sama seperti "Persebaran Pelanggan".</li>
 *   <li>Pin order MILIK perangkat ini berkedip, supaya kurir langsung kenali antriannya sendiri
 *       di tengah pin perangkat lain.</li>
 *   <li>Klik pin order milik PERANGKAT LAIN → tawaran Ambil Alih (dua-ketukan, cermin "Lihat
 *       Antrian Perangkat Lain").</li>
 *   <li>Posisi Saya (GPS live) + pin posisi terakhir tiap perangkat lain.</li>
 * </ul>
 */
public class DeliveryMapActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_LOCATION = 402;

    private WebView webView;
    private ProgressBar progress;
    private TextView tvEmpty;
    private SyncSettings cfg;
    private String myDeviceUuid;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean pageReady = false;
    private Location pendingLocation;

    @SuppressLint("SetJavaScriptEnabled")
    /** Pin posisi LIVE perangkat lain — dipasang di semua peta aplikasi. */
    private LiveDeviceOverlay liveDev;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_map);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tvEmpty);

        cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        myDeviceUuid = cfg.getDeviceUuid();

        if (!cfg.isEnrolled()) {
            progress.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Perangkat belum terhubung ke server.");
            return;
        }

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
                    liveDev = new LiveDeviceOverlay(DeliveryMapActivity.this, webView);
                }
                liveDev.start();
                if (pendingLocation != null) {
                    pushLocationToMap(pendingLocation);
                    pendingLocation = null;
                }
            }
        });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = new SyncApi(cfg).deliveryMap();
            } catch (Exception e) {
                err = "Gagal memuat peta — periksa koneksi internet.";
            }
            final JSONObject fr = r;
            final String ferr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (fr == null) {
                    Toast.makeText(this, ferr, Toast.LENGTH_LONG).show();
                    tvEmpty.setVisibility(View.VISIBLE);
                    return;
                }
                render(fr);
            });
        }).start();
    }

    private void render(JSONObject data) {
        JSONArray queue = data.optJSONArray("queue");
        if (queue == null || queue.length() == 0) {
            webView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        webView.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        getSupportActionBar();
        setTitle("Peta Antrian Delivery (" + queue.length() + ")");
        webView.loadDataWithBaseURL("https://unpkg.com", buildMapHtml(data), "text/html", "UTF-8", null);
        ensureLocationPermissionThenTrack();
    }

    // ---------------------------------------------------------------- lokasi (GPS) — cermin CustomerMapActivity

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
        if (requestCode == REQUEST_PERMISSION_LOCATION
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (locationManager == null || locationListener != null) return;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) { pushLocationToMap(location); }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, locationListener);
            }
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) pushLocationToMap(last);
        } catch (Exception ignored) {}
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
            locationListener = null;
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
        if (loc == null || webView == null) return;
        if (!pageReady) { pendingLocation = loc; return; }
        evalJs("updateMe(" + loc.getLatitude() + "," + loc.getLongitude() + ");");
    }

    private void evalJs(String js) {
        if (webView != null) webView.evaluateJavascript(js, null);
    }

    private static String formatRupiah(double v) {
        return String.format(java.util.Locale.US, "%,d", (long) v).replace(',', '.');
    }

    // ---------------------------------------------------------------- bridge (Ambil Alih dari pin)

    private class MapBridge {
        @JavascriptInterface
        public void claim(String trxUuid, String expectedDeviceUuid, String custName,
                           String items, double total) {
            runOnUiThread(() -> confirmClaim(trxUuid, expectedDeviceUuid, custName, items, total));
        }
    }

    private void confirmClaim(String trxUuid, String expectedDeviceUuid, String custName,
                               String items, double total) {
        StringBuilder msg = new StringBuilder();
        msg.append("Order \"").append(custName).append("\" akan dipindahkan ke antrian perangkat ini.\n\n");
        if (items != null && !items.isEmpty()) {
            msg.append("Penjualan: ").append(items).append('\n');
        }
        msg.append("Total: Rp ").append(formatRupiah(total)).append("\n\n");
        msg.append("Ketuk \"Ambil Alih\" dua kali untuk memastikan.");

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Ambil Alih Pengiriman?")
                .setCancelable(false)
                .setMessage(msg.toString())
                .setPositiveButton("Ambil Alih", null)
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final android.widget.Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final int[] clicks = {0};
            pos.setOnClickListener(v -> {
                if (++clicks[0] < 2) {
                    pos.setText("Ketuk sekali lagi");
                    return;
                }
                pos.setEnabled(false);
                pos.setText("Memindahkan…");
                dialog.setCancelable(false);
                android.widget.Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (neg != null) neg.setEnabled(false);
                doClaim(dialog, pos, neg, trxUuid, expectedDeviceUuid);
            });
        });
        dialog.show();
    }

    private void doClaim(AlertDialog dialog, android.widget.Button pos, android.widget.Button neg,
                          String trxUuid, String expectedDeviceUuid) {
        new Thread(() -> {
            String okMsg = null, errMsg = null;
            try {
                JSONObject body = new JSONObject();
                body.put("transaction_uuid", trxUuid);
                body.put("expected_device_uuid", expectedDeviceUuid != null ? expectedDeviceUuid : "");
                JSONObject r = new SyncApi(cfg).claimDelivery(body);
                okMsg = r.optString("message", "Order diambil alih ke perangkat ini.");
            } catch (SyncApi.SyncException se) {
                try { errMsg = new JSONObject(se.body).optString("message", null); } catch (Exception ignored) {}
                if (errMsg == null) errMsg = "Gagal mengambil alih (kode " + se.code + ").";
            } catch (Exception e) {
                errMsg = "Gagal mengambil alih — periksa koneksi internet.";
            }
            final String fOk = okMsg, fErr = errMsg;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fOk != null) {
                    Toast.makeText(this, fOk, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    load();
                } else {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Ambil Alih");
                    if (neg != null) neg.setEnabled(true);
                    dialog.setCancelable(true);
                }
            });
        }).start();
    }

    // ---------------------------------------------------------------- HTML

    /**
     * Daftarkan/lengkapi satu baris legenda. Baris yang SUDAH ada tidak ditimpa namanya, tapi ikon &
     * warnanya DILENGKAPI bila sumber berikutnya membawanya — roster tak punya ikon/warna, sedangkan
     * baris antrian & posisi live punya, jadi identitas perangkat tetap sama dengan dashboard.
     */
    private static void putLegend(java.util.LinkedHashMap<String, JSONObject> map, String uuid,
                                  String name, String icon, String color) {
        if (uuid == null || uuid.isEmpty() || "null".equals(uuid)) return;
        try {
            JSONObject o = map.get(uuid);
            if (o == null) {
                o = new JSONObject();
                o.put("uuid", uuid);
                o.put("name", name == null || name.isEmpty() ? "Perangkat" : name);
                o.put("icon", "🚚");
                o.put("color", "#475569");
                map.put(uuid, o);
            }
            if (icon != null && !icon.isEmpty() && !"null".equals(icon)) o.put("icon", icon);
            if (color != null && !color.isEmpty() && !"null".equals(color)) o.put("color", color);
        } catch (Exception ignored) {}
    }

    private String buildMapHtml(JSONObject data) {
        JSONArray queue = data.optJSONArray("queue");
        JSONArray positions = data.optJSONArray("positions");
        if (queue == null) queue = new JSONArray();
        if (positions == null) positions = new JSONArray();

        // LEGENDA/ceklis perangkat — GABUNGAN tiga sumber, bukan hanya roster:
        //   1. roster "devices" (perangkat delivery) — tetap tampil walau antriannya kosong;
        //   2. perangkat yang muncul di baris ANTRIAN;
        //   3. perangkat yang muncul di POSISI LIVE.
        //
        // BUG yang diperbaiki: dulu legenda HANYA dari roster, sedangkan pin posisi digambar
        // LiveDeviceOverlay untuk SETIAP perangkat yang mengirim GPS. Perangkat yang tak masuk
        // roster delivery (mis. "PAKADI") karena itu tampil sebagai pin di peta TANPA baris legenda —
        // tak bisa dikenali, tak bisa disembunyikan, dan kini tak bisa dilacak. Aturannya sekarang:
        // apa pun yang bisa MUNCUL di peta WAJIB punya barisnya di legenda.
        java.util.LinkedHashMap<String, JSONObject> legendMap = new java.util.LinkedHashMap<>();
        JSONArray devices = data.optJSONArray("devices");
        if (devices != null) {
            for (int i = 0; i < devices.length(); i++) {
                JSONObject dv = devices.optJSONObject(i);
                if (dv == null) continue;
                putLegend(legendMap, dv.optString("uuid"), dv.optString("name", "Perangkat"), null, null);
            }
        }
        for (int j = 0; j < queue.length(); j++) {
            JSONObject q = queue.optJSONObject(j);
            if (q == null) continue;
            putLegend(legendMap, q.optString("device_uuid"), q.optString("device_name", "Perangkat"),
                    q.optString("icon", null), q.optString("color", null));
        }
        for (int j = 0; j < positions.length(); j++) {
            JSONObject p = positions.optJSONObject(j);
            if (p == null) continue;
            // Posisi live memakai kunci 'vehicle' untuk ikon kendaraan (lihat LiveDeviceOverlay).
            putLegend(legendMap, p.optString("device_uuid"), p.optString("device_name", "Perangkat"),
                    p.optString("vehicle", p.optString("icon", null)), p.optString("color", null));
        }
        // Perangkat SENDIRI tak pernah digambar LiveDeviceOverlay, tapi ordernya ada di antrian —
        // barisnya tetap berguna untuk menyembunyikan/menampilkan order sendiri.
        JSONArray legend = new JSONArray();
        for (JSONObject o : legendMap.values()) legend.put(o);

        double sumLat = 0, sumLng = 0;
        int n = queue.length();

        for (int i = 0; i < n; i++) {
            JSONObject q = queue.optJSONObject(i);
            if (q == null) continue;
            sumLat += q.optDouble("lat", 0);
            sumLng += q.optDouble("lng", 0);
        }
        double centerLat = n > 0 ? sumLat / n : -7.55;
        double centerLng = n > 0 ? sumLng / n : 110.83;

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
                "  .leaflet-popup-content a.claimbtn{display:block;margin-top:8px;padding:9px 12px;background:#c62828;color:#fff !important;border-radius:6px;text-decoration:none;font-size:13px;font-weight:bold;text-align:center;}\n" +
                "  .pname{font-size:14px;font-weight:bold;color:#222;}\n" +
                "  .pmeta{font-size:12px;color:#666;margin-top:2px;}\n" +
                "  .pmeta.inprogress{color:#b45309;font-weight:bold;margin-top:6px;}\n" +
                "  .badge{display:inline-block;font-size:10px;font-weight:bold;padding:1px 6px;border-radius:8px;margin-left:4px;vertical-align:middle;background:#dcfce7;color:#166534;}\n" +
                "  .pin{position:relative;width:30px;height:38px;}\n" +
                "  .pin svg{position:absolute;top:0;left:0;}\n" +
                "  .pin .em{position:absolute;top:3px;left:0;width:30px;text-align:center;font-size:14px;line-height:20px;}\n" +
                "  .pin.mine{animation:pinblink 1s infinite;}\n" +
                "  @keyframes pinblink{0%,100%{opacity:1;transform:scale(1);}50%{opacity:.55;transform:scale(1.18);}}\n" +
                // "Pesanan Terbuka" (lelang) belum diklaim — glow indigo berdenyut (beda dari blink
                // scale "mine" supaya kedua status tak tertukar) + lencana 🎲 di sudut pin.
                "  .pin.opendispatch{animation:pinglow 1.2s ease-in-out infinite;}\n" +
                "  @keyframes pinglow{0%,100%{filter:drop-shadow(0 0 1px rgba(99,102,241,.9));}50%{filter:drop-shadow(0 0 7px rgba(99,102,241,.95));}}\n" +
                "  .odbadge{position:absolute;top:-3px;right:-3px;width:15px;height:15px;border-radius:8px;background:#6366F1;color:#fff;font-size:9px;line-height:15px;text-align:center;box-shadow:0 0 0 2px #fff;}\n" +
                "  .pmeta.opendispatch{color:#4338CA;font-weight:bold;margin-top:6px;}\n" +
                "  .devpin{position:relative;width:22px;height:22px;border-radius:11px;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.4);display:flex;align-items:center;justify-content:center;font-size:12px;}\n" +
                "  #btnme{position:fixed;right:14px;bottom:22px;width:48px;height:48px;border-radius:24px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center;font-size:24px;z-index:1000;cursor:pointer;}\n" +
                // LEGENDA diperbesar: sasaran sentuh sebelumnya ~20px (font 12px, padding 3px) —
                // di bawah ambang 48dp Android dan sering meleset saat HP dipakai sambil berkendara.
                "  #legend{position:fixed;left:8px;top:8px;max-width:82%;max-height:46%;overflow:auto;z-index:1000;}\n" +
                "  .lchip{display:inline-flex;align-items:center;gap:7px;background:#fff;border-radius:20px;padding:8px 8px 8px 12px;margin:3px;box-shadow:0 2px 6px rgba(0,0,0,.3);font-size:14px;font-weight:600;cursor:pointer;border:2px solid transparent;}\n" +
                "  .lchip:active{transform:scale(.96);}\n" +
                "  .lchip.off{opacity:.4;}\n" +
                "  .lchip.nogeo{border-color:#cbd5e1;}\n" +
                "  .ldot{width:13px;height:13px;border-radius:7px;display:inline-block;flex:0 0 auto;}\n" +
                // Tombol mata TERPISAH di dalam chip: ketuk nama = LACAK ke perangkat, ketuk mata =
                // sembunyikan/tampilkan. Dua aksi berbeda butuh dua sasaran sentuh berbeda — kalau
                // digabung jadi satu ketukan, mustahil melacak tanpa ikut menyembunyikan.
                "  .leye{display:inline-flex;align-items:center;justify-content:center;width:30px;height:30px;border-radius:15px;background:#f1f5f9;font-size:14px;flex:0 0 auto;}\n" +
                "  .lchip.off .leye{background:#e2e8f0;}\n" +
                MapTiles.BRIGHT_TILE_CSS + "\n" +
                "</style>\n</head><body>\n" +
                "<div id='map'></div>\n" +
                "<div id='legend'></div>\n" +
                "<div id='btnme' onclick='goMe()' title='Posisi Saya'>&#128205;</div>\n" +
                "<script>\n" +
                "var pts = " + queue + ";\n" +
                "var positions = " + positions + ";\n" +
                "var legend = " + legend + ";\n" +
                "var myUuid = " + JSONObject.quote(myDeviceUuid == null ? "" : myDeviceUuid) + ";\n" +
                "var map = L.map('map',{zoomControl:true}).setView([" + centerLat + "," + centerLng + "], 13);\n" +
                "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{subdomains:'" + MapTiles.SUBDOMAINS + "',maxZoom:19,attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);\n" +
                "var cluster = L.markerClusterGroup({maxClusterRadius:50,spiderfyOnMaxZoom:true});\n" +
                "map.addLayer(cluster);\n" +
                "var hiddenDevices = {};\n" +
                "function pinIcon(color,emoji,mine,openDispatch){\n" +
                "  var svg='<svg width=\"30\" height=\"38\" viewBox=\"0 0 30 38\" xmlns=\"http://www.w3.org/2000/svg\">'+\n" +
                "    '<path d=\"M15 0C6.7 0 0 6.7 0 15c0 10 15 23 15 23s15-13 15-23C30 6.7 23.3 0 15 0z\" fill=\"'+color+'\"/>'+\n" +
                "    '<circle cx=\"15\" cy=\"14\" r=\"10\" fill=\"#fff\"/></svg>';\n" +
                "  var badge=openDispatch?'<div class=\"odbadge\">🎲</div>':'';\n" +
                "  return L.divIcon({className:'',html:'<div class=\"pin'+(mine?' mine':'')+(openDispatch?' opendispatch':'')+'\">'+svg+'<div class=\"em\">'+emoji+'</div>'+badge+'</div>',iconSize:[30,38],iconAnchor:[15,38],popupAnchor:[0,-34]});\n" +
                "}\n" +
                // KEAMANAN: sama seperti CustomerMapActivity — popup dirakit sebagai string HTML dari
                // data pelanggan (nama/nomor bisa diisi orang luar via checkout online publik).
                "function escHtml(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){\n" +
                "  return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}\n" +
                "var markers=[];\n" +
                "var openDispatchPts=[];\n" +
                "pts.forEach(function(p){\n" +
                "  var m=L.marker([p.lat,p.lng],{icon:pinIcon(p.color,p.icon,p.mine,p.open_dispatch)});\n" +
                "  m._p=p;\n" +
                "  var rp = 'Rp '+Math.round(p.total||0).toLocaleString('id-ID');\n" +
                "  var meta = escHtml(p.device_name)+' • '+(+p.galon)+' galon • '+rp;\n" +
                "  var itemsHtml = p.items ? '<div class=\"pmeta\">'+escHtml(p.items)+'</div>' : '';\n" +
                "  var mineBadge = p.mine ? '<span class=\"badge\">Milik Saya</span>' : '';\n" +
                // "Pesanan Terbuka" (lelang) belum diklaim — catatan indigo di popup, cermin badge
                // "🎲 PESANAN TERBUKA" di kartu Antrian Delivery.
                "  var odNote = p.open_dispatch ? '<div class=\"pmeta opendispatch\">🎲 Pesanan Terbuka — staf mana pun boleh mengklaim</div>' : '';\n" +
                // Kurir PEMEGANG order sedang menjalankannya (▶ Jalankan ditekan di HP-nya) → jangan
                // tawarkan Ambil Alih sama sekali (dua kurir bisa berujung di jalan yang sama untuk
                // order yang sama); tunjukkan keterangan statusnya saja.
                "  var progressNote = (!p.mine && p.in_progress) ? '<div class=\"pmeta inprogress\">🚚 Sedang dalam pengiriman — tidak bisa diambil alih</div>' : '';\n" +
                // Pesanan lelang yang masih DIJEDA ikut dipetakan (server mengirimnya) supaya tak
                // hilang dari pandangan, tapi belum boleh diklaim sampai jadwalnya tiba — server pun
                // menolak klaim atas order non-PENDING, jadi tombolnya sengaja tak ditampilkan.
                "  var pausedNote = p.tertunda_until ? '<div class=\"pmeta opendispatch\">⏸ Dijeda sampai '+escHtml(p.tertunda_until)+' — belum bisa diambil</div>' : '';\n" +
                "  var claimBtn = (p.mine || p.in_progress || p.tertunda_until) ? '' : '<a class=\"claimbtn\" href=\"#\" onclick=\"claimById(\\''+p.uuid+'\\');return false;\">📥 Ambil Alih</a>';\n" +
                "  var html='<div class=\"pname\">'+escHtml(p.name)+mineBadge+'</div><div class=\"pmeta\">'+meta+'</div>'+itemsHtml+odNote+pausedNote+progressNote+claimBtn;\n" +
                "  m.bindPopup(html);\n" +
                "  markers.push(m);\n" +
                "  if(p.open_dispatch){ openDispatchPts.push(p); }\n" +
                "});\n" +
                "function visible(p){ return !hiddenDevices[p.device_uuid||'__none__']; }\n" +
                "function applyFilter(){\n" +
                "  cluster.clearLayers();\n" +
                "  var b=[];\n" +
                "  markers.forEach(function(m){ if(visible(m._p)){cluster.addLayer(m); b.push([m._p.lat,m._p.lng]);} });\n" +
                "  if(b.length>1){map.fitBounds(b,{padding:[40,40],maxZoom:16});}\n" +
                "  else if(b.length===1){map.setView(b[0],16);}\n" +
                "  redrawOpenDispatchLines();\n" +
                "}\n" +
                "function claimById(uuid){for(var i=0;i<pts.length;i++){if(pts[i].uuid===uuid){\n" +
                "  if(window.Android&&Android.claim){Android.claim(uuid, pts[i].device_uuid||'', pts[i].name,\n" +
                "    pts[i].items||'', +(pts[i].total||0));}\n" +
                "  return;}}}\n" +
                // LACAK perangkat dari legenda. Prioritas posisi: (1) posisi LIVE terkini dari
                // LiveDeviceOverlay (paling benar — diperbarui tiap 25 detik), (2) posisi awal yang
                // ikut dikirim bersama halaman, (3) titik-titik ANTRIAN perangkat itu (kurirnya belum
                // pernah kirim GPS, tapi ordernya tetap menunjukkan di mana ia bekerja).
                "function deviceLatLng(uuid){\n" +
                "  var live=(window.__liveDev&&window.__liveDev.latest)?window.__liveDev.latest():[];\n" +
                "  for(var i=0;i<live.length;i++){ if(live[i].device_uuid===uuid) return [live[i].lat,live[i].lng]; }\n" +
                "  for(var j=0;j<positions.length;j++){ if(positions[j].device_uuid===uuid){\n" +
                "    var la=+positions[j].lat, ln=+positions[j].lng;\n" +
                "    if(la||ln) return [la,ln]; } }\n" +
                "  return null;\n" +
                "}\n" +
                "function locateDevice(g){\n" +
                "  var ll=deviceLatLng(g.uuid);\n" +
                "  if(ll){ map.setView(ll,17,{animate:true});\n" +
                "    if(window.__liveDev&&window.__liveDev.openPopup) window.__liveDev.openPopup(g.uuid);\n" +
                "    return; }\n" +
                // Tanpa posisi GPS → rapatkan ke order-order perangkat itu.
                "  var b=[];\n" +
                "  markers.forEach(function(m){ if((m._p.device_uuid||'')===g.uuid) b.push([m._p.lat,m._p.lng]); });\n" +
                "  if(b.length>1){ map.fitBounds(b,{padding:[60,60],maxZoom:17}); }\n" +
                "  else if(b.length===1){ map.setView(b[0],17,{animate:true}); }\n" +
                "  else { toast(g.name+' belum mengirim posisi & tidak punya order di antrian.'); }\n" +
                "}\n" +
                "function toast(msg){\n" +
                "  var t=document.getElementById('maptoast');\n" +
                "  if(!t){ t=document.createElement('div'); t.id='maptoast';\n" +
                "    t.style.cssText='position:fixed;left:50%;transform:translateX(-50%);bottom:86px;'+\n" +
                "      'background:rgba(17,24,39,.92);color:#fff;padding:10px 14px;border-radius:10px;'+\n" +
                "      'font-size:13px;z-index:1200;max-width:80%;text-align:center;';\n" +
                "    document.body.appendChild(t); }\n" +
                "  t.textContent=msg; t.style.display='block';\n" +
                "  clearTimeout(t._h); t._h=setTimeout(function(){t.style.display='none';},2600);\n" +
                "}\n" +
                "function renderLegend(){\n" +
                "  var el=document.getElementById('legend');el.innerHTML='';\n" +
                "  legend.forEach(function(g){\n" +
                "    var hasGeo=!!deviceLatLng(g.uuid);\n" +
                "    var c=document.createElement('div');\n" +
                "    c.className='lchip'+(hiddenDevices[g.uuid]?' off':'')+(hasGeo?'':' nogeo');\n" +
                "    var lbl=document.createElement('span');\n" +
                "    lbl.style.cssText='display:inline-flex;align-items:center;gap:7px;';\n" +
                "    lbl.innerHTML='<span class=\"ldot\" style=\"background:'+g.color+'\"></span>'\n" +
                "      +'<span>'+g.icon+' '+escHtml(g.name)+'</span>';\n" +
                "    lbl.onclick=function(ev){ ev.stopPropagation(); locateDevice(g); };\n" +
                "    var eye=document.createElement('span');\n" +
                "    eye.className='leye';\n" +
                "    eye.textContent=hiddenDevices[g.uuid]?'🚫':'👁';\n" +
                "    eye.title='Sembunyikan/tampilkan order perangkat ini';\n" +
                "    eye.onclick=function(ev){ ev.stopPropagation();\n" +
                "      hiddenDevices[g.uuid]=!hiddenDevices[g.uuid]; renderLegend(); applyFilter(); };\n" +
                "    c.appendChild(lbl); c.appendChild(eye);\n" +
                "    el.appendChild(c);\n" +
                "  });\n" +
                "}\n" +
                "renderLegend();applyFilter();\n" +
                // Posisi live datang belakangan (LiveDeviceOverlay menyegarkan tiap 25 detik) —
                // gambar ulang legenda supaya penanda "belum ada posisi" ikut mutakhir.
                "window.__onLiveDev=function(){ try{ renderLegend(); }catch(e){} };\n" +
                // Ikon sepeda motor (Material Symbols "two_wheeler", viewBox 24x24) — dipakai UNTUK
                // pin posisi perangkat (devpin, perangkat lain) MAUPUN posisi live perangkat ini
                // (RIDER), supaya kedua jenis pin sama-sama terbaca sebagai kurir bermotor.
                "function motoSvg(size,fill){\n" +
                "  return '<svg width=\"'+size+'\" height=\"'+size+'\" viewBox=\"0 0 24 24\" fill=\"'+fill+'\" xmlns=\"http://www.w3.org/2000/svg\">'+\n" +
                "    '<path d=\"M19.44 9.03L15.41 5H11v2h3.59l2 2H5c-2.8 0-5 2.2-5 5s2.2 5 5 5c2.46 0 4.45-1.69 4.9-4h1.65l2.77-2.77c-.21.54-.32 1.14-.32 1.77 0 2.8 2.2 5 5 5s5-2.2 5-5c0-2.65-1.97-4.77-4.56-4.97zM7.82 15C7.4 16.15 6.28 17 5 17c-1.63 0-3-1.37-3-3s1.37-3 3-3c1.28 0 2.4.85 2.82 2H5v2h2.82zM19 17c-1.63 0-3-1.37-3-3 0-.93.44-1.78 1.14-2.32l.5.5C17.02 12.42 17 12.7 17 13c0 1.1.9 2 2 2s2-.9 2-2-.9-2-2-2c-.3 0-.58.02-.84.14l-.5-.5C18.22 10.44 18.07 10 19 10c1.63 0 3 1.37 3 3s-1.37 3-3 3z\"/>'+\n" +
                "  '</svg>';\n" +
                "}\n" +
                // Pin posisi perangkat LAIN tidak digambar di sini lagi: LiveDeviceOverlay yang
                // menggambarnya untuk SEMUA peta aplikasi, dengan ikon KENDARAAN berbeda tiap
                // perangkat dan penyegaran berkala. Digambar di sini juga cuma menumpuk dua marker
                // di titik yang sama.
                // ---- posisi live perangkat INI (rider) — sepeda motor biru di dalam lingkaran putih ----
                "var RIDER='<svg width=\"44\" height=\"44\" viewBox=\"0 0 48 48\" xmlns=\"http://www.w3.org/2000/svg\">'+\n" +
                "  '<circle cx=\"24\" cy=\"24\" r=\"22\" fill=\"#ffffff\" fill-opacity=\"0.92\" stroke=\"#1565C0\" stroke-width=\"2\"/>'+\n" +
                "  '</svg>'+'<div style=\"position:absolute;top:12px;left:12px;\">'+motoSvg(24,'#1565C0')+'</div>';\n" +
                "var meIcon=L.divIcon({className:'',html:'<div style=\"position:relative;width:44px;height:44px;\">'+RIDER+'</div>',iconSize:[44,44],iconAnchor:[22,22]});\n" +
                "var meMarker=null;\n" +
                // Garis putus-putus dari posisi staf ke SETIAP "Pesanan Terbuka" yang belum diklaim —
                // ditarik ulang tiap posisi GPS diperbarui supaya tetap menempel ke posisi terkini.
                "var odLines=[];\n" +
                "function redrawOpenDispatchLines(){\n" +
                "  odLines.forEach(function(l){ map.removeLayer(l); });\n" +
                "  odLines=[];\n" +
                "  if(!meMarker) return;\n" +
                "  var mePos=meMarker.getLatLng();\n" +
                "  openDispatchPts.forEach(function(p){\n" +
                "    if(!visible(p)) return;\n" +
                "    var line=L.polyline([mePos,[p.lat,p.lng]],{color:'#6366F1',weight:2,opacity:.75,dashArray:'6,6'});\n" +
                "    line.addTo(map);\n" +
                "    odLines.push(line);\n" +
                "  });\n" +
                "}\n" +
                "function updateMe(lat,lng){\n" +
                "  if(!meMarker){meMarker=L.marker([lat,lng],{icon:meIcon,zIndexOffset:1000,interactive:false}).addTo(map);}\n" +
                "  else{meMarker.setLatLng([lat,lng]);}\n" +
                "  redrawOpenDispatchLines();\n" +
                "}\n" +
                "function goMe(){if(meMarker){map.setView(meMarker.getLatLng(),16);}}\n" +
                "</script></body></html>";
    }
}
