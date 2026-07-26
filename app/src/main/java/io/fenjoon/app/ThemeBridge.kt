package io.fenjoon.app

import android.app.Activity
import android.webkit.JavascriptInterface

class ThemeBridge(
    private val activity: Activity,
    private val onThemeChanged: (Boolean) -> Unit
) {
    @JavascriptInterface
    fun setTheme(isDark: Boolean) {
        activity.runOnUiThread { onThemeChanged(isDark) }
    }
}
