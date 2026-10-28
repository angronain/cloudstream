package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Finds new episodes of the shows in the history, every show with at least one watched episode.
 * Each show has to be loaded from its extension, so this runs in the background once a day.
 */
object NewEpisodes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val INTERVAL_MS = 20L * 60 * 60 * 1000

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Checks if the setting is on and the last check is old enough, used on startup */
    fun checkIfDue() {
        if (!AppData.settings.value.newEpisodeChecks) return
        if (System.currentTimeMillis() - AppData.episodesCheckedAt < INTERVAL_MS) return
        check()
    }

    fun check() {
        if (_checking.value) return
        _checking.value = true
        scope.launch {
            try {
                // A few at a time, so the extensions' sites are not flooded
                val limit = Semaphore(3)
                AppData.history.value.filter { it.episodeName != null }.map { progress ->
                    launch {
                        limit.withPermit {
                            runCatching { episodeCount(progress) }.onFailure { logError(it) }.getOrNull()
                                ?.let { AppData.recordEpisodeCount(progress.media.url, it) }
                        }
                    }
                }.forEach { it.join() }
                AppData.episodesCheckedAt = System.currentTimeMillis()
            } finally {
                _checking.value = false
            }
        }
    }
}
