package io.github.isaakrichards.instaban

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * InstaBan: Instagram Direct and nothing else.
 *
 * It wraps Instagram's own website (no private API, no password ever touches
 * this code; you log in on instagram.com itself) and refuses to go anywhere
 * that isn't the inbox, a chat, or the login/security pages.
 */
class MainActivity : Activity() {

    companion object {
        private const val INBOX = Rules.INBOX
        private const val REQ_FILE = 1001
        private const val REQ_PERMS = 1002
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var activityButton: ImageButton

    private var injectJs: String = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injectJs = "window.__instabanMode='main';" +
            assets.open("inject.js").bufferedReader().use { it.readText() }

        val root = FrameLayout(this)
        web = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        errorView = buildErrorView()

        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.TOP))
        root.addView(errorView, FrameLayout.LayoutParams(MATCH, MATCH))
        activityButton = buildActivityButton()
        root.addView(activityButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(88))
        })
        setContentView(root)

        Rules.configure(web)
        web.addJavascriptInterface(Bridge(), "InstaBan")
        web.webViewClient = Gatekeeper()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(startUrl(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(startUrl(intent)) }
    }

    private fun startUrl(intent: Intent?): String {
        val data = intent?.data ?: return INBOX
        return if (Rules.isAllowedInstagram(data)) data.toString() else INBOX
    }

    // ---------- the wall ----------

    private var lastViewerUrl: String? = null
    private var lastViewerAt = 0L

    /** A friend sent a reel/post/story: show just that one, in the locked viewer. */
    private fun openViewer(uri: Uri) {
        val url = uri.toString()
        val now = System.currentTimeMillis()
        if (Rules.sharedKey(uri) == lastViewerUrl?.let { Rules.sharedKey(Uri.parse(it)) } && now - lastViewerAt < 1500) return
        lastViewerUrl = url
        lastViewerAt = now
        startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_URL, url))
    }

    private inner class Gatekeeper : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()

            if (scheme != "http" && scheme != "https") {
                // instagram://, intent:// etc. Never hand these to the real app.
                return true
            }

            // Links people send you in chats go through l.instagram.com; open the
            // real destination in your normal browser.
            Rules.unwrapLinkShim(uri)?.let { Rules.openExternal(this@MainActivity, it); return true }
            if (uri.host == "l.instagram.com") return true

            if (Rules.isAllowedInstagram(uri) || Rules.isAuthHost(uri)) return false

            if (Rules.sharedKey(uri) != null) {
                openViewer(uri)
                return true
            }

            if (Rules.isInstagram(uri)) {
                // Home, explore, reels feed, profiles: no.
                // The root path right after login is the usual case; send it to the inbox.
                if ((uri.path ?: "/") == "/") {
                    view.loadUrl(INBOX)
                } else {
                    blocked()
                }
                return true
            }

            // Anything else on the web: system browser.
            Rules.openExternal(this@MainActivity, uri)
            return true
        }

        // Instagram is a single-page app: tapping a tab changes the URL with
        // history.pushState, which never reaches shouldOverrideUrlLoading.
        // This catches those in-page jumps and bounces them back.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            val uri = Uri.parse(url ?: return)
            updateActivityButton(uri)
            if (!Rules.isInstagram(uri) || Rules.isAllowedInstagram(uri)) return

            // The bare root is the home feed: Instagram lands there after login,
            // so go straight to the inbox instead of back into the login page.
            if ((uri.path ?: "/") == "/") {
                view.loadUrl(INBOX)
                return
            }
            // A shared reel opened inside the chat: move it to the locked viewer
            // and put the chat back the way it was.
            if (Rules.sharedKey(uri) != null) openViewer(uri) else blocked()
            if (view.canGoBack()) {
                view.goBack()
                // If going back still lands outside (e.g. first page was the feed),
                // the next doUpdateVisitedHistory call will send us to the inbox.
            } else {
                view.loadUrl(INBOX)
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            errorView.visibility = View.GONE
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(injectJs, null)
            progress.visibility = View.GONE
            url?.let { updateActivityButton(Uri.parse(it)) }
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) errorView.visibility = View.VISIBLE
        }
    }

    /** Called from inject.js. */
    private inner class Bridge {
        @JavascriptInterface
        fun openShared(url: String) = runOnUiThread {
            val uri = Uri.parse(url)
            if (Rules.sharedKey(uri) != null) openViewer(uri)
        }

        @JavascriptInterface
        fun doom() = runOnUiThread {
            val now = System.currentTimeMillis()
            if (now - lastToast > 2000) {
                lastToast = now
                Toast.makeText(this@MainActivity, R.string.doom_blocked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- notifications button ----------

    /** Instagram keeps its heart button on the (banned) home feed, so the inbox gets its own. */
    private fun buildActivityButton() = ImageButton(this).apply {
        setImageResource(R.drawable.ic_activity)
        contentDescription = getString(R.string.notifications)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC2B2140.toInt())
        }
        elevation = dp(4).toFloat()
        visibility = View.GONE
        setOnClickListener { web.loadUrl(Rules.ACTIVITY) }
    }

    private fun updateActivityButton(uri: Uri) {
        val path = uri.path ?: ""
        activityButton.visibility =
            if (Rules.isInstagram(uri) && path.startsWith("/direct/inbox")) View.VISIBLE else View.GONE
    }

    private var lastToast = 0L
    private fun blocked() {
        val now = System.currentTimeMillis()
        if (now - lastToast > 2000) {
            lastToast = now
            Toast.makeText(this, R.string.blocked, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- photos, voice notes, progress ----------

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            // Re-inject early too, so the feed tabs never flash on screen.
            if (newProgress > 30) view.evaluateJavascript(injectJs, null)
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = mutableListOf<String>()
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) wanted += Manifest.permission.RECORD_AUDIO
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) wanted += Manifest.permission.CAMERA
            val origin = request.origin.host ?: ""
            if (!origin.endsWith("instagram.com")) {
                request.deny(); return
            }
            val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                request.grant(request.resources)
            } else {
                pendingPermission = request
                requestPermissions(missing.toTypedArray(), REQ_PERMS)
            }
        }
    }

    @Deprecated("Activity result API needs AndroidX; this keeps the app dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERMS) return
        val req = pendingPermission ?: return
        pendingPermission = null
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            req.grant(req.resources)
        } else {
            req.deny()
        }
    }

    private fun download(url: String, ua: String, disposition: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    // ---------- lifecycle & back ----------

    @Deprecated("Fine for minSdk 26 without AndroidX.")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // ---------- small helpers ----------

    private fun buildErrorView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setBackgroundColor(if (isNight()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        isClickable = true
        addView(TextView(context).apply {
            setText(R.string.offline)
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(16))
        })
        addView(Button(context).apply {
            setText(R.string.retry)
            setOnClickListener { web.reload() }
        })
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
