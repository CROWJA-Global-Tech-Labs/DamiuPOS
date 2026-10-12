package com.crowja.damiupos.util;

import android.animation.ValueAnimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Kedip hemat daya. Animator kedip tak berujung (border kartu prioritas/terlambat/strategi) dulu
 * memperbarui warna di SETIAP frame (60×/detik). Tiap pembaruan menggambar ulang kartu, terus-
 * menerus selama layar menyala, termasuk saat layar antrean tak di depan. Di HP lama (Galaxy A7
 * 2018) ini ikut membuat HP terlalu panas sampai sistem menutup aplikasi.
 *
 * <p>{@link #stepped} membatasi pembaruan ke beberapa tingkat per siklus (mis. 4 → ±6 gambar ulang
 * per detik untuk durasi 700 ms), jadi tampilannya tetap berkedip dengan biaya sebagian kecil.
 * {@link #track}/{@link #pauseAll}/{@link #resumeAll} menghentikan semuanya sementara saat layar
 * pemiliknya tak tampil.
 */
public final class LowPowerBlink {

    private LowPowerBlink() {}

    private static final Set<ValueAnimator> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    /** Bungkus listener supaya hanya dipanggil saat tingkat kedip berganti ({@code steps} tingkat per arah). */
    public static ValueAnimator.AnimatorUpdateListener stepped(int steps, ValueAnimator.AnimatorUpdateListener l) {
        final int n = Math.max(2, steps);
        final int[] last = {-1};
        return a -> {
            int s = Math.min(n - 1, (int) (a.getAnimatedFraction() * n));
            if (s != last[0]) {
                last[0] = s;
                l.onAnimationUpdate(a);
            }
        };
    }

    /** Catat animator kedip supaya bisa dijeda bersama saat layarnya tak tampil. */
    public static ValueAnimator track(ValueAnimator a) {
        if (a != null) {
            synchronized (LIVE) {
                LIVE.add(a);
            }
        }
        return a;
    }

    public static void pauseAll() {
        for (ValueAnimator a : snapshot()) {
            if (a.isStarted() && !a.isPaused()) a.pause();
        }
    }

    public static void resumeAll() {
        for (ValueAnimator a : snapshot()) {
            if (a.isPaused()) a.resume();
        }
    }

    private static ArrayList<ValueAnimator> snapshot() {
        synchronized (LIVE) {
            return new ArrayList<>(LIVE);
        }
    }
}
