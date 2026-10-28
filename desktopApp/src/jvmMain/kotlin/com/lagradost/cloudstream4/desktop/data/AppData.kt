package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class RepositoryEntry(
    val name: String,
    val url: String,
    val iconUrl: String? = null,
)

data class InstalledPlugin(
    val internalName: String,
    val name: String,
    val repositoryUrl: String,
    val version: Int,
    val filePath: String,
    val iconUrl: String? = null,
    /** If the extension was converted from an Android-only .cs3 file */
    val convertedFromDex: Boolean = false,
    /** [com.lagradost.cloudstream4.desktop.plugins.DexConverter.VERSION] used for the conversion */
    val converterVersion: Int = 0,
)

/** Something the user saved or watched, enough to open the result page again. */
data class MediaEntry(
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String? = null,
    val type: TvType? = null,
    val posterHeaders: Map<String, String>? = null,
)

fun SearchResponse.toMediaEntry() = MediaEntry(name, url, apiName, posterUrl, type, posterHeaders)

data class WatchProgress(
    val media: MediaEntry,
    val episodeName: String? = null,
    val episodeData: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Source of the last watched episode, preferred for the next episodes of the show */
    val source: SavedSource? = null,
)

/** The link that was playing, so resuming starts on the same source without searching for links again */
data class SavedSource(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: Int = 0,
    val type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    val headers: Map<String, String> = emptyMap(),
) {
    /** If [link] is the same source and quality, possibly with a fresh url */
    fun matches(link: ExtractorLink): Boolean = link.source == source && link.name == name && link.quality == quality

    suspend fun toLink(): ExtractorLink = newExtractorLink(source, name, url, type) {
        referer = this@SavedSource.referer
        quality = this@SavedSource.quality
        headers = this@SavedSource.headers
    }

    companion object {
        fun from(link: ExtractorLink) =
            SavedSource(link.source, link.name, link.url, link.referer, link.quality, link.type, link.headers)
    }
}

data class Position(
    val positionMs: Long,
    val durationMs: Long,
    val source: SavedSource? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    @get:JsonIgnore
    val isFinished: Boolean get() = fraction >= 0.95f

    /** Finished or long untouched sources are dropped, their links have expired anyway and they only take up space */
    fun withoutStaleSource(now: Long = System.currentTimeMillis()): Position =
        if (source != null && (isFinished || now - updatedAt > SOURCE_MAX_AGE_MS)) copy(source = null) else this

    companion object {
        const val SOURCE_MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000
    }
}

enum class PlayerKind { Internal, External }

data class Settings(
    val theme: CloudStreamThemeMode = CloudStreamThemeMode.Dark,
    val primaryColor: CloudStreamPrimaryColor = CloudStreamPrimaryColor.NORMAL,
    val player: PlayerKind = PlayerKind.Internal,
    /** Full path or command of the external player, empty means auto detect VLC/mpv */
    val externalPlayer: String = "",
    /** Route raw.githubusercontent.com through jsDelivr, useful where GitHub is blocked */
    val jsdelivrProxy: Boolean = false,
    /** The provider shown on the home page */
    val homeProvider: String? = null,
    val seekSeconds: Int = 10,
    /** Empty means ~/Downloads/CloudStream */
    val downloadDirectory: String = "",
    val checkForUpdates: Boolean = true,
    /** Download updates in the background and install them on restart or quit */
    val autoUpdate: Boolean = true,
)

/**
 * Persistent app state for the desktop app. Each collection is exposed as a [StateFlow]
 * so the UI updates as soon as anything changes.
 */
object AppData {
    private val store = JsonFileStore(File(AppDirs.dataDir, "data.json"))

    private val _settings = MutableStateFlow(store.get("settings", Settings()))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _repositories = MutableStateFlow(store.get("repositories", emptyList<RepositoryEntry>()))
    val repositories: StateFlow<List<RepositoryEntry>> = _repositories.asStateFlow()

    private val _plugins = MutableStateFlow(store.get("plugins", emptyList<InstalledPlugin>()))
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    private val _bookmarks = MutableStateFlow(store.get("bookmarks", emptyList<MediaEntry>()))
    val bookmarks: StateFlow<List<MediaEntry>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow(store.get("history", emptyList<WatchProgress>()))
    val history: StateFlow<List<WatchProgress>> = _history.asStateFlow()

    private fun <T> MutableStateFlow<T>.persist(key: String, transform: (T) -> T) {
        update(transform)
        store.set(key, value)
    }

    fun updateSettings(transform: (Settings) -> Settings) = _settings.persist("settings", transform)

    fun addRepository(repo: RepositoryEntry) = _repositories.persist("repositories") { list ->
        val index = list.indexOfFirst { it.url == repo.url }
        if (index == -1) list + repo else list.toMutableList().also { it[index] = repo }
    }

    fun removeRepository(url: String) = _repositories.persist("repositories") { list ->
        list.filterNot { it.url == url }
    }

    fun putPlugin(plugin: InstalledPlugin) = _plugins.persist("plugins") { list ->
        list.filterNot { it.filePath == plugin.filePath } + plugin
    }

    fun removePlugin(filePath: String) = _plugins.persist("plugins") { list ->
        list.filterNot { it.filePath == filePath }
    }

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }

    fun toggleBookmark(entry: MediaEntry) = _bookmarks.persist("bookmarks") { list ->
        if (list.any { it.url == entry.url }) list.filterNot { it.url == entry.url } else listOf(entry) + list
    }

    /** Playback position per episode/movie data string, used to resume and show watched bars */
    private val _positions = MutableStateFlow(store.get("positions", emptyMap<String, Position>()))
    val positions: StateFlow<Map<String, Position>> = _positions.asStateFlow()

    init {
        // Clean up the remembered sources on every launch
        val pruned = _positions.value.mapValues { it.value.withoutStaleSource() }
        if (pruned != _positions.value) _positions.persist("positions") { LinkedHashMap(pruned) }
        val now = System.currentTimeMillis()
        val history = _history.value.map {
            if (it.source != null && now - it.updatedAt > Position.SOURCE_MAX_AGE_MS) it.copy(source = null) else it
        }
        if (history != _history.value) _history.persist("history") { history }
    }

    /** Source last used for any episode of the show at [mediaUrl] */
    fun showSource(mediaUrl: String): SavedSource? = _history.value.firstOrNull { it.media.url == mediaUrl }?.source

    /** Keeps one history entry per show, the most recently watched episode, newest first */
    fun saveProgress(progress: WatchProgress, source: SavedSource? = null) {
        _history.persist("history") { list ->
            val showSource = source ?: list.firstOrNull { it.media.url == progress.media.url }?.source
            listOf(progress.copy(source = showSource)) + list.filterNot { it.media.url == progress.media.url }.take(200)
        }
        val key = progress.episodeData ?: return
        _positions.persist("positions") { map ->
            // LinkedHashMap keeps insertion order, so the oldest entries are dropped first
            val next = LinkedHashMap(map)
            val previous = next.remove(key)
            next[key] = Position(progress.positionMs, progress.durationMs, source ?: previous?.source).withoutStaleSource()
            if (next.size > 5000) next.keys.take(next.size - 5000).forEach { next.remove(it) }
            next
        }
    }

    /** Marks an episode or movie as finished without changing the history order */
    fun markWatched(episodeData: String, durationMs: Long) = _positions.persist("positions") { map ->
        val duration = durationMs.takeIf { it > 0 } ?: 1
        LinkedHashMap(map).apply { this[episodeData] = Position(duration, duration) }
    }

    /** Audio track descriptions the user picked, per show url, "" holds the last one picked anywhere */
    private val _audioPreferences = MutableStateFlow(store.get("audioPreferences", emptyMap<String, String>()))

    /** The audio track to prefer for the show at [mediaUrl], the show's own choice first */
    fun audioPreference(mediaUrl: String): String? = _audioPreferences.value.let { it[mediaUrl] ?: it[""] }

    fun setAudioPreference(mediaUrl: String, description: String) = _audioPreferences.persist("audioPreferences") { map ->
        // LinkedHashMap keeps insertion order, so the oldest shows are dropped first
        val next = LinkedHashMap(map)
        next.remove(mediaUrl)
        next[mediaUrl] = description
        next[""] = description
        if (next.size > 1000) next.keys.filter { it.isNotEmpty() }.take(next.size - 1000).forEach { next.remove(it) }
        next
    }

    fun removeHistory(url: String) = _history.persist("history") { list -> list.filterNot { it.media.url == url } }

    fun clearHistory() = _history.persist("history") { emptyList() }
}
