# CyTube APK

**A native CyTube client for Android, Android TV and Amazon Fire TV.**

Watch synchronized videos with others, chat in real time, browse channels, manage playlists and use CyTube custom emotes — without relying on a browser.

[**Download the latest APK →**](https://github.com/Hostu404/CyTube-APK/releases/latest)

---

## Screenshots

<img width="719" height="965" alt="image" src="https://github.com/user-attachments/assets/684f609c-9abd-4a1f-8342-c8fb956f78ee" />


---

## What is CyTube APK?

CyTube APK brings the CyTube experience to Android devices as a dedicated native application.

It is designed for phones, tablets, Android TV and Amazon Fire TV, with the interface adapting to the device you're using.

You can join CyTube channels, watch synchronized media with other people, participate in chat and manage playlists directly from the app.

This is a **native Android application**, not simply the CyTube website wrapped inside a WebView.

---

## Features

* Browse and join channels
* Channel information
* User list
* Playlists
* Polls
* Favourites
* Recently joined channels
* Guest access
* Native video playback (YouTube — including live streams, direct files, HLS, Google Drive and more)
* Embedded playback for Dailymotion and Vimeo — plays inside the app itself (chat, playlist and sync all stay native) rather than dropping straight to Compatibility View
* Automatic fallback to Compatibility View for sources native and embedded playback can't handle (e.g. Twitch, which needs the real page's own domain to work at all)
* Playback stays in sync with the room, with an adjustable sync tolerance
* Turn off "stay in sync" to browse the playlist and watch your own picks independently — the app auto-advances to your next pick when one finishes, without affecting anyone else in the room
* Custom CyTube emotes
* NicoNico Chat - Enable by tapping the square.
* Quick tap to reply/repost emotes
* Real-time channel chat
* User messages and system messages
* Mute toggle
* Ambient glow behind the video
* Fullscreen playback
* Tap the channel name to refresh chat and playback (rate-limited)
* Password-protected channel support
* Opens `cytu.be/r/<channel>` links directly into the app

### TV support

Designed to work on:

* Android TV
* Amazon Fire TV
* TV remotes and D-pad navigation
* Large-screen displays

**Accessing chat and the playlist on TV:** from the video, press **Down** on the D-pad.

* If "stay in sync" is **on** (Settings), Down opens a dedicated full-screen chat view.
* If "stay in sync" is **off**, Down opens a full-screen playlist view instead, with a search bar for quickly filtering items and the same personal-pick behaviour as on phone — chat isn't reachable while in this mode.

Press **Up** or **Back** to return to the video from either view.

The same APK can be sideloaded onto supported Android TV and Fire TV devices.

---

## Download

### Android

Download the latest release:

[**Download the latest APK**](https://github.com/Hostu404/CyTube-APK/releases/latest)

Then open the APK on your Android device and install it.

You may need to allow installation from unknown sources depending on your device.

### Android TV / Fire TV

The easiest path is [Downloader](https://amzn.to/2Ihmizw) (search "Downloader" on the Fire TV
Appstore) — enter the direct link to the APK and it installs it for you.
You'll need "Apps from Unknown Sources" turned on for whichever app you
used to download it, under Settings → My Fire TV → Developer Options.

There's no Play Store or Amazon Appstore listing — this is a hobby project,
distributed as an open-source APK.

---

## Requirements

* **Android 8.0 (API 26) or newer**
* Internet connection
* A CyTube account is optional

Some media sources may have additional playback requirements or compatibility limitations.

---

## Privacy & Security

CyTube APK is open source.

CyTube credentials are handled locally by the application.

Your password is sent once, directly to your CyTube server, the same way the CyTube website itself logs in — it is never written to disk. If you choose to stay logged in, only the signed session cookie the server returns is saved, encrypted on your device. Logging out clears it locally and tells the server to invalidate it too.

The source code is available in this repository for inspection.

---

## Current limitations

CyTube supports a large variety of media sources, and compatibility can vary between them.

Known limitations include:

* Some video sources may not play correctly.
* Some playback synchronisation edge cases remain.
* Picture-in-picture support is still being improved.
* Certain media providers have their own restrictions.
* TV-specific behaviour and compatibility are still being refined.
* Vimeo's embedded player currently plays with audio but doesn't fill its frame correctly (video renders undersized) — being tracked, use Compatibility View as a workaround in the meantime if it bothers you.

The application is actively being developed, so behaviour may change between releases.

If you find a problem, please report it with enough information to reproduce it.

---

## Reporting a problem

Found a bug?

[**Open an issue →**](https://github.com/Hostu404/CyTube-APK/issues)

When reporting an issue, include:

* Device model
* Android version
* App version
* CyTube channel, if relevant
* What you expected to happen
* What actually happened
* Steps to reproduce the problem

Screenshots or logs are useful when available.

---

## For developers

CyTube APK is built as a native Android application.

The project is structured around separate components for the CyTube connection, playback handling, chat and application UI.

The source code is available here on GitHub.

### Build from source

Clone the repository:

```bash
git clone https://github.com/Hostu404/CyTube-APK.git
```

Open the project in Android Studio and allow Gradle to synchronise.

Build the application using the standard Android Studio build tools.

---

## Credits

This app exists because of [CyTube](https://github.com/calzoneman/sync)
itself — all of the protocol behaviour here (sync logic, emote handling,
frame shapes) is a port of what CyTube's own client and server already do,
not something invented from scratch. It also depends on:

- [Jetpack Compose](https://developer.android.com/jetpack/compose) and
  [Media3/ExoPlayer](https://developer.android.com/media/media3) — UI and
  playback
- [Socket.IO (Java client)](https://github.com/socketio/socket.io-client-java) —
  the same realtime protocol the CyTube site itself speaks
- [OkHttp](https://square.github.io/okhttp/) — networking
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) —
  resolving YouTube items to a direct stream
- [Jsoup](https://jsoup.org/) — parsing/sanitising the HTML CyTube sends for
  chat, MOTDs, and polls
- [Coil](https://coil-kt.github.io/coil/) — image and animated-GIF loading

---

## License

CyTube APK is released under the **GNU General Public License v3.0**.

See [`LICENSE`](LICENSE) for the complete licence.

---

## Support

This is a hobby project, built and maintained in spare time. If you get some
use out of it:

**☕ [ko-fi.com/hostu](https://ko-fi.com/hostu)**
