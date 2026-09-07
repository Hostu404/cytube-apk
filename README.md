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
* Guest access
* Custom CyTube emotes
* NicoNico Chat - Enable by tapping the square.
* Quick tap to reply/repost emotes
* Real-time channel chat
* User messages and system messages

### TV support

Designed to work on:

* Android TV
* Amazon Fire TV
* TV remotes and D-pad navigation
* Large-screen displays

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

This app exists because of **CyTube itself** — all of the protocol behaviour here (sync logic, emote handling, frame shapes) is a port of what CyTube's own client and server already do, not something invented from scratch.

And a very special thank you to **Roberts Kinoplex** for the many hours of time that brought me countless free joy.

It also depends on:

* **Jetpack Compose** and **Media3/ExoPlayer** — UI and playback
* **Socket.IO (Java client)** — the same realtime protocol the CyTube site itself speaks
* **OkHttp** — networking
* **NewPipeExtractor** — resolving YouTube items to a direct stream
* **Jsoup** — parsing/sanitising the HTML CyTube sends for chat, MOTDs, and polls
* **Coil** — image and animated-GIF loading

---

## License

CyTube APK is released under the **GNU General Public License v3.0**.

See [`LICENSE`](LICENSE) for the complete licence.

---

## Support

This is a hobby project, built and maintained in spare time. If you get some
use out of it:

**☕ [ko-fi.com/hostu](https://ko-fi.com/hostu)**

