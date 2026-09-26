package com.cytube.mobile.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Android TV / Fire TV — the actual runtime signal, not just "no touchscreen"
 * (a Chromebook or a phone in a desktop dock can be touchscreen-less too).
 * `UiModeManager.currentModeType` is what Android itself uses to decide this,
 * and it's what an Android TV/Fire TV emulator or device reports correctly.
 * Also checks leanback and television system features for TV / Fire TV form factors.
 *
 * Used throughout: the channel and home screens (D-pad layouts), Settings
 * (rows hidden on TV), PlayerSurface (TV decoder and buffer settings),
 * MainActivity (the app exits when it leaves the screen on TV) and the
 * default sync tolerance below.
 */
fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }
    val pm = runCatching { context.packageManager }.getOrNull() ?: return false
    return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        @Suppress("DEPRECATION")
        pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
}

/**
 * Default sync tolerance in seconds: 5.0s on Android TV, 2.0s on phones/tablets.
 */
fun defaultSyncAccuracy(context: Context): Double =
    if (isTvDevice(context)) 5.0 else 2.0
