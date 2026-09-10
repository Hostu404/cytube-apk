package com.cytube.mobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cytube.mobile.data.CHANNEL_NAME_REGEX
import com.cytube.mobile.data.ChannelIndexRepository.PublicChannel
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val query: String = "",
    val loading: Boolean = true,
    val channels: List<PublicChannel> = emptyList(),
    val favourites: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
    val loggedInAs: String? = null,
    val indexUnavailable: Boolean = false
) {
    val filtered: List<PublicChannel>
        get() = if (query.isBlank()) channels else channels.filter {
            it.name.contains(query, true) ||
                it.pageTitle.contains(query, true) ||
                it.nowPlaying.contains(query, true)
        }

    /** Direct entry, mirroring the "Enter Channel" box on the CyTube homepage. */
    val directEntryName: String?
        get() = query.trim().takeIf { q ->
            q.isNotBlank() &&
                CHANNEL_NAME_REGEX.matches(q) &&
                filtered.none { it.name.equals(q, true) }
        }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
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
        _state.update { it.copy(loggedInAs = Graph.auth(app).savedSession()?.name) }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val list = runCatching { Graph.channelIndex.publicChannels(force) }.getOrDefault(emptyList())
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

    fun refreshSession() {
        _state.update { it.copy(loggedInAs = Graph.auth(getApplication()).savedSession()?.name) }
    }
}
