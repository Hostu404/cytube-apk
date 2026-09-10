package com.cytube.mobile

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.app.PendingIntent
import android.app.RemoteAction
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cytube.mobile.data.CHANNEL_NAME_REGEX
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.data.ThemeMode
import com.cytube.mobile.ui.channel.ChannelScreen
import com.cytube.mobile.ui.channel.PlaybackHost
import com.cytube.mobile.ui.home.HomeScreen
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.ui.login.LoginScreen
import com.cytube.mobile.ui.settings.SettingsScreen
import com.cytube.mobile.ui.theme.CyTubeSettingsTheme
import com.cytube.mobile.ui.theme.CyTubeTheme

class MainActivity : ComponentActivity() {

    private var fullscreen by mutableStateOf(false)
    private var pendingChannel by mutableStateOf<String?>(null)
    private var inPip by mutableStateOf(false)
    private var playbackHost by mutableStateOf<PlaybackHost?>(null)

    /** True between onStop and onStart — Home/Recents/screen-off, but NOT
     *  PiP (PiP keeps the Activity started, so onStop never fires for it;
     *  see the class doc on onStop/onStart below). Read by ChannelScreen to
     *  drop the ExoPlayer video track and back off the socket reconnect
     *  cadence while nothing's on screen — see ChannelScreen's
     *  isAppInBackground param and CyTubeClient.setBackgrounded. */
    private var appInBackground by mutableStateOf(false)

    /** Last isPlaying value refreshPipParams() actually applied, so
     *  onPlaybackHostChange (which fires on every recomposition — every
     *  chat message on a busy channel) only calls into
     *  setPictureInPictureParams() when play/pause genuinely flipped,
     *  instead of once per recomposition while floating. Reset whenever PiP
     *  is (re)entered so the icon is still synced at least once. */
    private var lastPipIsPlaying: Boolean? = null

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_PLAY_PAUSE) playbackHost?.onTogglePlayPause?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingChannel = channelFromIntent(intent)

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_PLAY_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            CyTubeTheme {
                val nav = rememberNavController()

                // Single source of truth for the home/settings ThemeMode
                // preference (Settings > Appearance), read once here and
                // handed down to both destinations below so a change in
                // Settings is reflected on both immediately, without either
                // screen re-reading SettingsStore on its own.
                val appContext = LocalContext.current
                val settingsStore = remember { SettingsStore(appContext) }
                val settings by settingsStore.settings.collectAsState(initial = Settings())
                val isDark = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }

                // Deep link support: a cytu.be/r/<channel> link (cold start or
                // while the app is already running, via onNewIntent below)
                // lands here once the nav graph exists to navigate on.
                val pending = pendingChannel
                if (pending != null) {
                    androidx.compose.runtime.LaunchedEffect(pending) {
                        nav.navigate("channel/$pending")
                        pendingChannel = null
                    }
                }

                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        // Phone mode: follow the Appearance setting (default:
                        // system light/dark), same as Settings itself — see
                        // isDark above. Reverted from an earlier version that
                        // forced the channel screen's always-dark palette
                        // here instead, per user request. TV keeps the
                        // generic DarkScheme — its layout/contrast was tuned
                        // separately and isn't part of this.
                        val context = LocalContext.current
                        val isTv = remember { isTvDevice(context) }
                        val homeContent: @Composable () -> Unit = {
                            HomeScreen(
                                onOpenChannel = { nav.navigate("channel/$it") },
                                onOpenLogin = { nav.navigate("login") },
                                onOpenSettings = { nav.navigate("settings") }
                            )
                        }
                        if (isTv) {
                            homeContent()
                        } else {
                            CyTubeSettingsTheme(darkTheme = isDark) { homeContent() }
                        }
                    }
                    composable(
                        "channel/{name}",
                        arguments = listOf(navArgument("name") { type = NavType.StringType })
                    ) { entry ->
                        ChannelScreen(
                            channel = entry.arguments?.getString("name").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onFullscreenChange = { fullscreen = it },
                            isInPictureInPicture = inPip,
                            isAppInBackground = appInBackground,
                            onPlaybackHostChange = { host ->
                                playbackHost = host
                                if (inPip && host != null && host.isPlaying != lastPipIsPlaying) {
                                    lastPipIsPlaying = host.isPlaying
                                    refreshPipParams()
                                }
                            }
                        )
                    }
                    composable("login") { LoginScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") {
                        // Follows the Appearance setting (default: system
                        // light/dark), unlike the rest of the app — per user
                        // request, since this is a plain preferences screen
                        // with no CyTube look to protect. See isDark above
                        // and CyTubeSettingsTheme's doc comment.
                        CyTubeSettingsTheme(darkTheme = isDark) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        channelFromIntent(intent)?.let { pendingChannel = it }
    }

    /**
     * PiP on leaving the app — but only while something is actually eligible
     * for it (a native player showing video) and the user has the Settings
     * toggle on. When it isn't (disabled, ineligible item, or the OS
     * declines), playback is deliberately left alone rather than paused:
     * Home/Recents should let video and audio keep playing in the
     * background using ExoPlayer/NewPipe's own normal lifecycle, the same
     * way PiP's floating window already does — not something this Activity
     * has to drive. onStop()/onStart() below intentionally do nothing to
     * *playback itself* (still no pause-on-background) — they only flip
     * appInBackground, which trims what's decoded/how eagerly we reconnect
     * while backgrounded. See its own doc comment.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    /**
     * Home/Recents/screen-off — but NOT PiP: entering PiP keeps this
     * Activity in the started state (that's what lets its video keep
     * rendering into the floating window), so onStop() never fires while
     * PiP is active, only once the user dismisses the PiP window too. No
     * new permission needed for any of this — it's plain Activity
     * lifecycle plus an ExoPlayer track selection flip and a couple of
     * socket.io Manager field writes, not a real foreground service (which
     * would need FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK /
     * POST_NOTIFICATIONS and wouldn't fit that constraint).
     */
    override fun onStop() {
        super.onStop()
        appInBackground = true
    }

    override fun onStart() {
        super.onStart()
        appInBackground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipActionReceiver) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // Forget the last-synced play/pause icon state on every transition
        // so the next time PiP is active it gets synced fresh at least once,
        // rather than possibly skipping the very first update after re-entry.
        lastPipIsPlaying = null
    }

    private fun maybeEnterPip(): Boolean {
        val host = playbackHost ?: return false
        if (!host.pipEnabled || !host.canPip) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        lastPipIsPlaying = host.isPlaying
        return runCatching { enterPictureInPictureMode(pipParams(host.isPlaying)) }.getOrDefault(false)
    }

    private fun refreshPipParams() {
        val host = playbackHost ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(pipParams(host.isPlaying)) }
    }

    private fun pipParams(isPlaying: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val icon = Icon.createWithResource(
                this,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            val pending = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            val label = if (isPlaying) "Pause" else "Play"
            builder.setActions(listOf(RemoteAction(icon, label, label, pending)))
        }
        return builder.build()
    }

    /** `https://cytu.be/r/<channel>` only; validated against CyTube's own
     *  channel-name charset before it's used for anything, the same rule
     *  HomeViewModel's direct-entry box and ChannelViewModel.start() apply —
     *  this can arrive from any other installed app, not just from typing it. */
    private fun channelFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.host != "cytu.be") return null
        val segments = data.pathSegments
        if (segments.size < 2 || segments[0] != "r") return null
        return segments[1].takeIf { CHANNEL_NAME_REGEX.matches(it) }
    }

    private companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.cytube.mobile.PIP_PLAY_PAUSE"
    }
}
