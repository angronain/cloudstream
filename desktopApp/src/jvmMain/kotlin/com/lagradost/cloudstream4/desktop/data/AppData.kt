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

/** A rating from another service, only used to find recommendations */
data class ImportedRating(
    val name: String,
    val year: Int? = null,
    /** 0.5 to 5, null if only watched or liked */
    val stars: Float? = null,
    val liked: Boolean = false,
    val source: String = "Letterboxd",
    /** Letterboxd films are all movies, Trakt says which */
    val movie: Boolean? = true,
) {
    @get:JsonIgnore
    val key: String get() = "${name.lowercase()}|${year ?: ""}"
}

enum class PlayerKind { Internal, External }

/** The lists bookmarks are sorted into, like in the Android app */
enum class WatchList(private val english: String) {
    Watching("Watching"),
    PlanToWatch("Plan to watch"),
    Completed("Completed"),
    OnHold("On hold"),
    Dropped("Dropped");

    val title: String get() = tr(english)
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
    /** Language code of the app, empty follows the system */
    val language: String = "",
    /** Player volume and speed, kept between episodes and launches */
    val playerVolume: Int = 100,
    val playerRate: Float = 1f,
    /** Subtitle text size in percent */
    val subtitleScale: Int = 100,
    /** A dark box behind subtitles, easier to read on bright scenes */
    val subtitleBackground: Boolean = false,
    /** Checks the shows in the history for new episodes once a day */
    val newEpisodeChecks: Boolean = true,
    /** A home row of titles like the ones the user rated, liked or watched, found on TMDB */
    val recommendations: Boolean = true,
    /** A folder shared between computers (iCloud Drive, Dropbox...) to sync the library through, empty is off */
    val syncFolder: String = "",
    /** Shows what is playing on the Discord profile */
    val discordPresence: Boolean = false,
    /** The Discord application whose name Discord shows, from discord.com/developers/applications */
    val discordApplicationId: String = "",
)

/** Episodes of a show at the last check, and how many appeared since the user last watched it */
data class EpisodeCheck(
    val count: Int,
    val newCount: Int = 0,
    val checkedAt: Long = System.currentTimeMillis(),
)

/** What one computer writes to the sync folder */
data class SyncSnapshot(
    val device: String,
    val deviceName: String,
    val savedAt: Long,
    val bookmarks: List<MediaEntry> = emptyList(),
    val watchLists: Map<String, WatchList> = emptyMap(),
    val ratings: Map<String, Rating> = emptyMap(),
    val history: List<WatchProgress> = emptyList(),
    val positions: Map<String, Position> = emptyMap(),
    val importedRatings: Map<String, ImportedRating> = emptyMap(),
    val dismissedRecommendations: Set<String> = emptySet(),
    val changes: Map<String, Long> = emptyMap(),
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
        changed("bookmark:" + entry.url)
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
        changed("bookmark:" + entry.url)
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
        if (next.stars == null && !next.liked) map.also { changed("rating:" + entry.url) } - entry.url else map + (entry.url to next)
    }

    fun rate(entry: MediaEntry, stars: Float?, year: Int? = null) = updateRating(entry, year) { it.copy(stars = stars) }

    fun toggleLike(entry: MediaEntry, year: Int? = null) = updateRating(entry, year) { it.copy(liked = !it.liked) }

    /** Ratings imported from Letterboxd or Trakt, by [ImportedRating.key] */
    private val _importedRatings = MutableStateFlow(store.get("importedRatings", emptyMap<String, ImportedRating>()))
    val importedRatings: StateFlow<Map<String, ImportedRating>> = _importedRatings.asStateFlow()

    /** Adds [ratings], replacing the earlier import of the same titles */
    fun importRatings(ratings: List<ImportedRating>) = _importedRatings.persist("importedRatings") { map ->
        map + ratings.associateBy { it.key }
    }

    fun clearImportedRatings(source: String) = _importedRatings.persist("importedRatings") { map ->
        map.filterValues { it.source != source }
    }

    /** TMDB ids of recommendations the user is not interested in, "movie:1" or "tv:1" */
    private val _dismissedRecommendations = MutableStateFlow(store.get("dismissedRecommendations", emptySet<String>()))
    val dismissedRecommendations: StateFlow<Set<String>> = _dismissedRecommendations.asStateFlow()

    fun dismissRecommendation(id: String) = _dismissedRecommendations.persist("dismissedRecommendations") { it + id }

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
            "importedRatings" to _importedRatings,
            "dismissedRecommendations" to _dismissedRecommendations,
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
        require(backup.path("app").asText() == "CloudStream desktop") { tr("Not a CloudStream desktop backup") }
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
        "importedRatings" to object : TypeReference<Map<String, ImportedRating>>() {},
        "dismissedRecommendations" to object : TypeReference<Set<String>>() {},
    )

    fun removeHistory(url: String) {
        changed("history:$url")
        _history.persist("history") { list -> list.filterNot { it.media.url == url } }
    }

    fun clearHistory() {
        _history.value.forEach { changed("history:" + it.media.url) }
        _history.persist("history") { emptyList() }
    }

    /**
     * When each bookmark last changed and when ratings and history entries were removed, by "bookmark:url",
     * "rating:url" and "history:url". Syncing needs them to tell a removal on one computer from an addition on another.
     */
    private val _changes = MutableStateFlow(store.get("changes", emptyMap<String, Long>()))

    private fun changed(key: String) = _changes.persist("changes") { it + (key to System.currentTimeMillis()) }

    /** This computer, so it can tell its own sync file from the others' */
    val deviceId: String by lazy {
        store.get<String>("deviceId") ?: java.util.UUID.randomUUID().toString().also { store.set("deviceId", it) }
    }

    fun syncSnapshot(deviceName: String) = SyncSnapshot(
        deviceId, deviceName, System.currentTimeMillis(), _bookmarks.value, _watchLists.value, _ratings.value, _history.value,
        _positions.value, _importedRatings.value, _dismissedRecommendations.value, _changes.value,
    )

    /** Takes what changed on another computer: for each title, whichever side changed it last wins */
    fun mergeSync(remote: SyncSnapshot) {
        val changes = HashMap(_changes.value)
        val remoteChanges = remote.changes
        fun time(key: String) = changes[key] ?: 0L
        fun remoteTime(key: String) = remoteChanges[key] ?: 0L

        // Bookmarks and their lists
        val local = _bookmarks.value.associateBy { it.url }
        val theirs = remote.bookmarks.associateBy { it.url }
        val lists = HashMap(_watchLists.value)
        val bookmarks = LinkedHashMap<String, MediaEntry>()
        val urls = (theirs.keys + local.keys + remoteChanges.keys.filter { it.startsWith("bookmark:") }.map { it.removePrefix("bookmark:") })
        urls.forEach { url ->
            val key = "bookmark:$url"
            when {
                remoteTime(key) > time(key) -> {
                    theirs[url]?.let { bookmarks[url] = it }
                    remote.watchLists[url]?.let { lists[url] = it } ?: lists.remove(url)
                    changes[key] = remoteTime(key)
                }
                // Neither knows when, as for bookmarks from before syncing: keep it if either has it
                remoteTime(key) == time(key) -> {
                    (local[url] ?: theirs[url])?.let { bookmarks[url] = it }
                    if (url !in lists) remote.watchLists[url]?.let { lists[url] = it }
                }
                else -> local[url]?.let { bookmarks[url] = it }
            }
        }
        // Keep this computer's order, with what came from the other one in front
        val ordered = bookmarks.values.filter { it.url !in local } + _bookmarks.value.filter { it.url in bookmarks }.map { bookmarks.getValue(it.url) }
        val keptLists = lists.filterKeys { it in bookmarks }

        // Ratings, a removal counts from when it happened
        val ratings = HashMap<String, Rating>()
        (_ratings.value.keys + remote.ratings.keys + remoteChanges.keys.filter { it.startsWith("rating:") }.map { it.removePrefix("rating:") }).forEach { url ->
            val key = "rating:$url"
            val mine = maxOf(_ratings.value[url]?.updatedAt ?: 0L, time(key))
            val other = maxOf(remote.ratings[url]?.updatedAt ?: 0L, remoteTime(key))
            val winner = if (other > mine) remote.ratings[url] else _ratings.value[url] ?: remote.ratings[url].takeIf { other == mine }
            if (other > mine && remoteTime(key) > time(key)) changes[key] = remoteTime(key)
            winner?.let { ratings[url] = it }
        }

        // History, one entry per show
        val history = HashMap<String, WatchProgress>()
        (_history.value + remote.history).forEach { progress ->
            val url = progress.media.url
            val removed = maxOf(time("history:$url"), remoteTime("history:$url"))
            if (progress.updatedAt <= removed) return@forEach
            if ((history[url]?.updatedAt ?: -1) < progress.updatedAt) history[url] = progress
        }
        remoteChanges.filterKeys { it.startsWith("history:") }.forEach { (key, at) -> if (at > time(key)) changes[key] = at }
        val mergedHistory = history.values.sortedByDescending { it.updatedAt }.take(200)

        // Positions, the latest per episode
        val positions = LinkedHashMap(_positions.value)
        remote.positions.forEach { (key, position) ->
            if ((positions[key]?.updatedAt ?: -1) < position.updatedAt) positions[key] = position.withoutStaleSource()
        }
        if (positions.size > 5000) positions.keys.take(positions.size - 5000).forEach { positions.remove(it) }

        val imported = remote.importedRatings + _importedRatings.value
        val dismissed = _dismissedRecommendations.value + remote.dismissedRecommendations

        if (ordered != _bookmarks.value) _bookmarks.persist("bookmarks") { ordered }
        if (keptLists != _watchLists.value) _watchLists.persist("watchLists") { keptLists }
        if (ratings != _ratings.value) _ratings.persist("ratings") { ratings }
        if (mergedHistory != _history.value) _history.persist("history") { mergedHistory }
        if (positions != _positions.value) _positions.persist("positions") { positions }
        if (imported != _importedRatings.value) _importedRatings.persist("importedRatings") { imported }
        if (dismissed != _dismissedRecommendations.value) _dismissedRecommendations.persist("dismissedRecommendations") { dismissed }
        remoteChanges.forEach { (key, at) -> if (at > time(key)) changes[key] = at }
        if (changes != _changes.value) _changes.persist("changes") { changes }
    }
}
