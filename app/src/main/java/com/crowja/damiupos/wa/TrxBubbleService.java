package com.crowja.damiupos.wa;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * Tombol melayang "+ Trx" ({@link TrxBubble}) — AccessibilityService TERSENDIRI, terpisah dari
 * {@link WaAutoSendService} (auto-kirim struk/kendala WA) supaya izin Aksesibilitas kedua fitur
 * bisa dicabut/diberikan INDEPENDEN satu sama lain di layar Pengaturan Aksesibilitas Android.
 *
 * <p>Dulu keduanya menumpang satu service (satu izin OS untuk dua fitur) — lihat riwayat
 * {@link WaAutoSendService} sebelum pemisahan ini. Bubble sendiri tidak pernah bereaksi terhadap
 * {@link AccessibilityEvent} (hanya membaca layar saat DITAP, lihat {@link TrxBubble#onTap}),
 * jadi {@link #onAccessibilityEvent} di sini sengaja kosong.</p>
 */
public class TrxBubbleService extends AccessibilityService {

    private static volatile TrxBubbleService instance;
    private TrxBubble bubble;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        bubble = new TrxBubble(this);
        bubble.refresh();
    }

    @Override
    public void onDestroy() {
        if (bubble != null) bubble.detach();
        instance = null;
        super.onDestroy();
    }

    /** Dipanggil SettingsActivity setelah toggle bubble diubah. No-op bila layanan belum aktif. */
    public static void refreshBubble() {
        TrxBubbleService s = instance;
        if (s != null && s.bubble != null) s.bubble.refresh();
    }

    /** True bila layanan ini sudah diaktifkan user di Pengaturan Aksesibilitas Android. */
    public static boolean isAccessibilityGranted(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String me = new ComponentName(c, TrxBubbleService.class).flattenToString();
        for (String part : flat.split(":")) {
            if (part.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Bubble bereaksi hanya saat ditap (lihat TrxBubble#onTap) — tidak perlu event apa pun.
    }

    @Override
    public void onInterrupt() { }
}
