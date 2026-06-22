package io.fenjoon.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.fenjoon.app.ui.theme.FenjoonTheme

private const val FENJOON_URL = "https://app.fenjoon.io"
private const val FENJOON_START_URL = "$FENJOON_URL?utm_source=direct"
private const val FENJOON_SCHEME = "fenjoon"
private const val FENJOON_HOST = "app.fenjoon.io"
private const val FENJOON_USER_AGENT = "Fenjoon-WebView"

class MainActivity : ComponentActivity() {
    private var currentUrl by mutableStateOf(FENJOON_START_URL)

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
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var isOnline by remember { mutableStateOf(context.isOnline()) }
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(backgroundColor)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
            webViewClient = FenjoonWebViewClient(
                onLoadingChanged = { isLoading = it },
                onError = { hasError = true }
            )
            settings.javaScriptEnabled = true
            settings.textZoom = 100
            settings.userAgentString = FENJOON_USER_AGENT
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.cacheMode = if (isOnline) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
        }
    }

    DisposableEffect(context, webView) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val mainHandler = Handler(Looper.getMainLooper())
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { isOnline = true }
            }

            override fun onLost(network: Network) {
                mainHandler.post { isOnline = context.isOnline() }
            }
        }

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )

        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    LaunchedEffect(isOnline) {
        webView.settings.cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        if (isOnline && hasError) {
            hasError = false
            isLoading = true
            webView.reload()
        }
    }

    LaunchedEffect(url) {
        if (webView.url != url) {
            hasError = false
            isLoading = true
            webView.loadUrl(url)
        }
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

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.setBackgroundColor(backgroundColor)
                it.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (hasError) {
            ErrorState(
                onRetry = {
                    hasError = false
                    isLoading = true
                    webView.reload()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.error_title),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.error_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(text = stringResource(R.string.error_retry))
        }
    }
}

private class FenjoonWebViewClient(
    private val onLoadingChanged: (Boolean) -> Unit,
    private val onError: () -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return when {
            url.isFenjoonUrl() -> {
                onLoadingChanged(true)
                url.scheme == FENJOON_SCHEME
            }
            url.isInternalWebViewUrl() -> false
            else -> {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: ActivityNotFoundException) {
                }
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onLoadingChanged(true)
    }

    override fun onPageFinished(view: WebView, url: String) {
        onLoadingChanged(false)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            onLoadingChanged(false)
            onError()
        }
    }
}

private fun Intent?.toFenjoonUrl(): String {
    val url = this?.data ?: return FENJOON_START_URL
    return url.toFenjoonWebUrl() ?: FENJOON_START_URL
}

private fun Context.isOnline(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun Uri.isFenjoonUrl(): Boolean {
    return scheme == "https" && host == FENJOON_HOST || scheme == FENJOON_SCHEME
}

private fun Uri.isInternalWebViewUrl(): Boolean {
    return scheme == "about" || scheme == "data" || scheme == "file"
}

private fun Uri.toFenjoonWebUrl(): String? {
    if (scheme == "https" && host == FENJOON_HOST) return toString()
    if (scheme != FENJOON_SCHEME) return null

    val deepLinkPath = path
    val targetPath = buildString {
        if (!host.isNullOrBlank()) {
            append('/')
            append(host)
            if (!deepLinkPath.isNullOrBlank() && deepLinkPath != "/") append(deepLinkPath)
        } else if (!deepLinkPath.isNullOrBlank() && deepLinkPath != "/") {
            append(deepLinkPath)
        }
    }

    return buildString {
        append(FENJOON_URL)
        append(targetPath)
        if (!query.isNullOrBlank()) {
            append('?')
            append(query)
        }
        if (!fragment.isNullOrBlank()) {
            append('#')
            append(fragment)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FenjoonWebViewPreview() {
    FenjoonTheme {
        FenjoonWebView()
    }
}
