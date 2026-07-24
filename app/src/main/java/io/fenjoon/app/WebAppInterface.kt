package io.fenjoon.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.io.encoding.Base64
import org.json.JSONException
import org.json.JSONObject

private const val SHARE_PROTOCOL_VERSION = 1
internal const val MAX_SHARE_FILE_BYTES = 10 * 1024 * 1024
private const val MAX_SHARE_MESSAGE_CHARS = 15 * 1024 * 1024
private const val MAX_TITLE_CHARS = 500
private const val MAX_TEXT_CHARS = 50_000
private const val MAX_URL_CHARS = 4_096
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
)

internal data class ShareFile(
    val type: String,
    val bytes: ByteArray
)

internal data class ShareRequest(
    val id: String,
    val title: String,
    val text: String,
    val url: String,
    val file: ShareFile?
)

internal class ShareRequestException(
    val errorName: String,
    message: String
) : IllegalArgumentException(message)

internal object ShareRequestParser {
    fun parse(rawMessage: String): ShareRequest {
        if (rawMessage.length > MAX_SHARE_MESSAGE_CHARS) {
            throw ShareRequestException("NotSupportedError", "Share request is too large")
        }

        val json = try {
            JSONObject(rawMessage)
        } catch (_: JSONException) {
            throw ShareRequestException("DataError", "Invalid share request")
        }

        if (json.optInt("version", -1) != SHARE_PROTOCOL_VERSION) {
            throw ShareRequestException("NotSupportedError", "Unsupported share protocol")
        }

        val id = boundedString(json, "id", 100, required = true)
        val title = boundedString(json, "title", MAX_TITLE_CHARS)
        val text = boundedString(json, "text", MAX_TEXT_CHARS)
        val url = boundedString(json, "url", MAX_URL_CHARS)
        if (json.has("files")) {
            throw ShareRequestException("TypeError", "Multiple files are not supported")
        }
        val fileJson = json.optJSONObject("file")
        val file = fileJson?.let(::parseFile)

        if (title.isEmpty() && text.isEmpty() && url.isEmpty() && file == null) {
            throw ShareRequestException("TypeError", "Nothing to share")
        }

        return ShareRequest(id, title, text, url, file)
    }

    private fun parseFile(json: JSONObject): ShareFile {
        val type = boundedString(json, "type", 100, required = true)
        if (type != "image/png") {
            throw ShareRequestException("NotSupportedError", "Only PNG images can be shared")
        }

        val declaredSize = json.optLong("size", -1)
        if (declaredSize <= 0 || declaredSize > MAX_SHARE_FILE_BYTES) {
            throw ShareRequestException("NotSupportedError", "Image size is not supported")
        }

        val dataUrl = boundedString(
            json,
            "dataUrl",
            MAX_SHARE_MESSAGE_CHARS,
            required = true
        )
        val prefix = "data:image/png;base64,"
        if (!dataUrl.startsWith(prefix)) {
            throw ShareRequestException("DataError", "Invalid PNG data URL")
        }

        val bytes = try {
            Base64.decode(dataUrl.substring(prefix.length))
        } catch (_: IllegalArgumentException) {
            throw ShareRequestException("DataError", "Invalid image encoding")
        }

        if (bytes.isEmpty() || bytes.size > MAX_SHARE_FILE_BYTES || bytes.size.toLong() != declaredSize) {
            throw ShareRequestException("DataError", "Image size does not match")
        }
        if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            throw ShareRequestException("DataError", "Invalid PNG image")
        }

        return ShareFile(type, bytes)
    }

    private fun boundedString(
        json: JSONObject,
        key: String,
        maxLength: Int,
        required: Boolean = false
    ): String {
        val value = if (json.has(key) && !json.isNull(key)) json.optString(key) else ""
        if (required && value.isEmpty()) {
            throw ShareRequestException("TypeError", "$key is required")
        }
        if (value.length > maxLength) {
            throw ShareRequestException("NotSupportedError", "$key is too long")
        }
        return value
    }
}

/** Handles validated share messages sent by the origin-scoped WebView message bridge. */
class WebAppInterface(private val activity: Activity) {
    private val executor = Executors.newSingleThreadExecutor()

    fun share(rawMessage: String, respond: (String) -> Unit) {
        executor.execute {
            val request = try {
                ShareRequestParser.parse(rawMessage)
            } catch (error: ShareRequestException) {
                respond(errorResponse(extractRequestId(rawMessage), error.errorName, error.message))
                return@execute
            } catch (_: Exception) {
                respond(errorResponse(extractRequestId(rawMessage), "AbortError", "Unable to share"))
                return@execute
            }

            try {
                val imageUri = request.file?.let { writeShareFile(it.bytes) }
                activity.runOnUiThread {
                    try {
                        launchChooser(request, imageUri)
                        respond(successResponse(request.id))
                    } catch (_: ActivityNotFoundException) {
                        respond(errorResponse(request.id, "NotSupportedError", "No sharing app is available"))
                    } catch (_: SecurityException) {
                        respond(errorResponse(request.id, "NotAllowedError", "Sharing is not allowed"))
                    } catch (_: Exception) {
                        respond(errorResponse(request.id, "AbortError", "Unable to open sharing"))
                    }
                }
            } catch (_: IOException) {
                respond(errorResponse(request.id, "AbortError", "Unable to prepare image"))
            } catch (_: SecurityException) {
                respond(errorResponse(request.id, "NotAllowedError", "Unable to access shared image"))
            }
        }
    }

    private fun writeShareFile(bytes: ByteArray): Uri {
        val directory = File(activity.cacheDir, "share").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }

        val temporary = File(directory, "story.tmp")
        val target = File(directory, "fenjoon-story.png")
        temporary.outputStream().use { it.write(bytes) }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }

        return FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            target
        )
    }

    private fun launchChooser(request: ShareRequest, imageUri: Uri?) {
        val shareBody = when {
            request.text.isNotEmpty() && request.url.isNotEmpty() -> "${request.text} ${request.url}"
            request.url.isNotEmpty() -> request.url
            else -> request.text
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = imageUri?.let { "image/png" } ?: "text/plain"
            if (shareBody.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, shareBody)
            if (request.title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, request.title)
            if (imageUri != null) {
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = ClipData.newUri(activity.contentResolver, "Fenjoon story", imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        activity.startActivity(Intent.createChooser(sendIntent, null))
    }

    private fun successResponse(id: String): String = JSONObject()
        .put("id", id)
        .put("ok", true)
        .toString()

    private fun errorResponse(id: String, error: String, message: String?): String = JSONObject()
        .put("id", id)
        .put("ok", false)
        .put("error", error)
        .put("message", message.orEmpty())
        .toString()

    private fun extractRequestId(rawMessage: String): String = try {
        JSONObject(rawMessage).optString("id")
    } catch (_: Exception) {
        ""
    }
}
