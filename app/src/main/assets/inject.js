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
    // Navigation tabs: home feed, search/explore, reels, create, activity.
    // (Exact hrefs only, so reels a friend shares in a chat stay visible.)
    'a[href="/"]',
    'a[href="/explore/"]',
    'a[href="/reels/"]',
    'a[href*="/create/"]',
    'svg[aria-label="Home"]',
    'svg[aria-label="Search"]',
    'svg[aria-label="Explore"]',
    'svg[aria-label="Reels"]',
    'svg[aria-label="New post"]',
    'svg[aria-label="Create"]',
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
                    'Create', 'Threads'];

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
      if (!href.startsWith('/direct') && !href.startsWith('/accounts') && !href.startsWith('/notifications')) {
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

  // ---------------------------------------------------------------------
  // Shared reels / posts / stories
  // ---------------------------------------------------------------------
  var MODE = window.__instabanMode || 'main';
  var bridge = window.InstaBan;
  var SHARED_PATH = /^\/(?:[^\/]+\/)?(p|reel|reels|tv)\/[A-Za-z0-9_-]+|^\/stories\/[^\/]+\/[^\/]+/;

  // 1) In a chat or on the notifications page, tapping a post/reel/story opens it in InstaBan's locked
  //    single-item viewer instead of Instagram's swipeable one.
  if (MODE === 'main') {
    document.addEventListener('click', function (e) {
      var here = location.pathname;
      if (!here.startsWith('/direct') && !here.startsWith('/accounts/activity') && !here.startsWith('/notifications')) return;
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      var u;
      try { u = new URL(a.getAttribute('href'), location.href); } catch (_) { return; }
      if (!/(^|\.)instagram\.com$/.test(u.hostname) || !SHARED_PATH.test(u.pathname)) return;
      e.preventDefault();
      e.stopPropagation();
      if (bridge) bridge.openShared(u.href);
    }, true);
  }

  // 2) Doom guard. Some shared reels open in an overlay right inside the
  //    chat with no link to catch. The first video that plays in such a
  //    viewer is the one your friend sent; any *other* video starting there
  //    (i.e. you swiped on to the next reel) is paused and you're snapped
  //    back. Videos sent as ordinary chat messages aren't affected.
  function viewerOf(el) {
    var d = el.closest('[role="dialog"]');
    if (d) return d;
    for (var n = el.parentElement; n && n !== document.body; n = n.parentElement) {
      var cs = getComputedStyle(n);
      if (cs.position === 'fixed' && n.offsetHeight > innerHeight * 0.8 && n.offsetWidth > innerWidth * 0.8) return n;
    }
    return MODE === 'viewer' ? document.body : null;
  }

  // Videos side by side in one swipe-left carousel belong to the same post.
  function carouselOf(el) {
    for (var n = el.parentElement; n && n !== document.body; n = n.parentElement) {
      var ox = getComputedStyle(n).overflowX;
      if ((ox === 'auto' || ox === 'scroll') && n.scrollWidth > n.clientWidth + 10) return n;
    }
    return null;
  }

  function sameItem(v, first) {
    if (v === first.el) return !first.src || !v.currentSrc || v.currentSrc === first.src;
    var c = carouselOf(first.el);
    if (c && c === carouselOf(v)) return true;
    var art = first.el.closest('article');
    return !!art && art === v.closest('article') && MODE === 'viewer';
  }

  // Stop vertical swiping in an in-chat reel viewer (horizontal carousels
  // and the comment sheet are left alone).
  function lockVertical(viewer, v) {
    if (viewer === document.body) return;
    for (var n = v.parentElement; n && n !== viewer.parentElement; n = n.parentElement) {
      var oy = getComputedStyle(n).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight > n.clientHeight + 10) {
        n.style.setProperty('overflow-y', 'hidden', 'important');
        n.style.setProperty('overscroll-behavior', 'none', 'important');
      }
    }
  }

  var firsts = new WeakMap();
  var lastDoom = 0;

  function closeViewer(viewer) {
    var x = viewer.querySelector('svg[aria-label="Close"], svg[aria-label="Back"]');
    var btn = x && x.closest('button, [role="button"], a');
    if (btn) { btn.click(); return; }
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  }

  document.addEventListener('playing', function (e) {
    var v = e.target;
    if (!(v instanceof HTMLVideoElement)) return;
    var viewer = viewerOf(v);
    if (!viewer) return;

    var first = firsts.get(viewer);
    if (!first || !first.el.isConnected) {
      firsts.set(viewer, { el: v, src: v.currentSrc });
      lockVertical(viewer, v);
      return;
    }
    if (sameItem(v, first)) return;

    // Doom detected.
    v.pause();
    v.muted = true;
    if (bridge) bridge.doom();
    var now = Date.now();
    if (now - lastDoom < 2500 && MODE === 'main') {
      closeViewer(viewer);          // twice in a row: just close the viewer
    } else if (first.el.isConnected && first.el !== v) {
      first.el.scrollIntoView({ block: 'center' });
    }
    lastDoom = now;
  }, true);
})();
