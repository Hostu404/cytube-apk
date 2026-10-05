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
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.cytube.mobile.ui.defaultSyncAccuracy
import com.cytube.mobile.ui.home.HomeScreen
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.ui.login.LoginScreen
import com.cytube.mobile.ui.settings.SettingsScreen
import com.cytube.mobile.ui.theme.CyTubeSettingsTheme
import com.cytube.mobile.ui.theme.CyTubeTheme

class MainActivity : ComponentActivity() {

    private var pendingChannel by mutableStateOf<String?>(null)
    private var inPip by mutableStateOf(false)
    private var playbackHost by mutableStateOf<PlaybackHost?>(null)

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
        // Only on a fresh start: when Android recreates the Activity (e.g.
        // after the process was killed in the background) it restores the
        // screen stack itself, and re-reading the same launch link would
        // open a second copy of the channel on top.
        if (savedInstanceState == null) pendingChannel = channelFromIntent(intent)

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
                val settings by settingsStore.settings.collectAsStateWithLifecycle(
                    initialValue = Settings(syncAccuracy = defaultSyncAccuracy(appContext))
                )
                val isDark = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK, ThemeMode.COT -> true
                }

                // The navigation bar is see-through everywhere, so each
                // screen's own background runs to the bottom edge behind its
                // buttons, which are dark or light to match the screen's
                // light/dark look (the Appearance setting). The channel
                // screen, always dark, sets its own (ChannelScreen). The
                // status bar keeps the usual styling. Re-applied after every
                // configuration change, a rotation included: androidx's
                // enableEdgeToEdge puts its default styling back on each one.
                val backStackEntry by nav.currentBackStackEntryAsState()
                val onChannel = backStackEntry?.destination?.route == "channel/{name}"
                val configuration = LocalConfiguration.current
                LaunchedEffect(isDark, onChannel, configuration) {
                    if (onChannel) return@LaunchedEffect
                    val clear = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        navigationBarStyle = if (isDark) SystemBarStyle.dark(clear)
                        else SystemBarStyle.light(clear, clear)
                    )
                }

                // Deep link support: a cytu.be/r/<channel> link (cold start or
                // while the app is already running, via onNewIntent below)
                // lands here once the nav graph exists to navigate on. Any
                // channel already open is closed first (popUpTo home): two
                // open channels meant two connections, and for a logged-in
                // user the server kicks one as a duplicate login.
                val pending = pendingChannel
                if (pending != null) {
                    androidx.compose.runtime.LaunchedEffect(pending) {
                        nav.navigate("channel/$pending") { popUpTo("home") }
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
                                onOpenSettings = { nav.navigate("settings") },
                                showCat = settings.themeMode == ThemeMode.COT
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
                            isInPictureInPicture = inPip,
                            onPlaybackHostChange = { host ->
                                playbackHost = host
                                if (inPip && host != null && host.isPlaying != lastPipIsPlaying) {
                                    lastPipIsPlaying = host.isPlaying
                                    refreshPipParams()
                                }
                            }
                        )
                    }
                    composable("login") {
                        // Same Appearance handling as home and settings.
                        val context = LocalContext.current
                        val isTv = remember { isTvDevice(context) }
                        if (isTv) {
                            LoginScreen(onBack = { nav.popBackStack() })
                        } else {
                            CyTubeSettingsTheme(darkTheme = isDark) {
                                LoginScreen(onBack = { nav.popBackStack() })
                            }
                        }
                    }
                    composable("settings") {
                        // Follows the Appearance setting (default: system
                        // light/dark), like home and login — the channel
                        // screen keeps its own always-dark look. See isDark
                        // above and CyTubeSettingsTheme's doc comment. (On TV
                        // the option is hidden, so this is the system mode.)
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
     * for it (a Media3 player showing video) and the user has the Settings
     * toggle on. When it isn't (disabled, ineligible item, or the OS
     * declines), playback is deliberately left alone rather than paused: it
     * carries on in the background for as long as Android lets the app run
     * (without a foreground service, Android usually freezes the app within
     * seconds to minutes). onStop()/onStart() below only set AppVisibility,
     * which ChannelViewModel uses to drop the video track while nothing's on
     * screen and to keep the playlist moving while it can.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    /**
     * Home/Recents/screen-off — but NOT entering PiP: that keeps this
     * Activity in the started state (that's what lets its video keep
     * rendering into the floating window). It does fire when the PiP window
     * is closed (see onPictureInPictureModeChanged), or the screen turns off
     * with the PiP window up. No
     * new permission needed for any of this — it's plain Activity
     * lifecycle plus an ExoPlayer track selection flip, not a real
     * foreground service (which would need FOREGROUND_SERVICE /
     * FOREGROUND_SERVICE_MEDIA_PLAYBACK / POST_NOTIFICATIONS and wouldn't
     * fit that constraint).
     *
     * AppVisibility rather than Compose state: Compose doesn't run while
     * the Activity is stopped, so a Compose-state flag set here was never
     * seen by anything until it had already flipped back.
     */
    override fun onStop() {
        super.onStop()
        // Never while the Activity is only being recreated for a
        // configuration change: onStop runs as part of that too, but the app
        // isn't leaving the screen — and on TV, exiting there would close
        // the app instead of just rebuilding the screen.
        if (isChangingConfigurations) return
        AppVisibility.set(true)
        if (isTvDevice(this)) {
            finishAndRemoveTask()
            System.exit(0)
        }
    }


    override fun onStart() {
        super.onStart()
        AppVisibility.set(false)
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
        // Leaving PiP with the Activity already stopped means the window was
        // closed, not expanded (Android's documented way to tell them
        // apart). Closing a video should stop it, not leave the sound
        // playing with no screen.
        if (!isInPictureInPictureMode) {
            playbackHost?.onPipLeft?.invoke(lifecycle.currentState == Lifecycle.State.CREATED)
        }
    }

    private fun maybeEnterPip(): Boolean {
        val host = playbackHost ?: return false
        if (!host.pipEnabled || !host.canPip) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        lastPipIsPlaying = host.isPlaying
        return runCatching { enterPictureInPictureMode(pipParams(host.isPlaying)) }.getOrDefault(false)
    }

    private fun refreshPipParams() {
        val host = playbackHost ?: return
        runCatching { setPictureInPictureParams(pipParams(host.isPlaying)) }
    }

    private fun pipParams(isPlaying: Boolean): PictureInPictureParams {
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
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(RemoteAction(icon, label, label, pending)))
            .build()
    }

    /** `https://cytu.be/r/<channel>` only; validated against CyTube's own
     *  channel-name charset before it's used for anything, the same rule
     *  HomeViewModel's direct-entry box and ChannelViewModel.start() apply —
     *  this can arrive from any other installed app, not just from typing it. */
    private fun channelFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme?.lowercase() != "https" || data.host?.lowercase() != "cytu.be") return null
        val segments = data.pathSegments
        if (segments.size < 2 || segments[0] != "r") return null
        return segments[1].takeIf { CHANNEL_NAME_REGEX.matches(it) }
    }

    private companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.cytube.mobile.PIP_PLAY_PAUSE"
    }
}
