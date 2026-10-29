package com.crowja.damiupos.wa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Bersama layar Broadcast Terjadwal (wa.WaSchedule*): warna (app light-only, semua pasangan ≥ 4,5:1 di atas
 * putih / #ECEFF1), pil status, tombol, dan pemanggil API di thread latar → hasil di UI thread.
 */
final class WaScheduleUi {

    private WaScheduleUi() {}

    static final int TEXT = 0xFF212121;       // 16:1 di atas putih
    static final int TEXT2 = 0xFF455A64;      // 7:1 di atas putih, 6:1 di atas #ECEFF1
    static final int BG = 0xFFECEFF1;
    static final int PRIMARY = 0xFF1565C0;    // putih di atasnya 5,8:1
    static final int GREEN_TXT = 0xFF1B5E20;  // 7:1 di atas #E8F5E9
    static final int GREEN_BG = 0xFFE8F5E9;
    static final int RED_TXT = 0xFFB71C1C;    // 7:1 di atas putih
    static final int AMBER_TXT = 0xFF7A4F01;  // 6,6:1 di atas #FFF3E0
    static final int AMBER_BG = 0xFFFFF3E0;
    static final int BLUE_TXT = 0xFF0D47A1;   // 7,8:1 di atas #E3F2FD
    static final int BLUE_BG = 0xFFE3F2FD;
    static final int VIOLET = 0xFF6A1B9A;     // putih di atasnya 8:1
    static final int TEAL = 0xFF00695C;       // putih di atasnya 6,6:1
    static final int BTN_GREEN = 0xFF2E7D32;  // putih di atasnya 5,1:1
    static final int BTN_RED = 0xFFC62828;    // putih di atasnya 5,6:1

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /** {bg, fg} pil status broadcast. */
    static int[] statusColors(String status) {
        if ("scheduled".equals(status)) return new int[]{BLUE_BG, BLUE_TXT};
        if ("sending".equals(status)) return new int[]{AMBER_BG, AMBER_TXT};
        if ("done".equals(status)) return new int[]{GREEN_BG, GREEN_TXT};
        if ("cancelled".equals(status)) return new int[]{BG, TEXT2};
        return new int[]{BG, 0xFF37474F};
    }

    static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        t.setBackground(round(c, bg, 10, 0));
        return t;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    /** Tombol isi penuh (teks putih) — warna dari konstanta di atas yang lolos 4,5:1. */
    static Button solid(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(round(c, color, 10, 0));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** Tombol garis (latar putih, teks & garis berwarna). */
    static Button outline(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(round(c, 0xFFFFFFFF, 10, color));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    interface Call {
        JSONObject run() throws Exception;
    }

    interface Ok {
        void done(JSONObject r);
    }

    interface Err {
        void fail(String message, int code);
    }

    /** Jalankan panggilan API di thread latar; callback di UI thread (dilewati bila activity sudah ditutup). */
    static void call(Activity a, Call call, Ok ok, @Nullable Err err) {
        new Thread(() -> {
            JSONObject r = null;
            String msg = null;
            int code = 0;
            try {
                r = call.run();
            } catch (com.crowja.damiupos.sync.SyncApi.SyncException se) {
                msg = WaAdmin.errorMessage(se);
                code = se.code;
            } catch (Exception e) {
                msg = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fm = msg;
            final int fc = code;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (fr != null) ok.done(fr);
                else if (err != null) err.fail(fm, fc);
                else android.widget.Toast.makeText(a, fm, android.widget.Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /**
     * Pelanggan ber-nomor yang cocok satu aturan wilayah (Aturan Trip / poligon gambar / batas wilayah / domisili) —
     * endpoint pemilih yang sama dengan form web. Panggil DI LUAR main thread.
     */
    static org.json.JSONArray areaCustomers(Activity a, String staffUuid, JSONObject area) throws Exception {
        com.crowja.damiupos.sync.SyncApi api = WaAdmin.api(a);
        java.util.Map<String, String> q = new java.util.HashMap<>();
        JSONObject r;
        if (WaScheduleDraft.has(area, "trip_rule_id")) {
            q.put("rule_id", String.valueOf(area.optLong("trip_rule_id")));
            r = api.adminWaGet("schedules/trip-customers", q, staffUuid);
        } else if (WaScheduleDraft.has(area, "trip_area_id")) {
            q.put("area_id", String.valueOf(area.optLong("trip_area_id")));
            r = api.adminWaGet("schedules/trip-customers", q, staffUuid);
        } else if (area.optJSONObject("geometry") != null) {
            r = api.adminWaPost("schedules/polygon-customers",
                    new JSONObject().put("geometry", area.getJSONObject("geometry").toString()), staffUuid);
        } else if (WaScheduleDraft.has(area, "area_id")) {
            q.put("area_id", String.valueOf(area.optLong("area_id")));
            r = api.adminWaGet("schedules/boundary-customers", q, staffUuid);
        } else {
            q.put("kecamatan", area.optString("kecamatan", ""));
            String desa = str(area, "desa");
            if (desa != null) q.put("desa", desa);
            r = api.adminWaGet("schedules/area-customers", q, staffUuid);
        }
        org.json.JSONArray rows = r.optJSONArray("customers");
        return rows != null ? rows : new org.json.JSONArray();
    }

    interface RuleDone {
        /** rows null = gagal ({@code error} berisi pesannya). */
        void done(@Nullable org.json.JSONArray rows, int added, @Nullable String error);
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Muat pelanggan satu aturan wilayah lalu GABUNGKAN ke draf (+ jumlah/anggota untuk chip) — juga bila layar
     * pemanggil sudah ditutup (mis. "Selesai" di peta sebelum balasan tiba): draf hidup lebih lama dari layarnya, dan
     * wilayah otomatis yang pelanggannya tak ikut terkirim dicatat server sebagai "sengaja dikeluarkan". Selama muat
     * berjalan {@link WaScheduleDraft#loads} menahan tombol simpan. {@code done} hanya dipanggil bila layar masih hidup.
     *
     * @param keepDirty muat awal form ubah (bukan perubahan oleh admin) — penanda "belum disimpan" tak berubah
     */
    static void loadRule(Activity a, String staffUuid, WaScheduleDraft draft, JSONObject rule, boolean keepDirty,
                         @Nullable RuleDone done) {
        final String key = WaScheduleDraft.areaKey(rule);
        draft.loads.begin(key);
        new Thread(() -> {
            org.json.JSONArray rows = null;
            String msg = null;
            int code = 0;
            try {
                rows = areaCustomers(a, staffUuid, rule);
            } catch (com.crowja.damiupos.sync.SyncApi.SyncException se) {
                msg = WaAdmin.errorMessage(se);
                code = se.code;
            } catch (Exception e) {
                msg = WaAdmin.errorMessage(e);
            }
            final org.json.JSONArray fr = rows;
            final String fm = msg;
            final int fc = code;
            MAIN.post(() -> {
                draft.loads.end(key);
                int added = 0;
                if (fr != null) {
                    boolean prev = draft.dirty;
                    added = draft.addAll(fr);
                    draft.remember(rule, fr);
                    if (keepDirty) draft.dirty = prev;
                } else if (fc == 404 || fc == 422) {
                    // Batas wilayah sudah tak ada / poligon tak sah lagi: server membuang aturan ini saat simpan
                    // (BroadcastAutoAreas::normalize), jadi tak ada pelanggan yang akan dianggap dikeluarkan.
                    draft.remember(rule, new org.json.JSONArray());
                }
                draft.changed();
                if (done != null && !a.isFinishing() && !a.isDestroyed()) done.done(fr, added, fm);
            });
        }).start();
    }

    /** "X: N pelanggan — +A ditambahkan (B sudah termasuk), jadi wilayah otomatis." */
    static String addedNote(String label, int found, int added, boolean auto) {
        return label + ": " + found + " pelanggan — +" + added + " ditambahkan (" + (found - added) + " sudah termasuk)"
                + (auto ? ", jadi wilayah otomatis." : ".");
    }

    /** Nilai string JSON yang benar-benar terisi (bukan null/"null"/kosong), atau null. */
    @Nullable
    static String str(@Nullable JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String s = o.optString(key, "");
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
