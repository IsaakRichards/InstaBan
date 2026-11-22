/*
 * InstaBan cosmetic layer.
 *
 * The real wall is in MainActivity (any navigation outside /direct is
 * bounced back). This script just hides the doors so you don't see them.
 * Instagram renames its CSS classes constantly, so we target stable
 * things instead: link hrefs, aria-labels, and roles. If Instagram moves
 * something and a feed button reappears, add a selector to HIDE below.
 */
(function () {
  if (window.__instabanInstalled) return;
  window.__instabanInstalled = true;

  var HIDE = [
    // Navigation tabs: home feed, search/explore, reels, create, activity
    'a[href="/"]',
    'a[href="/explore/"]',
    'a[href^="/explore/"]',
    'a[href="/reels/"]',
    'a[href^="/reels/"]',
    'a[href*="/create/"]',
    'a[href="/accounts/activity/"]',
    'a[href="/notifications/"]',
    'svg[aria-label="Home"]',
    'svg[aria-label="Search"]',
    'svg[aria-label="Explore"]',
    'svg[aria-label="Reels"]',
    'svg[aria-label="New post"]',
    'svg[aria-label="Create"]',
    'svg[aria-label="Notifications"]',
    'svg[aria-label="Threads"]',
    // "Open in app" / "Get the app" nags
    'a[href*="play.google.com"]',
    'a[href*="app.adjust.com"]',
    'a[href*="instagram://"]'
  ];

  var style = document.createElement('style');
  style.id = 'instaban-style';
  style.textContent = HIDE.join(',\n') + ' { display: none !important; }';
  (document.head || document.documentElement).appendChild(style);

  // Some icons are wrapped in a button/div rather than a link; hide the
  // clickable ancestor of those SVGs too, but only in navigation bars so
  // we never touch message bubbles.
  var NAV_LABELS = ['Home', 'Search', 'Explore', 'Reels', 'New post',
                    'Create', 'Notifications', 'Threads'];

  function sweep() {
    NAV_LABELS.forEach(function (label) {
      document.querySelectorAll('svg[aria-label="' + label + '"]').forEach(function (svg) {
        var clickable = svg.closest('a, [role="button"], [role="link"]');
        if (clickable && clickable.closest('nav, [role="navigation"], [role="tablist"]')) {
          clickable.style.setProperty('display', 'none', 'important');
        }
      });
    });
    // The profile tab: a nav link wrapping your own avatar.
    document.querySelectorAll('nav a, [role="navigation"] a, [role="tablist"] a').forEach(function (a) {
      var href = a.getAttribute('href') || '';
      if (!href.startsWith('/direct') && !href.startsWith('/accounts')) {
        a.style.setProperty('display', 'none', 'important');
      }
    });
  }

  var pending = false;
  new MutationObserver(function () {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () { pending = false; sweep(); });
  }).observe(document.documentElement, { childList: true, subtree: true });

  sweep();
})();
