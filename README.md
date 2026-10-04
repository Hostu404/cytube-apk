# CyTube APK

**A native CyTube client for Android, Android TV and Amazon Fire TV.**

Watch videos in sync with others, chat in real time, add to the playlist and use channel emotes, as a real Android app rather than the CyTube website wrapped in a WebView. The interface adapts to phones, tablets and TVs.

[![Latest release](https://img.shields.io/github/v/release/Hostu404/cytube-apk?label=latest&sort=date)](https://github.com/Hostu404/cytube-apk/releases/latest)
[![Nightly](https://img.shields.io/github/release-date-pre/Hostu404/cytube-apk?label=nightly)](https://github.com/Hostu404/cytube-apk/releases/tag/nightly)
[![Nightly build](https://img.shields.io/github/actions/workflow/status/Hostu404/cytube-apk/nightly.yml?label=nightly%20build)](https://github.com/Hostu404/cytube-apk/actions/workflows/nightly.yml)
[![Tests](https://img.shields.io/github/actions/workflow/status/Hostu404/cytube-apk/ci.yml?branch=main&label=tests)](https://github.com/Hostu404/cytube-apk/actions/workflows/ci.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)

[**Download the latest APK →**](https://github.com/Hostu404/CyTube-APK/releases/latest)

[**Download the latest nightly build →**](https://github.com/Hostu404/cytube-apk/releases/tag/nightly) 

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
* Embedded playback for YouTube live streams, Dailymotion, Vimeo, Odysee and custom embeds (such as 8chan) — the video plays inside the app while chat, playlist and sync stay native
* Compatibility View (the full CyTube page) as a fallback for sources neither can handle, such as Twitch
* Plays DTS, Dolby Digital and Dolby TrueHD audio (common in film rips) even on devices that can't decode it themselves, using built-in FFmpeg decoders
* For videos offered in several qualities, picks one your connection can handle, steps down if playback stalls and back up when it can
* Turn off "stay in sync" to watch your own picks from the playlist without affecting anyone else; it moves on to your next pick when one finishes
* Subtitles: the **CC** button turns on a video's subtitles and picks between them — a custom manifest's subtitle files, a Google Drive video's, YouTube captions, or ones inside the stream. They stay off until you turn them on, and stay on for the next video once you have
* Fullscreen, mute toggle, and an ambient glow behind the video
* Lights down (phone): the moon button dims everything around the video, tinted with the video's colour. Touch the screen to lift them a little for a moment; everything still works while dimmed

### Chat

* Real-time chat with custom channel emotes
* Messages that mention your name are highlighted
* Private messages (phone): tap a PM's name to reply, or the mail icon in the user list to start one
* Tap an emote in chat to reuse it
* Channel emote effects: on channels that set them up, modifiers like `/reverse`, `/rainbow` or `/overlay` flip, tint, stack and animate the emote after them, in chat and the Niconico overlay, as on the website
* Site-wide announcements appear in chat once, not again in every channel
* Niconico-style chat overlay across the video; tap the circle to turn it on

### Channels

* Browse and join channels, with favorites and recently joined
* Add videos to the playlist from a link (phone), with **Play next** if the channel allows it. Links from your phone's share sheet work as they are: YouTube mobile, Shorts and youtu.be links, links pasted with a title around them, and short links like t.co
* Vote in polls; results stay visible after a poll closes
* User list, channel notice, password-protected channels and guest access
* The user list shows names in the channel's own rank colours
* The channel page uses CyTube's familiar Slate greys, toned down for comfortable viewing
* Tap the channel name to refresh chat and playback
* Opens `cytu.be/r/<channel>` links straight into the app

### Android TV / Fire TV

Works with a TV remote's D-pad. From the video, press **Down**:

* With "stay in sync" **on** (Settings), Down opens a full-screen chat view. When the video has subtitles, press **Left** from the Niconico circle to reach the **CC** button.
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

Your password is sent once, directly to your CyTube server, the same way the CyTube website logs in — it is never written to disk. The app keeps only the signed session cookie the server returns: if you choose to stay logged in it's saved encrypted on your device, otherwise it's kept in memory until the app closes. If you open Compatibility View, the cookie is also given to Android's WebView so the CyTube page there is logged in too; WebView keeps it in its own cookie store.

Logging out removes the cookie from the app and from WebView. CyTube has no way for an app to end a session on the server, so a copy of the cookie stays valid until it expires or you change your password.

Channels can style their page with their own CSS and scripts. The app reads a channel's CSS only for emote effects and name colours, as plain data: it never runs a channel's scripts and never loads anything the CSS links to.

The full source code is in this repository for anyone to inspect.

---

## Current limitations

* CyTube supports a huge range of media sources, and some play better than others.
* Some playback synchronisation edge cases remain.
* Odysee videos need a tap on their play button to start, and don't follow the leader skipping to a new point until you rejoin the channel.
* If you switch channels very quickly, CyTube may briefly refuse the connection ("Too many connections from your network"); the app waits a few seconds and connects again by itself.

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

Built with Kotlin 2.4, Jetpack Compose (BOM 2026.09) and Media3 1.11, on Android Gradle plugin 9.3 and Gradle 9.6, compiling against API 37. Every library and its version is listed in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### Build from source

```bash
git clone https://github.com/Hostu404/CyTube-APK.git
```

Open the project in Android Studio (Quail 2 / 2026.1.2 or newer, for Android Gradle plugin 9.3) and let Gradle sync, or build from the command line with `./gradlew assembleRelease` (`.\gradlew assembleRelease` on Windows). Building needs JDK 17 or newer and the Android SDK platform for API 37.

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
- [NextLib](https://github.com/anilbeesetti/nextlib) and [FFmpeg](https://ffmpeg.org/) —
  software audio decoders for formats a device can't play itself (DTS,
  Dolby Digital, TrueHD)

---

## License

CyTube APK is released under the **GNU General Public License v3.0**.

See [`LICENSE`](LICENSE) for the complete licence.
