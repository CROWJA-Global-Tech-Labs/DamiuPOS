package com.crowja.damiupos.wa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.crowja.damiupos.sync.Http;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Potongan UI bersama layar Status WhatsApp ({@link WaStatusActivity}, {@link WaStatusEditActivity}). App light-only;
 * setiap pasangan teks/latar di sini ≥ 4,5:1 (dihitung WCAG): teks utama #212121 dan sekunder #455A64 di atas putih,
 * lencana bertinta (mis. #1B5E20 di atas #E8F5E9 ≈ 7:1).
 */
final class WaStatusUi {

    static final int TEXT = 0xFF212121;
    static final int TEXT_2 = 0xFF455A64;
    static final int PAGE_BG = 0xFFECEFF1;
    static final int BLUE = 0xFF0D47A1;
    static final int BLUE_BG = 0xFFE3F2FD;
    static final int GREEN = 0xFF1B5E20;
    static final int GREEN_BG = 0xFFE8F5E9;
    static final int AMBER = 0xFF7A4F01;
    static final int AMBER_BG = 0xFFFFF8E1;
    static final int RED = 0xFFB71C1C;
    static final int RED_BG = 0xFFFFEBEE;
    static final int RED_SOLID = 0xFFC62828;
    static final int SLATE = 0xFF37474F;
    static final int SLATE_BG = 0xFFECEFF1;
    static final int PRIMARY = 0xFF1565C0;

    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WaStatusUi() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    static GradientDrawable box(int fill, int stroke, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /** Kotak keterangan bertinta (info biru / peringatan kuning / merah). */
    static TextView note(Context c, CharSequence s, int fg, int bg) {
        TextView t = text(c, s, 13, fg, false);
        t.setBackground(box(bg, 0, 10, c));
        int p = dp(c, 10);
        t.setPadding(p, p, p, p);
        return t;
    }

    /** Lencana kecil berwarna (status, sumber, penanda). */
    static TextView badge(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 12, fg, true);
        t.setBackground(box(bg, 0, 12, c));
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        return t;
    }

    /** Warna lencana per status (sama makna dengan halaman web). [0]=teks, [1]=latar. */
    static int[] statusColors(String status) {
        switch (status == null ? "" : status) {
            case "scheduled": return new int[]{BLUE, BLUE_BG};
            case "posting":
            case "partial": return new int[]{AMBER, AMBER_BG};
            case "posted": return new int[]{GREEN, GREEN_BG};
            case "failed": return new int[]{RED, RED_BG};
            case "cancelled": return new int[]{TEXT_2, SLATE_BG};
            default: return new int[]{SLATE, SLATE_BG};
        }
    }

    static String sourceIcon(String key) {
        switch (key == null ? "" : key) {
            case "article": return "📰";
            case "article_cycle": return "🔁";
            case "text_cycle": return "🔄";
            case "image": return "🖼️";
            case "image_cycle": return "🎞️";
            default: return "✍️";
        }
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams full(int topMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginPx;
        return lp;
    }

    /** String JSON atau null (org.json mengubah null menjadi "null"). */
    @Nullable
    static String str(@Nullable JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String s = o.optString(key, "");
        return s.isEmpty() ? null : s;
    }

    static void hide(View v, boolean hidden) {
        v.setVisibility(hidden ? View.GONE : View.VISIBLE);
    }

    /**
     * Muat gambar kecil (URL /storage publik atau berkas lokal) ke ImageView di latar belakang; tag ImageView
     * menjaga supaya hasil lama tak menimpa gambar yang sudah diganti.
     */
    static void thumb(ImageView iv, String src, int sizePx) {
        iv.setTag(src);
        THUMBS.execute(() -> {
            Bitmap bm = null;
            try {
                byte[] bytes;
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    try (Response r = Http.SHARED.newCall(new Request.Builder().url(src).get().build()).execute()) {
                        bytes = r.isSuccessful() && r.body() != null ? r.body().bytes() : null;
                    }
                } else {
                    bytes = readFile(new File(src));
                }
                if (bytes != null) bm = decodeSampled(bytes, sizePx);
            } catch (Exception ignored) {
                // gambar tak termuat → kotak kosong
            }
            final Bitmap fb = bm;
            MAIN.post(() -> {
                if (fb != null && src.equals(iv.getTag())) iv.setImageBitmap(fb);
            });
        });
    }

    @Nullable
    private static byte[] readFile(File f) throws java.io.IOException {
        if (!f.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Nullable
    private static Bitmap decodeSampled(byte[] bytes, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
    }
}
