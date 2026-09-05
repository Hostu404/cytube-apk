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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cytube.mobile.ui.channel.ChannelScreen
import com.cytube.mobile.ui.channel.PlaybackHost
import com.cytube.mobile.ui.home.HomeScreen
import com.cytube.mobile.ui.login.LoginScreen
import com.cytube.mobile.ui.settings.SettingsScreen
import com.cytube.mobile.ui.theme.CyTubeTheme

class MainActivity : ComponentActivity() {

    private var fullscreen by mutableStateOf(false)
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

    /** True only right after we ourselves entered PiP from [onUserLeaveHint] —
     *  distinguishes "the app is still visible, just small" from "the app is
     *  genuinely backgrounded", which is what decides whether [onStop] should
     *  pause playback. */
    private var enteredPip = false

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
                        HomeScreen(
                            onOpenChannel = { nav.navigate("channel/$it") },
                            onOpenLogin = { nav.navigate("login") },
                            onOpenSettings = { nav.navigate("settings") }
                        )
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
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
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
     * toggle on. If we don't enter PiP here, [onStop] pauses playback instead
     * of leaving it running invisibly in the background.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enteredPip = maybeEnterPip()
    }

    override fun onStop() {
        super.onStop()
        if (!enteredPip) playbackHost?.onPauseForBackground?.invoke()
    }

    override fun onStart() {
        super.onStart()
        enteredPip = false
        playbackHost?.onResumeForForeground?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipActionReceiver) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) enteredPip = false
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
        return segments[1].takeIf { CHANNEL_NAME.matches(it) }
    }

    private companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.cytube.mobile.PIP_PLAY_PAUSE"
        val CHANNEL_NAME = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
