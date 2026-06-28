package com.crowja.damiupos.wa;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Auto-menekan tombol "Kirim" WhatsApp tepat setelah DAMIU POS membagikan struk ke chat pelanggan,
 * supaya staf tidak perlu menekan Kirim manual.
 *
 * <p>Privasi & keamanan: layanan ini DIBATASI ke paket WhatsApp saja (lihat
 * res/xml/wa_autosend_service.xml {@code android:packageNames}) — tidak pernah membaca layar aplikasi
 * lain. Ia juga hanya aktif dalam jendela {@link #ARM_WINDOW_MS} setelah {@link #arm()} dipanggil
 * (oleh ReceiptActivity tepat sebelum membuka WhatsApp), lalu langsung nonaktif setelah satu kali
 * klik. Jadi membuka chat WhatsApp lain secara manual TIDAK akan terkirim otomatis.</p>
 */
public class WaAutoSendService extends AccessibilityService {

    private static final long ARM_WINDOW_MS = 20_000L;
    private static final String PREFS = "damiu_wa";
    private static final String KEY_ENABLED = "autosend_enabled";

    /** Auto-kirim hanya boleh sampai epoch-ms ini. Volatile: diakses lintas-thread. */
    private static volatile long armedUntil = 0L;
    /** Cegah klik ganda dalam satu kali arm. */
    private static volatile boolean clickedThisArm = false;

    /** Dipanggil ReceiptActivity tepat sebelum melempar struk ke WhatsApp. */
    public static void arm() {
        armedUntil = System.currentTimeMillis() + ARM_WINDOW_MS;
        clickedThisArm = false;
    }

    public static void disarm() {
        armedUntil = 0L;
        clickedThisArm = true;
    }

    // --- Toggle (lokal per-perangkat; akses Aksesibilitas memang spesifik perangkat) ---

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    /** True bila layanan ini sudah diaktifkan user di Pengaturan Aksesibilitas Android. */
    public static boolean isAccessibilityGranted(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String me = new ComponentName(c, WaAutoSendService.class).flattenToString();
        for (String part : flat.split(":")) {
            if (part.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Idle cepat: tidak melakukan apa-apa kecuali sedang "armed".
        if (clickedThisArm || System.currentTimeMillis() > armedUntil) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        CharSequence pkg = root.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (!p.equals("com.whatsapp") && !p.equals("com.whatsapp.w4b")) return;

        AccessibilityNodeInfo send = findSendButton(root, p);
        if (send == null) return;

        // Naik ke ancestor yang clickable bila node-nya sendiri tidak clickable.
        AccessibilityNodeInfo target = send;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            disarm();   // satu kali kirim per arm
        }
    }

    /** Tombol Kirim: utamakan view-id ":id/send" (layar pratinjau media / percakapan), lalu fallback
     *  ke content-description "Send"/"Kirim". */
    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (byId != null) {
            for (AccessibilityNodeInfo n : byId) {
                if (n != null && n.isVisibleToUser()) return n;
            }
        }
        return findByDesc(root);
    }

    private AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null) {
            String s = cd.toString().trim();
            if ((s.equalsIgnoreCase("Send") || s.equalsIgnoreCase("Kirim")) && node.isVisibleToUser()) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByDesc(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    @Override
    public void onInterrupt() { }
}
