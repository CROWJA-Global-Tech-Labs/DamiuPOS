package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.billingclient.api.ProductDetails;
import com.crowja.damiupos.ads.AdManager;
import com.crowja.damiupos.billing.BillingManager;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Map;

/**
 * Paywall UI: user pilih salah satu subscription
 *  - Bulanan ({@link BuildConfig#SUB_PRODUCT_MONTHLY})  — Rp 20.000 / bulan
 *  - Tahunan ({@link BuildConfig#SUB_PRODUCT_YEARLY})   — Rp 200.000 / tahun (gratis 2 bulan)
 */
public class UpgradeActivity extends AppCompatActivity implements BillingManager.Listener {

    private MaterialCardView cardMonthly, cardYearly, cardRewarded;
    private TextView tvMonthlyPrice, tvYearlyPrice, tvMonthlyTrial, tvProBadge,
            tvRewardedSubtitle, tvRewardedCooldown;
    private MaterialButton btnSubscribe, btnManageSubscription, btnWatchRewarded,
            btnContactSupport;

    private String selectedProductId = BuildConfig.SUB_PRODUCT_YEARLY; // default highlight tahunan
    private BillingManager billing;
    private SettingsDao settingsDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upgrade);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        cardMonthly = findViewById(R.id.cardMonthly);
        cardYearly = findViewById(R.id.cardYearly);
        cardRewarded = findViewById(R.id.cardRewarded);
        tvMonthlyPrice = findViewById(R.id.tvMonthlyPrice);
        tvYearlyPrice = findViewById(R.id.tvYearlyPrice);
        tvMonthlyTrial = findViewById(R.id.tvMonthlyTrial);
        tvProBadge = findViewById(R.id.tvProBadge);
        tvRewardedSubtitle = findViewById(R.id.tvRewardedSubtitle);
        tvRewardedCooldown = findViewById(R.id.tvRewardedCooldown);
        btnSubscribe = findViewById(R.id.btnSubscribe);
        btnManageSubscription = findViewById(R.id.btnManageSubscription);
        btnWatchRewarded = findViewById(R.id.btnWatchRewarded);
        btnContactSupport = findViewById(R.id.btnContactSupport);

        settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));

        cardMonthly.setOnClickListener(v -> selectPlan(BuildConfig.SUB_PRODUCT_MONTHLY));
        cardYearly.setOnClickListener(v -> selectPlan(BuildConfig.SUB_PRODUCT_YEARLY));
        btnSubscribe.setOnClickListener(v -> launchSubscribe());
        btnManageSubscription.setOnClickListener(v -> openPlayStoreSubscriptions());
        btnWatchRewarded.setOnClickListener(v -> watchRewardedAd());
        btnContactSupport.setOnClickListener(v -> openWhatsAppSupport());

        billing = DamiuApplication.get().getBillingManager();
        if (billing != null) {
            billing.addListener(this);
            // Re-query in case user just returned online
            billing.queryExistingPurchases();
            onProductsLoaded(billing.getProductDetails());
        }

        updateProBadge();
        selectPlan(selectedProductId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh in case Pro temp was activated/expired di paywall lain
        updateProBadge();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (billing != null) billing.removeListener(this);
    }

    private void selectPlan(String productId) {
        selectedProductId = productId;
        boolean monthly = BuildConfig.SUB_PRODUCT_MONTHLY.equals(productId);
        cardMonthly.setStrokeWidth(monthly ? dp(2) : dp(1));
        cardMonthly.setStrokeColor(monthly ? getResources().getColor(R.color.primary) : 0xFFCCCCCC);
        cardYearly.setStrokeWidth(!monthly ? dp(2) : dp(1));
        cardYearly.setStrokeColor(!monthly ? getResources().getColor(R.color.primary) : 0xFFCCCCCC);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void updateProBadge() {
        boolean proSub = settingsDao.isProSubscriber();
        boolean proTemp = settingsDao.isProTempActive();

        if (proSub) {
            // Permanent Pro subscriber — hide reward card (no need)
            tvProBadge.setText("Status: Pro Aktif");
            tvProBadge.setBackgroundColor(0xFFC8E6C9);
            btnSubscribe.setText("Anda Sudah Pro");
            btnSubscribe.setEnabled(false);
            btnManageSubscription.setVisibility(View.VISIBLE);
            cardRewarded.setVisibility(View.GONE);
            btnContactSupport.setVisibility(View.VISIBLE);
        } else if (proTemp) {
            // Temp Pro from rewarded ad — encourage upgrade to permanent
            long mins = (settingsDao.getProTempUntil() - System.currentTimeMillis()) / 60_000L;
            String when = mins >= 60
                    ? (mins / 60) + " jam " + (mins % 60) + " menit lagi"
                    : mins + " menit lagi";
            tvProBadge.setText("🎁 Pro Temp aktif (" + when + ")");
            tvProBadge.setBackgroundColor(0xFFFFF59D);
            btnSubscribe.setText("Upgrade ke Pro Permanen");
            btnSubscribe.setEnabled(true);
            btnManageSubscription.setVisibility(View.GONE);
            cardRewarded.setVisibility(View.GONE); // Already temp Pro, can't stack
            btnContactSupport.setVisibility(View.GONE);
        } else {
            tvProBadge.setText("Status: Gratis");
            tvProBadge.setBackgroundColor(0xFFE0F2F1);
            btnSubscribe.setText("Mulai Berlangganan");
            btnSubscribe.setEnabled(true);
            btnManageSubscription.setVisibility(View.GONE);
            cardRewarded.setVisibility(View.VISIBLE);
            btnContactSupport.setVisibility(View.GONE);
            updateRewardedCooldown();
        }
    }

    /** Refresh reward button state based on cooldown. */
    private void updateRewardedCooldown() {
        if (settingsDao.canWatchRewardAd()) {
            btnWatchRewarded.setEnabled(true);
            btnWatchRewarded.setText("Tonton Iklan Sekarang");
            tvRewardedCooldown.setVisibility(View.GONE);
        } else {
            btnWatchRewarded.setEnabled(false);
            btnWatchRewarded.setText("Iklan tersedia lagi nanti");
            long secs = settingsDao.secondsUntilNextReward();
            tvRewardedCooldown.setVisibility(View.VISIBLE);
            tvRewardedCooldown.setText("Bisa tonton lagi dalam " + formatDuration(secs));
        }
    }

    private void watchRewardedAd() {
        if (!settingsDao.canWatchRewardAd()) {
            Toast.makeText(this, "Tunggu cooldown selesai dulu", Toast.LENGTH_SHORT).show();
            updateRewardedCooldown();
            return;
        }
        Toast.makeText(this, "Memuat iklan...", Toast.LENGTH_SHORT).show();
        AdManager.getInstance(this).showRewarded(this, new AdManager.RewardedListener() {
            @Override
            public void onCompleted(boolean granted) {
                if (granted) {
                    long now = System.currentTimeMillis();
                    settingsDao.setProTempUntil(now + BuildConfig.REWARD_DURATION_MS);
                    settingsDao.setLastRewardAt(now);
                    Toast.makeText(UpgradeActivity.this,
                            "✓ Pro aktif 24 jam. Terima kasih!",
                            Toast.LENGTH_LONG).show();
                    AdManager.getInstance(UpgradeActivity.this).clearAds();
                    updateProBadge();
                } else {
                    Toast.makeText(UpgradeActivity.this,
                            "Iklan ditutup sebelum selesai — coba lagi",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(UpgradeActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void openWhatsAppSupport() {
        String waNumber = BuildConfig.PRO_SUPPORT_WA;
        String text = "Halo, saya pengguna DAMIU POS Pro";
        try {
            Uri uri = Uri.parse("https://wa.me/" + waNumber + "?text="
                    + Uri.encode(text));
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatDuration(long secs) {
        if (secs < 60) return secs + " detik";
        long mins = secs / 60;
        if (mins < 60) return mins + " menit";
        long hrs = mins / 60;
        long remMins = mins % 60;
        if (remMins == 0) return hrs + " jam";
        return hrs + " jam " + remMins + " menit";
    }

    private void launchSubscribe() {
        if (billing == null) {
            Toast.makeText(this, "Billing belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        ProductDetails pd = billing.getProductDetails().get(selectedProductId);
        String offerToken = null;
        if (pd != null && pd.getSubscriptionOfferDetails() != null
                && !pd.getSubscriptionOfferDetails().isEmpty()) {
            // Pilih offer dengan free trial (harga pertama = 0) kalau ada, else offer pertama
            for (ProductDetails.SubscriptionOfferDetails offer : pd.getSubscriptionOfferDetails()) {
                if (offer.getPricingPhases() != null
                        && !offer.getPricingPhases().getPricingPhaseList().isEmpty()) {
                    double firstPrice = offer.getPricingPhases().getPricingPhaseList()
                            .get(0).getPriceAmountMicros() / 1_000_000.0;
                    if (firstPrice == 0) {
                        offerToken = offer.getOfferToken();
                        break;
                    }
                }
            }
            if (offerToken == null) {
                offerToken = pd.getSubscriptionOfferDetails().get(0).getOfferToken();
            }
        }
        billing.launchPurchase(this, selectedProductId, offerToken);
    }

    private void openPlayStoreSubscriptions() {
        try {
            Uri uri = Uri.parse("https://play.google.com/store/account/subscriptions?sku="
                    + selectedProductId + "&package=" + getPackageName());
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka Play Store", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- BillingManager.Listener ----------------

    @Override
    public void onProStatusChanged(boolean isPro) {
        runOnUiThread(() -> {
            updateProBadge();
            if (isPro) {
                AdManager.getInstance(this).clearAds();
                Toast.makeText(this, "Terima kasih! Pro sudah aktif.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onProductsLoaded(Map<String, ProductDetails> products) {
        runOnUiThread(() -> {
            ProductDetails monthly = products.get(BuildConfig.SUB_PRODUCT_MONTHLY);
            ProductDetails yearly = products.get(BuildConfig.SUB_PRODUCT_YEARLY);
            if (monthly != null) {
                tvMonthlyPrice.setText(formatPrice(monthly) + " / bulan");
                tvMonthlyTrial.setVisibility(hasFreeTrial(monthly) ? View.VISIBLE : View.GONE);
            }
            if (yearly != null) {
                tvYearlyPrice.setText(formatPrice(yearly) + " / tahun");
            }
        });
    }

    @Override
    public void onPurchaseError(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private String formatPrice(ProductDetails pd) {
        if (pd.getSubscriptionOfferDetails() == null
                || pd.getSubscriptionOfferDetails().isEmpty()) return "-";
        // Ambil phase dengan harga > 0 (skip free trial)
        for (ProductDetails.SubscriptionOfferDetails offer : pd.getSubscriptionOfferDetails()) {
            if (offer.getPricingPhases() == null) continue;
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() > 0) {
                    return phase.getFormattedPrice();
                }
            }
        }
        return "-";
    }

    private boolean hasFreeTrial(ProductDetails pd) {
        if (pd.getSubscriptionOfferDetails() == null) return false;
        for (ProductDetails.SubscriptionOfferDetails offer : pd.getSubscriptionOfferDetails()) {
            if (offer.getPricingPhases() == null) continue;
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() == 0) return true;
            }
        }
        return false;
    }
}
