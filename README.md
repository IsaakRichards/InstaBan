# InstaBan

Instagram Direct, and nothing else. No feed, no stories, no reels, no explore, no profile browsing.

## How it works

InstaBan is a thin shell around Instagram's own website (`instagram.com/direct/inbox/`):

- **The wall (`MainActivity.kt`)**: every navigation is checked. Only `/direct/...` and the login / 2FA / security-check pages are allowed. Tapping into the feed, reels, explore, a profile or a post gets bounced back with a "Only messages live here" toast. This also catches Instagram's in-page (pushState) navigation.
- **The curtain (`assets/inject.js`)**: hides the Home / Search / Reels / Create / Profile buttons and the "get the app" nags so you don't see the doors at all.
- Links people send you in chats open in your normal browser.
- Photo sending, voice notes, downloads, and dark mode (follows the system) all work.

Because it uses the real website, you log in on Instagram's own page. InstaBan never sees your password and doesn't use any private API, which keeps the ban risk about as low as using Instagram in a browser.

## Building

No Android Studio needed: every push to `main` builds the APK and publishes it under [Releases](../../releases). Grab the newest `InstaBan-*.apk`.

Locally: `gradle assembleRelease` with Gradle 8.14 and the Android SDK (API 35).

## Limits

- **No push notifications.** A web wrapper can't get Instagram's push. Open the app to check messages.
- Shared posts/reels inside chats can't be opened (that would be the feed sneaking back in).
- Instagram changes its web markup often. If a feed button reappears, add a selector to the `HIDE` list in `inject.js`. The URL wall still stops it from working in the meantime.
