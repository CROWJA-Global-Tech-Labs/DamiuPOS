package com.crowja.damiupos.map;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pin POSISI LIVE perangkat lain, untuk SEMUA peta di aplikasi.
 *
 * <p>Semua peta di app ini adalah Leaflet di dalam WebView dan sama-sama memakai variabel global
 * {@code map}. Karena itu lapisan ini tidak perlu ikut ditulis ke dalam HTML tiap layar: JS-nya
 * disuntikkan lewat {@code evaluateJavascript} SETELAH halaman selesai dimuat. Konsekuensinya satu
 * kelas ini bisa dipasang di peta mana pun (peta pelanggan, peta delivery, pratinjau mini, bahkan
 * pemilih koordinat) tanpa menyentuh pembangun HTML masing-masing layar.
 *
 * <p>Ikon tiap perangkat adalah KENDARAAN yang berbeda-beda, diambil dari server
 * ({@code DeviceIcon::vehicleFor}) supaya identitas perangkat sama persis antara HP dan dashboard,
 * dan tidak berubah-ubah karena ditentukan dari uuid perangkat.
 *
 * <p>Perangkat SENDIRI sengaja tidak ikut digambar: tiap layar sudah punya cara sendiri menampilkan
 * posisi pemakainya (mis. marker rider di Peta Delivery), dan dua pin di titik yang sama hanya
 * membingungkan.
 *
 * <p>Hasil terakhir disimpan STATIS lintas layar, jadi membuka peta berikutnya langsung menampilkan
 * pin tanpa menunggu jaringan; penyegaran tetap jalan di belakang.
 */
public final class LiveDeviceOverlay {

    /** Jarak antar-penyegaran. Cukup rapat untuk terasa "live", cukup jarang untuk tidak boros. */
    private static final long PERIOD_MS = 25_000L;

    /** Hasil terakhir (JSON array) — dibagi ke semua layar peta. */
    private static volatile String cachedJson = "[]";

    private final Activity act;
    private final WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) {
                return;
            }
            fetch();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    public LiveDeviceOverlay(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    /** Panggil dari {@code onPageFinished} peta mana pun. Aman dipanggil berkali-kali. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        inject();                 // definisi JS + gambar hasil cache (langsung terlihat)
        handler.post(tick);       // lalu segarkan dari server, berulang
    }

    /** Panggil dari {@code onPause}/{@code onDestroy} layar peta. */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void inject() {
        eval(JS_DEFS);
        render(cachedJson);
    }

    private void fetch() {
        new Thread(() -> {
            String json;
            try {
                SyncSettings cfg = new SyncSettings(
                        new SettingsDao(DatabaseHelper.getInstance(act)));
                if (cfg.getBaseUrl() == null || cfg.getBaseUrl().trim().isEmpty()
                        || cfg.getToken() == null || cfg.getToken().trim().isEmpty()) {
                    return;   // belum tersambung ke server — diam saja, peta tetap normal
                }
                JSONObject res = new SyncApi(cfg).deliveryMap();
                JSONArray positions = res != null ? res.optJSONArray("positions") : null;
                if (positions == null) {
                    return;
                }
                String me = cfg.getDeviceUuid();
                JSONArray out = new JSONArray();
                for (int i = 0; i < positions.length(); i++) {
                    JSONObject p = positions.optJSONObject(i);
                    if (p == null) {
                        continue;
                    }
                    String uuid = p.optString("device_uuid", "");
                    if (uuid.isEmpty() || uuid.equals(me)) {
                        continue;   // perangkat sendiri digambar layarnya masing-masing
                    }
                    double lat = p.optDouble("lat", 0), lng = p.optDouble("lng", 0);
                    if (lat == 0 && lng == 0) {
                        continue;   // belum pernah kirim GPS
                    }
                    out.put(p);
                }
                json = out.toString();
            } catch (Throwable t) {
                return;   // jaringan mati / server sibuk: pertahankan pin terakhir, jangan ganggu peta
            }
            cachedJson = json;
            if (!running) {
                return;
            }
            act.runOnUiThread(() -> {
                if (running) {
                    render(json);
                }
            });
        }).start();
    }

    private void render(String json) {
        eval("window.__liveDev&&window.__liveDev.render(" + json + ");");
    }

    private void eval(String js) {
        try {
            if (act.isFinishing() || act.isDestroyed()) {
                return;
            }
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
            // WebView sudah dilepas — bukan alasan untuk menjatuhkan layar
        }
    }

    /**
     * Lapisan marker yang memperbarui DIRI SENDIRI: marker yang sudah ada dipindah posisinya
     * (bukan dibuat ulang) supaya pin tidak berkedip tiap penyegaran, dan perangkat yang hilang
     * dari daftar dibuang. Menempel ke variabel global {@code map} milik tiap halaman peta.
     */
    private static final String JS_DEFS =
            "(function(){\n"
            + "if(window.__liveDev) return;\n"
            + "var st=document.createElement('style');\n"
            + "st.textContent='.ldevpin{width:28px;height:28px;border-radius:50%;display:flex;"
            + "align-items:center;justify-content:center;font-size:15px;line-height:1;"
            + "border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.45)}"
            + ".ldevname{font-weight:700}.ldevmeta{color:#64748b;font-size:11px}';\n"
            + "document.head.appendChild(st);\n"
            + "var layer=null,leaders=null,markers={},last=[];\n"
            + "function esc(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){"
            + "return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}\n"
            // ---- ANTI-TUMPUK -------------------------------------------------------------------
            // Dua perangkat yang berdekatan (mis. dua kurir di depot yang sama) menempati piksel yang
            // sama sehingga salah satunya TERTUTUP TOTAL — dan makin jauh di-zoom-out, makin banyak
            // yang saling menelan. Pin di sini TIDAK di-cluster (cluster akan menyembunyikan justru
            // yang ingin dilihat), jadi yang digeser adalah POSISI GAMBARNYA: tiap kelompok yang
            // berjarak < MIN_PX disebar melingkar di sekitar titik tengah kelompok.
            //
            // Pergeseran dihitung dalam PIKSEL lalu dikembalikan ke lat/lng, jadi jaraknya tetap
            // konstan di layar berapa pun zoom-nya — karena itu harus dihitung ULANG tiap 'zoomend'.
            // Supaya tidak menyesatkan, tiap pin yang digeser diberi GARIS PENGHUBUNG tipis ke titik
            // aslinya; jadi terlihat jelas mana lokasi sebenarnya.
            + "var MIN_PX=34;\n"
            + "function relayout(){\n"
            + "  if(typeof map==='undefined'||!map||!window.L) return;\n"
            + "  if(!leaders) leaders=L.layerGroup().addTo(map);\n"
            + "  leaders.clearLayers();\n"
            + "  var keys=Object.keys(markers);\n"
            + "  if(!keys.length) return;\n"
            + "  var pts=keys.map(function(k){\n"
            + "    var t=markers[k]._true;\n"
            + "    return {k:k,ll:t,p:map.latLngToLayerPoint(t)};\n"
            + "  });\n"
            + "  var used=[],groups=[];\n"
            + "  pts.forEach(function(a){\n"
            + "    if(used.indexOf(a.k)>=0) return;\n"
            + "    var g=[a]; used.push(a.k);\n"
            + "    pts.forEach(function(b){\n"
            + "      if(used.indexOf(b.k)>=0) return;\n"
            + "      if(a.p.distanceTo(b.p)<MIN_PX){ g.push(b); used.push(b.k); }\n"
            + "    });\n"
            + "    groups.push(g);\n"
            + "  });\n"
            + "  groups.forEach(function(g){\n"
            + "    if(g.length===1){ markers[g[0].k].setLatLng(g[0].ll); return; }\n"
            // Jari-jari lingkaran penyebaran. Yang harus >= MIN_PX adalah jarak LURUS (chord) antar
            // pin bertetangga, BUKAN panjang busurnya: chord = 2r·sin(π/n). Memakai keliling
            // (r = n·MIN_PX/2π) membuat busur pas tapi chord-nya lebih pendek, sehingga mulai 5 pin
            // ke atas mereka kembali bersentuhan — persis bug yang fitur ini ingin hilangkan.
            + "    var r=Math.max(26, MIN_PX/(2*Math.sin(Math.PI/g.length)));\n"
            + "    var cx=0,cy=0;\n"
            + "    g.forEach(function(o){ cx+=o.p.x; cy+=o.p.y; });\n"
            + "    cx/=g.length; cy/=g.length;\n"
            + "    g.forEach(function(o,i){\n"
            + "      var ang=(2*Math.PI*i)/g.length - Math.PI/2;\n"
            + "      var np=L.point(cx+r*Math.cos(ang), cy+r*Math.sin(ang));\n"
            + "      var nll=map.layerPointToLatLng(np);\n"
            + "      markers[o.k].setLatLng(nll);\n"
            + "      leaders.addLayer(L.polyline([o.ll,nll],{color:'#334155',weight:1.5,"
            + "opacity:.65,dashArray:'3,3',interactive:false}));\n"
            + "      leaders.addLayer(L.circleMarker(o.ll,{radius:2.5,color:'#334155',"
            + "fillColor:'#334155',fillOpacity:1,weight:0,interactive:false}));\n"
            + "    });\n"
            + "  });\n"
            + "}\n"
            + "function render(list){\n"
            + "  if(typeof map==='undefined'||!map||!window.L) return;\n"
            + "  if(!layer) layer=L.layerGroup().addTo(map);\n"
            + "  last=list||[];\n"
            + "  var seen={};\n"
            + "  last.forEach(function(p){\n"
            + "    if(!p||typeof p.lat!=='number'||typeof p.lng!=='number') return;\n"
            + "    seen[p.device_uuid]=1;\n"
            + "    var html='<div class=\"ldevpin\" style=\"background:'+(p.color||'#475569')+'\">'"
            + "+(p.vehicle||p.icon||'🛵')+'</div>';\n"
            + "    var icon=L.divIcon({className:'',html:html,iconSize:[28,28],iconAnchor:[14,14]});\n"
            + "    var m=markers[p.device_uuid];\n"
            + "    if(m){ m.setLatLng([p.lat,p.lng]); m.setIcon(icon); }\n"
            + "    else { m=L.marker([p.lat,p.lng],{icon:icon,zIndexOffset:900}).addTo(layer);"
            + " markers[p.device_uuid]=m; }\n"
            // Posisi ASLI disimpan terpisah: setLatLng() nanti ditimpa relayout() saat pin digeser,
            // jadi tanpa ini titik sebenarnya akan hilang setelah penyegaran pertama.
            + "    m._true=L.latLng(p.lat,p.lng);\n"
            + "    m.bindPopup('<div class=\"ldevname\">'+esc(p.device_name)+'</div>'"
            + "+'<div class=\"ldevmeta\">'+esc(p.staff||'')+(p.at?' • '+esc(p.at):'')+'</div>');\n"
            + "  });\n"
            + "  Object.keys(markers).forEach(function(k){\n"
            + "    if(!seen[k]){ layer.removeLayer(markers[k]); delete markers[k]; }\n"
            + "  });\n"
            + "  relayout();\n"
            // Halaman peta boleh ikut bereaksi (mis. legenda Peta Antrian Delivery menandai perangkat
            // yang belum punya posisi). Dibungkus try supaya galat di halaman tak mematikan overlay.
            + "  if(window.__onLiveDev){ try{ window.__onLiveDev(); }catch(e){} }\n"
            + "}\n"
            // Jarak piksel berubah tiap zoom → sebaran harus dihitung ulang, kalau tidak pin akan
            // tampak melayang jauh dari posisinya saat di-zoom-in.
            + "if(typeof map!=='undefined'&&map&&map.on){ map.on('zoomend',relayout); }\n"
            + "function latest(){ return last; }\n"
            + "function openPopup(uuid){ var m=markers[uuid]; if(m&&m.openPopup) m.openPopup(); }\n"
            + "window.__liveDev={render:render,latest:latest,openPopup:openPopup,relayout:relayout};\n"
            + "})();";
}
