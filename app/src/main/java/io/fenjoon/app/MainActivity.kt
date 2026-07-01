package io.fenjoon.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.os.SystemClock
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.fenjoon.app.ui.theme.AriaBlackFontFamily
import io.fenjoon.app.ui.theme.FenjoonTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val FENJOON_URL = "https://app.fenjoon.io"
private const val FENJOON_START_URL = "$FENJOON_URL?utm_source=direct"
private const val FENJOON_SCHEME = "fenjoon"
private const val FENJOON_HOST = "app.fenjoon.io"
private const val FENJOON_USER_AGENT = "Fenjoon-WebView"
private const val SHARE_BRIDGE_NAME = "AndroidShare"

private const val SPLASH_HEAD = "فـ" // ف + kashida, so it shows its connecting (initial) form
private const val SPLASH_TAIL = "نجون"
private const val SPLASH_HEAD_DELAY_MS = 500L
private const val SPLASH_REVEAL_MS = 550

// Keep the splash up long enough to show the centered "ف" and the reveal of "نجون"
// before it can fade out (so it isn't cut off when the page loads instantly from cache).
private const val SPLASH_MIN_DURATION_MS = SPLASH_HEAD_DELAY_MS + 800L

/**
 * Polyfill for the Web Share API, which Android's WebView does not implement.
 * Forwards `navigator.share()` calls to the native [WebAppInterface] bridge so
 * the web app's existing share code works unchanged inside the WebView.
 */
private const val WEB_SHARE_POLYFILL = """
(function() {
  if (!window.$SHARE_BRIDGE_NAME || navigator.__fenjoonShare) return;
  navigator.__fenjoonShare = true;
  navigator.share = function(data) {
    data = data || {};
    try {
      window.$SHARE_BRIDGE_NAME.share(
        data.title != null ? String(data.title) : '',
        data.text != null ? String(data.text) : '',
        data.url != null ? String(data.url) : ''
      );
      return Promise.resolve();
    } catch (e) {
      return Promise.reject(e);
    }
  };
  navigator.canShare = function(data) {
    return !(data && data.files && data.files.length);
  };
})();
"""

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
    var showSplash by remember { mutableStateOf(true) }
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
            addJavascriptInterface(WebAppInterface(context), SHARE_BRIDGE_NAME)
            // Inject the polyfill before any page script runs so `navigator.share`
            // exists by the time the web app feature-detects it. onPageStarted
            // below is the fallback for WebViews without DOCUMENT_START_SCRIPT.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    WEB_SHARE_POLYFILL,
                    setOf("https://$FENJOON_HOST")
                )
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

    // Hold the animated splash until the very first page load finishes (honoring a
    // minimum on-screen time), then let it fade out.
    LaunchedEffect(Unit) {
        val splashStart = SystemClock.uptimeMillis()
        snapshotFlow { isLoading }.first { !it }
        val remaining = SPLASH_MIN_DURATION_MS - (SystemClock.uptimeMillis() - splashStart)
        if (remaining > 0) delay(remaining)
        showSplash = false
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

        AnimatedVisibility(
            visible = showSplash,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = 400)),
            modifier = Modifier.fillMaxSize()
        ) {
            FenjoonSplash()
        }
    }
}

@Composable
private fun FenjoonSplash(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember { context.appVersionName() }
    var expanded by remember { mutableStateOf(false) }

    // Show "ف" centered first, then slide it aside as "نجون" is revealed right-to-left.
    LaunchedEffect(Unit) {
        delay(SPLASH_HEAD_DELAY_MS)
        expanded = true
    }

    val reveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = SPLASH_REVEAL_MS),
        label = "splash-reveal"
    )

    val textStyle = remember {
        TextStyle(fontFamily = AriaBlackFontFamily, fontSize = 64.sp)
    }
    val measurer = rememberTextMeasurer()
    val tailWidth = remember(textStyle) { measurer.measure(SPLASH_TAIL, textStyle).size.width }
    val tailWidthDp = with(LocalDensity.current) { tailWidth.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The row is centered as a whole: while "نجون" has zero width the "ف" sits dead
        // center; as the tail grows the row widens and pushes "ف" to the right (RTL),
        // and the clip reveals "نجون" from its right edge leftward.
        Row(
            horizontalArrangement = Arrangement.spacedBy((-8).dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-24).dp)
        ) {
            Text(
                text = SPLASH_HEAD,
                color = MaterialTheme.colorScheme.onBackground,
                style = textStyle
            )
            Box(
                modifier = Modifier
                    .width(tailWidthDp * reveal)
                    .clipToBounds()
            ) {
                Text(
                    text = SPLASH_TAIL,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = textStyle,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.splash_tagline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!version.isNullOrEmpty()) {
                Text(
                    text = version,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

private fun Context.appVersionName(): String? = try {
    packageManager.getPackageInfo(packageName, 0).versionName
} catch (_: PackageManager.NameNotFoundException) {
    null
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
        // Fallback for WebViews that don't support DOCUMENT_START_SCRIPT. The
        // polyfill is idempotent, so it's a no-op when the document-start
        // injection already ran.
        view.evaluateJavascript(WEB_SHARE_POLYFILL, null)
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
