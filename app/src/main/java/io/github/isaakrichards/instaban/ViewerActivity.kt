package io.github.isaakrichards.instaban

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast

/**
 * Shows exactly one thing a friend sent you: one reel, one post, one story.
 *
 * The window is locked to that single item. Swiping to the next reel, tapping
 * "more reels", opening a profile, or letting a story auto-advance to someone
 * else's all get bounced back. Press back and you're returned to the chat,
 * right where you left it (the chat window is never touched).
 */
class ViewerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var lockedUrl: String
    private lateinit var lockedKey: String
    private lateinit var lockedPath: String
    private var injectJs = ""
    private var lastToast = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        val key = uri?.let(Rules::sharedKey)
        if (uri == null || key == null) {
            finish(); return
        }
        lockedUrl = uri.toString()
        lockedKey = key
        lockedPath = uri.path ?: "/"
        injectJs = "window.__instabanMode='viewer';" +
            assets.open("inject.js").bufferedReader().use { it.readText() }

        val root = FrameLayout(this)
        web = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(progress, FrameLayout.LayoutParams(MATCH, (3 * resources.displayMetrics.density).toInt(), Gravity.TOP))
        setContentView(root)

        Rules.configure(web)
        web.addJavascriptInterface(Bridge(), "InstaBan")
        web.webViewClient = Lock()
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress > 30) view.evaluateJavascript(injectJs, null)
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(lockedUrl)
    }

    private fun isAllowedHere(uri: Uri): Boolean {
        if (Rules.sharedKey(uri) == lockedKey) return true
        // Sub-pages of the same item: comments, likes.
        if (Rules.isInstagram(uri) && (uri.path ?: "").startsWith(lockedPath.trimEnd('/') + "/")) return true
        // Login / security checks, in case the session needs refreshing.
        return Rules.isAllowedInstagram(uri) && !(uri.path ?: "").startsWith("/direct") || Rules.isAuthHost(uri)
    }

    private inner class Lock : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true
            Rules.unwrapLinkShim(uri)?.let { Rules.openExternal(this@ViewerActivity, it); return true }
            if (isAllowedHere(uri)) return false
            if (Rules.isInstagram(uri)) {
                if ((uri.path ?: "").startsWith("/direct")) finish() else blocked()
                return true
            }
            Rules.openExternal(this@ViewerActivity, uri)
            return true
        }

        // Catches the swipe-to-next-reel / story auto-advance, which change the
        // URL in-page without a real navigation.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            val uri = Uri.parse(url ?: return)
            if (isAllowedHere(uri)) return
            blocked()
            if (view.canGoBack()) view.goBack() else view.loadUrl(lockedUrl)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(injectJs, null)
            progress.visibility = View.GONE
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun doom() = runOnUiThread { blocked() }

        // In the viewer, a tap on another shared item stays put.
        @JavascriptInterface
        fun openShared(url: String) = runOnUiThread { blocked() }
    }

    private fun blocked() {
        val now = System.currentTimeMillis()
        if (now - lastToast > 2000) {
            lastToast = now
            Toast.makeText(this, R.string.doom_blocked, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Fine for minSdk 26 without AndroidX.")
    override fun onBackPressed() {
        // Don't walk back through history in here: back means "done watching".
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) web.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
