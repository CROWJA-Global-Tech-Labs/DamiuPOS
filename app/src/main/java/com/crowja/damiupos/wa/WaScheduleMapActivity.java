package com.crowja.damiupos.wa;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.MapTiles;
import com.crowja.damiupos.R;
import com.crowja.damiupos.Wilayah;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 🗺️ Pilih penerima Broadcast Terjadwal dari peta — cermin peta form web: titik pelanggan bergeo (ketuk =
 * tambah/keluarkan, hijau = sudah termasuk), ▭ pilih/keluarkan area (seret kotak), 🗺️ batas kecamatan/desa
 * (ketuk → tambahkan pelanggan di dalamnya, bisa jadi wilayah otomatis), dan ✏️ gambar wilayah sendiri
 * (titik demi titik, jumlah dihitung server). Leaflet di WebView (pola IderModeActivity); semua data lewat
 * jembatan {@code Android.*} — WebView tak pernah memegang token. Mengubah {@link WaScheduleDraft#current} langsung.
 */
public class WaScheduleMapActivity extends AppCompatActivity {

    private static final String[] LEVELS = {"desa", "kecamatan", "none"};
    private static final String[] LEVEL_LABELS = {"Desa", "Kecamatan", "Sembunyikan"};

    private String staffUuid;
    private WaScheduleDraft draft;
    private WebView web;
    private TextView tvState, tvCount, tvNote, tvDrawHint, tvDrawCount;
    private CheckBox cbAuto;
    private View barTools, barDraw, panelDraw;
    private Button btnBoxAdd, btnBoxDel, btnLevel, btnDrawAdd;
    private EditText etDrawLabel;
    @Nullable private ColorStateList outlineTint;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile String pointsJson = "[]";
    private volatile String boundsJson = "{\"features\":[]}";
    private final Map<Long, JSONObject> points = new HashMap<>();
    private final Map<Long, JSONObject> boundProps = new HashMap<>();
    private boolean pageReady, pointsReady, boundsReady, pointsPushed, boundsPushed;
    private int level = 0;
    @Nullable private String boxMode;
    @Nullable private String drawGeom;
    private JSONArray drawRows = new JSONArray();
    private int drawSeq;
    private final Runnable recountRun = this::recountNow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        draft = WaScheduleDraft.current;
        if (draft == null) {
            Toast.makeText(this, "Form broadcast sudah ditutup — buka lagi dari daftar.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_wa_schedule_map);
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        tb.setNavigationOnClickListener(v -> finish());

        web = findViewById(R.id.web);
        tvState = findViewById(R.id.tvMapState);
        tvCount = findViewById(R.id.tvMapCount);
        tvNote = findViewById(R.id.tvMapNote);
        tvDrawHint = findViewById(R.id.tvDrawHint);
        tvDrawCount = findViewById(R.id.tvDrawCount);
        cbAuto = findViewById(R.id.cbMapAuto);
        barTools = findViewById(R.id.barTools);
        barDraw = findViewById(R.id.barDraw);
        panelDraw = findViewById(R.id.panelDraw);
        btnBoxAdd = findViewById(R.id.btnBoxAdd);
        btnBoxDel = findViewById(R.id.btnBoxDel);
        btnLevel = findViewById(R.id.btnLevel);
        btnDrawAdd = findViewById(R.id.btnDrawAdd);
        etDrawLabel = findViewById(R.id.etDrawLabel);
        etDrawLabel.setHintTextColor(WaScheduleUi.TEXT2);
        outlineTint = btnBoxAdd.getBackgroundTintList();

        cbAuto.setChecked(draft.auto);
        cbAuto.setOnCheckedChangeListener((b, c) -> {
            draft.auto = c;
            js("setAutoFlag(" + c + ")");
            updateDrawAddLabel();
        });
        btnBoxAdd.setOnClickListener(v -> setBox("add"));
        btnBoxDel.setOnClickListener(v -> setBox("del"));
        btnLevel.setOnClickListener(v -> {
            level = (level + 1) % LEVELS.length;
            btnLevel.setText("🗺️ Batas: " + LEVEL_LABELS[level]);
            js("showLevel('" + LEVELS[level] + "')");
        });
        findViewById(R.id.btnDraw).setOnClickListener(v -> startDraw());
        findViewById(R.id.btnDrawUndo).setOnClickListener(v -> js("edUndo()"));
        findViewById(R.id.btnDrawDone).setOnClickListener(v -> web.evaluateJavascript("edFinish()", res -> {
            if (!"true".equals(res)) tvDrawHint.setText("Minimal 3 titik untuk membentuk wilayah.");
        }));
        findViewById(R.id.btnDrawCancel).setOnClickListener(v -> endDraw());
        findViewById(R.id.btnDrawDiscard).setOnClickListener(v -> endDraw());
        btnDrawAdd.setOnClickListener(v -> addDrawn());

        setupWeb();
        loadData();
        renderCount();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(recountRun);
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Selesai").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ data

    private void loadData() {
        WaScheduleUi.call(this, () -> {
            JSONObject r = WaAdmin.api(this).adminWaGet("schedules/map-points", null, staffUuid);
            JSONArray arr = r.optJSONArray("points");
            Map<Long, JSONObject> m = new HashMap<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) m.put(p.optLong("id"), p);
            }
            synchronized (points) {
                points.clear();
                points.putAll(m);
            }
            pointsJson = arr != null ? arr.toString() : "[]";
            return new JSONObject().put("n", m.size());
        }, r -> {
            pointsReady = true;
            int n = r.optInt("n");
            tvState.setText(n == 0 ? "Belum ada pelanggan bertitik lokasi." : n + " titik pelanggan · memuat batas wilayah…");
            push();
        }, (msg, code) -> tvState.setText(msg));

        WaScheduleUi.call(this, () -> {
            JSONObject r = WaAdmin.api(this).adminWaGet("schedules/boundaries", null, staffUuid);
            JSONArray fs = r.optJSONArray("features");
            Map<Long, JSONObject> m = new HashMap<>();
            for (int i = 0; fs != null && i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                JSONObject p = f != null ? f.optJSONObject("properties") : null;
                if (p != null) m.put(p.optLong("id"), p);
            }
            synchronized (boundProps) {
                boundProps.clear();
                boundProps.putAll(m);
            }
            boundsJson = new JSONObject().put("features", fs != null ? fs : new JSONArray()).toString();
            return new JSONObject().put("n", m.size());
        }, r -> {
            boundsReady = true;
            if (r.optInt("n") == 0) tvNote.setText("Batas wilayah belum tersedia (diperbarui otomatis tiap malam). Titik & gambar wilayah tetap bisa dipakai.");
            push();
        }, (msg, code) -> tvNote.setText(msg));
    }

    /** Kirim data ke peta begitu halaman siap. */
    private void push() {
        if (!pageReady) return;
        if (pointsReady && !pointsPushed) {
            pointsPushed = true;
            js("loadPoints()");
            pushSel();
        }
        if (boundsReady && !boundsPushed) {
            boundsPushed = true;
            js("loadBounds()");
        }
        if (pointsReady && boundsReady) tvState.setVisibility(View.GONE);
        pushAuto();
        js("setAutoFlag(" + draft.auto + ")");
    }

    private void pushSel() {
        js("setSel(" + draft.selectedIds() + ")");
        renderCount();
    }

    private void pushAuto() {
        try {
            js("setAuto(" + JSONObject.quote(new JSONObject().put("ids", draft.autoBoundaryIds())
                    .put("polys", draft.autoPolygons()).toString()) + ")");
        } catch (Exception ignored) {
        }
    }

    private void renderCount() {
        tvCount.setText(draft.sel.size() + " penerima dipilih" + (draft.autoAreas.isEmpty() ? ""
                : " · " + draft.autoAreas.size() + " wilayah otomatis"));
    }

    private void js(String code) {
        if (pageReady && web != null) web.evaluateJavascript(code, null);
    }

    // ------------------------------------------------------------------ alat

    private void setBox(String mode) {
        boxMode = mode.equals(boxMode) ? null : mode;
        styleTool(btnBoxAdd, "add".equals(boxMode), WaScheduleUi.BTN_GREEN, 0xFF1B5E20);
        styleTool(btnBoxDel, "del".equals(boxMode), WaScheduleUi.BTN_RED, WaScheduleUi.RED_TXT);
        js("setBox(" + (boxMode == null ? "null" : "'" + boxMode + "'") + ")");
        tvNote.setText(boxMode == null ? "Ketuk titik untuk menambah/mengeluarkan · ● hijau = sudah termasuk."
                : "Seret di peta untuk menggambar kotak — semua pelanggan ber-nomor di dalamnya "
                + ("add".equals(boxMode) ? "ditambahkan" : "dikeluarkan") + ". Ketuk tombolnya lagi untuk keluar mode.");
    }

    private void styleTool(Button b, boolean on, int fill, int text) {
        b.setBackgroundTintList(on ? ColorStateList.valueOf(fill) : outlineTint);
        b.setTextColor(on ? 0xFFFFFFFF : text);
    }

    private void startDraw() {
        if (boxMode != null) setBox(boxMode);
        js("edStart()");
        barTools.setVisibility(View.GONE);
        panelDraw.setVisibility(View.GONE);
        barDraw.setVisibility(View.VISIBLE);
        tvDrawHint.setText("Ketuk peta untuk menaruh titik batas (minimal 3)…");
    }

    private void endDraw() {
        js("edClear()");
        drawSeq++;
        drawGeom = null;
        drawRows = new JSONArray();
        ui.removeCallbacks(recountRun);
        barDraw.setVisibility(View.GONE);
        panelDraw.setVisibility(View.GONE);
        barTools.setVisibility(View.VISIBLE);
    }

    private void recount(String g) {
        drawGeom = g;
        tvDrawCount.setText("✏️ Wilayah gambar — … pelanggan ber-nomor di dalamnya");
        btnDrawAdd.setEnabled(false);
        ui.removeCallbacks(recountRun);
        ui.postDelayed(recountRun, 350);
    }

    private void recountNow() {
        final String g = drawGeom;
        if (g == null) return;
        final int my = ++drawSeq;
        WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaPost("schedules/polygon-customers",
                new JSONObject().put("geometry", g), staffUuid), r -> {
            if (my != drawSeq) return;
            JSONArray rows = r.optJSONArray("customers");
            drawRows = rows != null ? rows : new JSONArray();
            tvDrawCount.setText("✏️ Wilayah gambar — " + drawRows.length() + " pelanggan ber-nomor di dalamnya");
            btnDrawAdd.setEnabled(true);
            updateDrawAddLabel();
        }, (msg, code) -> {
            if (my == drawSeq) tvDrawCount.setText("✏️ " + msg);
        });
    }

    private void updateDrawAddLabel() {
        if (btnDrawAdd == null) return;
        btnDrawAdd.setText("➕ Tambahkan " + drawRows.length() + " pelanggan" + (draft.auto ? " + otomatis" : ""));
    }

    private void addDrawn() {
        if (drawGeom == null) return;
        String label = etDrawLabel.getText().toString().trim();
        if (label.isEmpty()) label = "Wilayah gambar";
        label = WaScheduleText.label60(label);
        int added = draft.addAll(drawRows);
        boolean full = false;
        if (draft.auto) {
            try {
                JSONObject a = new JSONObject().put("geometry", new JSONObject(drawGeom)).put("label", label);
                if (draft.addArea(a)) draft.remember(a, drawRows);
                else full = true;
            } catch (Exception ignored) {
            }
        }
        tvNote.setText("✏️ " + label + ": " + drawRows.length() + " pelanggan — +" + added + " ditambahkan"
                + (draft.auto && !full ? ", jadi wilayah otomatis." : "."));
        if (full) Toast.makeText(this, "Wilayah otomatis maksimal " + draft.maxAutoAreas + ".", Toast.LENGTH_LONG).show();
        etDrawLabel.setText("");
        endDraw();
        pushSel();
        pushAuto();
    }

    private void addBoundary(long areaId) {
        JSONObject p;
        synchronized (boundProps) {
            p = boundProps.get(areaId);
        }
        if (p == null) return;
        JSONObject a = new JSONObject();
        try {
            a.put("area_id", areaId).put("kecamatan", p.optString("kecamatan"));
            String desa = WaScheduleUi.str(p, "desa");
            a.put("desa", desa != null ? desa : "");
        } catch (Exception ignored) {
        }
        final boolean auto = draft.auto;
        if (auto && !draft.addArea(a)) {
            Toast.makeText(this, "Wilayah otomatis maksimal " + draft.maxAutoAreas + ".", Toast.LENGTH_LONG).show();
        }
        pushAuto();
        renderCount();
        String desa = WaScheduleUi.str(p, "desa");
        final String label = "🗺️ " + (desa != null ? "Desa " + desa + ", " : "") + p.optString("kecamatan");
        tvNote.setText("Memuat pelanggan " + label + "…");
        WaScheduleUi.call(this, () -> new JSONObject().put("rows", WaScheduleUi.areaCustomers(this, staffUuid, a)), r -> {
            JSONArray rows = r.optJSONArray("rows");
            int added = draft.addAll(rows);
            draft.remember(a, rows);
            tvNote.setText(WaScheduleUi.addedNote(label, rows != null ? rows.length() : 0, added, auto && draft.hasArea(a)));
            pushSel();
        }, (msg, code) -> tvNote.setText(msg));
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWeb() {
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(MapTiles.userAgent());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                push();
            }
        });
        double[] center = Wilayah.parseCenter(WaAdmin.cfg(this).getBranchCenter());
        double lat = center != null ? center[0] : -7.55, lng = center != null ? center[1] : 110.83;
        web.loadDataWithBaseURL("https://unpkg.com", html(lat, lng), "text/html", "UTF-8", null);
    }

    /** Jembatan JS → native. Dipanggil di thread JavaBridge; perubahan UI di-post ke main thread. */
    private final class Bridge {
        @JavascriptInterface
        public String data(String key) {
            return "bounds".equals(key) ? boundsJson : pointsJson;
        }

        @JavascriptInterface
        public void toggle(String idStr) {
            ui.post(() -> {
                long id = parse(idStr);
                JSONObject p;
                synchronized (points) {
                    p = points.get(id);
                }
                if (p == null || isFinishing()) return;
                JSONObject c = customer(p);
                draft.toggle(c);
                boolean on = draft.sel.containsKey(id);
                js("selOne(" + id + "," + on + ")");
                renderCount();
                tvNote.setText((on ? "✓ " : "✕ ") + p.optString("name") + (on ? " ditambahkan" : " dikeluarkan")
                        + (WaScheduleUi.str(p, "phone") == null ? " (tanpa nomor)" : "") + ".");
            });
        }

        @JavascriptInterface
        public void box(String mode, String idsJson, int noPhone) {
            ui.post(() -> {
                if (isFinishing()) return;
                int n = 0;
                try {
                    JSONArray ids = new JSONArray(idsJson);
                    for (int i = 0; i < ids.length(); i++) {
                        long id = ids.optLong(i);
                        if ("add".equals(mode)) {
                            JSONObject p;
                            synchronized (points) {
                                p = points.get(id);
                            }
                            if (p != null && draft.add(customer(p))) n++;
                        } else if (draft.sel.containsKey(id)) {
                            draft.remove(id);
                            n++;
                        }
                    }
                } catch (Exception ignored) {
                }
                pushSel();
                tvNote.setText(("add".equals(mode) ? "+" : "−") + n + " pelanggan" + (noPhone > 0 ? " (" + noPhone
                        + " tanpa nomor dilewati)" : "") + ". Seret lagi untuk area lain, atau ketuk tombolnya untuk keluar mode.");
            });
        }

        @JavascriptInterface
        public void boundary(String idStr) {
            ui.post(() -> {
                if (!isFinishing()) addBoundary(parse(idStr));
            });
        }

        @JavascriptInterface
        public void drawPoints(int n) {
            ui.post(() -> {
                if (isFinishing()) return;
                tvDrawHint.setText(n + " titik — " + (n < 3 ? "minimal 3 titik" : n > draft.maxDrawnVertices
                        ? "terlalu banyak (maks " + draft.maxDrawnVertices + ")" : "ketuk titik pertama atau \"Selesai\""));
            });
        }

        @JavascriptInterface
        public void drawDone(String geom) {
            ui.post(() -> {
                if (isFinishing()) return;
                barDraw.setVisibility(View.GONE);
                panelDraw.setVisibility(View.VISIBLE);
                recount(geom);
            });
        }

        @JavascriptInterface
        public void drawChanged(String geom) {
            ui.post(() -> {
                if (!isFinishing() && panelDraw.getVisibility() == View.VISIBLE) recount(geom);
            });
        }
    }

    private static long parse(String s) {
        try {
            return (long) Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONObject customer(JSONObject p) {
        JSONObject c = new JSONObject();
        try {
            c.put("id", p.optLong("id")).put("name", p.optString("name"))
                    .put("phone", p.isNull("phone") ? JSONObject.NULL : p.optString("phone"))
                    .put("kecamatan", p.isNull("kecamatan") ? JSONObject.NULL : p.optString("kecamatan"))
                    .put("desa", p.isNull("desa") ? JSONObject.NULL : p.optString("desa"));
        } catch (Exception ignored) {
        }
        return c;
    }

    private String html(double lat, double lng) {
        return "<!DOCTYPE html>\n<html><head>\n"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css'/>\n"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.Default.css'/>\n"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n"
                + "<script src='https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster.js'></script>\n"
                + "<style>\n"
                + "html,body{height:100%;margin:0;padding:0;font-family:sans-serif;}\n"
                + "#map{width:100%;height:100%;}\n"
                + MapTiles.BRIGHT_TILE_CSS + "\n"
                + ".pp b{font-size:14px;color:#212121;} .pp .s{font-size:12px;color:#455A64;margin:3px 0 8px;}\n"
                + ".pp a{display:block;padding:9px 10px;background:#2E7D32;color:#fff !important;border-radius:8px;"
                + "text-align:center;font-weight:bold;text-decoration:none;font-size:13px;}\n"
                + ".v{width:16px;height:16px;box-sizing:border-box;background:#fff;border:3px solid #6A1B9A;}\n"
                + ".v0{background:#6A1B9A;}\n"
                + "</style>\n</head><body>\n<div id='map'></div>\n<script>\n"
                + "var map=L.map('map',{zoomControl:true}).setView([" + lat + "," + lng + "],12);\n"
                + "L.tileLayer('" + MapTiles.LEAFLET_URL + "',{maxZoom:19,attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);\n"
                + "map.createPane('areas');map.getPane('areas').style.zIndex=350;\n"
                + "var cluster=L.markerClusterGroup({maxClusterRadius:28,showCoverageOnHover:false,spiderfyOnMaxZoom:true}).addTo(map);\n"
                + "var selLayer=L.layerGroup().addTo(map), saved=L.layerGroup().addTo(map);\n"
                + "var COLOR_IN={color:'#ffffff',weight:2,fillColor:'#059669',fillOpacity:1,radius:9};\n"
                + "var COLOR_OUT={color:'#475569',weight:1,fillColor:'#94a3b8',fillOpacity:.85,radius:7};\n"
                + "var markers={},ALL=[],SEL={},AUTO_IDS={},AUTO_ON=false,BOUNDS={kecamatan:null,desa:null},level='desa',boxMode=null;\n"
                + "var ED={active:false,drawing:false,markers:[],pts:[],line:null,poly:null};\n"
                + "function esc(s){return String(s==null?'':s).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}\n"
                // ---- titik pelanggan
                + "function restyle(id){var m=markers[id];if(!m)return;var on=!!SEL[id];m.setStyle(on?COLOR_IN:COLOR_OUT);\n"
                + "  if(on&&cluster.hasLayer(m)){cluster.removeLayer(m);selLayer.addLayer(m);}\n"
                + "  else if(!on&&selLayer.hasLayer(m)){selLayer.removeLayer(m);cluster.addLayer(m);}}\n"
                + "function loadPoints(){var pts=JSON.parse(Android.data('points'));var b=[];ALL=pts;\n"
                + "  pts.forEach(function(p){var m=L.circleMarker([p.lat,p.lng],COLOR_OUT);\n"
                + "    m.on('click',function(){if(ED.active||boxMode)return;Android.toggle(String(p.id));});\n"
                + "    markers[p.id]=m;cluster.addLayer(m);b.push([p.lat,p.lng]);});\n"
                + "  if(b.length)map.fitBounds(b,{padding:[30,30],maxZoom:16});}\n"
                + "function setSel(ids){var n={};ids.forEach(function(i){n[i]=1;});var old=SEL;SEL=n;\n"
                + "  for(var k in old){if(!n[k])restyle(k);} for(var j in n){if(!old[j])restyle(j);}}\n"
                + "function selOne(id,on){if(on)SEL[id]=1;else delete SEL[id];restyle(id);}\n"
                // ---- batas wilayah
                + "function boundStyle(f){var est=f.properties.source==='estimate',on=!!AUTO_IDS[f.properties.id];\n"
                + "  return {pane:'areas',color:on?'#059669':(est?'#d97706':'#2563eb'),weight:on?3:2,dashArray:est?'6 5':null,\n"
                + "    fillColor:on?'#10b981':(est?'#f59e0b':'#3b82f6'),fillOpacity:on?0.22:0.06};}\n"
                + "function boundLabel(p){return (p.level==='desa'?'Desa '+esc(p.desa)+' · ':'')+esc(p.kecamatan);}\n"
                + "function pick(id){map.closePopup();Android.boundary(String(id));}\n"
                + "function onEachBound(f,layer){var p=f.properties;layer.on('click',function(e){if(ED.active||boxMode)return;\n"
                + "  var h='<div class=\"pp\"><b>'+boundLabel(p)+'</b><div class=\"s\">'+p.count+' pelanggan ber-nomor di dalam batas · '\n"
                + "    +(p.source==='estimate'?'batas PERKIRAAN dari titik pelanggan':'batas resmi OpenStreetMap')+'</div>'\n"
                + "    +'<a href=\"#\" onclick=\"pick('+p.id+');return false;\">➕ Tambahkan '+p.count+' pelanggan'+(AUTO_ON?' + otomatis':'')+'</a></div>';\n"
                + "  L.popup().setLatLng(e.latlng).setContent(h).openOn(map);});}\n"
                + "function loadBounds(){var fc=JSON.parse(Android.data('bounds'));\n"
                + "  ['kecamatan','desa'].forEach(function(l){BOUNDS[l]=L.geoJSON({type:'FeatureCollection',features:fc.features.filter(function(f){return f.properties.level===l;})},\n"
                + "    {style:boundStyle,onEachFeature:onEachBound});});showLevel(level);return fc.features.length;}\n"
                + "function showLevel(l){level=l;['kecamatan','desa'].forEach(function(x){if(BOUNDS[x])map.removeLayer(BOUNDS[x]);});\n"
                + "  if(BOUNDS[l])BOUNDS[l].addTo(map);}\n"
                + "function setAuto(json){var o=JSON.parse(json);AUTO_IDS={};o.ids.forEach(function(i){AUTO_IDS[i]=1;});\n"
                + "  ['kecamatan','desa'].forEach(function(l){if(BOUNDS[l])BOUNDS[l].setStyle(boundStyle);});\n"
                + "  saved.clearLayers();o.polys.forEach(function(p){L.geoJSON(p.geometry,{pane:'areas',interactive:false,\n"
                + "    style:{color:'#059669',weight:3,fillColor:'#10b981',fillOpacity:0.22}}).addTo(saved);});}\n"
                + "function setAutoFlag(b){AUTO_ON=!!b;}\n"
                // ---- pilih area: seret kotak
                + "var bs=null,br=null,cont=map.getContainer();\n"
                + "function setBox(m){boxMode=m;cont.style.cursor=m?'crosshair':'';if(m){map.dragging.disable();map.closePopup();}else{map.dragging.enable();}}\n"
                + "cont.addEventListener('pointerdown',function(e){if(!boxMode||ED.active)return;bs=map.mouseEventToLatLng(e);\n"
                + "  br=L.rectangle([bs,bs],{color:boxMode==='add'?'#059669':'#dc2626',weight:2,fillOpacity:.12,interactive:false}).addTo(map);\n"
                + "  try{cont.setPointerCapture(e.pointerId);}catch(x){} e.preventDefault();e.stopPropagation();},true);\n"
                + "cont.addEventListener('pointermove',function(e){if(bs&&br){br.setBounds(L.latLngBounds(bs,map.mouseEventToLatLng(e)));e.stopPropagation();}},true);\n"
                + "function endBox(e){if(!bs||!br)return;var b=L.latLngBounds(bs,map.mouseEventToLatLng(e));map.removeLayer(br);br=null;bs=null;\n"
                + "  var ids=[],noPhone=0;ALL.forEach(function(p){if(!b.contains([p.lat,p.lng]))return;\n"
                + "    if(boxMode==='add'){if(!p.phone){noPhone++;return;}if(!SEL[p.id])ids.push(p.id);}else if(SEL[p.id])ids.push(p.id);});\n"
                + "  Android.box(boxMode,JSON.stringify(ids),noPhone);}\n"
                + "cont.addEventListener('pointerup',function(e){if(bs){endBox(e);e.stopPropagation();}},true);\n"
                + "cont.addEventListener('pointercancel',function(){if(br){map.removeLayer(br);}br=null;bs=null;},true);\n"
                // ---- gambar wilayah sendiri
                + "var vIcon=function(first){return L.divIcon({className:'',html:'<div class=\"v'+(first?' v0':'')+'\"></div>',iconSize:[16,16],iconAnchor:[8,8]});};\n"
                + "function syncPts(){ED.pts=ED.markers.map(function(m){return m.getLatLng();});}\n"
                + "function redraw(){if(ED.line){map.removeLayer(ED.line);ED.line=null;}if(ED.poly){map.removeLayer(ED.poly);ED.poly=null;}\n"
                + "  if(ED.drawing){if(ED.pts.length>1)ED.line=L.polyline(ED.pts,{color:'#7c3aed',weight:3,dashArray:'6 4',interactive:false}).addTo(map);}\n"
                + "  else if(ED.pts.length>=3){ED.poly=L.polygon(ED.pts,{color:'#7c3aed',weight:3,fillColor:'#8b5cf6',fillOpacity:0.2,interactive:false}).addTo(map);}}\n"
                + "function geom(){var r=ED.pts.map(function(p){return [Math.round(p.lng*1e6)/1e6,Math.round(p.lat*1e6)/1e6];});r.push(r[0]);\n"
                + "  return {type:'Polygon',coordinates:[r]};}\n"
                + "function addVertex(ll){var first=ED.markers.length===0;var m=L.marker(ll,{icon:vIcon(first),draggable:true,zIndexOffset:4000}).addTo(map);\n"
                + "  m.on('click',function(){if(ED.drawing&&ED.markers[0]===m&&ED.pts.length>=3)edFinish();});\n"
                + "  m.on('drag',function(){syncPts();redraw();});\n"
                + "  m.on('dragend',function(){syncPts();redraw();if(!ED.drawing&&ED.pts.length>=3)Android.drawChanged(JSON.stringify(geom()));});\n"
                + "  ED.markers.push(m);syncPts();redraw();Android.drawPoints(ED.pts.length);}\n"
                + "map.on('click',function(e){if(ED.drawing)addVertex(e.latlng);});\n"
                + "function edStart(){edClear();ED.active=true;ED.drawing=true;map.closePopup();}\n"
                + "function edUndo(){if(!ED.drawing)return;var m=ED.markers.pop();if(m)map.removeLayer(m);syncPts();redraw();Android.drawPoints(ED.pts.length);}\n"
                + "function edFinish(){if(ED.pts.length<3)return false;ED.drawing=false;redraw();Android.drawDone(JSON.stringify(geom()));return true;}\n"
                + "function edClear(){ED.markers.forEach(function(m){map.removeLayer(m);});ED.markers=[];ED.pts=[];ED.drawing=false;ED.active=false;redraw();}\n"
                + "setTimeout(function(){map.invalidateSize();},300);\n"
                + "</script>\n</body></html>";
    }
}
