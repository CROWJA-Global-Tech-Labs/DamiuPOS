package com.crowja.damiupos.ads;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.DamiuApplication;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

/**
 * Singleton helper untuk menampilkan iklan AdMob.
 * Semua method otomatis jadi no-op saat user Pro aktif.
 */
public class AdManager {

    private static final String TAG = "AdManager";

    private static volatile AdManager INSTANCE;

    private InterstitialAd interstitialAd;
    private boolean interstitialLoading = false;
    private int transactionsSinceLastAd = 0;
    private static final int INTERSTITIAL_FREQ = 3; // 1x interstitial setiap 3 transaksi (fallback)

    private RewardedAd rewardedAd;
    private boolean rewardedLoading = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingInterstitialRunnable;

    private AdManager() {}

    public static AdManager getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (AdManager.class) {
                if (INSTANCE == null) INSTANCE = new AdManager();
            }
        }
        return INSTANCE;
    }

    private boolean isPro(Context ctx) {
        try {
            if (ctx == null) return false;
            return new SettingsDao(DatabaseHelper.getInstance(ctx.getApplicationContext()))
                    .isProActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Pasang banner ad ke container. Jika user Pro, container akan disembunyikan.
     */
    public void attachBanner(@NonNull Activity activity, @NonNull ViewGroup container) {
        if (isPro(activity)) {
            container.setVisibility(android.view.View.GONE);
            return;
        }
        try {
            container.removeAllViews();
            AdView adView = new AdView(activity);
            adView.setAdUnitId(BuildConfig.ADMOB_BANNER_ID);
            adView.setAdSize(AdSize.BANNER);
            container.addView(adView);
            adView.loadAd(new AdRequest.Builder().build());
            container.setVisibility(android.view.View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "attachBanner failed", t);
            container.setVisibility(android.view.View.GONE);
        }
    }

    /** Pre-cache interstitial supaya saat ingin di-show tidak delay. */
    public void preloadInterstitial(@NonNull Context ctx) {
        if (isPro(ctx)) return;
        if (interstitialAd != null || interstitialLoading) return;
        interstitialLoading = true;
        try {
            InterstitialAd.load(ctx, BuildConfig.ADMOB_INTERSTITIAL_ID,
                    new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull InterstitialAd ad) {
                            interstitialAd = ad;
                            interstitialLoading = false;
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                            interstitialAd = null;
                            interstitialLoading = false;
                            Log.w(TAG, "interstitial failed: " + adError.getMessage());
                        }
                    });
        } catch (Throwable t) {
            interstitialLoading = false;
            Log.w(TAG, "preloadInterstitial failed", t);
        }
    }

    /**
     * Panggil setelah transaksi selesai. Akan menampilkan interstitial
     * setiap INTERSTITIAL_FREQ kali agar tidak terlalu sering mengganggu.
     */
    public void maybeShowInterstitialAfterTransaction(@NonNull Activity activity) {
        if (isPro(activity)) return;
        transactionsSinceLastAd++;
        if (transactionsSinceLastAd < INTERSTITIAL_FREQ) {
            // Warm-up untuk next trigger
            preloadInterstitial(activity);
            return;
        }
        if (interstitialAd == null) {
            preloadInterstitial(activity);
            return;
        }
        try {
            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    interstitialAd = null;
                    transactionsSinceLastAd = 0;
                    preloadInterstitial(activity);
                }

                @Override
                public void onAdFailedToShowFullScreenContent(
                        @NonNull com.google.android.gms.ads.AdError adError) {
                    interstitialAd = null;
                    preloadInterstitial(activity);
                }
            });
            interstitialAd.show(activity);
        } catch (Throwable t) {
            Log.w(TAG, "show interstitial failed", t);
        }
    }

    /**
     * Jadwalkan interstitial muncul setelah delay tertentu.
     * Dipakai setelah transaksi selesai: panggil dengan delayMs=5000 agar
     * user sempat melihat struk dulu sebelum iklan muncul.
     *
     * Otomatis:
     * - No-op saat user Pro
     * - Cancel & reschedule bila dipanggil berulang sebelum yang sebelumnya fire
     * - Warm-up iklan bila belum ready
     */
    public void scheduleInterstitialAfterDelay(@NonNull final Activity activity,
                                               long delayMs) {
        if (isPro(activity)) return;

        // Batalkan scheduled sebelumnya (supaya tidak numpuk)
        cancelScheduledInterstitial();

        // Pre-load kalau belum ada
        if (interstitialAd == null) {
            preloadInterstitial(activity);
        }

        final Context appContext = activity.getApplicationContext();
        pendingInterstitialRunnable = () -> {
            pendingInterstitialRunnable = null;
            if (isPro(appContext)) return;
            // Tampilkan di activity foreground saat ini — tetap fire walaupun
            // user sudah menekan Back dari halaman struk.
            Activity target = com.crowja.damiupos.DamiuApplication.get() != null
                    ? com.crowja.damiupos.DamiuApplication.get().getCurrentActivity()
                    : null;
            if (target == null || target.isFinishing() || target.isDestroyed()) {
                // App di-background / tidak ada activity; reschedule sebentar
                // sampai ada activity yang aktif (max 10 detik).
                mainHandler.postDelayed(this::retryShowInterstitial, 500L);
                return;
            }
            showInterstitialNow(target);
        };
        mainHandler.postDelayed(pendingInterstitialRunnable, delayMs);
    }

    private int retryCount = 0;
    private void retryShowInterstitial() {
        if (isPro(null != com.crowja.damiupos.DamiuApplication.get()
                ? com.crowja.damiupos.DamiuApplication.get() : null)) {
            retryCount = 0;
            return;
        }
        Activity target = com.crowja.damiupos.DamiuApplication.get() != null
                ? com.crowja.damiupos.DamiuApplication.get().getCurrentActivity()
                : null;
        if (target != null && !target.isFinishing() && !target.isDestroyed()) {
            retryCount = 0;
            showInterstitialNow(target);
            return;
        }
        if (retryCount++ < 20) { // 20 * 500ms = 10 detik max
            mainHandler.postDelayed(this::retryShowInterstitial, 500L);
        } else {
            retryCount = 0;
        }
    }

    /** Batalkan interstitial yang ter-schedule (mis. activity di-destroy duluan). */
    public void cancelScheduledInterstitial() {
        if (pendingInterstitialRunnable != null) {
            mainHandler.removeCallbacks(pendingInterstitialRunnable);
            pendingInterstitialRunnable = null;
        }
    }

    /** Tampilkan interstitial sekarang juga tanpa throttling. */
    private void showInterstitialNow(@NonNull Activity activity) {
        if (interstitialAd == null) {
            preloadInterstitial(activity);
            return;
        }
        try {
            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    interstitialAd = null;
                    transactionsSinceLastAd = 0;
                    preloadInterstitial(activity);
                }

                @Override
                public void onAdFailedToShowFullScreenContent(
                        @NonNull com.google.android.gms.ads.AdError adError) {
                    interstitialAd = null;
                    preloadInterstitial(activity);
                }
            });
            interstitialAd.show(activity);
        } catch (Throwable t) {
            Log.w(TAG, "show interstitial failed", t);
        }
    }

    /** Dipanggil saat user baru membeli Pro agar ads langsung berhenti tampil di layar. */
    public void clearAds() {
        cancelScheduledInterstitial();
        interstitialAd = null;
        rewardedAd = null;
    }

    // ============================================================
    // Rewarded ad — user tonton 1 ad → unlock Pro temp 24 jam.
    // Cooldown enforcement happens di SettingsDao.canWatchRewardAd();
    // di sini cuma load + show + invoke callback saat user benar-benar
    // earn reward (bukan kalau dia close ad early).
    // ============================================================

    public interface RewardedListener {
        /** Dipanggil saat ad dismissed — granted=true kalau user nonton sampai earn. */
        void onCompleted(boolean granted);

        /** Ad gagal show / load. */
        default void onError(String message) {}
    }

    /** Pre-cache rewarded ad supaya saat ingin di-show tidak delay (~5-15 detik). */
    public void preloadRewarded(@NonNull Context ctx) {
        if (rewardedAd != null || rewardedLoading) return;
        rewardedLoading = true;
        try {
            RewardedAd.load(ctx, BuildConfig.ADMOB_REWARDED_ID,
                    new AdRequest.Builder().build(),
                    new RewardedAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull RewardedAd ad) {
                            rewardedAd = ad;
                            rewardedLoading = false;
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                            rewardedAd = null;
                            rewardedLoading = false;
                            Log.w(TAG, "rewarded failed: " + adError.getMessage());
                        }
                    });
        } catch (Throwable t) {
            rewardedLoading = false;
            Log.w(TAG, "preloadRewarded failed", t);
        }
    }

    /**
     * Tampilkan rewarded ad. Kalau ad belum siap, trigger preload dan
     * panggil listener.onError(). Kalau user nonton sampai earn,
     * listener.onCompleted(true) — kalau dismissed early (skip), false.
     *
     * Listener pasti dipanggil exactly once (kecuali ad system crash).
     */
    public void showRewarded(@NonNull Activity activity, @NonNull RewardedListener listener) {
        if (rewardedAd == null) {
            preloadRewarded(activity);
            listener.onError("Iklan belum siap, coba lagi sebentar.");
            return;
        }
        final boolean[] earned = {false};
        try {
            rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    rewardedAd = null;
                    listener.onCompleted(earned[0]);
                    // Pre-cache for the next prompt
                    preloadRewarded(activity);
                }

                @Override
                public void onAdFailedToShowFullScreenContent(
                        @NonNull com.google.android.gms.ads.AdError adError) {
                    rewardedAd = null;
                    listener.onError("Gagal menampilkan iklan: " + adError.getMessage());
                    preloadRewarded(activity);
                }
            });
            rewardedAd.show(activity, (RewardItem rewardItem) -> {
                // Fired when user has finished watching the ad (or nearly so)
                earned[0] = true;
            });
        } catch (Throwable t) {
            Log.w(TAG, "showRewarded failed", t);
            listener.onError("Tidak dapat memutar iklan.");
        }
    }

    /** True kalau rewarded ad sudah ter-cache, jadi show() langsung. */
    public boolean isRewardedReady() {
        return rewardedAd != null;
    }
}
