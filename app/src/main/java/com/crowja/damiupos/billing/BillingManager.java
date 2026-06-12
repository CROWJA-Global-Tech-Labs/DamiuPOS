package com.crowja.damiupos.billing;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import com.android.billingclient.api.Purchase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wrapper di atas Google Play Billing Library untuk 2 SKU subscription:
 *  - {@link BuildConfig#SUB_PRODUCT_MONTHLY}  (Rp 20.000 / bulan)
 *  - {@link BuildConfig#SUB_PRODUCT_YEARLY}   (Rp 200.000 / tahun, bayar 10 bulan gratis 2 bulan)
 *
 * Cara pakai:
 *  - Di Application.onCreate(): {@code new BillingManager(ctx).start()}
 *  - Di UpgradeActivity: ambil instance dari {@link com.crowja.damiupos.DamiuApplication},
 *    panggil {@link #launchPurchase(Activity, String, String)}.
 *  - Subscribe ke {@link Listener} untuk notifikasi status Pro berubah.
 */
public class BillingManager implements PurchasesUpdatedListener, BillingClientStateListener {

    private static final String TAG = "BillingManager";

    public interface Listener {
        void onProStatusChanged(boolean isPro);
        void onProductsLoaded(Map<String, ProductDetails> products);
        void onPurchaseError(String message);
    }

    private final Context appContext;
    private final SettingsDao settingsDao;
    private final BillingClient billingClient;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ProductDetails> productDetailsMap = new HashMap<>();

    private boolean connected = false;

    public BillingManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.settingsDao = new SettingsDao(DatabaseHelper.getInstance(appContext));
        this.billingClient = BillingClient.newBuilder(appContext)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build())
                .build();
    }

    public void start() {
        if (!billingClient.isReady()) {
            try {
                billingClient.startConnection(this);
            } catch (Exception e) {
                Log.w(TAG, "startConnection failed", e);
            }
        }
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Apakah user sedang aktif Pro (cache lokal). */
    public boolean isPro() {
        return settingsDao.isProActive();
    }

    public Map<String, ProductDetails> getProductDetails() {
        return Collections.unmodifiableMap(productDetailsMap);
    }

    // ---------------- BillingClientStateListener ----------------

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            connected = true;
            querySubscriptionProducts();
            queryExistingPurchases();
        } else {
            Log.w(TAG, "Billing setup failed: " + billingResult.getDebugMessage());
        }
    }

    @Override
    public void onBillingServiceDisconnected() {
        connected = false;
        // Reconnect lazily saat next launchPurchase / queryExistingPurchases
    }

    // ---------------- Product query ----------------

    private void querySubscriptionProducts() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BuildConfig.SUB_PRODUCT_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BuildConfig.SUB_PRODUCT_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && productDetailsList != null) {
                productDetailsMap.clear();
                for (ProductDetails pd : productDetailsList) {
                    productDetailsMap.put(pd.getProductId(), pd);
                }
                for (Listener l : listeners) l.onProductsLoaded(productDetailsMap);
            } else {
                Log.w(TAG, "queryProductDetails failed: " + billingResult.getDebugMessage());
            }
        });
    }

    /** Query semua subscription aktif milik user — dipanggil saat app start & saat perlu re-verify. */
    public void queryExistingPurchases() {
        if (!connected) {
            start();
            return;
        }
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();
        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            boolean anyActive = false;
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && purchases != null) {
                for (Purchase p : purchases) {
                    if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        anyActive = true;
                        handlePurchase(p);
                    }
                }
            }
            setProActive(anyActive);
        });
    }

    // ---------------- Purchase flow ----------------

    /**
     * Launch billing flow untuk subscription tertentu.
     * @param productId salah satu BuildConfig.SUB_PRODUCT_*
     * @param offerToken offer token dari ProductDetails (pilih offer yang diinginkan, mis. free trial)
     */
    public void launchPurchase(Activity activity, String productId, String offerToken) {
        ProductDetails pd = productDetailsMap.get(productId);
        if (pd == null) {
            notifyError("Produk belum siap, coba lagi sebentar.");
            return;
        }
        if (offerToken == null) {
            // Fallback: pakai offer pertama yang ada
            List<ProductDetails.SubscriptionOfferDetails> offers = pd.getSubscriptionOfferDetails();
            if (offers == null || offers.isEmpty()) {
                notifyError("Penawaran subscription tidak tersedia.");
                return;
            }
            offerToken = offers.get(0).getOfferToken();
        }

        BillingFlowParams.ProductDetailsParams pdp = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(pd)
                .setOfferToken(offerToken)
                .build();

        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(pdp))
                .build();

        BillingResult result = billingClient.launchBillingFlow(activity, flowParams);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            notifyError("Gagal memulai pembelian: " + result.getDebugMessage());
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult,
                                   List<Purchase> purchases) {
        int code = billingResult.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) handlePurchase(p);
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyError("Pembelian dibatalkan.");
        } else {
            notifyError("Error: " + billingResult.getDebugMessage());
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;

        // WAJIB acknowledge dalam 3 hari, kalau tidak refund otomatis
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            billingClient.acknowledgePurchase(ackParams, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    persistProPurchase(purchase);
                }
            });
        } else {
            persistProPurchase(purchase);
        }
    }

    /**
     * Simpan token + product id + estimasi expiry ke SettingsDao, lalu set Pro aktif.
     * Expiry dihitung dari {@link Purchase#getPurchaseTime()} + durasi siklus:
     *  - monthly: +30 hari
     *  - yearly : +365 hari
     * Ini estimasi UI-only; sumber kebenaran tetap Google Play saat re-query.
     */
    private void persistProPurchase(Purchase purchase) {
        String productId = purchase.getProducts() != null && !purchase.getProducts().isEmpty()
                ? purchase.getProducts().get(0) : "";
        long purchaseTime = purchase.getPurchaseTime();
        long expiry = 0L;
        if (BuildConfig.SUB_PRODUCT_MONTHLY.equals(productId)) {
            expiry = purchaseTime + 30L * 24L * 60L * 60L * 1000L;
        } else if (BuildConfig.SUB_PRODUCT_YEARLY.equals(productId)) {
            expiry = purchaseTime + 365L * 24L * 60L * 60L * 1000L;
        }
        settingsDao.setProPurchaseToken(purchase.getPurchaseToken());
        settingsDao.setProProductId(productId);
        settingsDao.setProExpiryAt(expiry);
        setProActive(true);
    }

    private void setProActive(boolean active) {
        boolean prev = settingsDao.isProActive();
        settingsDao.setProActive(active);
        if (!active) {
            // Bersihkan cache expiry/product biar About dialog tidak menampilkan tanggal stale
            settingsDao.setProExpiryAt(0L);
            settingsDao.setProProductId("");
        }
        boolean next = settingsDao.isProActive();
        if (prev != next) {
            for (Listener l : listeners) l.onProStatusChanged(next);
        }
    }

    private void notifyError(String msg) {
        for (Listener l : listeners) l.onPurchaseError(msg);
    }
}
