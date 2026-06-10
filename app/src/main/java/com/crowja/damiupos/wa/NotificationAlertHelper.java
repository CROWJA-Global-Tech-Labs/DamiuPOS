package com.crowja.damiupos.wa;

import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

/**
 * Mengelola animasi blink di toolbar MainActivity saat ada pesanan WA
 * pending. Hanya visual — suara di-handle oleh {@link OrderAlertService}
 * yang berjalan sebagai foreground service supaya tetap bunyi walau app
 * tidak di foreground.
 *
 * <p>Singleton state — hanya satu animasi yang aktif sekaligus. Aman
 * di-call berulang dari main thread; idempotent.
 */
public class NotificationAlertHelper {

    private static AlphaAnimation blinkAnim;
    private static View animatedView;

    /** Mulai blink view. Idempotent — kalau view yang sama sudah animating, no-op. */
    public static synchronized void startAlert(View viewToBlink) {
        if (viewToBlink == null) return;
        if (animatedView == viewToBlink && blinkAnim != null) return;
        stopAlert();
        AlphaAnimation anim = new AlphaAnimation(1f, 0.25f);
        anim.setDuration(450);
        anim.setRepeatCount(Animation.INFINITE);
        anim.setRepeatMode(Animation.REVERSE);
        viewToBlink.startAnimation(anim);
        blinkAnim = anim;
        animatedView = viewToBlink;
    }

    /** Stop blink. Aman dipanggil walau belum start. */
    public static synchronized void stopAlert() {
        if (blinkAnim != null) {
            blinkAnim.cancel();
            blinkAnim = null;
        }
        if (animatedView != null) {
            animatedView.clearAnimation();
            animatedView.setAlpha(1f);
            animatedView = null;
        }
    }
}
