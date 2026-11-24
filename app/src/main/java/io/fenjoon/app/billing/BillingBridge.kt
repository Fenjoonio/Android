package io.fenjoon.app.billing

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.fenjoon.app.BuildConfig
import org.json.JSONObject

/**
 * JS bridge exposed as `window.AndroidBilling`. The web app asks for a purchase or subscription
 * and gets the outcome back through `window.__fenjoonBilling.onResult(json)`, then sends the
 * purchase token to the backend, which verifies it with the store before premium is granted.
 * Bazaar sells premium as subscriptions ([subscribe]); Myket as one-time passes ([purchase]).
 *
 * `@JavascriptInterface` methods run on the WebView's JavaBridge thread, so each one hops to the
 * main thread before touching the store SDK.
 */
class BillingBridge(
    private val billing: StoreBilling,
    private val webView: WebView,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun getStore(): String = BuildConfig.STORE

    @JavascriptInterface
    fun purchase(productId: String?, payload: String?) {
        if (productId == null || !PlanCatalog.isAllowed(productId)) {
            deliver(PurchaseOutcome.Failed(PURCHASE_FAILED_MESSAGE))
            return
        }
        mainHandler.post { billing.purchase(productId, payload.orEmpty(), ::deliver) }
    }

    @JavascriptInterface
    fun subscribe(productId: String?, payload: String?) {
        if (productId == null || !PlanCatalog.isAllowed(productId)) {
            deliver(PurchaseOutcome.Failed(PURCHASE_FAILED_MESSAGE))
            return
        }
        mainHandler.post { billing.subscribe(productId, payload.orEmpty(), ::deliver) }
    }

    @JavascriptInterface
    fun consume(purchaseToken: String?) {
        if (purchaseToken.isNullOrBlank()) return
        mainHandler.post { billing.consume(purchaseToken) }
    }

    private fun deliver(outcome: PurchaseOutcome) {
        val json = JSONObject().apply {
            when (outcome) {
                is PurchaseOutcome.Purchased -> {
                    put("status", "purchased")
                    put("purchaseToken", outcome.purchaseToken)
                }
                PurchaseOutcome.Cancelled -> put("status", "cancelled")
                is PurchaseOutcome.Failed -> {
                    put("status", "error")
                    put("message", outcome.message)
                }
            }
        }
        val script =
            "window.__fenjoonBilling && window.__fenjoonBilling.onResult(${JSONObject.quote(json.toString())});"
        mainHandler.post { webView.evaluateJavascript(script, null) }
    }
}
