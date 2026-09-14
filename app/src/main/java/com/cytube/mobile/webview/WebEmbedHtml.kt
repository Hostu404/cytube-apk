package com.cytube.mobile.webview

const val EMBED_DEFENSIVE_SHIM_JS = """
(function(){
  if (typeof window.getCookie !== 'function') {
    window.getCookie = function(name) { return null; };
  }
  window.addEventListener('error', function(e) {
    try {
      console.warn('[shim] suppressed page error: ' + (e && e.message));
    } catch (ignored) {}
  }, true);
})();
"""

const val EMBED_PAGE_ORIGIN = "https://example.com"
const val EMBED_ENDED_SENTINEL = "__CYTUBE_EMBED_ENDED__"
const val EMBED_STATE_SENTINEL = "__CYTUBE_EMBED_STATE__:"

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
            if (window.DM && DM.Player && DM.Player._INSTANCES && DM.Player._INSTANCES[el.id]) {
              DM.Player.destroy(el.id);
            }
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

          window.addEventListener('error', function(e) {
            console.log('DIAG window error: ' + (e.message || e) + ' @ ' + (e.filename || '?') + ':' + (e.lineno || '?'));
          });
          window.addEventListener('unhandledrejection', function(e) {
            console.log('DIAG unhandledrejection: ' + (e.reason && e.reason.message ? e.reason.message : e.reason));
          });
          document.addEventListener('visibilitychange', function() {
            console.log('DIAG visibilitychange -> ' + document.visibilityState);
          });

          var player = null;
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
                    e.target.unMute();
                  }
                  if (e.data === 0) {
                    console.log('$EMBED_ENDED_SENTINEL');
                  }
                  reportState();
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
          }).catch(function(){});
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
          var isBuffering = false;
          var unmuted = false;
          var ended = false;

          window.__cytubeEmbed = {
            play: function() { if (player) player.play().catch(function(e){}); },
            pause: function() { if (player) player.pause().catch(function(e){}); },
            seekTo: function(s) { if (player) player.seek(s).catch(function(e){}); },
            setVolume: function(v) {
              if (player) {
                if (v <= 0 && player.mute) player.mute().catch(function(e){});
                else {
                  if (player.unMute) player.unMute().catch(function(e){});
                  if (player.setVolume) player.setVolume(v).catch(function(e){});
                }
              }
            }
          };

          function reportState() {
            console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
              paused: lastKnownPaused,
              currentTime: lastKnownPosition,
              buffering: isBuffering
            }));
          }

          player.ready().then(function() {
            if ($startSeconds > 0) {
              player.seek($startSeconds).catch(function(e){});
            }
            player.addEventListener('playbackStatusChange', function(status) {
              lastKnownPaused = (status === 'paused');
              isBuffering = (status === 'buffering');
              if (status === 'playing' && !unmuted) {
                unmuted = true;
                if (player.unMute) player.unMute().catch(function() {});
                if (player.setVolume) player.setVolume(1).catch(function() {});
              }
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
          }).catch(function(e) { console.log('peertube ready rejected: ' + (e && e.message ? e.message : e)); });
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
          var isBuffering = false;
          var finishing = false;
          var unmuted = false;

          window.__cytubeEmbed = {
            play: function() { if (player) player.play(); },
            pause: function() { if (player) player.pause(); },
            seekTo: function(s) { if (player) player.setCurrentTime(s); },
            setVolume: function(v) { if (player) player.setVolume(v * 100); }
          };

          function reportState() {
            console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
              paused: lastKnownPaused,
              currentTime: lastKnownTime,
              buffering: isBuffering
            }));
          }

          player.on('ready', function() {
            if ($startSeconds > 0) {
              player.setCurrentTime($startSeconds);
            }
            player.on('pause', function() { lastKnownPaused = true; reportState(); });
            player.on('play', function() {
              lastKnownPaused = false;
              isBuffering = false;
              if (!unmuted && $autoplayParam === 1) {
                unmuted = true;
                try { player.unmute(); } catch(e){}
                try { player.setVolume(100); } catch(e){}
              }
              reportState();
            });
            player.on('timeupdate', function(time) {
              if (typeof time.seconds === 'number') lastKnownTime = time.seconds;
              if (finishing) return;
              if (typeof time.duration === 'number' && typeof time.seconds === 'number' &&
                  time.duration - time.seconds < 1) {
                finishing = true;
                setTimeout(function() {
                  console.log('$EMBED_ENDED_SENTINEL');
                }, (time.duration - time.seconds) * 1000);
              }
              reportState();
            });
            player.on('buffering', function() { isBuffering = true; reportState(); });
            player.on('buffered', function() { isBuffering = false; reportState(); });
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
