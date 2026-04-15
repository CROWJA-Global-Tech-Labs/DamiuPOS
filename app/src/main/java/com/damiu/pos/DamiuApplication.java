package com.damiu.pos;

import android.app.Application;

import com.damiu.pos.ads.AdManager;
import com.damiu.pos.billing.BillingManager;
import com.google.android.gms.ads.MobileAds;

/**
 * Application class untuk inisialisasi SDK global:
 * - Google Mobile Ads (AdMob)
 * - Google Play Billing (subscription)
 */
public class DamiuApplication extends Application {

    private static DamiuApplication instance;
    private BillingManager billingManager;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Inisialisasi AdMob SDK (async, tidak memblokir startup)
        try {
            MobileAds.initialize(this, initializationStatus -> {
                // no-op; status dapat dicek via initializationStatus
            });
        } catch (Throwable ignored) {
            // Guard terhadap Play Services tidak tersedia
        }

        // Warm-up interstitial cache
        AdManager.getInstance(this).preloadInterstitial(this);

        // Start billing client + query existing subscriptions
        billingManager = new BillingManager(this);
        billingManager.start();
    }

    public static DamiuApplication get() {
        return instance;
    }

    public BillingManager getBillingManager() {
        return billingManager;
    }
}
