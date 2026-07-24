package io.fenjoon.app.notifications

import android.webkit.CookieManager
import io.fenjoon.app.BuildConfig
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Narrow HTTP client for actions originating from chat notifications.
 *
 * Push payload URLs are untrusted input. A request is opened, and WebView cookies are queried,
 * only after the URL is proven to be an exact Fenjoon API action endpoint. Redirect following is
 * disabled so credentials can never be forwarded to a redirect target.
 *
 * The trusted API origin is injected at build time via BuildConfig.NOTIFICATION_API_ORIGIN so
 * debug and release builds can target different backends without code changes.
 */
class NotificationActionHttpClient(
    private val cookieProvider: (String) -> String? = { url ->
        CookieManager.getInstance().getCookie(url)
    },
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    fun postMessage(
        rawUrl: String,
        conversationId: String,
        message: String,
        readThroughMessageId: String,
    ): Boolean {
        val messageId = readThroughMessageId.toPositiveLongOrNull() ?: return false
        if (message.isBlank()) return false
        val body = "{\"message\":${message.toJsonString()},\"readThroughMessageId\":$messageId}"
        return postJson(
            rawUrl = rawUrl,
            action = Action.MESSAGE,
            conversationId = conversationId,
            body = body,
            idempotencyKey = UUID.randomUUID().toString(),
        )
    }

    fun postRead(
        rawUrl: String,
        conversationId: String,
        messageId: String,
    ): Boolean {
        val numericMessageId = messageId.toPositiveLongOrNull() ?: return false
        return postJson(
            rawUrl = rawUrl,
            action = Action.READ,
            conversationId = conversationId,
            body = "{\"messageId\":$numericMessageId}",
            idempotencyKey = null,
        )
    }

    private fun postJson(
        rawUrl: String,
        action: Action,
        conversationId: String,
        body: String,
        idempotencyKey: String?,
    ): Boolean {
        val endpoint = validate(rawUrl, action, conversationId) ?: return false

        // Do not move this above validation: CookieManager may return authentication cookies.
        val cookie = cookieProvider(endpoint.cookieUrl)
        val connection = connectionFactory(endpoint.url).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            idempotencyKey?.let { setRequestProperty("Idempotency-Key", it) }
            cookie?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Cookie", it)
                csrfToken(it)?.let { token -> setRequestProperty("X-XSRF-TOKEN", token) }
            }
        }

        return try {
            connection.outputStream.use { output: OutputStream ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    enum class Action(val suffix: String) {
        MESSAGE("messages"),
        READ("read"),
    }

    /** Trusted scheme+host+port for action endpoints. Internal so unit tests can construct/compare. */
    internal data class AllowedOrigin(val scheme: String, val host: String, val port: Int)

    internal data class ValidatedEndpoint(
        val url: URL,
        val conversationId: String,
    ) {
        /** Origin-form URL for CookieManager lookups (auth cookies are domain-scoped). */
        val cookieUrl: String
            get() {
                val defaultPort = defaultPortFor(url.protocol)
                return if (url.port == -1 || url.port == defaultPort) {
                    "${url.protocol}://${url.host}"
                } else {
                    "${url.protocol}://${url.host}:${url.port}"
                }
            }
    }

    internal companion object {
        private const val TIMEOUT_MS = 8_000
        private const val UUID_PATTERN =
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"

        private val ACTION_PATH = Regex(
            "^/v1/conversations/($UUID_PATTERN)/(messages|read)$",
        )

        /**
         * Trusted scheme+host+port for action endpoints, parsed once from BuildConfig so unit
         * tests and prod share the same validation path. Defaults to https://api.fenjoon.io.
         */
        internal val ALLOWED_ORIGIN: AllowedOrigin =
            parseAllowedOrigin(BuildConfig.NOTIFICATION_API_ORIGIN)
                ?: error("BuildConfig.NOTIFICATION_API_ORIGIN is invalid: ${BuildConfig.NOTIFICATION_API_ORIGIN}")

        internal fun defaultPortFor(scheme: String): Int = when (scheme.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }

        internal fun parseAllowedOrigin(raw: String): AllowedOrigin? {
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.takeIf(String::isNotBlank)?.lowercase() ?: return null
            val host = uri.host?.takeIf(String::isNotBlank)?.lowercase() ?: return null
            if (uri.rawUserInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            val path = uri.rawPath
            if (path != null && path != "/" && path.isNotEmpty()) return null
            val port = if (uri.port != -1) uri.port else defaultPortFor(scheme)
            return AllowedOrigin(scheme, host, port)
        }

        /** Returns null unless [rawUrl] is the exact approved action endpoint on the trusted origin. */
        fun validate(
            rawUrl: String,
            action: Action,
            expectedConversationId: String? = null,
        ): ValidatedEndpoint? {
            val uri = try {
                URI(rawUrl)
            } catch (_: Exception) {
                return null
            }
            if (!uri.isAbsolute || uri.isOpaque) return null
            if (!uri.scheme.equals(ALLOWED_ORIGIN.scheme, ignoreCase = true)) return null
            if (!uri.host.equals(ALLOWED_ORIGIN.host, ignoreCase = true)) return null
            val port = if (uri.port != -1) uri.port else defaultPortFor(uri.scheme)
            if (port != ALLOWED_ORIGIN.port) return null
            if (uri.rawUserInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null

            val match = ACTION_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return null
            val conversationId = match.groupValues[1]
            if (match.groupValues[2] != action.suffix) return null
            if (expectedConversationId != null &&
                !conversationId.equals(expectedConversationId.trim(), ignoreCase = true)
            ) {
                return null
            }

            val url = try {
                uri.toURL()
            } catch (_: Exception) {
                return null
            }
            return ValidatedEndpoint(url, conversationId)
        }

        private fun String.toPositiveLongOrNull(): Long? =
            trim().toLongOrNull()?.takeIf { it > 0L }

        private fun csrfToken(cookie: String): String? = cookie
            .split(';')
            .asSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.takeIf(String::isNotBlank)

        private fun String.toJsonString(): String = buildString(length + 2) {
            append('"')
            for (character in this@toJsonString) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }
    }
}
