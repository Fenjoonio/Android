package io.fenjoon.app.billing

import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.ConnectionState
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.entity.PurchaseState
import ir.cafebazaar.poolakey.request.PurchaseRequest

/** Bazaar in-app billing through the Poolakey SDK. */
internal class PoolakeyStoreBilling(private val activity: ComponentActivity) : StoreBilling {

    // Local signature checks are off: the backend verifies every purchase token with Bazaar's
    // developer API, which is the condition Poolakey sets for turning them off.
    private val payment = Payment(
        context = activity,
        config = PaymentConfiguration(localSecurityCheck = SecurityCheck.Disable),
    )

    private var connection: Connection? = null

    override fun purchase(productId: String, payload: String, onResult: (PurchaseOutcome) -> Unit) {
        withConnection(onFailure = { onResult(PurchaseOutcome.Failed(STORE_UNAVAILABLE_MESSAGE)) }) {
            payment.getPurchasedProducts {
                querySucceed { owned ->
                    val leftover = owned.firstOrNull {
                        it.productId == productId && it.purchaseState == PurchaseState.PURCHASED
                    }
                    if (leftover != null) {
                        onResult(PurchaseOutcome.Purchased(leftover.purchaseToken))
                    } else {
                        startPurchase(productId, payload, onResult)
                    }
                }
                queryFailed { startPurchase(productId, payload, onResult) }
            }
        }
    }

    private fun startPurchase(
        productId: String,
        payload: String,
        onResult: (PurchaseOutcome) -> Unit,
    ) {
        payment.purchaseProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(productId = productId, payload = payload),
        ) {
            purchaseSucceed { purchase -> onResult(PurchaseOutcome.Purchased(purchase.purchaseToken)) }
            purchaseCanceled { onResult(PurchaseOutcome.Cancelled) }
            purchaseFailed { onResult(PurchaseOutcome.Failed(PURCHASE_FAILED_MESSAGE)) }
            failedToBeginFlow { onResult(PurchaseOutcome.Failed(STORE_UNAVAILABLE_MESSAGE)) }
        }
    }

    override fun subscribe(productId: String, payload: String, onResult: (PurchaseOutcome) -> Unit) {
        withConnection(onFailure = { onResult(PurchaseOutcome.Failed(STORE_UNAVAILABLE_MESSAGE)) }) {
            payment.getSubscribedProducts {
                querySucceed { subscribed ->
                    val active = subscribed.firstOrNull {
                        it.productId == productId && it.purchaseState == PurchaseState.PURCHASED
                    }
                    if (active != null) {
                        onResult(PurchaseOutcome.Purchased(active.purchaseToken))
                    } else {
                        startSubscription(productId, payload, onResult)
                    }
                }
                queryFailed { startSubscription(productId, payload, onResult) }
            }
        }
    }

    private fun startSubscription(
        productId: String,
        payload: String,
        onResult: (PurchaseOutcome) -> Unit,
    ) {
        payment.subscribeProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(productId = productId, payload = payload),
        ) {
            purchaseSucceed { purchase -> onResult(PurchaseOutcome.Purchased(purchase.purchaseToken)) }
            purchaseCanceled { onResult(PurchaseOutcome.Cancelled) }
            purchaseFailed { onResult(PurchaseOutcome.Failed(PURCHASE_FAILED_MESSAGE)) }
            failedToBeginFlow { onResult(PurchaseOutcome.Failed(STORE_UNAVAILABLE_MESSAGE)) }
        }
    }

    override fun consume(purchaseToken: String) {
        withConnection(onFailure = {}) {
            payment.consumeProduct(purchaseToken) {
                consumeSucceed {}
                consumeFailed {}
            }
        }
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
    }

    private fun withConnection(onFailure: () -> Unit, action: () -> Unit) {
        if (connection?.getState() is ConnectionState.Connected) {
            action()
            return
        }
        connection?.disconnect()
        connection = payment.connect {
            connectionSucceed { action() }
            connectionFailed { onFailure() }
        }
    }
}
