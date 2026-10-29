package com.lagradost.cloudstream4.desktop.ui

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.I18n
import com.lagradost.cloudstream4.desktop.data.Tmdb
import com.lagradost.cloudstream4.desktop.data.TmdbTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** A title to suggest, [because] is the title the user liked that it is most like */
data class Recommendation(val title: TmdbTitle, val score: Double, val because: String)

/**
 * Suggests titles like the ones the user rated well, liked or watched, from TMDB's recommendations.
 * Ratings imported from Letterboxd or Trakt count too, that is what they are imported for.
 */
object Recommendations {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(AppDirs.cacheDir, "recommendations.json")
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_SEEDS = 60
    private const val YEAR_MS = 365.0 * 24 * 60 * 60 * 1000

    private data class Seed(val name: String, val year: Int?, val movie: Boolean?, val weight: Double)

    private data class Cache(
        val seeds: Int = 0,
        val language: String = "",
        val builtAt: Long = 0,
        val items: List<Recommendation> = emptyList(),
        /** TMDB titles found for "name|year", null if none, so each name is only searched once */
        val found: Map<String, TmdbTitle?> = emptyMap(),
    )

    private var cache: Cache = runCatching { mapper.readValue<Cache>(file) }.getOrDefault(Cache())

    private val _items = MutableStateFlow(cache.items)
    val items: StateFlow<List<Recommendation>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private fun TvType?.isMovie(): Boolean? = when (this) {
        null, TvType.Others -> null
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> true
        else -> false
    }

    /** What the user liked, the better the rating the more it counts, low ratings count against */
    private fun seeds(): List<Seed> {
        val seeds = LinkedHashMap<String, Seed>()
        val now = System.currentTimeMillis()
        fun add(name: String, year: Int?, movie: Boolean?, stars: Float?, liked: Boolean, watched: Boolean, date: Long?) {
            val opinion = (stars?.let { it - 2.75 } ?: if (watched) 0.75 else 0.0) + if (liked) 1.0 else 0.0
            if (opinion == 0.0) return
            // Tastes change: an opinion counts half as much after a year, but never less than a quarter.
            // Rounded, so the list is not rebuilt on every launch only because a day passed
            val years = date?.let { (now - it).coerceAtLeast(0) / YEAR_MS } ?: 0.0
            val weight = kotlin.math.round(opinion * maxOf(0.25, Math.pow(0.5, years)) * 10) / 10
            if (weight == 0.0) return
            // One opinion per title, the app's own rating wins over its history and over an imported one
            val key = name.lowercase()
            if (key !in seeds) seeds[key] = Seed(name, year, movie, weight)
        }
        AppData.ratings.value.values.sortedByDescending { it.updatedAt }.forEach {
            add(it.media.name, it.year, it.media.type.isMovie(), it.stars, it.liked, true, it.updatedAt)
        }
        AppData.history.value.forEach { add(it.media.name, null, it.media.type.isMovie(), null, false, true, it.updatedAt) }
        AppData.importedRatings.value.values.forEach { add(it.name, it.year, it.movie, it.stars, it.liked, true, it.date) }
        // The strongest opinions first, the dislikes only matter for the titles they would bring
        return seeds.values.sortedByDescending { kotlin.math.abs(it.weight) }.take(MAX_SEEDS)
    }

    /** Rebuilds the list if what the user rated changed, the language changed or it is a few days old */
    fun refreshIfDue() {
        if (!AppData.settings.value.recommendations) return
        val seeds = seeds()
        if (seeds.hashCode() == cache.seeds && I18n.current == cache.language &&
            System.currentTimeMillis() - cache.builtAt < MAX_AGE_MS
        ) return
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        scope.launch {
            try {
                build()
            } catch (t: Throwable) {
                logError(t)
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun build() {
        val seeds = seeds()
        val limit = Semaphore(4)
        val found = cache.found.toMutableMap()
        // TMDB returns names in the app's language, the searches stay valid
        val resolved = scope.async {
            seeds.map { seed ->
                async {
                    val key = "${seed.name.lowercase()}|${seed.year ?: ""}|${seed.movie}"
                    val title = if (key in found) found[key] else limit.withPermit {
                        runCatching { Tmdb.find(seed.name, seed.year, seed.movie) }.onFailure { logError(it) }.getOrNull()
                            .also { synchronized(found) { found[key] = it } }
                    }
                    title?.let { seed to it }
                }
            }.awaitAll().filterNotNull()
        }.await()

        val known = resolved.map { it.second.key }.toSet() + AppData.dismissedRecommendations.value
        val knownNames = (seeds.map { it.name.lowercase() } + resolved.map { it.second.name.lowercase() }).toSet()
        val scores = HashMap<String, Double>()
        val titles = HashMap<String, TmdbTitle>()
        val because = HashMap<String, Pair<String, Double>>()
        resolved.map { (seed, title) ->
            scope.async {
                seed to limit.withPermit { runCatching { Tmdb.recommendations(title) }.onFailure { logError(it) }.getOrDefault(emptyList()) }
            }
        }.awaitAll().forEach { (seed, recommended) ->
            // Only the closest few, so one favourite does not fill the whole row
            recommended.take(10).forEachIndexed { rank, title ->
                if (title.key in known || title.name.lowercase() in knownNames) return@forEachIndexed
                // TMDB lists the closest titles first
                val points = seed.weight * (1.0 - rank / 12.0)
                scores[title.key] = (scores[title.key] ?: 0.0) + points
                titles[title.key] = title
                if (points > (because[title.key]?.second ?: 0.0)) because[title.key] = seed.name to points
            }
        }
        val items = scores.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(40).map { (key, score) ->
            Recommendation(titles.getValue(key), score, because[key]?.first ?: "")
        }
        cache = Cache(seeds.hashCode(), I18n.current, System.currentTimeMillis(), items, found)
        _items.value = items
        runCatching { mapper.writeValue(file, cache) }.onFailure { logError(it) }
    }

    fun dismiss(item: Recommendation) {
        AppData.dismissRecommendation(item.title.key)
        _items.value = _items.value - item
    }
}
