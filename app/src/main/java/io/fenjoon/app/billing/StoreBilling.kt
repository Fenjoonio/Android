package io.fenjoon.app.billing

/** Result of one in-app purchase attempt, handed back to the web app. */
sealed interface PurchaseOutcome {
    data class Purchased(val purchaseToken: String) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}

/**
 * One store's in-app billing (Bazaar's Poolakey or the Myket billing client). Each store build
 * flavor supplies its own implementation through [createStoreBilling]; the direct build has none.
 * Every method must be called on the main thread and every callback arrives on it.
 */
interface StoreBilling {
    /**
     * Starts the store's purchase screen for [productId]. If the user still owns an unconsumed
     * purchase of that product (a crash or a failed verification left it behind), its token is
     * returned instead of opening a second purchase, which the store would reject.
     */
    fun purchase(productId: String, payload: String, onResult: (PurchaseOutcome) -> Unit)

    /**
     * Starts the store's subscription screen for [productId]. If the user already has that
     * subscription, its token is returned so the backend can link it to the signed-in account.
     * Stores without verifiable subscriptions (Myket) report [PurchaseOutcome.Failed].
     */
    fun subscribe(productId: String, payload: String, onResult: (PurchaseOutcome) -> Unit)

    /** Consumes a purchase so the product can be bought again. Best effort. */
    fun consume(purchaseToken: String)

    /** Releases the connection to the store. Call from the activity's onDestroy. */
    fun dispose()
}

internal const val STORE_UNAVAILABLE_MESSAGE = "اتصال به فروشگاه برقرار نشد"
internal const val PURCHASE_FAILED_MESSAGE = "خرید انجام نشد"
internal const val SUBSCRIPTION_UNSUPPORTED_MESSAGE = "خرید اشتراک در این فروشگاه پشتیبانی نمی‌شود"
