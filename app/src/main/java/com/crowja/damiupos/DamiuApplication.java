package com.crowja.damiupos;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.crowja.damiupos.ads.AdManager;
import com.crowja.damiupos.billing.BillingManager;
import com.google.android.gms.ads.MobileAds;

import java.lang.ref.WeakReference;

/**
 * Application class untuk inisialisasi SDK global:
 * - Google Mobile Ads (AdMob)
 * - Google Play Billing (subscription)
 */
public class DamiuApplication extends Application {

    private static DamiuApplication instance;
    private BillingManager billingManager;
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Bypass Pro mengikuti BuildConfig.BYPASS_PRO (set di app/build.gradle).
        // Tidak perlu setBypassPro() di sini lagi.

        // Inisialisasi AdMob SDK (async, tidak memblokir startup)
        try {
            MobileAds.initialize(this, initializationStatus -> {
                // no-op; status dapat dicek via initializationStatus
            });
        } catch (Throwable ignored) {
            // Guard terhadap Play Services tidak tersedia
        }

        // Warm-up interstitial + rewarded cache
        AdManager.getInstance(this).preloadInterstitial(this);
        AdManager.getInstance(this).preloadRewarded(this);

        // Start billing client + query existing subscriptions
        billingManager = new BillingManager(this);
        billingManager.start();

        // Lacak activity foreground supaya AdManager bisa menampilkan
        // interstitial di activity manapun yang sedang aktif (tidak tergantung
        // activity yang menjadwalkan iklan).
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityResumed(@NonNull Activity a) {
                currentActivity = new WeakReference<>(a);
            }
            @Override public void onActivityPaused(@NonNull Activity a) {
                if (currentActivity.get() == a) currentActivity = new WeakReference<>(null);
            }
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
        });
    }

    /** Activity yang sedang aktif di foreground, atau null bila app di-background. */
    @Nullable
    public Activity getCurrentActivity() {
        return currentActivity.get();
    }

    public static DamiuApplication get() {
        return instance;
    }

    public BillingManager getBillingManager() {
        return billingManager;
    }
}
