/* Copyright (c) 2021 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.billing;

import android.app.Activity;
import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.InAppMessageParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.chromium.base.ApplicationState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.brave_leo.BraveLeoPrefUtils;
import org.chromium.chrome.browser.brave_leo.BraveLeoUtils;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.util.LiveDataUtil;
import org.chromium.ui.widget.Toast;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@NullMarked
public class InAppPurchaseWrapper {
    public static final String MANAGE_SUBSCRIPTION_PAGE =
            "https://play.google.com/store/account/subscriptions";
    private static final String TAG = "InAppPurchaseWrapper";
    // Brave Leo AI Chat subscription product IDs
    private static final String LEO_MONTHLY_SUBSCRIPTION = "brave.leo.monthly";
    private static final String LEO_YEARLY_SUBSCRIPTION = "brave.leo.yearly";

    private @Nullable BillingClient mBillingClient;

    private static final long MICRO_UNITS =
            1000000; // 1,000,000 micro-units equal one unit of the currency

    private static volatile @Nullable InAppPurchaseWrapper sInAppPurchaseWrapper;
    private static final Object sMutex = new Object();

    public enum SubscriptionType {
        MONTHLY,
        YEARLY
    }

    /**
     * Enumeration of available subscription products in Brave browser. Each product represents a
     * different premium service offering.
     */
    public enum SubscriptionProduct {
        LEO // Brave Leo AI Chat premium features
    }

    // Leo monthly subscription product details
    private final MutableLiveData<ProductDetails> mMutableMonthlyProductDetailsLeo =
            new MutableLiveData<>();
    private final LiveData<ProductDetails> mMonthlyProductDetailsLeo =
            mMutableMonthlyProductDetailsLeo;

    /**
     * Sets the monthly subscription product details for the specified subscription product. Updates
     * the appropriate LiveData based on the product type.
     *
     * @param productDetails The Google Play Billing product details
     * @param product The subscription product type (LEO)
     */
    private void setMonthlyProductDetails(
            @Nullable ProductDetails productDetails, SubscriptionProduct product) {
        if (product.equals(SubscriptionProduct.LEO)) {
            mMutableMonthlyProductDetailsLeo.postValue(productDetails);
        }
    }

    /**
     * Gets the LiveData containing monthly subscription product details for the specified product.
     *
     * @param product The subscription product type (LEO)
     * @return LiveData containing the product details, for the requested product
     */
    public LiveData<ProductDetails> getMonthlyProductDetails(SubscriptionProduct product) {
        if (product.equals(SubscriptionProduct.LEO)) {
            return mMonthlyProductDetailsLeo;
        }
        throw new IllegalArgumentException("Product has no monthly subscription");
    }

    // Leo yearly subscription product details
    private final MutableLiveData<ProductDetails> mMutableYearlyProductDetailsLeo =
            new MutableLiveData<>();
    private final LiveData<ProductDetails> mYearlyProductDetailsLeo =
            mMutableYearlyProductDetailsLeo;

    private void setYearlyProductDetails(
            @Nullable ProductDetails productDetails, SubscriptionProduct product) {
        if (product.equals(SubscriptionProduct.LEO)) {
            mMutableYearlyProductDetailsLeo.postValue(productDetails);
        }
    }

    public LiveData<ProductDetails> getYearlyProductDetails(SubscriptionProduct product) {
        if (product.equals(SubscriptionProduct.LEO)) {
            return mYearlyProductDetailsLeo;
        }
        throw new IllegalArgumentException("Product has no yearly subscription");
    }

    private boolean mSuppressToasts;
    private boolean mInAppMessagesShownThisForeground;
    private boolean mAppStateListenerRegistered;

    private InAppPurchaseWrapper() {}

    public static InAppPurchaseWrapper getInstance() {
        InAppPurchaseWrapper result = sInAppPurchaseWrapper;
        if (result == null) {
            synchronized (sMutex) {
                result = sInAppPurchaseWrapper;
                if (result == null) sInAppPurchaseWrapper = result = new InAppPurchaseWrapper();
            }
        }
        return result;
    }

    private void startBillingServiceConnection(
            @Nullable MutableLiveData<Boolean> billingClientConnectionState) {
        Context context = ContextUtils.getApplicationContext();
        // End existing connection if any before we start another connection
        endConnection();

        mBillingClient =
                BillingClient.newBuilder(context)
                        .enablePendingPurchases(
                                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                        .setListener(getPurchasesUpdatedListener(context))
                        .build();
        if (!mBillingClient.isReady()) {
            try {
                mBillingClient.startConnection(
                        new BillingClientStateListener() {
                            @Override
                            public void onBillingServiceDisconnected() {
                                retryBillingServiceConnection(billingClientConnectionState);
                            }

                            @Override
                            public void onBillingSetupFinished(BillingResult billingResult) {
                                if (billingResult.getResponseCode()
                                        == BillingClient.BillingResponseCode.OK) {
                                    if (billingClientConnectionState != null) {
                                        billingClientConnectionState.postValue(true);
                                    }
                                } else {
                                    showToast(billingResult.getDebugMessage());
                                    retryBillingServiceConnection(billingClientConnectionState);
                                }
                            }
                        });
            } catch (IllegalStateException exc) {
                // That prevents a crash that some users experience
                // https://github.com/brave/brave-browser/issues/27751.
                // It's unknown what causes it, we tried to add retries, but it
                // didn't help.
                Log.e(TAG, "startBillingServiceConnection " + exc.getMessage());
            }
        } else {
            if (billingClientConnectionState != null) {
                billingClientConnectionState.postValue(mBillingClient.getConnectionState()
                        == BillingClient.ConnectionState.CONNECTED);
            }
        }
    }

    private void endConnection() {
        if (mBillingClient != null) {
            mBillingClient.endConnection();
            mBillingClient = null;
        }
    }

    public String getProductId(SubscriptionProduct product, SubscriptionType subscriptionType) {
        if (product.equals(SubscriptionProduct.LEO)) {
            return subscriptionType == SubscriptionType.MONTHLY
                    ? LEO_MONTHLY_SUBSCRIPTION
                    : LEO_YEARLY_SUBSCRIPTION;
        } else {
            assert false;
            return "";
        }
    }

    public void queryProductDetailsAsync(SubscriptionProduct product) {
        Map<String, ProductDetails> productDetails = new HashMap<>();
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(getProductId(product, SubscriptionType.MONTHLY))
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build());
        products.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(getProductId(product, SubscriptionType.YEARLY))
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build());
        QueryProductDetailsParams queryProductDetailsParams =
                QueryProductDetailsParams.newBuilder().setProductList(products).build();

        MutableLiveData<Boolean> _billingConnectionState = new MutableLiveData<>();
        LiveData<Boolean> billingConnectionState = _billingConnectionState;
        startBillingServiceConnection(_billingConnectionState);
        LiveDataUtil.observeOnce(
                billingConnectionState,
                isConnected -> {
                    if (isConnected && mBillingClient != null) {
                        mBillingClient.queryProductDetailsAsync(
                                queryProductDetailsParams,
                                (billingResult, queryProductDetailsResult) -> {
                                    // End connection after getting the product details
                                    endConnection();

                                    if (billingResult.getResponseCode()
                                            == BillingClient.BillingResponseCode.OK) {
                                        for (ProductDetails productDetail :
                                                queryProductDetailsResult.getProductDetailsList()) {
                                            productDetails.put(
                                                    productDetail.getProductId(), productDetail);
                                        }
                                        setMonthlyProductDetails(
                                                productDetails.get(
                                                        getProductId(
                                                                product, SubscriptionType.MONTHLY)),
                                                product);
                                        setYearlyProductDetails(
                                                productDetails.get(
                                                        getProductId(
                                                                product, SubscriptionType.YEARLY)),
                                                product);
                                    } else {
                                        Log.e(
                                                TAG,
                                                "queryProductDetailsAsync failed"
                                                        + billingResult.getDebugMessage());
                                        showToast(billingResult.getDebugMessage());
                                    }
                                });
                    }
                });
    }

    public void queryPurchases(
            MutableLiveData<PurchaseModel> mutableActivePurchases, SubscriptionProduct type) {
        String productType = BillingClient.ProductType.SUBS;
        MutableLiveData<Boolean> _billingConnectionState = new MutableLiveData<>();
        LiveData<Boolean> billingConnectionState = _billingConnectionState;
        startBillingServiceConnection(_billingConnectionState);
        LiveDataUtil.observeOnce(
                billingConnectionState,
                isConnected -> {
                    if (isConnected && mBillingClient != null) {
                        mBillingClient.queryPurchasesAsync(
                                QueryPurchasesParams.newBuilder()
                                        .setProductType(productType)
                                        .build(),
                                (billingResult, purchases) -> {
                                    // End connection after getting purchases
                                    endConnection();

                                    PurchaseModel activePurchaseModel = null;
                                    if (billingResult.getResponseCode()
                                            == BillingClient.BillingResponseCode.OK) {
                                        for (Purchase purchase : purchases) {
                                            if (purchase.getPurchaseState()
                                                    != Purchase.PurchaseState.PURCHASED) {
                                                continue;
                                            }
                                            List<String> productIds = purchase.getProducts();
                                            boolean isLeoProduct = isLeoProduct(productIds);
                                            if (isLeoProduct
                                                    && type.equals(SubscriptionProduct.LEO)) {
                                                activePurchaseModel =
                                                        new PurchaseModel(
                                                                purchase.getPurchaseToken(),
                                                                purchase.getProducts()
                                                                        .get(0)
                                                                        .toString(),
                                                                purchase);
                                                break;
                                            }
                                        }
                                    } else {
                                        Log.e(
                                                TAG,
                                                "queryPurchases failed"
                                                        + billingResult.getDebugMessage());
                                        showToast(billingResult.getDebugMessage());
                                    }
                                    mutableActivePurchases.postValue(activePurchaseModel);
                                });
                    } else {
                        mutableActivePurchases.postValue(null);
                    }
                });
    }

    public void initiatePurchase(Activity activity, ProductDetails productDetails) {
        List<ProductDetails.SubscriptionOfferDetails> offerDetails =
                productDetails.getSubscriptionOfferDetails();
        if (offerDetails == null || offerDetails.isEmpty()) {
            Log.e(TAG, "No subscription offer details available");
            return;
        }
        String offerToken = offerDetails.get(0).getOfferToken();
        List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();
        productDetailsParamsList.add(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build());

        BillingFlowParams billingFlowParams =
                BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build();
        MutableLiveData<Boolean> _billingConnectionState = new MutableLiveData<>();
        LiveData<Boolean> billingConnectionState = _billingConnectionState;
        startBillingServiceConnection(_billingConnectionState);
        LiveDataUtil.observeOnce(
                billingConnectionState,
                isConnected -> {
                    if (isConnected && mBillingClient != null) {
                        BillingResult unused_billingResult =
                                mBillingClient.launchBillingFlow(activity, billingFlowParams);
                    }
                });
    }

    public void processPurchases(Context context, Purchase activePurchase) {
        acknowledgePurchase(context, activePurchase);
    }

    private void acknowledgePurchase(Context context, Purchase purchase) {
        AcknowledgePurchaseParams acknowledgePurchaseParams =
                AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();
        List<String> productIds = purchase.getProducts();
        boolean isLeoProduct = isLeoProduct(productIds);
        if (!isLeoProduct) return;
        if (!purchase.isAcknowledged()) {
            MutableLiveData<Boolean> _billingConnectionState = new MutableLiveData<>();
            LiveData<Boolean> billingConnectionState = _billingConnectionState;
            startBillingServiceConnection(_billingConnectionState);
            LiveDataUtil.observeOnce(
                    billingConnectionState,
                    isConnected -> {
                        if (isConnected && mBillingClient != null) {
                            mBillingClient.acknowledgePurchase(
                                    acknowledgePurchaseParams,
                                    billingResult -> {
                                        // End connection after getting the response of the purchase
                                        // acknowledgment
                                        endConnection();
                                        if (billingResult.getResponseCode()
                                                == BillingClient.BillingResponseCode.OK) {
                                            receiptAcknowledged(context, purchase);
                                        } else {
                                            showToast(
                                                    context.getResources()
                                                            .getString(
                                                                    R.string.fail_to_aknowledge));
                                        }
                                    });
                        }
                    });
        } else {
            receiptAcknowledged(context, purchase);
        }
    }

    private void receiptAcknowledged(Context context, Purchase purchase) {
        BraveActivity activity = null;
        try {
            activity = BraveActivity.getBraveActivity();
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "acknowledgePurchase " + e.getMessage());
        }
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            BraveLeoPrefUtils.setIsSubscriptionActive(true);
                            BraveLeoPrefUtils.setChatPackageName();
                            BraveLeoPrefUtils.setChatProductId(
                                    purchase.getProducts().get(0).toString());
                            BraveLeoPrefUtils.setChatPurchaseToken(purchase.getPurchaseToken());
                            BraveLeoUtils.bringMainActivityOnTop();
                        }
                    });
        }
        showToast(context.getResources().getString(R.string.subscription_consumed));
    }

    private boolean isLeoProduct(List<String> productIds) {
        return productIds.contains(LEO_MONTHLY_SUBSCRIPTION)
                || productIds.contains(LEO_YEARLY_SUBSCRIPTION);
    }

    /**
     * Asks Play to surface any pending in-app messages for the user's subscriptions (failed
     * renewals, opt-in price-rise consent). Gated on the user having an active Play subscription to
     * Leo, and throttled to one call per foreground session. The throttle is reset
     * automatically when the app is fully backgrounded; callers do not need to manage it.
     */
    public void maybeShowSubscriptionInAppMessages(Activity activity, Profile profile) {
        if (mInAppMessagesShownThisForeground) {
            return;
        }
        boolean hasLeoSubscription = BraveLeoPrefUtils.getIsSubscriptionActive(profile);
        if (!hasLeoSubscription) {
            return;
        }
        // Register the throttle-reset listener lazily, only after confirming
        // this user actually has a subscription. Non-subscribers never pay any
        // listener cost.
        ensureAppStateListenerRegistered();
        mInAppMessagesShownThisForeground = true;
        showInAppMessages(activity);
    }

    private void ensureAppStateListenerRegistered() {
        if (mAppStateListenerRegistered) {
            return;
        }
        mAppStateListenerRegistered = true;
        // Clear the per-foreground throttle only when *all* activities are
        // stopped, i.e. the app is truly backgrounded. In-app navigation
        // (BraveActivity -> Settings -> back) stays in HAS_RUNNING_ACTIVITIES
        // and must not re-trigger a Play call.
        ApplicationStatus.registerApplicationStateListener(
                state -> {
                    if ((state == ApplicationState.HAS_STOPPED_ACTIVITIES
                                    || state == ApplicationState.HAS_DESTROYED_ACTIVITIES)
                            && mInAppMessagesShownThisForeground) {
                        mInAppMessagesShownThisForeground = false;
                    }
                });
    }

    /**
     * Surfaces Play in-app messages for subscription-related events: declined payment methods
     * (account hold / grace period) and opt-in price-rise consent. Play renders any pending message
     * as a dialog above {@code activity}; if there is nothing to show the callback returns
     * NO_ACTION_NEEDED and the user sees nothing. Only SUBS products are eligible — Origin (INAPP)
     * is excluded by Play.
     */
    private void showInAppMessages(Activity activity) {
        InAppMessageParams params =
                InAppMessageParams.newBuilder()
                        .addInAppMessageCategoryToShow(
                                InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
                        .build();

        MutableLiveData<Boolean> _billingConnectionState = new MutableLiveData<>();
        LiveData<Boolean> billingConnectionState = _billingConnectionState;
        startBillingServiceConnection(_billingConnectionState);
        LiveDataUtil.observeOnce(
                billingConnectionState,
                isConnected -> {
                    if (isConnected && mBillingClient != null) {
                        mBillingClient.showInAppMessages(
                                activity, params, result -> endConnection());
                    } else {
                        endConnection();
                    }
                });
    }

    private PurchasesUpdatedListener getPurchasesUpdatedListener(Context context) {
        return (billingResult, purchases) -> {
            endConnection();
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                if (purchases != null) {
                    for (Purchase purchase : purchases) {
                        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            processPurchases(context, purchase);
                        }
                    }
                }
            } else if (billingResult.getResponseCode()
                    == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                showToast(context.getResources().getString(R.string.already_subscribed));
            } else if (billingResult.getResponseCode()
                    == BillingClient.BillingResponseCode.USER_CANCELED) {
                showToast(context.getResources().getString(R.string.error_caused_by_user));
            } else {
                showToast(context.getResources().getString(R.string.purchased_failed));
            }
        };
    }

    private int mMaxTries;
    private int mTries;
    private boolean mIsConnectionEstablished;

    private void retryBillingServiceConnection(
            @Nullable MutableLiveData<Boolean> billingClientConnectionState) {
        mMaxTries = 3;
        mTries = 1;
        mIsConnectionEstablished = false;
        do {
            try {
                // End existing connection if any before we start another connection
                endConnection();

                Context context = ContextUtils.getApplicationContext();

                mBillingClient =
                        BillingClient.newBuilder(context)
                                .enablePendingPurchases(
                                        PendingPurchasesParams.newBuilder()
                                                .enableOneTimeProducts()
                                                .build())
                                .setListener(getPurchasesUpdatedListener(context))
                                .build();

                mBillingClient.startConnection(
                        new BillingClientStateListener() {
                            @Override
                            public void onBillingServiceDisconnected() {
                                if (mTries == mMaxTries && billingClientConnectionState != null) {
                                    billingClientConnectionState.postValue(false);
                                }
                            }

                            @Override
                            public void onBillingSetupFinished(BillingResult billingResult) {
                                if (billingResult.getResponseCode()
                                        == BillingClient.BillingResponseCode.OK) {
                                    mIsConnectionEstablished = true;
                                    if (billingClientConnectionState != null) {
                                        billingClientConnectionState.postValue(true);
                                    }
                                } else {
                                    showToast(billingResult.getDebugMessage());
                                }
                            }
                        });
            } catch (Exception ex) {
                Log.e(TAG, "retryBillingServiceConnection " + ex.getMessage());
            } finally {
                mTries++;
            }
        } while (mTries <= mMaxTries && !mIsConnectionEstablished);
    }

    private ProductDetails.@Nullable PricingPhase getPricingPhase(ProductDetails productDetails) {
        if (productDetails.getSubscriptionOfferDetails() != null) {
            for (ProductDetails.SubscriptionOfferDetails subscriptionOfferDetails :
                    productDetails.getSubscriptionOfferDetails()) {
                if (subscriptionOfferDetails.getOfferId() == null) {
                    for (ProductDetails.PricingPhase pricingPhase :
                            subscriptionOfferDetails.getPricingPhases().getPricingPhaseList()) {
                        if (pricingPhase.getPriceAmountMicros() > 0) {
                            return pricingPhase;
                        }
                    }
                }
            }
        }
        return null;
    }

    public @Nullable SpannableString getFormattedProductPrice(
            Context context, ProductDetails productDetails, int stringRes) {
        ProductDetails.PricingPhase pricingPhase = getPricingPhase(productDetails);
        if (pricingPhase != null) {
            double price = ((double) pricingPhase.getPriceAmountMicros() / MICRO_UNITS);
            String priceString = String.format(Locale.getDefault(), "%.2f", price);
            String currencySymbol =
                    Currency.getInstance(pricingPhase.getPriceCurrencyCode()).getSymbol();
            String priceWithSymbol = currencySymbol + priceString;
            String finalPrice =
                    context.getResources()
                            .getString(
                                    stringRes,
                                    pricingPhase.getPriceCurrencyCode() + " " + priceWithSymbol);
            SpannableString priceSpannable = new SpannableString(finalPrice);
            int index = finalPrice.indexOf(priceWithSymbol);
            priceSpannable.setSpan(
                    new TextAppearanceSpan(context, R.style.LargeSemibold),
                    index,
                    index + priceWithSymbol.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            priceSpannable.setSpan(
                    new ForegroundColorSpan(context.getColor(android.R.color.white)),
                    index,
                    index + priceWithSymbol.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return priceSpannable;
        }
        return null;
    }

    public @Nullable SpannableString getFormattedFullProductPrice(
            Context context, ProductDetails productDetails) {
        ProductDetails.PricingPhase pricingPhase = getPricingPhase(productDetails);
        if (pricingPhase != null) {
            double yearlyPrice = ((double) pricingPhase.getPriceAmountMicros() / MICRO_UNITS) * 12;
            String priceString = String.format(Locale.getDefault(), "%.2f", yearlyPrice);
            String currencySymbol =
                    Currency.getInstance(pricingPhase.getPriceCurrencyCode()).getSymbol();
            String priceWithSymbol = currencySymbol + priceString;
            String finalPrice = pricingPhase.getPriceCurrencyCode() + " " + priceWithSymbol;
            SpannableString priceSpannable = new SpannableString(finalPrice);
            int index = finalPrice.indexOf(priceWithSymbol);
            priceSpannable.setSpan(
                    new TextAppearanceSpan(context, R.style.LargeSemibold),
                    index,
                    index + priceWithSymbol.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            priceSpannable.setSpan(
                    new ForegroundColorSpan(context.getColor(android.R.color.white)),
                    index,
                    index + priceWithSymbol.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return priceSpannable;
        }
        return null;
    }

    public int getYearlyDiscountPercentage(
            ProductDetails monthlyProductDetails, ProductDetails yearlyProductDetails) {
        ProductDetails.PricingPhase monthlyPricingPhase = getPricingPhase(monthlyProductDetails);
        ProductDetails.PricingPhase yearlyPricingPhase = getPricingPhase(yearlyProductDetails);
        if (monthlyPricingPhase != null && yearlyPricingPhase != null) {
            double yearlyPrice =
                    ((double) monthlyPricingPhase.getPriceAmountMicros() / MICRO_UNITS) * 12;
            double yearlyActualPrice =
                    ((double) yearlyPricingPhase.getPriceAmountMicros() / MICRO_UNITS);
            if (yearlyPrice == 0) {
                return 0;
            }
            double discountPrice = yearlyPrice - yearlyActualPrice;
            double discountPercentage = (discountPrice / yearlyPrice) * 100;
            return (int) Math.round(discountPercentage);
        }
        return 0;
    }

    /** Returns the formatted price string for a one-time purchase (INAPP) product. */
    public @Nullable String getFormattedOneTimePurchasePrice(ProductDetails productDetails) {
        ProductDetails.OneTimePurchaseOfferDetails offerDetails =
                productDetails.getOneTimePurchaseOfferDetails();
        if (offerDetails != null) {
            return offerDetails.getFormattedPrice();
        }
        return null;
    }

    /** Returns the currency code for a one-time purchase product (e.g. "USD"). */
    public @Nullable String getOneTimePurchaseCurrencyCode(ProductDetails productDetails) {
        ProductDetails.OneTimePurchaseOfferDetails offerDetails =
                productDetails.getOneTimePurchaseOfferDetails();
        if (offerDetails != null) {
            return offerDetails.getPriceCurrencyCode();
        }
        return null;
    }

    public void setSuppressToasts(boolean suppress) {
        mSuppressToasts = suppress;
    }

    private void showToast(String message) {
        if (mSuppressToasts) return;
        Context context = ContextUtils.getApplicationContext();
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
