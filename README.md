# CyTube APK

**☕ If this is useful to you, [im thirsty for a dunkaccino on (((Ko-fi)))](https://ko-fi.com/hostu) — it genuinely helps to keep this going.**

<img width="1063" height="1581" alt="image" src="https://github.com/user-attachments/assets/47d58f2f-5da6-4708-a88b-56fcbd69e4b7" />


This is a real native Android app for [CyTube](https://cytu.be) — not a
website stuffed into a WebView.

- 📺 **Native video playback** — YouTube, direct files, HLS, The Kinoplex
  and more, playing through the phone's own player instead of a browser tab
- 💬 **Live chat** — inline emotes (including
  animated GIF emotes that actually animate), greentext, spoilers, tap a
  username to reply
- 💬 **NicoNico chat** — tap the square!
- 📋 **Playlist, user list, and polls**, right there in the app
- 😊 **Quick reply/Emotes** - tap a name in chat to quick reply, works the same for emotes!
- ⭐ **Favourites and a recents list** so your regular channels are one tap
  away, plus direct-join by name and a browsable public channel list
- 📺 **Works on Fire TV / Android TV out of the box** — sideload the same
  APK, no separate build or setup
- 🔓 **No account required** — join as a guest or log in, your call
- 🆓 **Free and open source.** No ads, no tracking, no login wall
- 🍞 **Works on a toaster!** — Probably

## Known Issues
- Picture in Picture (PIP) still janky *turned off by default and marked experimental in settings
- Google Drive UserScript rooms do not work
- Some rooms might need a few extra seconds to work correctly
- NicoNico chat not available on Fire TV - but will be soon TM.
---

## What's not here yet

- No vote-to-skip button in the UI (the app can vote, there's just no button
  for it yet)
- No poll creation or moderation tools — voting works, running the room
  doesn't. Do kicks, bans, mutes, and permission changes from the site.
- No sending private messages (PMs you receive show up in chat; there's no
  compose flow yet)
- Only the standard CyTube rank tiers are labelled by name; per-channel
  custom ranks aren't
- Fire TV / Android TV navigation works fully with a D-pad, but the layouts
  are the same ones used on phones — no dedicated 10-foot UI yet
- Google Drive links occasionally fail to resolve or drift out of sync —
  CyTube has no official server-side support for Drive playback, so this
  talks to the same unofficial endpoint Drive's own web player uses, which
  Google can change without notice
- Requires Android 8.0 (API 26) or newer

## Install

Grab the APK from this repo's [Releases](../../releases) page and install
it. Android will ask permission to install from whatever app you downloaded
it with the first time — that's expected for anything installed outside an
app store.

- **Phone or tablet**: open the downloaded `.apk` directly.
- **Fire TV / Android TV**: the easiest path is
  [Downloader](https://amzn.to/2Ihmizw) (search "Downloader" on the Fire TV
  Appstore) — enter the direct link to the APK and it installs it for you.
  You'll need "Apps from Unknown Sources" turned on for whichever app you
  used to download it, under Settings → My Fire TV → Developer Options.

There's no Play Store or Amazon Appstore listing — this is a hobby project,
distributed as an open-source APK.

## Building it yourself

```bash
git clone https://github.com/hostu404/cytube-apk.git
cd cytube-apk
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio (Ladybug or newer) and press Run.
Requires JDK 17. No API keys or manual setup needed for a debug build —
everything it needs is either in the repo or resolved from public Maven
repositories (including [JitPack](https://jitpack.io), which hosts
NewPipeExtractor).

## How it's put together

```
Compose UI
    ↓
ViewModels (ChannelViewModel, HomeViewModel)
    ↓
CyTubeClient · SyncEngine · AuthRepository · ChannelIndexRepository
    ↓
Socket.IO · OkHttp · Media3 · WebView
```

`net/` doesn't know about Android UI; `ui/` never touches a socket directly.
Playback goes through one `PlayerHandle` interface regardless of which
backend actually resolved the stream, so the sync algorithm in `SyncEngine`
is written once and applies to all of them. It's a faithful port of CyTube's
own client-side sync logic — same leader/drift rules, adapted to close small
drift with a gentle speed nudge instead of a hard seek, since a mobile
connection can't rebuffer a scrub as fast as a browser can.

## Credit

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

## Security & privacy

- **Your password is never stored.** Login uses the same flow the CyTube
  site itself uses (a CSRF token, one POST with your credentials, then only
  the signed session cookie the server hands back). The password is
  discarded the instant that request finishes. The session cookie is kept in
  [`EncryptedSharedPreferences`](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)
  (AES-256, keyed to your device), and logging out tells the server to
  invalidate the session too, not just forget it locally.
- **No secrets are checked into this repo.** The only embedded key is a
  public, non-secret one Google Drive's own web player uses for anyone with
  a "link sharing" file — the same one tools like yt-dlp use.
- **Cleartext traffic is disabled app-wide**, and the app only trusts your
  device's built-in certificate store.
- **The Compatibility View WebView can't reach into the app** — no
  JavaScript bridge, file/content access disabled, mixed content blocked,
  and navigation locked to the channel's own host, so a chat link opens in
  your normal browser instead of inside the WebView.
- **Channel names are validated** against CyTube's own naming rules before
  they ever reach a network request — this matters because a
  `cytu.be/r/<channel>` link can hand a channel name to the app from any
  other installed app, not just from typing it in yourself.

Found a security issue? Please open an issue (or reach out privately if it's
sensitive) rather than a public PR with exploit details.

## License

GPLv3. This project depends on
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), which is
GPLv3, compiled directly into the same app rather than run separately — so
the whole app is distributed under GPLv3 too. Full text in
[`LICENSE`](LICENSE).

## Support

This is a hobby project, built and maintained in spare time. If you get some
use out of it:

**☕ [ko-fi.com/hostu](https://ko-fi.com/hostu)**
