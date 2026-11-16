package com.crowja.damiupos;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * Tombol dialog harus terlihat (teks kontras dengan latarnya) dengan overlay Tema v2 — framework
 * android.app.AlertDialog maupun androidx AlertDialog. Dibuat setelah laporan "tombol popup tak terlihat".
 */
@RunWith(AndroidJUnit4.class)
public class DialogButtonsOverlayTest {

    private static double lum(int c) {
        double[] v = {Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(int a, int b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private String check(boolean framework, boolean overlay) throws Exception {
        AtomicReference<String> out = new AtomicReference<>("");
        try (ActivityScenario<ChangelogActivity> sc = ActivityScenario.launch(ChangelogActivity.class)) {
            AtomicReference<Dialog> dlg = new AtomicReference<>();
            sc.onActivity(a -> {
                if (overlay) a.getTheme().applyStyle(R.style.ThemeOverlay_DAMIUPOS_Gojek, true);
                if (framework) {
                    dlg.set(new android.app.AlertDialog.Builder(a).setTitle("Di luar wilayah").setMessage("Tetap kirim?")
                            .setPositiveButton("Ya, kirim", null).setNegativeButton("Batal", null).show());
                } else {
                    dlg.set(new androidx.appcompat.app.AlertDialog.Builder(a).setTitle("Judul").setMessage("Pesan")
                            .setPositiveButton("Ya", null).setNegativeButton("Batal", null).show());
                }
            });
            Thread.sleep(800);
            sc.onActivity(a -> {
                Dialog d = dlg.get();
                Button b = framework ? ((android.app.AlertDialog) d).getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        : ((androidx.appcompat.app.AlertDialog) d).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                int tc = b.getCurrentTextColor();
                // Warna latar tombol: ambil piksel dari gambar tombol (tengah-pinggir).
                Bitmap bmp = Bitmap.createBitmap(Math.max(1, b.getWidth()), Math.max(1, b.getHeight()), Bitmap.Config.ARGB_8888);
                b.draw(new android.graphics.Canvas(bmp));
                int bg = bmp.getPixel(2, Math.max(0, b.getHeight() / 2));
                if (Color.alpha(bg) < 128) bg = Color.WHITE;   // transparan → latar dialog putih
                out.set((framework ? "framework" : "androidx") + (overlay ? "+overlay" : "") + " class=" + b.getClass().getSimpleName()
                        + " vis=" + b.getVisibility() + " w=" + b.getWidth() + " h=" + b.getHeight()
                        + String.format(" text=#%08X bg=#%08X ratio=%.2f", tc, bg, contrast(tc, bg)));
            });
            Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            File f = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),
                    "dlg_" + (framework ? "fw" : "ax") + (overlay ? "_ov" : "") + ".png");
            try (FileOutputStream os = new FileOutputStream(f)) { shot.compress(Bitmap.CompressFormat.PNG, 90, os); }
        }
        android.util.Log.i("DLGTEST", out.get());
        return out.get();
    }

    @Test
    public void buttonsVisible() throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (boolean fw : new boolean[]{true, false}) {
            for (boolean ov : new boolean[]{false, true}) {
                String r = check(fw, ov);
                sb.append(r).append('\n');
                double ratio = Double.parseDouble(r.substring(r.indexOf("ratio=") + 6).replace(',', '.'));
                if (ratio < 3.0 || r.contains(" w=0")) ok = false;
            }
        }
        assertTrue(sb.toString(), ok);
    }
}
