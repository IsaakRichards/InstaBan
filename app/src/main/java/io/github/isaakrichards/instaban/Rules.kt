package io.github.isaakrichards.instaban

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/** Where InstaBan is and isn't allowed to go. */
object Rules {
    const val INBOX = "https://www.instagram.com/direct/inbox/"

    private val IG_HOSTS = setOf("instagram.com", "www.instagram.com", "m.instagram.com")

    // Hosts needed for login, 2FA, and account checks.
    private val AUTH_HOSTS = setOf(
        "accountscenter.instagram.com",
        "www.facebook.com", "m.facebook.com", "facebook.com",
        "auth.meta.com", "accounts.meta.com"
    )

    // The Instagram paths the main window may be on.
    private val ALLOWED_PREFIXES = listOf(
        "/direct",
        "/accounts/login", "/accounts/onetap", "/accounts/emailsignup",
        "/accounts/password", "/accounts/logout", "/accounts/suspended",
        "/accounts/two_factor", "/accounts/consent",
        "/challenge", "/two_factor", "/auth_platform",
        "/consent", "/session", "/oauth", "/legal", "/privacy"
    )

    // Things a friend can send you: a post, a reel, an IGTV video, a story or highlight.
    private val SHARED = Regex("^/(?:[^/]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)")
    private val STORY = Regex("^/stories/([^/]+)/([^/]+)")

    fun isInstagram(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host in IG_HOSTS || host.endsWith(".instagram.com")
    }

    fun isAuthHost(uri: Uri): Boolean = uri.host?.lowercase() in AUTH_HOSTS

    fun isAllowedInstagram(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host !in IG_HOSTS) return false
        val path = uri.path ?: "/"
        return ALLOWED_PREFIXES.any { path.startsWith(it) }
    }

    /**
     * A stable key for one shared item ("reel/ABC123", "stories/name/987"),
     * or null if the URL isn't a single shared item. /reel/X and /reels/X
     * count as the same item.
     */
    fun sharedKey(uri: Uri): String? {
        if (!isInstagram(uri)) return null
        val path = uri.path ?: return null
        SHARED.find(path)?.let { m ->
            val kind = when (val k = m.groupValues[1]) { "reels" -> "reel"; else -> k }
            return "$kind/${m.groupValues[2]}"
        }
        STORY.find(path)?.let { m -> return "stories/${m.groupValues[1]}/${m.groupValues[2]}" }
        return null
    }

    fun openExternal(context: Context, uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Links sent in chats go through l.instagram.com; returns the real target. */
    fun unwrapLinkShim(uri: Uri): Uri? =
        if (uri.host == "l.instagram.com") uri.getQueryParameter("u")?.let(Uri::parse) else null

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Strip the WebView markers so Instagram treats us like mobile Chrome
            // instead of pushing the "get the app" wall.
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
    }
}
