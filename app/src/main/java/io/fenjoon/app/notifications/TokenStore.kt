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
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun get(): String? = prefs.getString(KEY_TOKEN, null)

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS = "fenjoon_notifications"
        const val KEY_TOKEN = "fcm_token"
    }
}
