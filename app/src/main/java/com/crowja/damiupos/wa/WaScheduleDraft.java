package com.crowja.damiupos.wa;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Isi form Broadcast Terjadwal yang sedang disunting: penerima terpilih + wilayah otomatis. Dipegang
 * {@link WaScheduleFormActivity} dan dibaca/diubah langsung oleh {@link WaScheduleMapActivity} (daftar bisa
 * ribuan id — terlalu besar untuk Intent extra). Cermin state JS form web /wa-schedules (sel, autoAreas,
 * areaCounts, areaMembers, centang 🔄 Otomatis).
 */
final class WaScheduleDraft {

    /** Draf form yang sedang terbuka (satu saja); null bila form ditutup. */
    @Nullable
    static WaScheduleDraft current;

    /** id pelanggan → {id, name, phone, kecamatan, desa}; urutan penambahan. */
    final LinkedHashMap<Long, JSONObject> sel = new LinkedHashMap<>();
    /** Aturan wilayah otomatis: {kecamatan,desa} | {area_id,kecamatan,desa} | {geometry,label} | {trip_rule_id,label} | {trip_area_id,label}. */
    final List<JSONObject> autoAreas = new ArrayList<>();
    final Map<String, Integer> areaCounts = new HashMap<>();
    final Map<String, Set<Long>> areaMembers = new HashMap<>();
    boolean auto;
    int maxAutoAreas = 50;
    int maxDrawnVertices = 300;
    boolean dirty;

    /** Kunci unik aturan — sama dengan areaKey() di form web. */
    static String areaKey(JSONObject a) {
        if (has(a, "trip_rule_id")) return "trip:" + a.optLong("trip_rule_id");
        if (has(a, "trip_area_id")) return "tripa:" + a.optLong("trip_area_id");
        JSONObject g = a.optJSONObject("geometry");
        if (g != null) {
            JSONArray co = g.optJSONArray("coordinates");
            return "poly:" + (co != null ? co.toString() : "");
        }
        if (has(a, "area_id")) return "id:" + a.optLong("area_id");
        return (a.optString("kecamatan", "") + "|" + desaOf(a)).toLowerCase(java.util.Locale.ROOT);
    }

    static boolean has(JSONObject a, String k) {
        return a.has(k) && !a.isNull(k) && !a.optString(k, "").isEmpty() && !"0".equals(a.optString(k, ""));
    }

    private static String desaOf(JSONObject a) {
        String d = WaScheduleUi.str(a, "desa");
        return d == null ? "" : d;
    }

    /** Label chip: "🚚 Trip X", "✏️ Perum X", "🗺️ Kec › Desa", "Kec (semua desa)". */
    static String areaLabel(JSONObject a) {
        if (has(a, "trip_rule_id") || has(a, "trip_area_id")) return "🚚 " + a.optString("label", "Aturan Trip");
        if (a.optJSONObject("geometry") != null) {
            String l = WaScheduleUi.str(a, "label");
            return "✏️ " + (l != null ? l : "Wilayah gambar");
        }
        boolean map = has(a, "area_id");
        String desa = desaOf(a);
        return (map ? "🗺️ " : "") + a.optString("kecamatan", "")
                + (!desa.isEmpty() ? " › " + desa : (map ? "" : " (semua desa)"));
    }

    boolean hasArea(JSONObject a) {
        String k = areaKey(a);
        for (JSONObject x : autoAreas) if (areaKey(x).equals(k)) return true;
        return false;
    }

    /** Tambah aturan bila belum ada & di bawah batas. @return false bila sudah penuh. */
    boolean addArea(JSONObject a) {
        if (hasArea(a)) return true;
        if (autoAreas.size() >= maxAutoAreas) return false;
        autoAreas.add(a);
        dirty = true;
        return true;
    }

    void removeArea(int i) {
        if (i >= 0 && i < autoAreas.size()) {
            autoAreas.remove(i);
            dirty = true;
        }
    }

    /** Tambahkan baris pelanggan {id,name,phone,kecamatan,desa} ke daftar. @return jumlah yang baru. */
    int addAll(@Nullable JSONArray rows) {
        int n = 0;
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c != null && add(c)) n++;
        }
        return n;
    }

    boolean add(JSONObject c) {
        long id = c.optLong("id", 0);
        if (id <= 0 || sel.containsKey(id)) return false;
        sel.put(id, c);
        dirty = true;
        return true;
    }

    void toggle(JSONObject c) {
        long id = c.optLong("id", 0);
        if (id <= 0) return;
        if (sel.remove(id) == null) sel.put(id, c);
        dirty = true;
    }

    void remove(long id) {
        if (sel.remove(id) != null) dirty = true;
    }

    /** Catat hasil muat satu aturan (jumlah + anggota) — untuk chip & penanda "🔄 wilayah otomatis". */
    void remember(JSONObject a, @Nullable JSONArray rows) {
        String k = areaKey(a);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c != null) ids.add(c.optLong("id"));
        }
        areaCounts.put(k, ids.size());
        areaMembers.put(k, ids);
    }

    /** Pelanggan ini termasuk salah satu wilayah otomatis (aturan nama: kecamatan/desa; lainnya: anggota termuat). */
    boolean inAutoArea(JSONObject c) {
        long id = c.optLong("id");
        for (JSONObject a : autoAreas) {
            boolean geo = has(a, "area_id") || a.optJSONObject("geometry") != null || has(a, "trip_rule_id") || has(a, "trip_area_id");
            if (geo) {
                Set<Long> m = areaMembers.get(areaKey(a));
                if (m != null && m.contains(id)) return true;
            } else {
                String desa = desaOf(a);
                String cd = WaScheduleUi.str(c, "desa");
                if (a.optString("kecamatan", "").equals(c.optString("kecamatan", ""))
                        && (desa.isEmpty() || desa.equals(cd == null ? "" : cd))) return true;
            }
        }
        return false;
    }

    int affectedTotal() {
        int t = 0;
        for (JSONObject a : autoAreas) {
            Integer n = areaCounts.get(areaKey(a));
            if (n != null) t += n;
        }
        return t;
    }

    /** id area geofence (batas wilayah) yang jadi wilayah otomatis — untuk warna hijau di peta. */
    JSONArray autoBoundaryIds() {
        JSONArray out = new JSONArray();
        for (JSONObject a : autoAreas) if (has(a, "area_id")) out.put(a.optLong("area_id"));
        return out;
    }

    /** Poligon wilayah otomatis yang punya geometri (gambar sendiri / wilayah trip) — digambar hijau di peta. */
    JSONArray autoPolygons() {
        JSONArray out = new JSONArray();
        for (JSONObject a : autoAreas) {
            JSONObject g = a.optJSONObject("geometry");
            if (g == null) continue;
            try {
                out.put(new JSONObject().put("geometry", g).put("label", areaLabel(a)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    JSONArray selectedIds() {
        JSONArray out = new JSONArray();
        for (Long id : sel.keySet()) out.put(id);
        return out;
    }

    /** auto_areas untuk POST — bentuk field persis seperti input tersembunyi form web. */
    JSONArray autoAreasPayload() throws Exception {
        JSONArray out = new JSONArray();
        for (JSONObject a : autoAreas) {
            JSONObject o = new JSONObject();
            if (has(a, "trip_rule_id")) {
                o.put("trip_rule_id", a.optLong("trip_rule_id")).put("label", a.optString("label", ""));
            } else if (has(a, "trip_area_id")) {
                o.put("trip_area_id", a.optLong("trip_area_id")).put("label", a.optString("label", ""));
            } else if (a.optJSONObject("geometry") != null) {
                o.put("geometry", a.optJSONObject("geometry").toString()).put("label", a.optString("label", "Wilayah gambar"));
            } else {
                if (has(a, "area_id")) o.put("area_id", a.optLong("area_id"));
                o.put("kecamatan", a.optString("kecamatan", "")).put("desa", desaOf(a));
            }
            out.put(o);
        }
        return out;
    }

    void clear() {
        sel.clear();
        autoAreas.clear();
        dirty = true;
    }
}
