package com.crowja.damiupos;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pilih jenis galon SAAT MEMBUAT transaksi (belum ada di server, jadi tanpa jaringan): Keran / Non-keran /
 * Aqua / Bebas, atau Campur dengan jumlah per produk per jenis (mis. 3 mineral = 2 keran + 1 non-keran).
 * Hasilnya ikut ter-push bersama transaksi barunya (sumber "staff").
 */
final class GalonTypePicker {

    interface Callback {
        /** {@code type} null = kembali ke bawaan (tidak diatur). {@code specJson} hanya untuk Campur. */
        void onPick(@Nullable String type, @Nullable String specJson);
    }

    private GalonTypePicker() {}

    static void show(Activity a, String title, LinkedHashMap<String, Integer> slugQty,
                     @Nullable String curType, @Nullable String curSpec, Callback cb) {
        final String[] keys = {GalonTypeUi.KERAN, GalonTypeUi.NON_KERAN, GalonTypeUi.AQUA, GalonTypeUi.BEBAS, GalonTypeUi.CAMPUR};
        final String[] labels = {"🚰 Keran", "⭕ Non-keran", "💧 Aqua", "✳️ Bebas (yang mana saja)", "🔀 Campur — atur per produk…"};
        // Tanpa pilihan = Keran (bawaan): ditandai di daftar, tetapi tidak dikirim sebagai pilihan eksplisit.
        int checked = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(curType)) checked = i;
        new AlertDialog.Builder(a).setTitle("🚰 Jenis Galon — " + title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (GalonTypeUi.CAMPUR.equals(keys[which])) {
                        mix(a, title, slugQty, curSpec, cb);
                    } else {
                        cb.onPick(keys[which], null);
                    }
                })
                .setNeutralButton("Bawaan (Keran)", (d, w) -> cb.onPick(null, null))
                .setNegativeButton("Batal", null)
                .show();
    }

    private static void mix(Activity a, String title, LinkedHashMap<String, Integer> slugQty,
                            @Nullable String curSpec, Callback cb) {
        if (slugQty.isEmpty()) {
            cb.onPick(GalonTypeUi.CAMPUR, null);
            return;
        }
        final String[] types = {GalonTypeUi.KERAN, GalonTypeUi.NON_KERAN, GalonTypeUi.AQUA};
        Map<String, LinkedHashMap<String, Integer>> cur = GalonTypeUi.specBySlug(curSpec);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(20 * d), Math.round(8 * d), Math.round(20 * d), 0);
        List<Object[]> inputs = new ArrayList<>();   // {slug, qty, EditText[3]}
        for (Map.Entry<String, Integer> e : slugQty.entrySet()) {
            TextView head = new TextView(a);
            head.setText(e.getKey() + " ×" + e.getValue());
            head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
            head.setPadding(0, Math.round(8 * d), 0, 0);
            box.addView(head);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            EditText[] ets = new EditText[types.length];
            LinkedHashMap<String, Integer> have = cur.get(e.getKey().trim().toUpperCase(Locale.US));
            for (int i = 0; i < types.length; i++) {
                EditText et = new EditText(a);
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                et.setHint(GalonTypeUi.label(types[i]));
                Integer v = have != null ? have.get(types[i]) : null;
                if (v != null) et.setText(String.valueOf(v));
                else if (have == null && i == 0) et.setText(String.valueOf(e.getValue()));
                row.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
                ets[i] = et;
            }
            box.addView(row);
            TextView legend = new TextView(a);
            legend.setText("Keran · Non-keran · Aqua");
            legend.setTextSize(11f);
            box.addView(legend);
            inputs.add(new Object[]{e.getKey(), e.getValue(), ets});
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("🔀 Campur — " + title).setView(sv)
                .setPositiveButton("Simpan", (DialogInterface.OnClickListener) null)
                .setNegativeButton("Batal", null).create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONArray spec = new JSONArray();
            java.util.Set<String> used = new java.util.HashSet<>();
            for (Object[] in : inputs) {
                String slug = (String) in[0];
                int want = (Integer) in[1];
                EditText[] ets = (EditText[]) in[2];
                int sum = 0;
                for (int i = 0; i < types.length; i++) {
                    int n;
                    try {
                        String t = ets[i].getText().toString().trim();
                        n = t.isEmpty() ? 0 : Integer.parseInt(t);
                    } catch (NumberFormatException ex) {
                        n = -1;
                    }
                    if (n < 0) {
                        ets[i].setError("Angka tidak valid");
                        return;
                    }
                    if (n > 0) {
                        try {
                            spec.put(new JSONObject().put("slug", slug).put("qty", n).put("type", types[i]));
                        } catch (Exception ignored) {
                        }
                        used.add(types[i]);
                    }
                    sum += n;
                }
                if (sum != want) {
                    ets[0].setError(slug + ": jumlah " + sum + " ≠ " + want + " galon");
                    return;
                }
            }
            dlg.dismiss();
            // Ternyata hanya satu jenis → jenis tunggal itu, bukan "Campur".
            if (used.size() == 1) cb.onPick(used.iterator().next(), null);
            else cb.onPick(GalonTypeUi.CAMPUR, spec.toString());
        }));
        dlg.show();
    }
}
