package com.crowja.damiupos.wa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Bersama untuk layar 📱 Akun WhatsApp ({@link WaAccountsActivity}, {@link WaAccountsSettingsActivity},
 * {@link WaAccountsConsoleActivity}): panggilan API di latar + elemen tampilan kecil.
 *
 * <p>Palet light-only, semua pasangan teks/latar ≥ 4,5:1 (WCAG AA): teks utama #212121 dan sekunder #455A64
 * di atas putih (16:1 / 7,2:1), #ECEFF1 (6,3:1 untuk sekunder) maupun latar EditText tema #D6DCE3 (hint
 * #455A64 5,2:1); badge hijau #1B5E20/#E8F5E9 (8,9:1),
 * biru #0D47A1/#E3F2FD (7,6:1), kuning #7C4A00/#FFF8E1 (7:1), merah #B71C1C/#FFEBEE (5,8:1).
 */
final class WaAccountsUi {

    static final int TEXT = 0xFF212121;
    static final int TEXT_2 = 0xFF455A64;
    static final int PAGE_BG = 0xFFECEFF1;
    static final int CARD_BG = 0xFFFFFFFF;
    static final int CARD_STROKE = 0xFFCFD8DC;
    static final int GREEN_FG = 0xFF1B5E20, GREEN_BG = 0xFFE8F5E9;
    static final int BLUE_FG = 0xFF0D47A1, BLUE_BG = 0xFFE3F2FD;
    static final int AMBER_FG = 0xFF7C4A00, AMBER_BG = 0xFFFFF8E1;
    static final int RED_FG = 0xFFB71C1C, RED_BG = 0xFFFFEBEE;

    private WaAccountsUi() {}

    interface Req {
        JSONObject call() throws Exception;
    }

    interface Ok {
        void done(JSONObject r);
    }

    interface Fail {
        void done(String message);
    }

    /**
     * Jalankan {@code req} di thread latar; hasil di thread UI. Balasan {@code ok:false} atau galat HTTP/jaringan
     * → {@code fail} dengan pesan server (atau pesan umum). Diabaikan bila activity sudah ditutup.
     */
    static void call(Activity a, Req req, Ok ok, Fail fail) {
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = req.call();
                if (r == null || !r.optBoolean("ok", true)) {
                    err = WaAdmin.message(r, "Ditolak server.");
                    r = null;
                }
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (fr != null) ok.done(fr);
                else fail.done(fe);
            });
        }).start();
    }

    /** Nilai string JSON; null untuk kunci hilang / JSON null / kosong (optString memberi "null"). */
    @Nullable
    static String str(@Nullable JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }

    static String nz(@Nullable String v) {
        return v != null ? v : "";
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    /** Label field form (12sp, sekunder, jarak atas). */
    static TextView label(Context c, String s) {
        TextView t = text(c, s, 12f, TEXT_2, false);
        t.setPadding(0, dp(c, 10), 0, dp(c, 2));
        return t;
    }

    /** Teks bantuan kecil di bawah field. */
    static TextView hint(Context c, String s) {
        TextView t = text(c, s, 11.5f, TEXT_2, false);
        t.setPadding(0, dp(c, 2), 0, 0);
        return t;
    }

    static TextView badge(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 11f, fg, true);
        t.setBackground(rounded(c, bg, 0, 10));
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        return t;
    }

    static EditText input(Context c, @Nullable String value, String hint, int maxLen, int inputType) {
        EditText e = new EditText(c);
        e.setText(value != null ? value : "");
        e.setHint(hint);
        e.setTextColor(TEXT);
        // Latar EditText dari tema (DAMIUEditText) = #D6DCE3, bukan putih: #546E7A cuma 3,9:1 di situ,
        // #455A64 5,2:1 (teks #212121 11,7:1).
        e.setHintTextColor(TEXT_2);
        e.setTextSize(14f);
        e.setInputType(inputType);
        if (maxLen > 0) e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        return e;
    }

    static EditText number(Context c, @Nullable String value, String hint, int maxLen) {
        return input(c, value, hint, maxLen, InputType.TYPE_CLASS_NUMBER);
    }

    static EditText multiline(Context c, @Nullable String value, String hint, int maxLen, int minLines) {
        EditText e = input(c, value, hint, maxLen,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(minLines);
        e.setMaxLines(8);
        e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        return e;
    }

    /** Kartu putih bersudut untuk mengelompokkan isi form. */
    static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(c, CARD_BG, CARD_STROKE, 12));
        int p = dp(c, 12);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        box.setLayoutParams(lp);
        return box;
    }

    /** Integer opsional dari field angka → JSON (kosong = null). */
    static Object intOrNull(EditText e) {
        String s = e.getText().toString().trim();
        if (s.isEmpty()) return JSONObject.NULL;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return s;   // biar server yang menolak dengan pesan yang jelas
        }
    }

    /** String opsional → JSON (kosong/spasi = null). */
    static Object strOrNull(EditText e) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? JSONObject.NULL : s;
    }

    static void gone(View v, boolean gone) {
        v.setVisibility(gone ? View.GONE : View.VISIBLE);
    }
}
