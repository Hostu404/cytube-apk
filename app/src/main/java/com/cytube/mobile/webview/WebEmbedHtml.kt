package com.cytube.mobile.webview

/** Injected into every frame of an embed page: a getCookie stub some embed
 *  scripts expect, and logging of uncaught page errors (they still reach
 *  the page as normal; this only makes them visible in Logcat). */
const val EMBED_DEFENSIVE_SHIM_JS = """
(function(){
  if (typeof window.getCookie !== 'function') {
    window.getCookie = function(name) { return null; };
  }
  window.addEventListener('error', function(e) {
    try {
      console.warn('[shim] page error: ' + (e && e.message));
    } catch (ignored) {}
  }, true);
})();
"""

const val EMBED_PAGE_ORIGIN = "https://example.com"
const val EMBED_ENDED_SENTINEL = "__CYTUBE_EMBED_ENDED__"
const val EMBED_STATE_SENTINEL = "__CYTUBE_EMBED_STATE__:"
/** Logged (followed by a short detail) when a provider's player reports that
 *  the video can't be played — removed, private, not embeddable, failed to
 *  load. PlayerSurface turns it into a playback failure. */
const val EMBED_ERROR_SENTINEL = "__CYTUBE_EMBED_ERROR__:"

/** Media types with their own player page below, as opposed to a link that
 *  is loaded directly. */
val PROVIDER_EMBED_TYPES = setOf("dm", "yt", "vi", "pt", "sb")

const val BLANK_EMBED_HTML =
    "<!DOCTYPE html><html><body style=\"background:#000;margin:0;\"></body></html>"

val SAFE_EMBED_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

fun dailymotionSdkHtml(id: String, initialTime: Double = 0.0, initialPaused: Boolean = false): String {
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    val startSeconds = if (initialTime > 0) initialTime.toInt() else 0
    val autoplayParam = if (initialPaused) 0 else 1
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta name="referrer" content="strict-origin">
        <style>
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #dmplayer { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="dmplayer"></div>
        <script>
          window.dmAsyncInit = function() {
            var el = document.getElementById('dmplayer');
            var player = DM.Player.create(el, {
              video: '$safeId',
              width: '100%',
              height: '100%',
              params: {
                autoplay: $autoplayParam,
                logo: 0,
                'queue-enable': false,
                'sharing-enable': false,
                'ui-logo': false,
                'ui-start-screen-info': false,
                start: $startSeconds,
                startTime: $startSeconds
              }
            });
            var ready = false;
            var lastKnownPaused = ${if (initialPaused) "true" else "false"};
            var lastKnownTime = $startSeconds;
            var isBuffering = false;

            window.__cytubeEmbed = {
              play: function() { if (player && ready) player.play(); },
              pause: function() { if (player && ready) player.pause(); },
              seekTo: function(s) { if (player && ready) player.seek(s); },
              setVolume: function(v) {
                if (player && ready) {
                  if (v <= 0 && player.setMuted) player.setMuted(true);
                  else {
                    if (player.setMuted) player.setMuted(false);
                    if (player.setVolume) player.setVolume(v);
                  }
                }
              }
            };

            function reportState() {
              if (!player || !ready) return;
              var curTime = lastKnownTime;
              if (typeof player.currentTime === 'number') {
                curTime = player.currentTime;
              }
              console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
                paused: lastKnownPaused,
                currentTime: curTime || 0.0,
                buffering: isBuffering
              }));
            }

            if (player && player.addEventListener) {
              player.addEventListener('apiready', function() {
                ready = true;
                if ($startSeconds > 0) player.seek($startSeconds);
                if ($autoplayParam === 0) player.pause();
                reportState();
              });
              player.addEventListener('pause', function() { lastKnownPaused = true; reportState(); });
              player.addEventListener('playing', function() { lastKnownPaused = false; isBuffering = false; reportState(); });
              player.addEventListener('timeupdate', function(e) {
                if (typeof player.currentTime === 'number') lastKnownTime = player.currentTime;
                else if (e && typeof e.time === 'number') lastKnownTime = e.time;
                reportState();
              });
              player.addEventListener('seeking', function() { isBuffering = true; reportState(); });
              player.addEventListener('seeked', function() { isBuffering = false; reportState(); });
              player.addEventListener('waiting', function() { isBuffering = true; reportState(); });
              player.addEventListener('ended', function() {
                console.log('$EMBED_ENDED_SENTINEL');
              });
              player.addEventListener('error', function() {
                var err = player.error || {};
                console.log('$EMBED_ERROR_SENTINEL' + (err.code || err.title || 'unknown'));
              });
            }
            setInterval(reportState, 1000);
          };
        </script>
        <script src="https://api.dmcdn.net/all.js"></script>
        </body>
        </html>
    """.trimIndent()
}

fun youtubeIframeApiHtml(id: String, initialTime: Double = 0.0, initialPaused: Boolean = false): String {
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    val startSeconds = if (initialTime > 0) initialTime.toInt() else 0
    val autoplayParam = if (initialPaused) 0 else 1
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta name="referrer" content="strict-origin">
        <style>
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #ytplayer { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="ytplayer"></div>
        <script>
          var tag = document.createElement('script');
          tag.src = 'https://www.youtube.com/iframe_api';
          document.getElementsByTagName('script')[0].parentNode.insertBefore(tag, document.getElementsByTagName('script')[0]);

          var player = null;
          // Last volume the app asked for (null: never asked). The player
          // starts muted so autoplay is allowed and is unmuted once it
          // plays — but only if the app hasn't asked for silence.
          var wantVol = null;
          window.onYouTubeIframeAPIReady = function() {
            var unmuted = false;
            player = new YT.Player('ytplayer', {
              videoId: '$safeId',
              width: '100%',
              height: '100%',
              playerVars: {
                autoplay: $autoplayParam,
                mute: 1,
                playsinline: 1,
                modestbranding: 1,
                rel: 0,
                start: $startSeconds
              },
              events: {
                onReady: function(e) {
                  e.target.mute();
                  if ($startSeconds > 0) {
                    e.target.seekTo($startSeconds, true);
                  }
                  if ($autoplayParam === 1) {
                    e.target.playVideo();
                  } else {
                    e.target.pauseVideo();
                  }
                },
                onStateChange: function(e) {
                  if (e.data === 1 && !unmuted) {
                    unmuted = true;
                    if (wantVol === null || wantVol > 0) {
                      e.target.unMute();
                      if (wantVol !== null) e.target.setVolume(wantVol * 100);
                    }
                  }
                  if (e.data === 0) {
                    console.log('$EMBED_ENDED_SENTINEL');
                  }
                  reportState();
                },
                // 2 bad id, 5 can't play in HTML5, 100 removed/private,
                // 101/150 not embeddable — all mean this video won't play.
                onError: function(e) {
                  console.log('$EMBED_ERROR_SENTINEL' + 'code ' + e.data);
                }
              }
            });

            function reportState() {
              if (!player || !player.getPlayerState) return;
              var state = player.getPlayerState();
              console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
                paused: state !== 1 && state !== 3,
                currentTime: player.getCurrentTime ? player.getCurrentTime() : 0.0,
                buffering: state === 3
              }));
            }

            window.__cytubeEmbed = {
              play: function() { if (player && player.playVideo) player.playVideo(); },
              pause: function() { if (player && player.pauseVideo) player.pauseVideo(); },
              seekTo: function(s) { if (player && player.seekTo) player.seekTo(s, true); },
              setVolume: function(v) {
                wantVol = v;
                if (player) {
                  if (v <= 0 && player.mute) player.mute();
                  else {
                    if (player.unMute) player.unMute();
                    if (player.setVolume) player.setVolume(v * 100);
                  }
                }
              }
            };
            setInterval(reportState, 1000);
          };
        </script>
        </body>
        </html>
    """.trimIndent()
}

fun vimeoSdkHtml(id: String, initialTime: Double = 0.0, initialPaused: Boolean = false): String {
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    val startSeconds = if (initialTime > 0) initialTime.toInt() else 0
    val autoplayParam = if (initialPaused) 0 else 1
    val timeFragment = if (startSeconds > 0) "#t=${startSeconds}s" else ""
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta name="referrer" content="strict-origin">
        <style>
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #vimeowrap { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="vimeowrap">
        <iframe id="vimeoplayer" src="https://player.vimeo.com/video/$safeId$timeFragment" allow="autoplay; fullscreen" allowfullscreen style="display:block;width:100%;height:100%;border:0;margin:0;padding:0;"></iframe>
        </div>
        <script src="https://player.vimeo.com/api/player.js"></script>
        <script>
          var player = new Vimeo.Player(document.getElementById('vimeoplayer'));
          var lastKnownPaused = ${if (initialPaused) "true" else "false"};
          var lastKnownTime = $startSeconds;
          var isBuffering = false;

          window.__cytubeEmbed = {
            play: function() { if (player) player.play().catch(function(){}); },
            pause: function() { if (player) player.pause().catch(function(){}); },
            seekTo: function(s) { if (player) player.setCurrentTime(s).catch(function(){}); },
            setVolume: function(v) {
              if (player) {
                if (v <= 0 && player.setMuted) player.setMuted(true).catch(function(){});
                else {
                  if (player.setMuted) player.setMuted(false).catch(function(){});
                  if (player.setVolume) player.setVolume(v).catch(function(){});
                }
              }
            }
          };

          function reportState() {
            if (!player) return;
            console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
              paused: lastKnownPaused,
              currentTime: lastKnownTime,
              buffering: isBuffering
            }));
          }

          player.on('pause', function() { lastKnownPaused = true; reportState(); });
          player.on('play', function() { lastKnownPaused = false; isBuffering = false; reportState(); });
          player.on('timeupdate', function(data) {
            if (data && typeof data.seconds === 'number') lastKnownTime = data.seconds;
            reportState();
          });
          player.on('bufferstart', function() { isBuffering = true; reportState(); });
          player.on('bufferend', function() { isBuffering = false; reportState(); });
          player.on('seeking', function() { isBuffering = true; reportState(); });
          player.on('seeked', function() { isBuffering = false; reportState(); });
          player.on('ended', function() { console.log('$EMBED_ENDED_SENTINEL'); });

          player.ready().then(function() {
            if ($startSeconds > 0) {
              player.setCurrentTime($startSeconds).catch(function(){});
            }
            if ($autoplayParam === 1) {
              player.play().catch(function(e) { console.log('vimeo play() rejected: ' + (e && e.message ? e.message : e)); });
            } else {
              player.pause().catch(function(){});
            }
          }).catch(function(e) {
            // ready() only rejects when the video can't be loaded at all.
            console.log('$EMBED_ERROR_SENTINEL' + (e && e.name ? e.name : 'not available'));
          });
          setInterval(reportState, 1000);
        </script>
        </body>
        </html>
    """.trimIndent()
}

fun peertubeSdkHtml(embedUrl: String?, initialTime: Double = 0.0, initialPaused: Boolean = false): String {
    if (embedUrl == null) return BLANK_EMBED_HTML
    val startSeconds = if (initialTime > 0) initialTime.toInt() else 0
    val autoplayParam = if (initialPaused) 0 else 1
    val startParam = if (startSeconds > 0) "&start=$startSeconds" else ""
    val src = "$embedUrl?autoplay=$autoplayParam&api=1$startParam"
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta name="referrer" content="strict-origin">
        <style>
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #ptwrap { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="ptwrap">
        <iframe id="ptplayer" src="$src" allow="autoplay; fullscreen" allowfullscreen style="display:block;width:100%;height:100%;border:0;margin:0;padding:0;"></iframe>
        </div>
        <script src="https://unpkg.com/@peertube/embed-api/build/player.min.js"></script>
        <script>
          var player = new PeerTubePlayer(document.getElementById('ptplayer'));
          var lastKnownPaused = ${if (initialPaused) "true" else "false"};
          var lastKnownPosition = $startSeconds;
          var ended = false;

          window.__cytubeEmbed = {
            play: function() { if (player) player.play().catch(function(e){}); },
            pause: function() { if (player) player.pause().catch(function(e){}); },
            seekTo: function(s) { if (player) player.seek(s).catch(function(e){}); },
            setVolume: function(v) { if (player) player.setVolume(v).catch(function(e){}); }
          };

          // PeerTube's embed API reports only playing/paused, so buffering
          // is always reported as false.
          function reportState() {
            console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
              paused: lastKnownPaused,
              currentTime: lastKnownPosition,
              buffering: false
            }));
          }

          player.ready().then(function() {
            if ($startSeconds > 0) {
              player.seek($startSeconds).catch(function(e){});
            }
            player.addEventListener('playbackStatusChange', function(status) {
              lastKnownPaused = (status === 'paused');
              reportState();
            });
            player.addEventListener('playbackStatusUpdate', function(status) {
              if (typeof status.position === 'number') lastKnownPosition = status.position;
              if (status.playbackState === 'ended' && !ended) {
                ended = true;
                console.log('$EMBED_ENDED_SENTINEL');
              }
              reportState();
            });
            if ($autoplayParam === 1) {
              player.play().catch(function(e) { console.log('peertube play() rejected: ' + (e && e.message ? e.message : e)); });
            } else {
              player.pause().catch(function(e) {});
            }
          }).catch(function(e) {
            // ready() only rejects when the video can't be loaded at all.
            console.log('$EMBED_ERROR_SENTINEL' + (e && e.message ? e.message : 'not available'));
          });
          setInterval(reportState, 1000);
        </script>
        </body>
        </html>
    """.trimIndent()
}

fun streamableSdkHtml(id: String, initialTime: Double = 0.0, initialPaused: Boolean = false): String {
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    val startSeconds = if (initialTime > 0) initialTime.toInt() else 0
    val autoplayParam = if (initialPaused) 0 else 1
    val timeParam = if (startSeconds > 0) "&t=$startSeconds" else ""
    val mutedParam = if (autoplayParam == 1) "&muted=1" else ""
    val src = "https://streamable.com/e/$safeId?autoplay=$autoplayParam$mutedParam$timeParam"
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta name="referrer" content="strict-origin">
        <style>
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #sbwrap { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="sbwrap">
        <iframe id="sbplayer" src="$src" allow="autoplay; fullscreen" allowfullscreen style="display:block;width:100%;height:100%;border:0;margin:0;padding:0;"></iframe>
        </div>
        <script src="https://cdn.embed.ly/player-0.1.0.min.js"></script>
        <script>
          var player = new playerjs.Player(document.getElementById('sbplayer'));
          var lastKnownPaused = ${if (initialPaused) "true" else "false"};
          var lastKnownTime = $startSeconds;
          var finishing = false;
          var finishTimer = null;
          var unmuted = false;
          // Last volume the app asked for (null: never asked) — see the
          // one-time unmute in the 'play' handler.
          var wantVol = null;

          window.__cytubeEmbed = {
            play: function() { if (player) player.play(); },
            pause: function() { if (player) player.pause(); },
            seekTo: function(s) { if (player) player.setCurrentTime(s); },
            setVolume: function(v) {
              wantVol = v;
              if (!player) return;
              if (v <= 0) {
                try { player.mute(); } catch(e){}
              } else {
                try { player.unmute(); } catch(e){}
                try { player.setVolume(v * 100); } catch(e){}
              }
            }
          };

          // player.js has no buffering events, so buffering is always
          // reported as false.
          function reportState() {
            console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
              paused: lastKnownPaused,
              currentTime: lastKnownTime,
              buffering: false
            }));
          }

          player.on('ready', function() {
            if ($startSeconds > 0) {
              player.setCurrentTime($startSeconds);
            }
            player.on('pause', function() { lastKnownPaused = true; reportState(); });
            player.on('play', function() {
              lastKnownPaused = false;
              if (!unmuted && $autoplayParam === 1) {
                unmuted = true;
                // Started muted (&muted=1) so autoplay is allowed.
                if (wantVol === null || wantVol > 0) {
                  try { player.unmute(); } catch(e){}
                  try { player.setVolume(wantVol === null ? 100 : wantVol * 100); } catch(e){}
                }
              }
              reportState();
            });
            player.on('timeupdate', function(time) {
              if (typeof time.seconds === 'number') lastKnownTime = time.seconds;
              var nearEnd = typeof time.duration === 'number' && typeof time.seconds === 'number' &&
                  time.duration - time.seconds < 1;
              if (finishing && !nearEnd) {
                // Seeked back out of the last second: not ending after all.
                clearTimeout(finishTimer);
                finishing = false;
              } else if (!finishing && nearEnd) {
                finishing = true;
                finishTimer = setTimeout(function() {
                  finishing = false;
                  console.log('$EMBED_ENDED_SENTINEL');
                }, (time.duration - time.seconds) * 1000);
              }
              reportState();
            });
            player.on('error', function(e) {
              console.log('$EMBED_ERROR_SENTINEL' + (e && e.message ? e.message : 'unknown'));
            });
            if ($autoplayParam === 1) {
              player.play();
            } else {
              player.pause();
            }
          });
          setInterval(reportState, 1000);
        </script>
        </body>
        </html>
    """.trimIndent()
}

fun sameSite(navigatingHost: String?, embedHost: String?): Boolean {
    if (navigatingHost == null || embedHost == null) return false
    if (navigatingHost.equals(embedHost, ignoreCase = true)) return true
    fun registrableDomain(host: String): String {
        val labels = host.split(".")
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
    }
    return registrableDomain(navigatingHost).equals(registrableDomain(embedHost), ignoreCase = true)
}
