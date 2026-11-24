package io.fenjoon.app.billing

import android.content.Context
import io.fenjoon.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * The product IDs the native bridge may buy, taken from the backend's plan list
 * (GET /v1/premium/plans), so a plan change needs no app release. The last good list is cached
 * on the device so the bridge still works offline. Until a list has been loaded once, nothing
 * can be bought.
 *
 * The backend stays the authority: it only issues purchases for its own plans and verifies every
 * token with the store, so this list limits what the app will attempt, nothing more.
 */
internal object PlanCatalog {
    private const val PREFS = "billing_catalog"
    private const val KEY_PRODUCT_IDS = "product_ids"
    private const val PLANS_PATH = "/v1/premium/plans"

    private val productIds = AtomicReference<Set<String>>(emptySet())

    /** Loads the cached list now and refreshes it from the backend in the background. */
    fun load(context: Context) {
        val appContext = context.applicationContext
        val cached = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRODUCT_IDS, null)
        if (cached != null) {
            runCatching { productIds.set(parseCached(JSONArray(cached))) }
        }
        thread(name = "premium-catalog") { refresh(appContext) }
    }

    fun isAllowed(productId: String): Boolean = productId in productIds.get()

    private fun refresh(context: Context) {
        try {
            val connection = URL(BuildConfig.NOTIFICATION_API_ORIGIN + PLANS_PATH)
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val plans = JSONObject(body).getJSONArray("data")
            val ids = parsePlans(plans)
            if (ids.isEmpty()) return

            productIds.set(ids)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRODUCT_IDS, JSONArray(ids.toList()).toString())
                .apply()
        } catch (_: Exception) {
            // Keep the cached list. A failed refresh must not stop purchases that were allowed before.
        }
    }

    private fun parsePlans(plans: JSONArray): Set<String> = buildSet {
        for (i in 0 until plans.length()) {
            val id = plans.optJSONObject(i)?.optString("productId").orEmpty()
            if (id.isNotBlank()) add(id)
        }
    }

    private fun parseCached(ids: JSONArray): Set<String> = buildSet {
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            if (id.isNotBlank()) add(id)
        }
    }
}
