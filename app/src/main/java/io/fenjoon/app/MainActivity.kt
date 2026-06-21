package io.fenjoon.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import io.fenjoon.app.ui.theme.FenjoonTheme

private const val FENJOON_URL = "https://app.fenjoon.io"
private const val FENJOON_SCHEME = "fenjoon"
private const val FENJOON_HOST = "app.fenjoon.io"

class MainActivity : ComponentActivity() {
    private var currentUrl by mutableStateOf(FENJOON_URL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUrl = intent.toFenjoonUrl()
        enableEdgeToEdge()
        setContent {
            FenjoonTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    FenjoonWebView(url = currentUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentUrl = intent.toFenjoonUrl()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FenjoonWebView(
    modifier: Modifier = Modifier,
    url: String = FENJOON_URL
) {
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(backgroundColor)
            webViewClient = FenjoonWebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
    }

    if (webView.url != url) {
        webView.loadUrl(url)
    }

    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            (context as? Activity)?.finish()
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
        update = {
            it.setBackgroundColor(backgroundColor)
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    )
}

private class FenjoonWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return if (url.isFenjoonUrl()) {
            false
        } else {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
            true
        }
    }
}

private fun Intent?.toFenjoonUrl(): String {
    val url = this?.data ?: return FENJOON_URL
    return if (url.isFenjoonUrl()) url.toString() else FENJOON_URL
}

private fun Uri.isFenjoonUrl(): Boolean {
    return scheme == "https" && host == FENJOON_HOST || scheme == FENJOON_SCHEME
}

@Preview(showBackground = true)
@Composable
fun FenjoonWebViewPreview() {
    FenjoonTheme {
        FenjoonWebView()
    }
}
