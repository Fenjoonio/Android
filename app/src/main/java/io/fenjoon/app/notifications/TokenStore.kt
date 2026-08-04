package io.fenjoon.app.notifications

import android.content.Context

/**
 * Persists the current FCM registration token so it survives restarts and is available to the
 * `AndroidNotifications` JS bridge immediately, before Firebase re-fetches it. The token is
 * mapped to a user by the web layer (which owns the session) — the device has no native
 * identity — so this is just a cache, not the source of truth.
 */
class TokenStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString(KEY_TOKEN, token).apply()
        TokenUpdateBus.publish(token)
    }

    fun get(): String? = prefs.getString(KEY_TOKEN, null)

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun addListener(listener: (String) -> Unit) {
        TokenUpdateBus.addListener(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        TokenUpdateBus.removeListener(listener)
    }

    private companion object {
        const val PREFS = "fenjoon_notifications"
        const val KEY_TOKEN = "fcm_token"
    }
}

internal object TokenUpdateBus {
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    fun publish(token: String) {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { it(token) }
    }
}
