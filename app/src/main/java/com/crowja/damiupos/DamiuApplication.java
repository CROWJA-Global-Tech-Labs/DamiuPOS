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
        // Pencatat crash PALING AWAL (sebelum SDK lain) + kirim laporan crash sebelumnya ke server.
        com.crowja.damiupos.util.CrashReporter.install(this);
        com.crowja.damiupos.util.CrashReporter.sendPending(this);

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
            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {
                applyGojekOverlay(a);
            }
            @Override public void onActivityStarted(@NonNull Activity a) {
                // Poll permintaan "Ambil Alih butuh persetujuan" hanya selama app di depan.
                com.crowja.damiupos.util.ClaimApprovalPrompter.onActivityStarted();
            }
            @Override public void onActivityResumed(@NonNull Activity a) {
                currentActivity = new WeakReference<>(a);
                com.crowja.damiupos.util.ClaimApprovalPrompter.onActivityResumed(a);
                // Pembaruan Wajib di layar MANA PUN yang dibuka (hormati Tunda 1 jam).
                com.crowja.damiupos.sync.VersionUpdater.maybePrompt(a);
            }
            @Override public void onActivityPaused(@NonNull Activity a) {
                if (currentActivity.get() == a) currentActivity = new WeakReference<>(null);
            }
            @Override public void onActivityStopped(@NonNull Activity a) {
                com.crowja.damiupos.util.ClaimApprovalPrompter.onActivityStopped();
            }
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {
                com.crowja.damiupos.util.ClaimApprovalPrompter.onActivityDestroyed(a);
            }
        });
    }

    /**
     * "Tampilan baru (gaya Gojek)" untuk layar LAMA: saat {@link com.crowja.damiupos.util.GojekUi#isEnabled}
     * (sesi admin + preferensi aktif) tumpuk ThemeOverlay.DAMIUPOS.Gojek ke tema activity. Callback ini
     * berjalan di dalam super.onCreate() — sebelum setContentView — jadi view, toolbar, dialog, dan
     * status bar ikut. Non-admin: tak ada yang berubah sama sekali.
     *
     * <p>Dilewati: DeepLinkActivity (tanpa UI, tema translucent), PortraitCaptureActivity (zxing, Holo),
     * AdminHomeActivity (sudah bertema penuh Theme.DAMIUPOS.Gojek), dan activity non-AppCompat / milik
     * pustaka (iklan, billing).</p>
     */
    private static void applyGojekOverlay(@NonNull Activity a) {
        try {
            if (a instanceof DeepLinkActivity
                    || a instanceof PortraitCaptureActivity
                    || a instanceof AdminHomeActivity
                    || a instanceof MainActivity) return;
            if (!(a instanceof androidx.appcompat.app.AppCompatActivity)) return;
            if (!a.getClass().getName().startsWith("com.crowja.damiupos.")) return;
            if (!com.crowja.damiupos.util.GojekUi.isEnabled(a)) return;
            com.crowja.damiupos.util.GojekUi.applyOverlay(a);
            // Jaga-jaga: dekor bisa saja sudah membaca android:statusBarColor sebelum overlay.
            a.getWindow().setStatusBarColor(
                    androidx.core.content.ContextCompat.getColor(a, R.color.gj_primary));
        } catch (Throwable ignored) {
            // Tema gagal ditumpuk → layar tetap tampil dengan tema lama.
        }
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
