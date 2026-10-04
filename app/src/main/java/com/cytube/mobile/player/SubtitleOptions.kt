package com.cytube.mobile.player

import androidx.compose.runtime.Immutable

/**
 * The subtitles the playing item offers, for the CC button: their names in
 * order, and which one is showing (-1 for none). Empty when the item has
 * none, which hides the button.
 *
 * [key] identifies the item and track list these were read from. A choice
 * made from the button carries it back (see NativePlayerHandle.selectSubtitle),
 * so a tap that lands just as the next item loads is ignored instead of
 * being applied to the new item's tracks.
 */
@Immutable
data class SubtitleOptions(val names: List<String>, val selected: Int, val key: String = "") {
    val available: Boolean get() = names.isNotEmpty()
    val showing: Boolean get() = selected in names.indices

    companion object {
        val NONE = SubtitleOptions(emptyList(), -1)
    }
}
