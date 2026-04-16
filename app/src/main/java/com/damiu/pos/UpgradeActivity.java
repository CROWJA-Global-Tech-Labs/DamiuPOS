package com.damiu.pos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.billingclient.api.ProductDetails;
import com.damiu.pos.ads.AdManager;
import com.damiu.pos.billing.BillingManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Map;

/**
 * Paywall UI: user pilih salah satu subscription
 *  - Bulanan ({@link BuildConfig#SUB_PRODUCT_MONTHLY})  — Rp 20.000 / bulan
 *  - Tahunan ({@link BuildConfig#SUB_PRODUCT_YEARLY})   — Rp 200.000 / tahun (gratis 2 bulan)
 */
public class UpgradeActivity extends AppCompatActivity implements BillingManager.Listener {

    private MaterialCardView cardMonthly, cardYearly;
    private TextView tvMonthlyPrice, tvYearlyPrice, tvMonthlyTrial, tvProBadge;
    private MaterialButton btnSubscribe, btnManageSubscription;

    private String selectedProductId = BuildConfig.SUB_PRODUCT_YEARLY; // default highlight tahunan
    private BillingManager billing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upgrade);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        cardMonthly = findViewById(R.id.cardMonthly);
        cardYearly = findViewById(R.id.cardYearly);
        tvMonthlyPrice = findViewById(R.id.tvMonthlyPrice);
        tvYearlyPrice = findViewById(R.id.tvYearlyPrice);
        tvMonthlyTrial = findViewById(R.id.tvMonthlyTrial);
        tvProBadge = findViewById(R.id.tvProBadge);
        btnSubscribe = findViewById(R.id.btnSubscribe);
        btnManageSubscription = findViewById(R.id.btnManageSubscription);

        cardMonthly.setOnClickListener(v -> selectPlan(BuildConfig.SUB_PRODUCT_MONTHLY));
        cardYearly.setOnClickListener(v -> selectPlan(BuildConfig.SUB_PRODUCT_YEARLY));
        btnSubscribe.setOnClickListener(v -> launchSubscribe());
        btnManageSubscription.setOnClickListener(v -> openPlayStoreSubscriptions());

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
        boolean pro = billing != null && billing.isPro();
        if (pro) {
            tvProBadge.setText("Status: Pro Aktif");
            tvProBadge.setBackgroundColor(0xFFC8E6C9);
            btnSubscribe.setText("Anda Sudah Pro");
            btnSubscribe.setEnabled(false);
            btnManageSubscription.setVisibility(View.VISIBLE);
        } else {
            tvProBadge.setText("Status: Gratis");
            btnSubscribe.setText("Mulai Berlangganan");
            btnSubscribe.setEnabled(true);
            btnManageSubscription.setVisibility(View.GONE);
        }
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
