package com.crowja.damiupos.report;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.SalesTrendMath;
import com.crowja.damiupos.view.TrendLineChart;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bagian "📈 Tren Penjualan" di Laporan: angka se-cabang dari server ({@link SyncApi#salesTrend}),
 * periode N hari lengkap (s/d kemarin) vs N hari sebelumnya, grafik garis, analisis otomatis, dan
 * pergerakan per jenis galon / karyawan. Hasil terakhir disimpan untuk dilihat saat offline.
 */
public final class SalesTrendPanel {

    private static final String TAG = "SalesTrend";
    private static final String CACHE_PREFIX = "sales_trend_cache_";
    /** Cache indikator ubin Beranda: {"at":ms,"cur":x,"prev":y,"_scope":..}. */
    public static final String TILE_KEY = "sales_trend_tile";
    /** Waktu percobaan terakhir ambil tag ubin (berhasil atau gagal), supaya TTL juga berlaku saat gagal. */
    private static final String TILE_TRY_KEY = "sales_trend_tile_try";
    public static final long TILE_TTL_MS = 10 * 60 * 1000L;
    /** Tag ubin lebih tua dari ini tak ditampilkan (angka basi). */
    public static final long TILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final String SCOPE = "_scope";
    private static final int[] PERIODS = {7, 30, 90};

    private final Activity act;
    private final SwipeRefreshLayout swipe;
    private final LinearLayout root;
    private final SettingsDao settings;
    private final float dp;
    /** Perangkat + cabang + staf saat panel dibuka; cache dari lingkup lain dibuang. */
    private final String scope;
    private int days = 30;
    private boolean rupiah = true;
    private int seq;
    @Nullable
    private JSONObject data;
    @Nullable
    private String notice;

    public SalesTrendPanel(Activity act, SwipeRefreshLayout swipe, LinearLayout root) {
        this.act = act;
        this.swipe = swipe;
        this.root = root;
        this.settings = new SettingsDao(DatabaseHelper.getInstance(act));
        this.scope = scope(act, settings);
        this.dp = act.getResources().getDisplayMetrics().density;
        swipe.setOnRefreshListener(this::load);
        swipe.setColorSchemeColors(col(R.color.gj_primary));
    }

    public void start() {
        data = readCache(days);
        notice = data != null ? "Memperbarui…" : null;
        render();
        load();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        final int s = ++seq;
        final int d = days;
        swipe.setRefreshing(true);
        final SyncSettings cfg = new SyncSettings(settings);
        if (!cfg.isEnrolled()) {
            swipe.setRefreshing(false);
            notice = "Perangkat belum terhubung ke server, jadi tren se-cabang belum bisa dimuat.";
            render();
            return;
        }
        final String staff = LocationReporter.currentStaffUuid(act);
        new Thread(() -> {
            JSONObject res = null;
            String err = null;
            boolean denied = false;
            try {
                res = new SyncApi(cfg).salesTrend(d, false, staff);
                if (!res.optBoolean("ok", false)) err = res.optString("message", "Gagal memuat.");
            } catch (SyncApi.SyncException se) {
                String m = se.serverMessage();
                err = m != null ? m : "Ditolak server (kode " + se.code + ").";
                denied = se.code == 401 || se.code == 403;
            } catch (Exception e) {
                err = "Tidak bisa terhubung ke server.";
            }
            final JSONObject r = err == null ? res : null;
            final String e = err;
            final boolean deny = denied;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed() || s != seq) return;
                swipe.setRefreshing(false);
                if (r != null) {
                    data = r;
                    notice = null;
                    writeCache(d, r);
                    if (d == 7) writeTile(act, settings, r);
                } else {
                    // Ditolak (peran dicabut / staf nonaktif): jangan tampilkan angka tersimpan.
                    if (deny) clearAll(settings);
                    JSONObject cached = deny ? null : readCache(d);
                    if (cached != null) {
                        data = cached;
                        notice = e + " Menampilkan data tersimpan " + stamp(cached) + ".";
                    } else {
                        data = null;
                        notice = e;
                    }
                }
                render();
            });
        }).start();
    }

    private JSONObject readCache(int d) {
        try {
            String raw = settings.get(CACHE_PREFIX + d, "");
            if (raw.isEmpty()) return null;
            JSONObject o = new JSONObject(raw);
            return scope.equals(o.optString(SCOPE, "")) ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeCache(int d, JSONObject r) {
        try {
            r.put("_cached_at", System.currentTimeMillis());
            r.put(SCOPE, scope);
            settings.set(CACHE_PREFIX + d, r.toString());
        } catch (Throwable t) {
            Log.w(TAG, "cache", t);
        }
    }

    private static String stamp(JSONObject r) {
        long at = r.optLong("_cached_at", 0);
        if (at <= 0) return "sebelumnya";
        return new SimpleDateFormat("d/M HH:mm", Locale.US).format(new Date(at));
    }

    /** Lingkup cache: perangkat + cabang + staf yang login. */
    public static String scope(Activity act, SettingsDao s) {
        SyncSettings cfg = new SyncSettings(s);
        return cfg.getDeviceUuid() + "|" + cfg.getBranchUuid() + "|" + LocationReporter.currentStaffUuid(act);
    }

    /** Hapus semua cache tren (periode + ubin), mis. setelah server menolak. */
    public static void clearAll(SettingsDao s) {
        for (int p : PERIODS) s.set(CACHE_PREFIX + p, "");
        s.set(TILE_KEY, "");
    }

    // ------------------------------------------------------------------ tile (Beranda)

    /** Simpan pendapatan 7 hari vs 7 hari sebelumnya untuk tag ubin Beranda. */
    public static void writeTile(Activity act, SettingsDao s, JSONObject r) {
        try {
            JSONObject t = new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put(SCOPE, scope(act, s))
                    .put("cur", r.getJSONObject("current").getJSONObject("totals").optDouble("revenue", 0))
                    .put("prev", r.getJSONObject("previous").getJSONObject("totals").optDouble("revenue", 0));
            s.set(TILE_KEY, t.toString());
        } catch (Throwable e) {
            Log.w(TAG, "tile", e);
        }
    }

    /** Cache tag ubin; null = belum pernah dimuat, lingkup lain, atau lebih tua dari {@link #TILE_MAX_AGE_MS}. */
    @Nullable
    public static JSONObject readTile(Activity act, SettingsDao s) {
        try {
            String raw = s.get(TILE_KEY, "");
            if (raw.isEmpty()) return null;
            JSONObject t = new JSONObject(raw);
            if (!scope(act, s).equals(t.optString(SCOPE, ""))) return null;
            if (System.currentTimeMillis() - t.optLong("at", 0) > TILE_MAX_AGE_MS) return null;
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Ambil ulang tag ubin (lite, 7 hari) bila percobaan terakhir lebih tua dari {@link #TILE_TTL_MS}
     * (gagal pun dihitung). Ditolak server: cache tren dihapus. Panggil dari thread latar.
     *
     * @return true bila tag berubah (perlu gambar ulang)
     */
    public static boolean refreshTileIfStale(Activity act, SettingsDao s) {
        long now = System.currentTimeMillis();
        long tried = 0;
        try {
            tried = Long.parseLong(s.get(TILE_TRY_KEY, "0"));
        } catch (NumberFormatException ignored) {
        }
        JSONObject t = readTile(act, s);
        if (t != null && now - t.optLong("at", 0) < TILE_TTL_MS) return false;
        if (tried > 0 && tried <= now && now - tried < TILE_TTL_MS) return false;
        SyncSettings cfg = new SyncSettings(s);
        if (!cfg.isEnrolled()) return false;
        s.set(TILE_TRY_KEY, String.valueOf(now));
        try {
            JSONObject r = new SyncApi(cfg).salesTrend(7, true, LocationReporter.currentStaffUuid(act));
            if (!r.optBoolean("ok", false)) return false;
            writeTile(act, s, r);
            return true;
        } catch (SyncApi.SyncException se) {
            if (se.code == 401 || se.code == 403) {
                clearAll(s);
                return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        root.removeAllViews();
        addPeriodChips();
        if (notice != null) root.addView(noticeView(notice));
        if (data == null) {
            if (notice == null) root.addView(muted("Memuat tren penjualan se-cabang…"));
            return;
        }
        try {
            renderData(data);
        } catch (Throwable t) {
            Log.w(TAG, "render", t);
            root.addView(noticeView("Data tren tidak bisa ditampilkan."));
        }
    }

    private void addPeriodChips() {
        ChipGroup g = new ChipGroup(act);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        for (int p : PERIODS) {
            Chip c = chip(p + " hari", p == days);
            c.setOnClickListener(v -> {
                if (days == p) return;
                days = p;
                data = readCache(p);
                notice = data != null ? "Memperbarui…" : null;
                render();
                load();
            });
            g.addView(c);
        }
        Chip rev = chip("Rupiah", rupiah);
        Chip gal = chip("Galon", !rupiah);
        ChipGroup m = new ChipGroup(act);
        m.setSingleSelection(true);
        m.setSelectionRequired(true);
        rev.setOnClickListener(v -> {
            if (!rupiah) {
                rupiah = true;
                render();
            }
        });
        gal.setOnClickListener(v -> {
            if (rupiah) {
                rupiah = false;
                render();
            }
        });
        m.addView(rev);
        m.addView(gal);
        root.addView(g);
        root.addView(m);
    }

    private Chip chip(String text, boolean checked) {
        Chip c = new Chip(act);
        c.setText(text);
        c.setCheckable(true);
        c.setChecked(checked);
        c.setCheckedIconVisible(false);
        c.setTextColor(col(checked ? R.color.white : R.color.gj_text_primary));
        c.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                col(checked ? R.color.gj_primary : R.color.gj_surface)));
        c.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                col(checked ? R.color.gj_primary : R.color.gj_border_strong)));
        c.setChipStrokeWidth(dp);
        c.setContentDescription(text + (checked ? ", dipilih" : ""));
        return c;
    }

    private void renderData(JSONObject r) throws Exception {
        JSONObject cur = r.getJSONObject("current");
        JSONObject prev = r.getJSONObject("previous");
        JSONObject ct = cur.getJSONObject("totals");
        JSONObject pt = prev.getJSONObject("totals");
        int n = r.optInt("days", days);

        String branch = r.optString("branch", "");
        root.addView(muted("Se-cabang" + (branch.isEmpty() || "null".equals(branch) ? "" : " " + branch)
                + " • " + shortDate(cur.optString("start")) + "–" + shortDate(cur.optString("end"))
                + " vs " + shortDate(prev.optString("start")) + "–" + shortDate(prev.optString("end"))
                + ". Hari ini belum dihitung karena masih berjalan."));

        // KPI 2x2
        LinearLayout row1 = hRow();
        LinearLayout row2 = hRow();
        row1.addView(kpi("Pendapatan", ct.optDouble("revenue", 0), pt.optDouble("revenue", 0), true));
        row1.addView(kpi("Galon terjual", ct.optDouble("galon", 0), pt.optDouble("galon", 0), false));
        row2.addView(kpi("Transaksi jual", ct.optDouble("trx", 0), pt.optDouble("trx", 0), false));
        if (!ct.isNull("customers") && !pt.isNull("customers")) {
            row2.addView(kpi("Pelanggan aktif", ct.optDouble("customers", 0), pt.optDouble("customers", 0), false));
        } else {
            View sp = new View(act);
            row2.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        root.addView(row1);
        root.addView(row2);

        JSONObject today = r.optJSONObject("today");
        if (today != null) {
            root.addView(muted("Hari ini (berjalan): " + SalesTrendMath.fmt(today.optDouble("revenue", 0), true)
                    + " • " + SalesTrendMath.fmt(today.optDouble("galon", 0), false)
                    + " • " + today.optInt("trx") + " transaksi"));
        }

        // Deret
        JSONArray cd = cur.optJSONArray("daily");
        JSONArray pd = prev.optJSONArray("daily");
        double[] cv = values(cd, rupiah ? "revenue" : "galon");
        double[] pv = values(pd, rupiah ? "revenue" : "galon");
        String[] dates = new String[cv.length];
        String[] labels = new String[cv.length];
        for (int i = 0; i < cv.length; i++) {
            dates[i] = cd.getJSONObject(i).optString("date");
            labels[i] = shortDate(dates[i]);
        }
        root.addView(chartCard(labels, cv, pv, n));

        // Analisis
        SalesTrendMath.Input in = new SalesTrendMath.Input();
        in.cur = cv;
        in.prev = pv;
        in.dates = dates;
        in.rupiah = rupiah;
        JSONObject mtd = r.optJSONObject("mtd");
        if (mtd != null) {
            in.mtdTotal = mtd.optDouble(rupiah ? "revenue" : "galon", 0);
            in.mtdDays = mtd.optInt("days", 0);
            in.daysInMonth = mtd.optInt("days_in_month", 0);
            JSONArray md = mtd.optJSONArray("daily");
            if (md != null) in.mtdDaily = values(md, rupiah ? "revenue" : "galon");
        }
        LinearLayout ab = cardBody("🧠 Analisis");
        for (String s : SalesTrendMath.analyze(in)) ab.addView(bullet(s));
        root.addView((View) ab.getParent());

        // Pergerakan
        renderMovement("💧 Pergerakan per jenis galon", cur.optJSONArray("products"), prev.optJSONArray("products"));
        renderMovement("🧑‍💼 Pergerakan per karyawan", cur.optJSONArray("staff"), prev.optJSONArray("staff"));
    }

    private View chartCard(String[] labels, double[] cv, double[] pv, int n) {
        LinearLayout b = cardBody(rupiah ? "Pendapatan harian" : "Galon terjual harian");
        if (SalesTrendMath.sum(cv) == 0 && SalesTrendMath.sum(pv) == 0) {
            b.addView(muted("Belum ada penjualan selesai di periode ini maupun sebelumnya."));
            return (View) b.getParent();
        }
        TrendLineChart ch = new TrendLineChart(act);
        ch.setColors(col(R.color.gj_divider), col(R.color.gj_text_secondary));
        List<TrendLineChart.Series> s = new ArrayList<>();
        s.add(new TrendLineChart.Series("periode ini", cv, col(R.color.gj_primary), false, true));
        // Garis pembanding digeser kelipatan 7 hari (hari yang sama di pekan lalu) supaya puncak
        // pekanan sejajar; untuk 30/90 hari itu 4/12 pekan lalu, bukan persis N hari sebelumnya.
        int weeks = n / 7;
        String prevName = null;
        if (weeks > 0 && pv.length == cv.length && cv.length == n) {
            prevName = weeks == 1 ? "Pekan lalu" : weeks + " pekan lalu";
            s.add(new TrendLineChart.Series("hari yang sama " + prevName.toLowerCase(Locale.ROOT),
                    weekAligned(pv, cv, weeks * 7), col(R.color.gj_text_tertiary), true, false));
        }
        int w = n >= 30 ? 7 : 3;
        s.add(new TrendLineChart.Series("rata-rata " + w + " hari", SalesTrendMath.movingAverage(cv, w),
                col(R.color.gj_orange_glyph), false, false));
        ch.setData(labels, s);
        b.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200)));
        ChipGroup legend = new ChipGroup(act);   // FlowLayout: pindah baris di layar sempit / huruf besar
        legend.setChipSpacingHorizontal(px(12));
        legend.setChipSpacingVertical(px(4));
        legend.addView(legendItem(col(R.color.gj_primary), "Periode ini", false));
        if (prevName != null) legend.addView(legendItem(col(R.color.gj_text_tertiary), prevName, true));
        legend.addView(legendItem(col(R.color.gj_orange_glyph), "Rata-rata " + w + "h", false));
        b.addView(legend);
        return (View) b.getParent();
    }

    /** Nilai {@code off} hari sebelum tiap hari periode ini (off &le; N), dari deret prev+cur. */
    private static double[] weekAligned(double[] pv, double[] cv, int off) {
        int n = cv.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int j = n + i - off;
            out[i] = j < n ? pv[j] : cv[j - n];
        }
        return out;
    }

    private View legendItem(int color, String text, boolean dashed) {
        LinearLayout l = new LinearLayout(act);
        l.setGravity(Gravity.CENTER_VERTICAL);
        View sw = new View(act);
        GradientDrawable g = new GradientDrawable();
        g.setColor(dashed ? 0 : color);
        if (dashed) g.setStroke(px(2), color, px(4), px(2));
        sw.setBackground(g);
        l.addView(sw, new LinearLayout.LayoutParams(px(16), px(dashed ? 4 : 3)));
        TextView t = text(text, 12, R.color.gj_text_secondary, false);
        t.setPadding(px(6), 0, 0, 0);
        l.addView(t);
        return l;
    }

    private void renderMovement(String title, @Nullable JSONArray cur, @Nullable JSONArray prev) throws Exception {
        if (cur == null && prev == null) return;
        // Gabung by name → (galon/revenue sekarang, lalu)
        java.util.LinkedHashMap<String, double[]> m = new java.util.LinkedHashMap<>();
        String k = rupiah ? "revenue" : "galon";
        if (cur != null) for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.getJSONObject(i);
            String key = o.optString("key", o.optString("name"));
            double[] v = m.get(key);
            if (v == null) m.put(key, v = new double[2]);
            v[0] += o.optDouble(k, 0);
        }
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (cur != null) for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.getJSONObject(i);
            names.put(o.optString("key", o.optString("name")), o.optString("name"));
        }
        if (prev != null) for (int i = 0; i < prev.length(); i++) {
            JSONObject o = prev.getJSONObject(i);
            String key = o.optString("key", o.optString("name"));
            double[] v = m.get(key);
            if (v == null) m.put(key, v = new double[2]);
            v[1] += o.optDouble(k, 0);
            if (!names.containsKey(key)) names.put(key, o.optString("name"));
        }
        if (m.isEmpty()) return;
        List<java.util.Map.Entry<String, double[]>> rows = new ArrayList<>(m.entrySet());
        // Urut perubahan absolut terbesar (naik tertinggi di atas, turun terdalam di bawah)
        rows.sort((a, b) -> Double.compare(b.getValue()[0] - b.getValue()[1], a.getValue()[0] - a.getValue()[1]));
        LinearLayout b = cardBody(title);
        int shown = 0;
        for (java.util.Map.Entry<String, double[]> e : rows) {
            if (shown >= 8) break;
            double c = e.getValue()[0], p = e.getValue()[1];
            if (c == 0 && p == 0) continue;
            b.addView(moveRow(names.get(e.getKey()), c, p));
            shown++;
        }
        if (rows.size() > shown) b.addView(muted("Menampilkan " + shown + " perubahan terbesar dari " + rows.size() + "."));
        root.addView((View) b.getParent());
    }

    private View moveRow(String name, double c, double p) {
        LinearLayout row = hRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(6), 0, px(6));
        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView nm = text(name == null || name.isEmpty() ? "(tanpa nama)" : name, 14, R.color.gj_text_primary, true);
        left.addView(nm);
        left.addView(text(SalesTrendMath.fmt(c, rupiah) + "  (sebelumnya " + SalesTrendMath.fmt(p, rupiah) + ")",
                12, R.color.gj_text_secondary, false));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int dir = SalesTrendMath.direction(c, p);
        Double pct = SalesTrendMath.pctChange(c, p);
        row.addView(pill(dir, pct, false));
        row.setContentDescription(name + ", " + spoken(dir, pct));
        return row;
    }

    private View kpi(String label, double cur, double prev, boolean money) {
        MaterialCardView card = card();
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(px(12), px(12), px(12), px(12));
        b.addView(text(label, 12, R.color.gj_text_secondary, false));
        TextView v = text(money ? compactRp(cur) : SalesTrendMath.fmt(cur, false).replace(" galon", ""),
                20, R.color.gj_text_primary, true);
        b.addView(v);
        int dir = SalesTrendMath.direction(cur, prev);
        Double pct = SalesTrendMath.pctChange(cur, prev);
        View pl = pill(dir, pct, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(4);
        b.addView(pl, lp);
        b.addView(text("sebelumnya " + (money ? compactRp(prev) : SalesTrendMath.fmt(prev, false).replace(" galon", "")),
                12, R.color.gj_text_tertiary, false));
        card.addView(b);
        card.setContentDescription(label + " " + (money ? compactRp(cur) : (long) cur) + ", "
                + spoken(dir, pct) + (pct != null ? " dibanding periode sebelumnya" : ""));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        clp.setMargins(px(4), px(4), px(4), px(4));
        card.setLayoutParams(clp);
        return card;
    }

    /** "Naik +12%" / "Stagnan" / "baru, belum ada pembanding" untuk pembaca layar. */
    public static String spoken(int dir, Double pct) {
        if (pct == null) return dir == SalesTrendMath.UP ? SalesTrendMath.NEW_SPOKEN : SalesTrendMath.word(dir);
        return SalesTrendMath.word(dir) + " " + SalesTrendMath.signed(pct);
    }

    /** Indikator: panah + kata + % (tak hanya warna). Hijau naik, merah turun, abu stagnan; basis nol: "Baru". */
    private TextView pill(int dir, Double pct, boolean withWord) {
        int bg = dir == SalesTrendMath.UP ? R.color.gj_success_bg
                : dir == SalesTrendMath.DOWN ? R.color.gj_danger_bg : R.color.gj_fill_subtle;
        int fg = dir == SalesTrendMath.UP ? R.color.gj_success_text
                : dir == SalesTrendMath.DOWN ? R.color.gj_danger_text : R.color.gj_text_secondary;
        boolean fresh = pct == null && dir == SalesTrendMath.UP;
        String s = SalesTrendMath.arrow(dir) + (fresh ? " " + SalesTrendMath.NEW_WORD
                : (withWord ? " " + SalesTrendMath.word(dir) : "") + (pct != null ? " " + SalesTrendMath.signed(pct) : ""));
        TextView t = text(s, 12, fg, true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(col(bg));
        g.setCornerRadius(px(10));
        t.setBackground(g);
        t.setPadding(px(8), px(2), px(8), px(2));
        return t;
    }

    private LinearLayout cardBody(String title) {
        MaterialCardView card = card();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(px(4), px(8), px(4), px(4));
        card.setLayoutParams(clp);
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(px(16), px(12), px(16), px(12));
        TextView t = text(title, 16, R.color.gj_text_primary, true);
        t.setPadding(0, 0, 0, px(8));
        b.addView(t);
        card.addView(b);
        return b;
    }

    private MaterialCardView card() {
        MaterialCardView c = new MaterialCardView(act);
        c.setRadius(px(12));
        c.setCardElevation(0);
        c.setStrokeColor(col(R.color.gj_divider));
        c.setStrokeWidth(px(1));
        c.setCardBackgroundColor(col(R.color.gj_surface));
        return c;
    }

    private View bullet(String s) {
        TextView t = text("• " + s, 14, R.color.gj_text_primary, false);
        t.setPadding(0, px(3), 0, px(3));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private View noticeView(String s) {
        TextView t = text(s, 13, R.color.gj_warning_text, false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(col(R.color.gj_warning_bg));
        g.setCornerRadius(px(8));
        t.setBackground(g);
        t.setPadding(px(12), px(8), px(12), px(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(4), px(4), px(4), px(8));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView muted(String s) {
        TextView t = text(s, 12, R.color.gj_text_secondary, false);
        t.setPadding(px(4), px(4), px(4), px(4));
        return t;
    }

    private LinearLayout hRow() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setBaselineAligned(false);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static double[] values(@Nullable JSONArray a, String key) {
        if (a == null) return new double[0];
        double[] v = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            v[i] = o != null ? o.optDouble(key, 0) : 0;
        }
        return v;
    }

    private static String shortDate(String ymd) {
        if (ymd == null) return "";
        String[] p = ymd.split("-");
        if (p.length != 3) return ymd;
        try {
            return Integer.parseInt(p[2]) + "/" + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return ymd;
        }
    }

    public static String compactRp(double v) {
        if (Math.abs(v) >= 1_000_000) return "Rp " + oneDec(v / 1_000_000) + " jt";
        if (Math.abs(v) >= 10_000) return "Rp " + oneDec(v / 1_000) + " rb";
        return SalesTrendMath.fmt(v, true);
    }

    private static String oneDec(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r).replace('.', ',');
    }

    private int col(int res) {
        return ContextCompat.getColor(act, res);
    }

    private int px(int d) {
        return Math.round(d * dp);
    }
}
