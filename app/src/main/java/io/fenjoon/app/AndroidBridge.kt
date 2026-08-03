package io.fenjoon.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import org.json.JSONObject

private const val TAG = "AndroidBridge"
private val WEB_OTP_PATTERN = Regex("""#\s*([0-9]{5})\b""")
private val OTP_PATTERN = Regex("""(?<![0-9])[0-9]{5}(?![0-9])""")

enum class ThemeSelection {
    Light,
    Dark,
    System,
}

internal fun parseThemeSelection(value: String): ThemeSelection? = when (value) {
    "light" -> ThemeSelection.Light
    "dark" -> ThemeSelection.Dark
    "system" -> ThemeSelection.System
    else -> null
}

internal fun extractOtpCode(smsMessage: String): String? {
    WEB_OTP_PATTERN.find(smsMessage)?.groupValues?.getOrNull(1)?.let { return it }
    return OTP_PATTERN.find(smsMessage)?.value
}

/**
 * JS bridge exposed as `window.Android`. Handles app messages from the web frontend.
 *
 * Uses the silent SMS Retriever API (no user consent UI). The outbound SMS **must**
 * contain this app's 11-character hash on its own line — otherwise Play Services will
 * not deliver the message. See [AppSignatureHelper] (debug) to print the hash, and put
 * it in the SMS pattern, e.g.:
 *
 * ```
 * فنجون
 * code: 12345
 *
 * <APP_HASH>
 * ```
 */
class AndroidBridge(
    private val activity: Activity,
    private val onThemeChanged: (ThemeSelection) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var smsReceiver: BroadcastReceiver? = null
    private var isListening = false
    private var isVerificationPageReady = false
    private var pendingOtpCode: String? = null
    private var webView: WebView? = null

    /**
     * Called from JavaScript: window.Android.postMessage(jsonString)
     * Parses the message type and dispatches native actions.
     */
    @JavascriptInterface
    fun postMessage(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val type = json.optString("type", "")
            when (type) {
                "startOtpListening" -> mainHandler.post {
                    isVerificationPageReady = false
                    pendingOtpCode = null
                    startOtpListening()
                }
                "otpVerificationPageReady" -> mainHandler.post {
                    isVerificationPageReady = true
                    val hasPendingOtp = pendingOtpCode != null
                    deliverPendingOtp()
                    if (!hasPendingOtp) startOtpListening()
                }
                "otpVerificationPageClosed" -> mainHandler.post {
                    // Keep listening + any cached OTP so a brief unmount/remount
                    // (or slow navigation) does not drop a code that already arrived.
                    isVerificationPageReady = false
                }
                "themeChanged" -> parseThemeSelection(json.optString("theme", ""))?.let { theme ->
                    mainHandler.post { onThemeChanged(theme) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $jsonString", e)
        }
    }

    fun setWebView(webView: WebView?) {
        this.webView = webView
    }

    private fun startOtpListening() {
        if (isListening) return
        if (!registerSmsReceiver()) return
        isListening = true

        SmsRetriever.getClient(activity).startSmsRetriever()
            .addOnSuccessListener {
                Log.d(TAG, "SMS Retriever started successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start SMS Retriever", e)
                stopOtpListeningOnMainThread()
            }
    }

    fun stopOtpListening() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopOtpListeningOnMainThread()
        } else {
            mainHandler.post(::stopOtpListeningOnMainThread)
        }
    }

    private fun stopOtpListeningOnMainThread() {
        isListening = false
        unregisterSmsReceiver()
    }

    private fun registerSmsReceiver(): Boolean {
        if (smsReceiver != null) return true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (SmsRetriever.SMS_RETRIEVED_ACTION != intent.action) return
                val extras = intent.extras ?: return
                // EXTRA_STATUS is a Status parcelable — getInt always returns the default.
                val status = BundleCompat.getParcelable(
                    extras,
                    SmsRetriever.EXTRA_STATUS,
                    Status::class.java,
                )
                val statusCode = status?.statusCode ?: CommonStatusCodes.ERROR

                when (statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        val smsMessage = extras
                            .getString(SmsRetriever.EXTRA_SMS_MESSAGE)
                            .orEmpty()
                        stopOtpListeningOnMainThread()
                        if (smsMessage.isNotEmpty()) {
                            Log.d(TAG, "SMS retrieved silently")
                            extractAndSendOtp(smsMessage)
                        } else {
                            Log.w(TAG, "SMS Retriever success with empty message")
                        }
                    }
                    CommonStatusCodes.TIMEOUT -> {
                        Log.d(TAG, "SMS Retriever timeout")
                        stopOtpListeningOnMainThread()
                    }
                    else -> {
                        Log.w(TAG, "SMS Retriever failed with status: $statusCode")
                        stopOtpListeningOnMainThread()
                    }
                }
            }
        }

        return try {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                SmsRetriever.SEND_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
            smsReceiver = receiver
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to register SMS receiver", e)
            false
        }
    }

    private fun unregisterSmsReceiver() {
        smsReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "SMS receiver was already unregistered", e)
            }
        }
        smsReceiver = null
    }

    private fun extractAndSendOtp(smsMessage: String) {
        val otpCode = extractOtpCode(smsMessage)

        if (otpCode != null) {
            Log.d(TAG, "Extracted OTP: $otpCode")
            pendingOtpCode = otpCode
            if (isVerificationPageReady) {
                deliverPendingOtp()
            } else {
                Log.d(TAG, "OTP cached until verification page is ready")
            }
        } else {
            Log.e(TAG, "Failed to extract OTP from SMS: $smsMessage")
        }
    }

    private fun deliverPendingOtp() {
        if (!isVerificationPageReady) return
        val code = pendingOtpCode ?: return
        val targetWebView = webView ?: return
        val escapedCode = JSONObject.quote(code)
        val javascript = """
            (function(code) {
                window.dispatchEvent(new CustomEvent('fenjoonOtpReceived', {
                    detail: { code: code }
                }));

                var input = document.querySelector('input[autocomplete="one-time-code"]');
                if (!(input instanceof HTMLInputElement)) return;

                var valueSetter = Object.getOwnPropertyDescriptor(
                    HTMLInputElement.prototype,
                    'value'
                ).set;
                valueSetter.call(input, code);
                input.dispatchEvent(new Event('input', { bubbles: true }));
            })($escapedCode);
        """.trimIndent()

        Log.d(TAG, "Delivering OTP to verification page")
        targetWebView.evaluateJavascript(javascript) {
            if (pendingOtpCode == code) pendingOtpCode = null
        }
    }
}
