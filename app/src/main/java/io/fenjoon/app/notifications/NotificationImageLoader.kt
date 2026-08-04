package io.fenjoon.app.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/** Loads notification artwork at Android's large-icon size. */
object NotificationImageLoader {
    private const val TAG = "NotifImageLoader"
    private const val MAX_DIMENSION = 512
    private const val LARGE_ICON_SIZE_DP = 64
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 2_000
    internal const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    private const val MAX_CACHE_ENTRIES = 12

    private val avatarCache = object : LinkedHashMap<String, Bitmap>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    internal fun loadChatAvatar(
        context: Context,
        rawUrl: String?,
        connectionFactory: (URL) -> HttpURLConnection = { url ->
            url.openConnection() as HttpURLConnection
        },
    ): Bitmap? = loadNotificationImage(context, rawUrl, connectionFactory)

    internal fun loadNotificationImage(
        context: Context,
        rawUrl: String?,
        connectionFactory: (URL) -> HttpURLConnection = { url ->
            url.openConnection() as HttpURLConnection
        },
    ): Bitmap? {
        val url = validateAvatarUrl(rawUrl) ?: return null
        synchronized(avatarCache) {
            avatarCache[url.toExternalForm()]?.let { return it }
        }

        return try {
            val bytes = download(url, connectionFactory) ?: return null
            val bitmap = decodeAvatar(bytes, targetSize(context)) ?: return null
            synchronized(avatarCache) {
                avatarCache[url.toExternalForm()] = bitmap
            }
            bitmap
        } catch (exception: IOException) {
            Log.w(TAG, "Failed to download notification avatar from ${url.host}")
            null
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification avatar request was blocked for ${url.host}")
            null
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Failed to decode notification avatar from ${url.host}")
            null
        }
    }

    internal fun validateAvatarUrl(rawUrl: String?): URL? {
        val uri = try {
            URI(rawUrl?.trim().orEmpty())
        } catch (_: Exception) {
            return null
        }
        if (!uri.isAbsolute || uri.isOpaque) return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return try {
            uri.toURL()
        } catch (_: Exception) {
            null
        }
    }

    internal fun download(
        url: URL,
        connectionFactory: (URL) -> HttpURLConnection,
    ): ByteArray? {
        val connection = connectionFactory(url).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = true
            setRequestProperty("Accept", "image/*")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_DOWNLOAD_BYTES) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    declaredLength.takeIf { it in 1..MAX_DOWNLOAD_BYTES }
                        ?.toInt()
                        ?: DEFAULT_BUFFER_SIZE,
                )
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray().takeIf(ByteArray::isNotEmpty)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeAvatar(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        var sampleSize = 1
        while (max(sourceWidth / sampleSize, sourceHeight / sampleSize) > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val squareSize = minOf(decoded.width, decoded.height)
        val left = (decoded.width - squareSize) / 2
        val top = (decoded.height - squareSize) / 2
        val square = Bitmap.createBitmap(decoded, left, top, squareSize, squareSize)
        val scaled = if (square.width == target && square.height == target) {
            square
        } else {
            Bitmap.createScaledBitmap(square, target, target, true)
        }
        if (square !== decoded && !decoded.isRecycled) decoded.recycle()
        if (scaled !== square && !square.isRecycled) square.recycle()
        return scaled
    }

    private fun targetSize(context: Context): Int =
        (LARGE_ICON_SIZE_DP * context.resources.displayMetrics.density)
            .roundToInt()
            .coerceIn(1, MAX_DIMENSION)

    internal fun clearAvatarCacheForTests() {
        synchronized(avatarCache) {
            avatarCache.clear()
        }
    }
}
