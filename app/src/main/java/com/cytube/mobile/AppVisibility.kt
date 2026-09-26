package com.cytube.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is out of sight (Home, Recents, another app, screen off),
 * set by MainActivity's onStop/onStart. Not true in picture-in-picture, which
 * keeps the Activity started.
 *
 * This lives outside Compose on purpose. Compose stops recomposing and
 * running effects while the Activity is stopped, so nothing in the UI —
 * including a LaunchedEffect keyed on a "backgrounded" flag — runs in the
 * background. ChannelViewModel reads this directly so it can turn the video
 * track off and load the next playlist item while the UI is frozen (see
 * loadWhileInBackground) — for as long as Android lets the app run: with
 * no foreground service it may freeze the whole app within seconds.
 */
object AppVisibility {
    private val _inBackground = MutableStateFlow(false)
    val inBackground: StateFlow<Boolean> = _inBackground.asStateFlow()

    fun set(background: Boolean) {
        _inBackground.value = background
    }
}
