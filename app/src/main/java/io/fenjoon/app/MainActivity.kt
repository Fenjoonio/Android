package io.fenjoon.app

import android.Manifest
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
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.fenjoon.app.notifications.ChatNotificationOpenIntent
import io.fenjoon.app.notifications.NotificationActionExtras
import io.fenjoon.app.notifications.NotificationActionHttpClient
import io.fenjoon.app.notifications.NotificationPresentationState
import io.fenjoon.app.notifications.Notifier
import io.fenjoon.app.notifications.ReminderScheduler
import io.fenjoon.app.notifications.TokenStore
import io.fenjoon.app.ui.theme.AriaBlackFontFamily
import io.fenjoon.app.ui.theme.FenjoonTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private const val FENJOON_URL = "https://app.fenjoon.io"
private const val FENJOON_START_URL = "$FENJOON_URL?utm_source=direct"
private const val FENJOON_SCHEME = "fenjoon"
private const val FENJOON_HOST = "app.fenjoon.io"
private const val FENJOON_USER_AGENT = "Fenjoon-WebView"
private const val SHARE_BRIDGE_NAME = "AndroidShare"
private const val NOTIFICATIONS_BRIDGE_NAME = "AndroidNotifications"
private const val STATE_NOTIFICATION_OPEN_KEY = "state_notification_open_key"

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
  var bridge = window.$SHARE_BRIDGE_NAME;
  if (!bridge || navigator.__fenjoonShareVersion === 2) return;

  var maxFileBytes = $MAX_SHARE_FILE_BYTES;
  var pending = null;
  var nextId = 1;

  function domError(name, message) {
    try { return new DOMException(message || name, name); }
    catch (_) { var error = new Error(message || name); error.name = name; return error; }
  }

  bridge.onmessage = function(event) {
    var response;
    try { response = JSON.parse(event.data); } catch (_) { return; }
    if (!pending || response.id !== pending.id) return;

    var current = pending;
    pending = null;
    if (response.ok) current.resolve();
    else current.reject(domError(response.error || 'AbortError', response.message));
  };

  navigator.canShare = function(data) {
    if (!data || typeof data !== 'object') return false;
    var hasText = data.title != null || data.text != null || data.url != null;
    var files = data.files;
    if (files == null || files.length === 0) return hasText;
    if (files.length !== 1) return false;

    var file = files[0];
    return file instanceof File &&
      file.type === 'image/png' &&
      file.size > 0 &&
      file.size <= maxFileBytes;
  };

  navigator.share = function(data) {
    data = data || {};
    if (!navigator.canShare(data)) {
      return Promise.reject(domError('NotSupportedError', 'This share data is not supported'));
    }
    if (pending) {
      return Promise.reject(domError('InvalidStateError', 'A share is already in progress'));
    }

    return new Promise(function(resolve, reject) {
      var id = String(nextId++);
      pending = { id: id, resolve: resolve, reject: reject };

      function send(fileData) {
        try {
          bridge.postMessage(JSON.stringify({
            version: 1,
            id: id,
            title: data.title != null ? String(data.title) : '',
            text: data.text != null ? String(data.text) : '',
            url: data.url != null ? String(data.url) : '',
            file: fileData || null
          }));
        } catch (error) {
          pending = null;
          reject(error);
        }
      }

      if (!data.files || data.files.length === 0) {
        send(null);
        return;
      }

      var file = data.files[0];
      var reader = new FileReader();
      reader.onerror = function() {
        pending = null;
        reject(domError('DataError', 'Unable to read the shared image'));
      };
      reader.onload = function() {
        send({
          name: file.name || 'fenjoon-story.png',
          type: file.type,
          size: file.size,
          dataUrl: String(reader.result || '')
        });
      };
      reader.readAsDataURL(file);
    });
  };

  navigator.__fenjoonShareVersion = 2;
})();
"""

/**
 * JS that hands the FCM token to the web app: sets `window.__fenjoonFcmToken` and calls
 * `window.onFcmToken(token)` if the page defined it. The web app maps the token to the
 * logged-in user, since the device itself has no native identity.
 */
private fun fcmTokenInjectionScript(token: String): String {
    val quoted = JSONObject.quote(token)
    return "window.__fenjoonFcmToken=$quoted;" +
        "if(typeof window.onFcmToken==='function'){try{window.onFcmToken($quoted);}catch(e){}}"
}

class MainActivity : ComponentActivity() {
    private var currentUrl by mutableStateOf(FENJOON_START_URL)
    private var lastHandledNotificationOpen: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastHandledNotificationOpen = savedInstanceState?.getString(STATE_NOTIFICATION_OPEN_KEY)
        if (savedInstanceState == null) {
            NotificationPresentationState.reset()
        }
        currentUrl = intent.toFenjoonUrl()
        handleChatNotificationOpen(intent)
        setContent {
            FenjoonTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
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
        handleChatNotificationOpen(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lastHandledNotificationOpen?.let { outState.putString(STATE_NOTIFICATION_OPEN_KEY, it) }
        super.onSaveInstanceState(outState)
    }

    private fun handleChatNotificationOpen(sourceIntent: Intent) {
        if (sourceIntent.action != ChatNotificationOpenIntent.ACTION_OPEN_CHAT) return
        val conversationId = sourceIntent.getStringExtra(NotificationActionExtras.CONVERSATION_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val messageId = sourceIntent.getStringExtra(NotificationActionExtras.MESSAGE_ID)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val openToken = ChatNotificationOpenIntent.openToken(sourceIntent) ?: return
        if (openToken == lastHandledNotificationOpen) return
        val readUrl = sourceIntent.getStringExtra(NotificationActionExtras.READ_URL)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        lastHandledNotificationOpen = openToken
        sourceIntent.action = Intent.ACTION_VIEW
        sourceIntent.data = Uri.parse(currentUrl)
        sourceIntent.removeExtra(NotificationActionExtras.READ_URL)

        // Navigation is already represented by currentUrl. Clear the consumed card immediately;
        // the receipt is deliberately best-effort and cannot delay activity startup.
        Notifier.cancelConversation(applicationContext, conversationId)
        if (messageId != null && readUrl != null) {
            Thread {
                try {
                    NotificationActionHttpClient().postRead(readUrl, conversationId, messageId)
                } catch (_: Exception) {
                    // The conversation is already open; a read-receipt failure must remain silent.
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationPresentationState.setActivityForeground(true)
        // Re-arm the inactivity reminder on every open, so it only fires after N days of no use.
        ReminderScheduler.rearm(this)
    }

    override fun onPause() {
        NotificationPresentationState.setActivityForeground(false)
        super.onPause()
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

    // Bridges the WebView's file <input> to the system picker. onShowFileChooser hands us a
    // callback; we stash it, launch a chooser (document picker + camera when the input accepts
    // images), and deliver the chosen URIs back from the result callback below. Delivering a
    // value (even null) is required — otherwise the <input> stays wedged and won't reopen.
    var pendingFileChooserCallback by remember {
        mutableStateOf<ValueCallback<Array<Uri>>?>(null)
    }
    // Non-null while a camera capture is pending: the photo lands here via EXTRA_OUTPUT, and
    // the camera app returns RESULT_OK with no data, so we read the result back from this URI.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    // Held while the CAMERA permission prompt is up, so the chooser can open once it resolves.
    var pendingChooserParams by remember {
        mutableStateOf<WebChromeClient.FileChooserParams?>(null)
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooserCallback
        val cameraUri = pendingCameraUri
        pendingFileChooserCallback = null
        pendingCameraUri = null
        if (callback != null) {
            val parsed = WebChromeClient.FileChooserParams
                .parseResult(result.resultCode, result.data)
            val uris = when {
                parsed?.isNotEmpty() == true -> parsed
                // A camera capture returns OK with no data — the photo is at cameraUri.
                result.resultCode == Activity.RESULT_OK && cameraUri != null ->
                    arrayOf(cameraUri)
                else -> null
            }
            callback.onReceiveValue(uris)
        }
    }

    fun launchFileChooser(params: WebChromeClient.FileChooserParams, includeCamera: Boolean) {
        val extraIntents = mutableListOf<Intent>()

        val cameraUri = if (includeCamera) context.createCameraCaptureUri() else null
        val cameraIntent = cameraUri?.let { uri ->
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .takeIf { it.resolveActivity(context.packageManager) != null }
        }
        pendingCameraUri = if (cameraIntent != null) cameraUri else null
        if (cameraIntent != null) extraIntents += cameraIntent

        // Offer the gallery/photos app directly — the base ACTION_GET_CONTENT picker opens the
        // Files-style Documents UI, which many users don't recognize as "the gallery".
        if (params.acceptsImages()) {
            context.galleryPickIntent(params.allowsMultiple())?.let { extraIntents += it }
        }

        val chooser = Intent.createChooser(
            params.createIntent(),
            context.getString(R.string.file_chooser_title)
        )
        if (extraIntents.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
        }
        try {
            fileChooserLauncher.launch(chooser)
        } catch (_: ActivityNotFoundException) {
            pendingFileChooserCallback?.onReceiveValue(null)
            pendingFileChooserCallback = null
            pendingCameraUri = null
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val params = pendingChooserParams
        pendingChooserParams = null
        if (params != null) {
            // A denial is fine — fall back to the document picker without the camera option.
            launchFileChooser(params, includeCamera = granted)
        }
    }

    // POST_NOTIFICATIONS needs a runtime grant on Android 13+. The AndroidNotifications bridge
    // can trigger this from the web app at a good moment; the LaunchedEffect below is a fallback
    // so notifications still work if the web app never asks.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted — web can re-check via AndroidNotifications.notificationsEnabled() */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            webChromeClient = FenjoonWebChromeClient { callback, params ->
                // Release any previous, still-pending callback so a stale picker
                // can't wedge the <input> permanently.
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = callback

                val imagesAccepted = params.acceptsImages()
                val hasCameraPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (imagesAccepted && !hasCameraPermission) {
                    // Ask for camera access first; the chooser opens from the result.
                    pendingChooserParams = params
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    launchFileChooser(params, includeCamera = imagesAccepted)
                }
                true
            }
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
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                val shareHandler = WebAppInterface(context as Activity)
                WebViewCompat.addWebMessageListener(
                    this,
                    SHARE_BRIDGE_NAME,
                    setOf(FENJOON_URL)
                ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                    if (
                        isMainFrame &&
                        sourceOrigin.scheme == "https" &&
                        sourceOrigin.host == FENJOON_HOST &&
                        sourceOrigin.port == -1
                    ) {
                        shareHandler.share(message.data ?: "") { response ->
                            replyProxy.postMessage(response)
                        }
                    }
                }
            }
            addJavascriptInterface(
                NotificationBridge(context) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                },
                NOTIFICATIONS_BRIDGE_NAME
            )
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
        val activity = context as Activity
        val insetView = activity.window.decorView
        val density = context.resources.displayMetrics.density
        var animationDuration = 0L
        var lastKeyboardHeight = -1

        fun dispatchKeyboardHeight(insets: WindowInsetsCompat) {
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navigationBarBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            val keyboardHeight = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                ((imeBottom - navigationBarBottom).coerceAtLeast(0) / density).toInt()
            } else {
                0
            }
            if (keyboardHeight == lastKeyboardHeight) return
            lastKeyboardHeight = keyboardHeight

            webView.post {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('keyboardHeightChange'," +
                        "{detail:{height:$keyboardHeight,duration:$animationDuration}}));true;",
                    null
                )
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(insetView) { _, insets ->
            dispatchKeyboardHeight(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            insetView,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        animationDuration = animation.durationMillis
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    dispatchKeyboardHeight(insets)
                    return insets
                }
            }
        )
        ViewCompat.requestApplyInsets(insetView)

        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(insetView, null)
            ViewCompat.setWindowInsetsAnimationCallback(insetView, null)
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
        val pageUri = Uri.parse(url)
        val isFenjoonPage = pageUri.scheme == "https" &&
            pageUri.host == FENJOON_HOST && pageUri.port == -1
        // Fallback for WebViews that don't support DOCUMENT_START_SCRIPT. The
        // polyfill is idempotent, so it's a no-op when the document-start
        // injection already ran.
        if (isFenjoonPage) {
            view.evaluateJavascript(WEB_SHARE_POLYFILL, null)
        }
        // Hand the current FCM token to the web layer so it can register token↔user with the
        // backend using the logged-in session.
        if (isFenjoonPage) {
            TokenStore(view.context).get()?.takeIf { it.isNotEmpty() }?.let { token ->
                view.evaluateJavascript(fcmTokenInjectionScript(token), null)
            }
        }
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

/**
 * Handles `<input type="file">` taps. Android's WebView has no default file picker,
 * so without this the upload field silently does nothing. Delegates the actual
 * picker launch (and result plumbing) back to the composable via [onShowFileChooser].
 */
private class FenjoonWebChromeClient(
    private val onShowFileChooser: (
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ) -> Boolean
) : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = onShowFileChooser(filePathCallback, fileChooserParams)
}

private fun Intent?.toFenjoonUrl(): String {
    val source = this ?: return FENJOON_START_URL
    if (source.action == ChatNotificationOpenIntent.ACTION_OPEN_CHAT) {
        val validated = ChatNotificationOpenIntent.safeWebLink(
            source.getStringExtra(NotificationActionExtras.LINK),
        )
        return validated ?: FENJOON_START_URL
    }
    val url = source.data ?: return FENJOON_START_URL
    return url.toFenjoonWebUrl() ?: FENJOON_START_URL
}

/**
 * Creates a FileProvider URI the camera app can write a captured photo into. Old captures
 * are cleared first so temp files don't accumulate in the cache.
 */
private fun Context.createCameraCaptureUri(): Uri? = try {
    val dir = File(cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
} catch (_: Exception) {
    null
}

/**
 * Whether the file <input>'s `accept` types include images, in which case offering the camera
 * and gallery makes sense. An empty/absent filter accepts anything, so both are offered too.
 */
private fun WebChromeClient.FileChooserParams.acceptsImages(): Boolean {
    val types = acceptTypes?.filter { it.isNotBlank() }
    if (types.isNullOrEmpty()) return true
    return types.any { type ->
        val value = type.trim().lowercase()
        value == "*/*" || value.startsWith("image/") ||
            value.endsWith(".jpg") || value.endsWith(".jpeg") ||
            value.endsWith(".png") || value.endsWith(".webp") ||
            value.endsWith(".gif") || value.endsWith(".heic") || value.endsWith(".heif")
    }
}

/** Whether the <input> allows selecting more than one file (the `multiple` attribute). */
private fun WebChromeClient.FileChooserParams.allowsMultiple(): Boolean =
    mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

/**
 * Intent that opens the device gallery/photos app to pick existing image(s), or null if no
 * gallery app is available. Needs no runtime permission — ACTION_PICK only returns what the
 * user explicitly selects.
 */
private fun Context.galleryPickIntent(allowMultiple: Boolean): Intent? {
    val intent = Intent(Intent.ACTION_PICK)
        .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
    if (allowMultiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    return intent.takeIf { it.resolveActivity(packageManager) != null }
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
