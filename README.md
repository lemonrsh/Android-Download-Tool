# Fetchly

A segmented download manager for Android (Kotlin / Jetpack Compose) plus a Firefox extension
that hands browser downloads to it. Original code: the feature set (download list, categories,
pause/resume, settings, browser integration) was modelled on what the NeatDM installer exposes,
but none of its code, assets or name were reused.

## What works
- Multi-connection downloads (1-8 segments), resumable across pause, retries and app restarts
- Foreground service with a speed notification; finished files land in Downloads/Fetchly
- Category filters (Video / Audio / Compressed / Documents / Programs / Other)
- Add by URL, clipboard prefill, Share-sheet target, `fetchly://add?url=...` (both ask for confirmation)
- Firefox extension that intercepts downloads and forwards URL + referer + cookies + user agent,
  detects videos/audio on each page (toolbar popup + badge count), and shows a floating
  Download button over playing videos. HLS/DASH streams are listed but not downloadable yet.

## Build the app
1. Open this folder in Android Studio (Koala or newer); it creates the Gradle wrapper.
2. Run on a device or emulator with Android 10+.
(This project was written without an Android SDK available, so it has not been compiled yet.
Expect to fix a few small compile errors on first build.)

## Install the extension (Firefox for Android)
- Quick test (temporary, until Firefox restarts): enable USB debugging on the phone and
  *Remote debugging via USB* in Firefox, then from this folder run
  `npx web-ext run --source-dir extension --target firefox-android --firefox-apk org.mozilla.firefox --android-device <adb serial>`
  (Nightly's package is `org.mozilla.fenix`, Beta's is `org.mozilla.firefox_beta`).
- Permanent: zip the `extension/` folder, sign it on addons.mozilla.org (unlisted is fine), install the .xpi.
- Chrome for Android has no extension support; on desktop Chromium this extension needs a Manifest V3 port.

## Pair them
1. In Fetchly: Settings → turn on *Browser integration* → *Copy token*.
2. In Firefox: Fetchly extension options → paste token → *Test connection*.

## How the integration is kept safe
The app listens only on 127.0.0.1:6877. Requests from web pages (http/https Origin) are rejected,
the Host header is checked, and every call needs the pairing token.

## Known limits / next steps
- No download queue or concurrency cap, no scheduler, no speed limit
- Android 15 caps `dataSync` foreground services at about 6 h/day, so "always on" integration can be cut off
- Cancelled navigations may leave a blank tab in Firefox
- Placeholder launcher icon
