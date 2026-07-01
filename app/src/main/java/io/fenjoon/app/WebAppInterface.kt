package io.fenjoon.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Bridge that backs the injected `navigator.share` polyfill.
 *
 * Android's WebView does not implement the Web Share API, so `navigator.share()`
 * throws inside the WebView even though it works in Chrome. The polyfill injected
 * in [MainActivity] forwards share requests here, where we fire a real
 * [Intent.ACTION_SEND] and let the user pick a target app.
 */
class WebAppInterface(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Called from JS on the WebView's JavaBridge thread — hop to the main thread
     * before touching the Activity to start the share chooser.
     */
    @JavascriptInterface
    fun share(title: String, text: String, url: String) {
        val shareBody = when {
            text.isNotEmpty() && url.isNotEmpty() -> "$text $url"
            url.isNotEmpty() -> url
            else -> text
        }

        mainHandler.post {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareBody)
                if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
            }
            context.startActivity(Intent.createChooser(sendIntent, null))
        }
    }
}
