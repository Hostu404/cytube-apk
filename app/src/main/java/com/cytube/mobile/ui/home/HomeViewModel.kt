package com.cytube.mobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cytube.mobile.data.CHANNEL_NAME_REGEX
import com.cytube.mobile.data.ChannelIndexRepository.PublicChannel
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val query: String = "",
    val loading: Boolean = true,
    val channels: List<PublicChannel> = emptyList(),
    val favourites: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val loggedInAs: String? = null,
    val indexUnavailable: Boolean = false
) {
    // `by lazy`, not a plain getter: HomeScreen reads this 3 times per
    // recomposition (the direct-entry check below, the list itself, the
    // empty-state check) and `query` changes on every keystroke. Each
    // `copy()` is a new instance with its own lazy, so it still follows
    // query/channels; it just filters the list once per state, not 3 times.
    val filtered: List<PublicChannel> by lazy {
        if (query.isBlank()) channels else channels.filter {
            it.name.contains(query, true) ||
                it.pageTitle.contains(query, true) ||
                it.nowPlaying.contains(query, true)
        }
    }

    /** Direct entry, mirroring the "Enter Channel" box on the CyTube homepage. */
    val directEntryName: String?
        get() = query.trim().takeIf { q ->
            q.isNotBlank() &&
                CHANNEL_NAME_REGEX.matches(q) &&
                filtered.none { it.name.equals(q, true) }
        }

    /** What the keyboard's Go key opens: the listed channel with exactly
     *  this name (the direct-entry card is hidden then, so Go must still
     *  work), or else the direct entry. */
    val submitName: String?
        get() {
            val q = query.trim()
            filtered.firstOrNull { it.name.equals(q, true) }?.let { return it.name }
            return directEntryName
        }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow(
        HomeUiState(
            channels = Graph.channelIndex.cachedChannels,
            loading = Graph.channelIndex.cachedChannels.isEmpty()
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // refresh() intentionally not called here -- see HomeScreen's LaunchedEffect(Unit).
        // Each of these runs on its own coroutine, so a plain
        // `_state.value = _state.value.copy(...)` risks a lost update if two
        // land back to back (read-modify-write is not atomic across a
        // suspension point). update {} is.
        viewModelScope.launch {
            settings.favourites.collect { f -> _state.update { it.copy(favourites = f) } }
        }
        viewModelScope.launch {
            settings.recents.collect { r -> _state.update { it.copy(recents = r) } }
        }
        refreshSession()
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            if (_state.value.channels.isEmpty()) {
                _state.update { it.copy(loading = true) }
            }
            val list = try {
                Graph.channelIndex.publicChannels(force)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update {
                it.copy(
                    loading = false,
                    channels = list,
                    // Scraping the homepage can legitimately return nothing. That
                    // is a degraded state, not a failure — favourites, recents and
                    // direct entry all still work.
                    indexUnavailable = list.isEmpty()
                )
            }
        }
    }

    fun setQuery(q: String) { _state.update { it.copy(query = q) } }

    fun toggleFavourite(name: String) {
        viewModelScope.launch { settings.toggleFavourite(name) }
    }

    fun clearRecents() {
        viewModelScope.launch { settings.clearRecents() }
    }

    /** Off the main thread: the first Graph.auth() call builds
     *  EncryptedSharedPreferences (a Keystore round-trip), and this is the
     *  first thing to make it on a cold start — see Graph.auth. */
    fun refreshSession() {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                Graph.auth(getApplication()).savedSession()?.name
            }
            _state.update { it.copy(loggedInAs = name) }
        }
    }
}
