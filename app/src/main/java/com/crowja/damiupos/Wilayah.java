package com.crowja.damiupos;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Wilayah" penugasan perangkat (cermin App\Support\Wilayah di server): peta persebaran pelanggan
 * dibagi menjadi sektor sudut (pie) mengelilingi koordinat cabang. Wilayah seorang pelanggan = arah
 * kompas (bearing) dari pusat cabang ke koordinatnya jatuh di sektor yang mana. Dipakai HP untuk:
 *  - default perangkat saat buat transaksi (perangkat wilayah pelanggan), dan
 *  - filter "Hanya Pelanggan Wilayah Saya".
 *
 * Config datang dari /api/me (pusat cabang + sektor) dan di-cache di {@link com.crowja.damiupos.sync.SyncSettings}.
 */
public final class Wilayah {

    private Wilayah() {}

    /** Bearing kompas pusat→titik: 0=Utara, 90=Timur, 180=Selatan, 270=Barat (derajat 0..360). */
    public static double bearing(double lat0, double lng0, double lat, double lng) {
        double dLng = Math.toRadians(lng - lng0), a = Math.toRadians(lat0), b = Math.toRadians(lat);
        double y = Math.sin(dLng) * Math.cos(b);
        double x = Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(dLng);
        double d = Math.toDegrees(Math.atan2(y, x));
        return (d + 360) % 360;
    }

    /** Apakah bearing di busur [start, end) searah jam (menangani lilitan 360°). */
    static boolean inArc(double bearing, double start, double end) {
        double bb = ((bearing - start) % 360 + 360) % 360;
        double sp = ((end - start) % 360 + 360) % 360;
        if (sp == 0) sp = 360;
        return bb < sp;
    }

    /** Index sektor (0..N-1) untuk koordinat, atau -1 bila tanpa config/koordinat. */
    public static int zoneIndex(JSONArray zones, double lat0, double lng0, double lat, double lng) {
        if (zones == null || zones.length() < 1) return -1;
        if (lat == 0 && lng == 0) return -1;
        double br = bearing(lat0, lng0, lat, lng);
        int n = zones.length();
        for (int i = 0; i < n; i++) {
            JSONObject a = zones.optJSONObject(i);
            JSONObject b = zones.optJSONObject((i + 1) % n);
            double s = a != null ? a.optDouble("start", 0) : 0;
            double e = b != null ? b.optDouble("start", 0) : 0;
            if (inArc(br, s, e)) return i;
        }
        return n - 1;
    }

    /** Pusat cabang {lat,lng} dari JSON cache, atau null. */
    public static double[] parseCenter(String centerJson) {
        if (centerJson == null || centerJson.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(centerJson);
            if (!o.has("lat") || !o.has("lng")) return null;
            return new double[]{o.optDouble("lat"), o.optDouble("lng")};
        } catch (Exception e) { return null; }
    }

    public static JSONArray parseZones(String zonesJson) {
        if (zonesJson == null || zonesJson.isEmpty()) return null;
        try { return new JSONArray(zonesJson); } catch (Exception e) { return null; }
    }

    /** uuid perangkat yang ditugaskan pada wilayah koordinat ini, atau null (tanpa config/tak
     *  ditugaskan/tanpa koordinat). */
    public static String deviceForCoord(String centerJson, String zonesJson, double lat, double lng) {
        double[] c = parseCenter(centerJson);
        JSONArray z = parseZones(zonesJson);
        if (c == null || z == null || z.length() < 2) return null;
        int idx = zoneIndex(z, c[0], c[1], lat, lng);
        if (idx < 0) return null;
        JSONObject zo = z.optJSONObject(idx);
        if (zo == null) return null;
        String dev = zo.optString("device", "");
        return dev.isEmpty() ? null : dev;
    }

    /** Perangkat PENUGASAN EFEKTIF pelanggan: override per-pelanggan (di-set web) MENGALAHKAN wilayah
     *  otomatis. Kosong/null override → jatuh ke {@link #deviceForCoord}. Null bila keduanya kosong. */
    public static String effectiveDevice(String override, String centerJson, String zonesJson, double lat, double lng) {
        if (override != null && !override.trim().isEmpty()) return override.trim();
        return deviceForCoord(centerJson, zonesJson, lat, lng);
    }
}
