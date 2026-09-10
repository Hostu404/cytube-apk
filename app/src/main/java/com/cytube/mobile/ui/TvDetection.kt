package com.cytube.mobile.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * Android TV / Fire TV — the actual runtime signal, not just "no touchscreen"
 * (a Chromebook or a phone in a desktop dock can be touchscreen-less too).
 * `UiModeManager.currentModeType` is what Android itself uses to decide this,
 * and it's what an Android TV/Fire TV emulator or device reports correctly.
 *
 * Shared by ChannelScreen (phone chrome vs. straight-to-fullscreen D-pad
 * layout) and SettingsScreen (hiding the two rows — Ambient glow, PiP — that
 * stay unavailable on TV). Was previously a private copy inside
 * ChannelScreen.kt; pulled out here once a second screen needed the same
 * check, rather than duplicating it.
 */
fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
