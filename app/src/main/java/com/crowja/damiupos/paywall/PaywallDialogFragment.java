package com.crowja.damiupos.paywall;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.android.billingclient.api.ProductDetails;
import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.DamiuApplication;
import com.crowja.damiupos.R;
import com.crowja.damiupos.ads.AdManager;
import com.crowja.damiupos.billing.BillingManager;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Map;

/**
 * Modal yang muncul saat user Free hit Pro feature (PDF export, limit
 * customer/transaksi). Kasih 3 jalan:
 *   1. Subscribe tahunan (highlighted)
 *   2. Subscribe bulanan
 *   3. Tonton iklan → Pro temp 24 jam (kalau cooldown OK)
 *
 * Caller pass {@link #ARG_REASON} (mis. "Export PDF") supaya copy modal
 * relevan, dan optional {@link Listener} untuk callback saat user dapat Pro
 * via rewarded ad (caller bisa retry action otomatis).
 */
public class PaywallDialogFragment extends DialogFragment {

    public static final String TAG = "PaywallDialog";
    public static final String ARG_REASON = "reason";

    public interface Listener {
        /** Dipanggil saat Pro temp baru saja aktif (dari rewarded ad). */
        void onProTempActivated();
    }

    private Listener listener;

    public static PaywallDialogFragment show(@NonNull FragmentManager fm,
                                             @NonNull String reason,
                                             @Nullable Listener listener) {
        PaywallDialogFragment dlg = new PaywallDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_REASON, reason);
        dlg.setArguments(args);
        dlg.listener = listener;
        dlg.show(fm, TAG);
        return dlg;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        if (d.getWindow() != null) {
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        }
        return d;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_paywall, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvReason = view.findViewById(R.id.tvPaywallReason);
        MaterialCardView cardYearly = view.findViewById(R.id.cardSubYearly);
        MaterialCardView cardMonthly = view.findViewById(R.id.cardSubMonthly);
        TextView tvYearlyPrice = view.findViewById(R.id.tvSubYearlyPrice);
        TextView tvMonthlyPrice = view.findViewById(R.id.tvSubMonthlyPrice);
        TextView tvMonthlyTrial = view.findViewById(R.id.tvSubMonthlyTrial);
        MaterialButton btnRewardedAd = view.findViewById(R.id.btnRewardedAd);
        TextView tvCooldown = view.findViewById(R.id.tvRewardCooldown);
        MaterialButton btnCancel = view.findViewById(R.id.btnPaywallCancel);

        // Reason copy
        Bundle args = getArguments();
        if (args != null && args.getString(ARG_REASON) != null) {
            tvReason.setText(args.getString(ARG_REASON));
        }

        // Pricing dari BillingManager
        BillingManager billing = DamiuApplication.get().getBillingManager();
        if (billing != null) {
            Map<String, ProductDetails> products = billing.getProductDetails();
            ProductDetails monthly = products.get(BuildConfig.SUB_PRODUCT_MONTHLY);
            ProductDetails yearly = products.get(BuildConfig.SUB_PRODUCT_YEARLY);
            if (monthly != null) {
                tvMonthlyPrice.setText(formatPrice(monthly) + " / bulan");
                tvMonthlyTrial.setVisibility(hasFreeTrial(monthly) ? View.VISIBLE : View.GONE);
            }
            if (yearly != null) {
                tvYearlyPrice.setText(formatPrice(yearly) + " / tahun");
            }
        }

        cardYearly.setOnClickListener(v -> launchSubscribe(BuildConfig.SUB_PRODUCT_YEARLY));
        cardMonthly.setOnClickListener(v -> launchSubscribe(BuildConfig.SUB_PRODUCT_MONTHLY));

        // Rewarded ad — cek cooldown + readiness
        SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(requireContext()));
        boolean canWatch = settings.canWatchRewardAd();
        if (!canWatch) {
            long secs = settings.secondsUntilNextReward();
            String human = formatDuration(secs);
            btnRewardedAd.setEnabled(false);
            btnRewardedAd.setText("🎁 Tonton iklan untuk Pro 24 jam");
            tvCooldown.setText("Tersedia lagi dalam " + human);
            tvCooldown.setTextColor(requireContext().getResources().getColor(R.color.text_secondary));
        } else {
            btnRewardedAd.setEnabled(true);
            btnRewardedAd.setOnClickListener(v -> watchRewardedAd());
        }

        btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());
    }

    private void launchSubscribe(String productId) {
        BillingManager billing = DamiuApplication.get().getBillingManager();
        if (billing == null) {
            Toast.makeText(getContext(), "Billing belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        ProductDetails pd = billing.getProductDetails().get(productId);
        String offerToken = null;
        if (pd != null && pd.getSubscriptionOfferDetails() != null
                && !pd.getSubscriptionOfferDetails().isEmpty()) {
            // Pilih offer dengan free trial kalau ada
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
        billing.launchPurchase(requireActivity(), productId, offerToken);
        dismissAllowingStateLoss();
    }

    private void watchRewardedAd() {
        SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(requireContext()));
        AdManager ads = AdManager.getInstance(requireContext());
        Toast.makeText(getContext(), "Memuat iklan...", Toast.LENGTH_SHORT).show();
        ads.showRewarded(requireActivity(), new AdManager.RewardedListener() {
            @Override
            public void onCompleted(boolean granted) {
                if (!isAdded()) return;
                if (granted) {
                    long now = System.currentTimeMillis();
                    settings.setProTempUntil(now + BuildConfig.REWARD_DURATION_MS);
                    settings.setLastRewardAt(now);
                    Toast.makeText(getContext(),
                            "✓ Pro aktif 24 jam. Terima kasih!",
                            Toast.LENGTH_LONG).show();
                    if (listener != null) listener.onProTempActivated();
                    dismissAllowingStateLoss();
                } else {
                    Toast.makeText(getContext(),
                            "Iklan ditutup sebelum selesai — coba lagi",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String formatPrice(ProductDetails pd) {
        if (pd.getSubscriptionOfferDetails() == null
                || pd.getSubscriptionOfferDetails().isEmpty()) return "-";
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

    private static boolean hasFreeTrial(ProductDetails pd) {
        if (pd.getSubscriptionOfferDetails() == null) return false;
        for (ProductDetails.SubscriptionOfferDetails offer : pd.getSubscriptionOfferDetails()) {
            if (offer.getPricingPhases() == null) continue;
            for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
                if (phase.getPriceAmountMicros() == 0) return true;
            }
        }
        return false;
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
}
