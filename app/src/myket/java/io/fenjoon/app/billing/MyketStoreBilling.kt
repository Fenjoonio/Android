package io.fenjoon.app.billing

import androidx.activity.ComponentActivity
import io.fenjoon.app.BuildConfig
import ir.myket.billingclient.IabHelper
import ir.myket.billingclient.util.IabResult
import ir.myket.billingclient.util.Purchase

/** Myket in-app billing through Myket's billing client (an in-app billing v3 helper). */
internal class MyketStoreBilling(private val activity: ComponentActivity) : StoreBilling {

    private var helper: IabHelper? = null
    private var isSetUp = false
    private var isSettingUp = false
    private val setupWaiters = mutableListOf<(Boolean) -> Unit>()

    /** Purchases seen in this session, by token: consuming needs the Purchase object. */
    private val knownPurchases = mutableMapOf<String, Purchase>()

    // Myket has no server API to verify subscriptions, so premium is sold as one-time passes.
    override fun subscribe(productId: String, payload: String, onResult: (PurchaseOutcome) -> Unit) {
        onResult(PurchaseOutcome.Failed(SUBSCRIPTION_UNSUPPORTED_MESSAGE))
    }

    override fun purchase(productId: String, payload: String, onResult: (PurchaseOutcome) -> Unit) {
        ensureSetUp { ready ->
            val iab = helper
            if (!ready || iab == null) {
                onResult(PurchaseOutcome.Failed(STORE_UNAVAILABLE_MESSAGE))
                return@ensureSetUp
            }
            // A purchase left unconsumed (a crash or a failed verification) blocks a new one, so
            // hand its token back instead of opening a second purchase.
            iab.queryInventoryAsync(false, listOf(productId)) { result, inventory ->
                val leftover = inventory?.getPurchase(productId)
                if (result.isSuccess && leftover != null && leftover.purchaseState == 0) {
                    knownPurchases[leftover.token] = leftover
                    onResult(PurchaseOutcome.Purchased(leftover.token))
                } else {
                    launchPurchase(iab, productId, payload, onResult)
                }
            }
        }
    }

    private fun launchPurchase(
        iab: IabHelper,
        productId: String,
        payload: String,
        onResult: (PurchaseOutcome) -> Unit,
    ) {
        iab.launchPurchaseFlow(activity, productId, { result: IabResult, purchase: Purchase? ->
            when {
                result.isSuccess && purchase != null -> {
                    knownPurchases[purchase.token] = purchase
                    onResult(PurchaseOutcome.Purchased(purchase.token))
                }
                result.response == IabHelper.IABHELPER_USER_CANCELLED ||
                    result.response == IabHelper.BILLING_RESPONSE_RESULT_USER_CANCELED ->
                    onResult(PurchaseOutcome.Cancelled)
                else -> onResult(PurchaseOutcome.Failed(PURCHASE_FAILED_MESSAGE))
            }
        }, payload)
    }

    override fun consume(purchaseToken: String) {
        ensureSetUp { ready ->
            val iab = helper
            if (!ready || iab == null) return@ensureSetUp
            val known = knownPurchases[purchaseToken]
            if (known != null) {
                consumePurchase(iab, known)
                return@ensureSetUp
            }
            // After a process restart the Purchase object is gone; find it again by token.
            iab.queryInventoryAsync(false, emptyList()) { result, inventory ->
                if (!result.isSuccess) return@queryInventoryAsync
                inventory?.allPurchases?.firstOrNull { it.token == purchaseToken }
                    ?.let { consumePurchase(iab, it) }
            }
        }
    }

    private fun consumePurchase(iab: IabHelper, purchase: Purchase) {
        iab.consumeAsync(purchase) { consumed, _ -> knownPurchases.remove(consumed.token) }
    }

    override fun dispose() {
        helper?.dispose()
        helper = null
        isSetUp = false
        isSettingUp = false
        setupWaiters.clear()
        knownPurchases.clear()
    }

    /** Connects to Myket once and reuses the connection. */
    private fun ensureSetUp(onReady: (Boolean) -> Unit) {
        if (isSetUp) {
            onReady(true)
            return
        }
        // The client verifies signatures locally, so it cannot start without the app's public key.
        if (BuildConfig.IAB_PUBLIC_KEY.isBlank()) {
            onReady(false)
            return
        }
        setupWaiters += onReady
        if (isSettingUp) return
        isSettingUp = true

        val iab = IabHelper(activity, BuildConfig.IAB_PUBLIC_KEY)
        helper = iab
        iab.startSetup { result ->
            isSettingUp = false
            isSetUp = result.isSuccess
            if (!result.isSuccess) {
                iab.dispose()
                helper = null
            }
            val waiters = setupWaiters.toList()
            setupWaiters.clear()
            waiters.forEach { it(result.isSuccess) }
        }
    }
}
