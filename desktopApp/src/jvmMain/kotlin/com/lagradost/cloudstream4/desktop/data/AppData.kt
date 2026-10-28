package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.mapper
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

/** The user's opinion of a title, on Letterboxd's scale so ratings carry over both ways */
data class Rating(
    val media: MediaEntry,
    /** 0.5 to 5 in half steps, null if only liked */
    val stars: Float? = null,
    val liked: Boolean = false,
    val year: Int? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class PlayerKind { Internal, External }

/** The lists bookmarks are sorted into, like in the Android app */
enum class WatchList(val title: String) {
    Watching("Watching"),
    PlanToWatch("Plan to watch"),
    Completed("Completed"),
    OnHold("On hold"),
    Dropped("Dropped"),
}

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
    /** Player volume and speed, kept between episodes and launches */
    val playerVolume: Int = 100,
    val playerRate: Float = 1f,
    /** Subtitle text size in percent */
    val subtitleScale: Int = 100,
    /** A dark box behind subtitles, easier to read on bright scenes */
    val subtitleBackground: Boolean = false,
    /** Checks the shows in the history for new episodes once a day */
    val newEpisodeChecks: Boolean = true,
)

/** Episodes of a show at the last check, and how many appeared since the user last watched it */
data class EpisodeCheck(
    val count: Int,
    val newCount: Int = 0,
    val checkedAt: Long = System.currentTimeMillis(),
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

    fun toggleBookmark(entry: MediaEntry) {
        val removing = isBookmarked(entry.url)
        _bookmarks.persist("bookmarks") { list ->
            if (removing) list.filterNot { it.url == entry.url } else listOf(entry) + list
        }
        if (removing && entry.url in _watchLists.value) _watchLists.persist("watchLists") { it - entry.url }
    }

    /** The list of each bookmark, by url, bookmarks without one are in [WatchList.PlanToWatch] */
    private val _watchLists = MutableStateFlow(store.get("watchLists", emptyMap<String, WatchList>()))
    val watchLists: StateFlow<Map<String, WatchList>> = _watchLists.asStateFlow()

    fun watchList(url: String): WatchList? = if (isBookmarked(url)) _watchLists.value[url] ?: WatchList.PlanToWatch else null

    /** Bookmarks [entry] if needed and moves it to [list] */
    fun setWatchList(entry: MediaEntry, list: WatchList) {
        if (!isBookmarked(entry.url)) _bookmarks.persist("bookmarks") { listOf(entry) + it }
        _watchLists.persist("watchLists") { it + (entry.url to list) }
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
        // Watching the show again means its new episodes were seen
        clearNewEpisodes(progress.media.url)
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

    /** Episode counts per show url, filled by the new episode checks */
    private val _episodeChecks = MutableStateFlow(store.get("episodeChecks", emptyMap<String, EpisodeCheck>()))
    val episodeChecks: StateFlow<Map<String, EpisodeCheck>> = _episodeChecks.asStateFlow()

    /** When the shows were last checked for new episodes */
    var episodesCheckedAt: Long
        get() = store.get("episodesCheckedAt", 0L)
        set(value) = store.set("episodesCheckedAt", value)

    /** Saves the episode count of a show, more episodes than at the last check are new */
    fun recordEpisodeCount(url: String, count: Int) = _episodeChecks.persist("episodeChecks") { map ->
        val previous = map[url]
        val added = if (previous != null && count > previous.count) count - previous.count else 0
        map + (url to EpisodeCheck(count, (previous?.newCount ?: 0) + added))
    }

    fun clearNewEpisodes(url: String) {
        if ((_episodeChecks.value[url]?.newCount ?: 0) == 0) return
        _episodeChecks.persist("episodeChecks") { map -> map[url]?.let { map + (url to it.copy(newCount = 0)) } ?: map }
    }

    /** Ratings and likes by url */
    private val _ratings = MutableStateFlow(store.get("ratings", emptyMap<String, Rating>()))
    val ratings: StateFlow<Map<String, Rating>> = _ratings.asStateFlow()

    private fun updateRating(entry: MediaEntry, year: Int?, change: (Rating) -> Rating) = _ratings.persist("ratings") { map ->
        val next = change(map[entry.url] ?: Rating(entry, year = year)).copy(media = entry, year = year ?: map[entry.url]?.year, updatedAt = System.currentTimeMillis())
        if (next.stars == null && !next.liked) map - entry.url else map + (entry.url to next)
    }

    fun rate(entry: MediaEntry, stars: Float?, year: Int? = null) = updateRating(entry, year) { it.copy(stars = stars) }

    fun toggleLike(entry: MediaEntry, year: Int? = null) = updateRating(entry, year) { it.copy(liked = !it.liked) }

    /** What a backup holds: the user's library and settings, not the installed extensions, whose files are not in it */
    private val backupFlows: Map<String, MutableStateFlow<*>> by lazy {
        mapOf(
            "settings" to _settings,
            "repositories" to _repositories,
            "bookmarks" to _bookmarks,
            "watchLists" to _watchLists,
            "ratings" to _ratings,
            "history" to _history,
            "positions" to _positions,
            "audioPreferences" to _audioPreferences,
            "episodeChecks" to _episodeChecks,
        )
    }

    fun exportBackup(file: File) {
        val backup = mapper.createObjectNode()
        backup.put("app", "CloudStream desktop")
        backup.put("version", 1)
        backupFlows.forEach { (key, flow) -> backup.set<JsonNode>(key, mapper.valueToTree(flow.value)) }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, backup)
    }

    /** Replaces the library and settings with the ones in a backup, returns how many parts were restored */
    fun restoreBackup(file: File): Int {
        val backup = mapper.readTree(file)
        require(backup.path("app").asText() == "CloudStream desktop") { "Not a CloudStream desktop backup" }
        // Everything is read before anything changes, so a broken backup changes nothing
        val values = backupFlows.mapNotNull { (key, flow) ->
            val node = backup.get(key) ?: return@mapNotNull null
            val type = mapper.typeFactory.constructType(backupTypes.getValue(key))
            Triple(key, flow, mapper.convertValue<Any>(node, type))
        }
        values.forEach { (key, flow, value) ->
            @Suppress("UNCHECKED_CAST")
            (flow as MutableStateFlow<Any?>).value = value
            store.set(key, value)
        }
        return values.size
    }

    private val backupTypes: Map<String, TypeReference<*>> = mapOf(
        "settings" to object : TypeReference<Settings>() {},
        "repositories" to object : TypeReference<List<RepositoryEntry>>() {},
        "bookmarks" to object : TypeReference<List<MediaEntry>>() {},
        "watchLists" to object : TypeReference<Map<String, WatchList>>() {},
        "ratings" to object : TypeReference<Map<String, Rating>>() {},
        "history" to object : TypeReference<List<WatchProgress>>() {},
        "positions" to object : TypeReference<LinkedHashMap<String, Position>>() {},
        "audioPreferences" to object : TypeReference<LinkedHashMap<String, String>>() {},
        "episodeChecks" to object : TypeReference<Map<String, EpisodeCheck>>() {},
    )

    fun removeHistory(url: String) = _history.persist("history") { list -> list.filterNot { it.media.url == url } }

    fun clearHistory() = _history.persist("history") { emptyList() }
}
