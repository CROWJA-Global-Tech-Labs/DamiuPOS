package com.crowja.damiupos;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Menampilkan Changelog &amp; daftar fitur aplikasi. Konten dibaca dari
 * {@code res/raw/changelog.md} lalu dirender dengan styling Markdown ringan
 * (heading, sub-heading, bullet, **tebal**).
 */
public class ChangelogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_changelog);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView tv = findViewById(R.id.tvChangelog);
        tv.setText(render(readRaw()));
    }

    private String readRaw() {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getResources().openRawResource(R.raw.changelog);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, Charset.forName("UTF-8")))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            return "Changelog tidak tersedia.";
        }
        return sb.toString();
    }

    /** Render Markdown ringan → teks berstyling. */
    private CharSequence render(String md) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (String raw : md.split("\n", -1)) {
            String line = raw;
            int color = 0;
            float size = 1f;
            boolean bold = false;

            if (line.startsWith("# ")) {
                line = line.substring(2);
                color = Color.parseColor("#1565C0"); size = 1.45f; bold = true;
            } else if (line.startsWith("## ")) {
                line = line.substring(3);
                color = Color.parseColor("#00695C"); size = 1.2f; bold = true;
            } else if (line.startsWith("### ")) {
                line = line.substring(4);
                color = Color.parseColor("#37474F"); size = 1.05f; bold = true;
            } else if (line.startsWith("- ")) {
                line = "  •  " + line.substring(2);
            } else if (line.startsWith("  - ")) {
                line = "       ◦  " + line.substring(4);
            }

            appendInline(out, line, color, size, bold);
            out.append('\n');
        }
        return out;
    }

    /** Tambahkan satu baris, render **tebal** inline. */
    private void appendInline(SpannableStringBuilder out, String line,
                              int color, float size, boolean boldAll) {
        int lineStart = out.length();
        // Pecah berdasarkan penanda **...**.
        List<int[]> boldRanges = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (i + 1 < line.length() && line.charAt(i) == '*' && line.charAt(i + 1) == '*') {
                int end = line.indexOf("**", i + 2);
                if (end > 0) {
                    int bStart = plain.length();
                    plain.append(line, i + 2, end);
                    boldRanges.add(new int[]{bStart, plain.length()});
                    i = end + 2;
                    continue;
                }
            }
            plain.append(line.charAt(i));
            i++;
        }
        out.append(plain.toString());
        int lineEnd = out.length();

        if (color != 0) {
            out.setSpan(new ForegroundColorSpan(color), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (size != 1f) {
            out.setSpan(new RelativeSizeSpan(size), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (boldAll) {
            out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            for (int[] r : boldRanges) {
                out.setSpan(new StyleSpan(Typeface.BOLD),
                        lineStart + r[0], lineStart + r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
