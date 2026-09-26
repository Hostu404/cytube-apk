# CyTube APK

**A native CyTube client for Android, Android TV and Amazon Fire TV.**

Watch videos in sync with others, chat in real time, add to the playlist and use channel emotes, as a real Android app rather than the CyTube website wrapped in a WebView. The interface adapts to phones, tablets and TVs.

[**Download the latest APK →**](https://github.com/Hostu404/CyTube-APK/releases/latest)

---

## Screenshots

<img width="719" height="965" alt="image" src="https://github.com/user-attachments/assets/684f609c-9abd-4a1f-8342-c8fb956f78ee" />

---

## Why is CyTube APK?

https://youtube.com/watch?v=5hLWTEFZLZM

## Support

This is a hobby project, built and maintained in spare time. If you get some
use out of it:

**☕ [ko-fi.com/hostu](https://ko-fi.com/hostu)**

---

## Features

### Watching

* Playback stays in sync with the room, with an adjustable sync tolerance. Small drift is corrected by briefly speeding up or slowing down rather than jumping.
* Native playback for YouTube, direct files, HLS, Google Drive, Streamable and PeerTube
* Embedded playback for YouTube live streams, Dailymotion, Vimeo and custom embeds (such as 8chan) — the video plays inside the app while chat, playlist and sync stay native
* Compatibility View (the full CyTube page) as a fallback for sources neither can handle, such as Twitch
* For videos offered in several qualities, picks one your connection can handle, steps down if playback stalls and back up when it can
* Turn off "stay in sync" to watch your own picks from the playlist without affecting anyone else; it moves on to your next pick when one finishes
* Fullscreen, mute toggle, and an ambient glow behind the video

### Chat

* Real-time chat with custom channel emotes
* Messages that mention your name are highlighted
* Private messages (phone): tap a PM's name to reply, or the mail icon in the user list to start one
* Tap an emote in chat to reuse it
* Niconico-style chat overlay across the video; tap the square to turn it on

### Channels

* Browse and join channels, with favourites and recently joined
* Add videos to the playlist from a link (phone), with **Play next** if the channel allows it. Links from your phone's share sheet work as they are: YouTube mobile, Shorts and youtu.be links, links pasted with a title around them, and short links like t.co
* Vote in polls; results stay visible after a poll closes
* User list, channel notice, password-protected channels and guest access
* Tap the channel name to refresh chat and playback
* Opens `cytu.be/r/<channel>` links straight into the app

### Android TV / Fire TV

Works with a TV remote's D-pad. From the video, press **Down**:

* With "stay in sync" **on** (Settings), Down opens a full-screen chat view.
* With "stay in sync" **off**, Down opens a full-screen playlist with a search bar, for picking your own videos (chat isn't available in this mode).

Press **Up** or **Back** to return to the video. Adding videos and private messages are phone-only.

---

## Download

Download the APK from the [releases page](https://github.com/Hostu404/CyTube-APK/releases/latest), open it on your device and install it. You may need to allow installing apps from unknown sources.

**Android TV / Fire TV:** the easiest way is [Downloader](https://amzn.to/2Ihmizw) (search "Downloader" on the Fire TV Appstore). Enter the direct link to the APK and it installs it for you. You'll need "Apps from Unknown Sources" turned on for Downloader, under Settings → My Fire TV → Developer Options.

There's no Play Store or Amazon Appstore listing — this is a hobby project, distributed as an open-source APK.

---

## Requirements

* **Android 8.0 (API 26) or newer**
* Internet connection
* A CyTube account is optional

---

## Privacy & Security

Your password is sent once, directly to your CyTube server, the same way the CyTube website logs in — it is never written to disk. If you choose to stay logged in, only the signed session cookie the server returns is saved, encrypted on your device. Logging out clears it locally and tells the server to invalidate it too.

The full source code is in this repository for anyone to inspect.

---

## Current limitations

* CyTube supports a huge range of media sources, and some play better than others.
* Some playback synchronisation edge cases remain.
* Picture-in-picture support is still being improved.

The app is actively being developed, so behaviour may change between releases.

---

## Reporting a problem

[**Open an issue →**](https://github.com/Hostu404/CyTube-APK/issues)

Please include:

* Device model, Android version and app version
* The CyTube channel, if relevant
* What you expected to happen, what actually happened, and steps to reproduce it

Screenshots or logs are useful when available.

---

## For developers

The project is split into separate parts for the CyTube connection, playback, chat and the app's UI.

### Build from source

```bash
git clone https://github.com/Hostu404/CyTube-APK.git
```

Open the project in Android Studio and let Gradle sync, or build from the command line with `./gradlew assembleRelease` (`.\gradlew assembleRelease` on Windows).

Release builds are signed only if you add a `keystore.properties` file (see `keystore.properties.example`); without one, the project still builds unsigned.

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
