package com.crowja.damiupos.wa;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Pilih aplikasi WhatsApp yang dibuka — SATU pintu untuk semua menu yang mengalihkan staf ke
 * WhatsApp (chat pelanggan, kirim struk, follow-up, WA Perkenalan, kendala pengiriman, …).
 *
 * <p>HP bisa punya DUA WhatsApp sekaligus (WhatsApp + WhatsApp Business), masing-masing dengan nomor
 * pengirim sendiri. Dulu tiap layar diam-diam memilih {@code com.whatsapp}, sehingga pesan ke
 * pelanggan bisa keluar dari nomor yang salah. Sekarang:</p>
 * <ul>
 *   <li>tak ada WhatsApp → {@code onPackage(null)}: pemanggil jatuh ke jalurnya yang lama (wa.me
 *       tanpa paket / chooser sistem);</li>
 *   <li>satu WhatsApp → langsung dipakai, tanpa dialog;</li>
 *   <li>dua WhatsApp → dialog "Buka dengan" (WhatsApp / WhatsApp Business). Batal = tak ada
 *       {@code onPackage} (opsional {@code onCancel}).</li>
 * </ul>
 *
 * <p>HANYA untuk alur yang diketuk staf di layar. Alur otomatis/latar ({@link WaAutoSendService},
 * {@link WaGateway} dari poller dashboard, TrxBubble, sinkron) tak punya layar untuk bertanya —
 * mereka tetap memakai WhatsApp bawaan ({@link #preferred}).</p>
 *
 * <p>WhatsApp KLONING (Dual Messenger / Aplikasi Ganda) memakai nama paket yang SAMA di profil
 * pengguna lain sehingga tak terlihat dari aplikasi ini; pilihan instansinya ditangani sistem HP.</p>
 */
public final class WaAppPicker {

    private static final String TAG = "DAMIU";

    public static final String PKG_WHATSAPP = "com.whatsapp";
    public static final String PKG_BUSINESS = "com.whatsapp.w4b";

    /** Urutan tampil dialog sekaligus urutan {@link #preferred}: WhatsApp biasa dulu, lalu Business. */
    private static final String[] PACKAGES = {PKG_WHATSAPP, PKG_BUSINESS};

    private WaAppPicker() {}

    /** Paket WhatsApp yang terpasang DAN aktif di HP ini (urut {@link #PACKAGES}). */
    public static List<String> installed(Context ctx) {
        List<String> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String p : PACKAGES) {
            try {
                if (pm.getApplicationInfo(p, 0).enabled) out.add(p);
            } catch (Exception ignored) {
                // tak terpasang
            }
        }
        return out;
    }

    /** WhatsApp yang dipakai TANPA bertanya (alur otomatis); null bila tak ada WhatsApp. */
    @Nullable
    public static String preferred(Context ctx) {
        List<String> all = installed(ctx);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Nama tampilan paket WhatsApp. */
    public static String label(@Nullable String pkg) {
        return PKG_BUSINESS.equals(pkg) ? "WhatsApp Business" : "WhatsApp";
    }

    /** Lihat {@link #pick(Activity, Consumer, Runnable)} — Batal tidak memanggil apa pun. */
    public static void pick(Activity a, Consumer<String> onPackage) {
        pick(a, onPackage, null);
    }

    /**
     * Tentukan WhatsApp yang dibuka lalu panggil {@code onPackage} (UI thread) dengan nama paketnya:
     * null bila tak ada WhatsApp terpasang, langsung bila hanya satu, atau sesudah staf memilih di
     * dialog "Buka dengan" bila ada dua.
     *
     * @param onCancel dipanggil bila staf menekan Batal / menutup dialog; boleh null (tak terjadi apa-apa)
     */
    public static void pick(Activity a, Consumer<String> onPackage, @Nullable Runnable onCancel) {
        final List<String> pkgs = installed(a);
        if (pkgs.size() <= 1 || a.isFinishing() || a.isDestroyed()) {
            // Tak ada pilihan — atau layarnya sudah ditutup sehingga tak bisa bertanya (perilaku lama).
            onPackage.accept(pkgs.isEmpty() ? null : pkgs.get(0));
            return;
        }

        final float dp = a.getResources().getDisplayMetrics().density;
        final Drawable[] icons = new Drawable[pkgs.size()];
        final String[] labels = new String[pkgs.size()];
        PackageManager pm = a.getPackageManager();
        for (int i = 0; i < pkgs.size(); i++) {
            labels[i] = label(pkgs.get(i));
            try {
                icons[i] = pm.getApplicationIcon(pkgs.get(i));
            } catch (Exception ignored) {
                icons[i] = null;
            }
        }
        ArrayAdapter<String> rows = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, labels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                // Aplikasi sengaja Light-only: teks gelap pekat di permukaan dialog terang, jangan
                // bergantung pada warna tema baris daftar bawaan.
                tv.setTextColor(0xFF212121);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                tv.setMinHeight(Math.round(56 * dp));
                tv.setPadding(Math.round(24 * dp), Math.round(8 * dp), Math.round(24 * dp), Math.round(8 * dp));
                tv.setCompoundDrawablePadding(Math.round(16 * dp));
                Drawable icon = icons[position];
                if (icon != null) {
                    int size = Math.round(36 * dp);
                    icon.setBounds(0, 0, size, size);
                }
                tv.setCompoundDrawablesRelative(icon, null, null, null);
                return tv;
            }
        };

        final boolean[] settled = {false};
        try {
            new AlertDialog.Builder(a)
                    .setTitle("Buka dengan")
                    .setAdapter(rows, (d, which) -> {
                        if (settled[0]) return;
                        settled[0] = true;
                        onPackage.accept(pkgs.get(which));
                    })
                    .setNegativeButton("Batal", (d, w) -> cancelOnce(settled, onCancel))
                    .setOnCancelListener(d -> cancelOnce(settled, onCancel))
                    .show();
        } catch (Exception e) {
            // Jendela layar sudah tak sah (mis. BadToken) → jangan sampai WhatsApp tak terbuka sama sekali.
            Log.w(TAG, "WA app picker dialog failed", e);
            if (!settled[0]) {
                settled[0] = true;
                onPackage.accept(pkgs.get(0));
            }
        }
    }

    /**
     * Varian untuk pemanggil yang hanya memegang {@link Context} (mis. {@code view.getContext()}):
     * Activity di baliknya dipakai untuk dialog; tanpa Activity (tak ada layar) → {@link #preferred}.
     */
    public static void pick(Context ctx, Consumer<String> onPackage) {
        Activity a = activityOf(ctx);
        if (a != null) {
            pick(a, onPackage, null);
        } else {
            onPackage.accept(preferred(ctx));
        }
    }

    private static void cancelOnce(boolean[] settled, @Nullable Runnable onCancel) {
        if (settled[0]) return;
        settled[0] = true;
        if (onCancel != null) onCancel.run();
    }

    /** Activity di balik sebuah Context (menembus ContextWrapper/ContextThemeWrapper); null bila tak ada. */
    @Nullable
    public static Activity activityOf(@Nullable Context ctx) {
        Context c = ctx;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /**
     * {@code ctx.startActivity(i)} diarahkan ke WhatsApp {@code pkg} (hasil {@link #pick}). Paket itu
     * tak bisa menangani intent ini → dikirim tanpa paket (sistem yang memilih = perilaku lama).
     * Kegagalan lain dilempar ke pemanggil persis seperti {@code startActivity} biasa.
     */
    public static void startTargeted(Context ctx, Intent i, @Nullable String pkg) {
        if (pkg != null) {
            try {
                ctx.startActivity(new Intent(i).setPackage(pkg));
                return;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, pkg + " tidak menangani tautan WhatsApp ini; jatuh ke pilihan sistem");
            }
        }
        ctx.startActivity(i);
    }

    /** Tautan yang dibuka aplikasi WhatsApp: wa.me, api.whatsapp.com, chat.whatsapp.com, skema whatsapp:. */
    public static boolean isWhatsAppLink(@Nullable Uri u) {
        if (u == null || u.getScheme() == null) return false;
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if ("whatsapp".equals(scheme)) return true;
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = u.getHost() != null ? u.getHost().toLowerCase(Locale.ROOT) : "";
        return host.equals("wa.me") || host.equals("www.wa.me")
                || host.equals("api.whatsapp.com") || host.equals("chat.whatsapp.com");
    }
}
