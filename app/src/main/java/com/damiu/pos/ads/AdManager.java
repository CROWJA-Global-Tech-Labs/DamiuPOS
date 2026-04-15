package com.damiu.pos.ads;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.damiu.pos.BuildConfig;
import com.damiu.pos.DamiuApplication;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.SettingsDao;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

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
    private static final int INTERSTITIAL_FREQ = 3; // 1x interstitial setiap 3 transaksi

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

    /** Dipanggil saat user baru membeli Pro agar ads langsung berhenti tampil di layar. */
    public void clearAds() {
        interstitialAd = null;
    }
}
