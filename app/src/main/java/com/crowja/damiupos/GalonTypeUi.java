package com.crowja.damiupos;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JENIS GALON pesanan di HP — salinan PERSIS App\Support\GalonType (web): nilai, label, tag, warna.
 *
 * <p>Tampilan utama = TAG di dalam kapsul slug produk: "MIN ×2 [KERAN]" — pil putih bertulisan warna jenis,
 * jadi terbaca tegas di atas warna produk apa pun (biru MIN, hijau OXY, zaitun BIO) dan sama di tema terang/
 * gelap. Pesanan CAMPUR dengan rincian dipecah per jenis ("MIN ×5 [KERAN]" + "MIN ×2 [NON-KERAN]"); tanpa
 * rincian → tag [CAMPUR] (catatan pesanan tetap jadi rujukan). Jenis yang DIASUMSIKAN (pelanggan tak menjawab,
 * sumber "default") digambar bergaris tepi dengan "?" — kurir tahu jenisnya belum dikonfirmasi.</p>
 */
public final class GalonTypeUi {
    public static final String KERAN = "KERAN";
    public static final String NON_KERAN = "NON_KERAN";
    public static final String AQUA = "AQUA";
    public static final String BEBAS = "BEBAS";
    public static final String CAMPUR = "CAMPUR";

    public static final String SOURCE_DEFAULT = "default";

    /** Urutan pilihan di dialog "🚰 Ubah Jenis Galon" (jenis tunggal). */
    public static final String[] SINGLE = {KERAN, NON_KERAN, AQUA, BEBAS};

    private GalonTypeUi() {}

    public static boolean known(String type) {
        return KERAN.equals(type) || NON_KERAN.equals(type) || AQUA.equals(type) || BEBAS.equals(type) || CAMPUR.equals(type);
    }

    /** "Keran" / "Non-keran" / "Aqua" / "Bebas" / "Campur" ("" bila tak dikenal). */
    public static String label(String type) {
        if (KERAN.equals(type)) return "Keran";
        if (NON_KERAN.equals(type)) return "Non-keran";
        if (AQUA.equals(type)) return "Aqua";
        if (BEBAS.equals(type)) return "Bebas";
        if (CAMPUR.equals(type)) return "Campur";
        return "";
    }

    /** Teks tag kapital ringkas — "KRN" / "NON-KRN" (dipendekkan supaya muat di kapsul slug). */
    public static String tag(String type) {
        if (KERAN.equals(type)) return "KRN";
        if (NON_KERAN.equals(type)) return "NON-KRN";
        return type != null ? type : "";
    }

    /** Warna jenis — sama dengan GalonType::COLORS di web. */
    public static int color(String type) {
        if (KERAN.equals(type)) return 0xFF0F766E;
        if (NON_KERAN.equals(type)) return 0xFFC2410C;
        if (AQUA.equals(type)) return 0xFF1D4ED8;
        if (CAMPUR.equals(type)) return 0xFFA21CAF;
        return 0xFF475569;   // BEBAS / lainnya
    }

    /** Emoji badge mandiri (panel layar terbagi / detail): 🚰 keran, ⭕ non-keran, 💧 Aqua, 🔀 campur, ✳️ bebas. */
    public static String emoji(String type) {
        if (KERAN.equals(type)) return "🚰";
        if (NON_KERAN.equals(type)) return "⭕";
        if (AQUA.equals(type)) return "💧";
        if (CAMPUR.equals(type)) return "🔀";
        return "✳️";
    }

    /** {@code base} + tag jenis sebagai pil. {@code chipFg} = warna teks kapsul (dipakai garis tepi tag asumsi). */
    public static CharSequence tagged(CharSequence base, String type, boolean assumed, int chipFg) {
        if (!known(type)) return base;
        // Jarak ke "MIN ×2" = margin kiri DI DALAM span (bukan karakter spasi): spasi sebelum pil sempat tampil
        // tanpa lebar di baris total muatan, membuat pil menempel/menimpa angka qty.
        SpannableStringBuilder sb = new SpannableStringBuilder(base);
        int start = sb.length();
        sb.append(tag(type)).append(assumed ? "?" : "");
        float d = Resources.getSystem().getDisplayMetrics().density;
        sb.setSpan(assumed ? new TagSpan(0, chipFg, chipFg, 4 * d, 6 * d, 5 * d) : new TagSpan(0xFFFFFFFF, color(type), 0, 4 * d, 6 * d, 5 * d),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Rincian CAMPUR → slug (KAPITAL) → (jenis → qty), urutan dipertahankan. Menerima String JSON atau JSONArray. */
    public static Map<String, LinkedHashMap<String, Integer>> specBySlug(Object raw) {
        Map<String, LinkedHashMap<String, Integer>> out = new HashMap<>();
        JSONArray arr = null;
        try {
            if (raw instanceof JSONArray) arr = (JSONArray) raw;
            else if (raw instanceof String && ((String) raw).trim().startsWith("[")) arr = new JSONArray((String) raw);
        } catch (Exception ignored) {
        }
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String slug = o.optString("slug", "").trim().toUpperCase(Locale.US);
            String type = o.optString("type", "");
            int qty = o.optInt("qty", 0);
            if (slug.isEmpty() || !known(type) || qty <= 0) continue;
            LinkedHashMap<String, Integer> m = out.get(slug);
            if (m == null) out.put(slug, m = new LinkedHashMap<>());
            Integer cur = m.get(type);
            m.put(type, (cur != null ? cur : 0) + qty);
        }
        return out;
    }

    /** "Keran", "Keran (asumsi — belum dikonfirmasi)", "Campur: MIN 5 Keran · MIN 2 Non-keran". "" bila tak ada. */
    public static String summary(String type, String source, Object spec) {
        if (!known(type)) return "";
        StringBuilder sb = new StringBuilder(label(type));
        if (SOURCE_DEFAULT.equals(source)) sb.append(" (asumsi — belum dikonfirmasi)");
        if (CAMPUR.equals(type)) {
            StringBuilder parts = new StringBuilder();
            for (Map.Entry<String, LinkedHashMap<String, Integer>> e : specBySlug(spec).entrySet()) {
                for (Map.Entry<String, Integer> r : e.getValue().entrySet()) {
                    if (parts.length() > 0) parts.append(" · ");
                    parts.append(e.getKey()).append(' ').append(r.getValue()).append(' ').append(label(r.getKey()));
                }
            }
            if (parts.length() > 0) sb.append(": ").append(parts);
        }
        return sb.toString();
    }

    /** Pil di dalam teks: latar (0 = transparan) + teks berwarna + garis tepi opsional (0 = tanpa). */
    static final class TagSpan extends ReplacementSpan {
        private final int bg, fg, stroke;
        private final float radius, padH, marginL;

        TagSpan(int bg, int fg, int stroke, float radius, float padH, float marginL) {
            this.bg = bg;
            this.fg = fg;
            this.stroke = stroke;
            this.radius = radius;
            this.padH = padH;
            this.marginL = marginL;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            // ceil, bukan round: lebar yang dilaporkan tak boleh lebih kecil dari yang digambar.
            return (int) Math.ceil(marginL + paint.measureText(text, start, end) + 2 * padH);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            Paint.FontMetrics fm = paint.getFontMetrics();
            float left = x + marginL;
            float w = paint.measureText(text, start, end) + 2 * padH;
            RectF r = new RectF(left, y + fm.ascent, left + w, y + fm.descent);
            int oldColor = paint.getColor();
            Paint.Style oldStyle = paint.getStyle();
            float oldStroke = paint.getStrokeWidth();
            if (bg != 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(bg);
                canvas.drawRoundRect(r, radius, radius, paint);
            }
            if (stroke != 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, Resources.getSystem().getDisplayMetrics().density));
                paint.setColor(stroke);
                canvas.drawRoundRect(r, radius, radius, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fg);
            canvas.drawText(text, start, end, left + padH, y, paint);
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
            paint.setStrokeWidth(oldStroke);
        }
    }
}
