package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper

/** A movie or show on TMDB */
data class TmdbTitle(
    val id: Int,
    val movie: Boolean,
    val name: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
) {
    @get:JsonIgnore
    val key: String get() = (if (movie) "movie:" else "tv:") + id

    @get:JsonIgnore
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

    @get:JsonIgnore
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
}

/** What the Home spotlight shows about a title */
data class TmdbDetails(
    val title: TmdbTitle,
    val overview: String?,
    val genres: List<String>,
    /** Minutes, of a movie or a typical episode */
    val runtime: Int?,
    val rating: Double?,
    val cast: List<String>,
    /** Episode counts by season number, specials left out */
    val seasons: Map<Int, Int>,
)

data class TmdbEpisode(val number: Int, val name: String)

/** The TMDB api, with the key the Android app uses */
object Tmdb {
    private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val HOST = "https://api.themoviedb.org/3"

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Result(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
    ) {
        fun toTitle(movie: Boolean) = TmdbTitle(
            id, movie, title ?: name ?: "", (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(), posterPath, backdropPath,
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Page(val results: List<Result> = emptyList())

    private val language: String
        get() = when (I18n.current) {
            "fr" -> "fr-FR"
            "zh" -> "zh-CN"
            else -> "en-US"
        }

    private suspend fun page(path: String, params: Map<String, String> = emptyMap()): List<Result> {
        val text = app.get("$HOST/$path", params = params + mapOf("api_key" to API_KEY, "language" to language)).text
        return mapper.readValue<Page>(text).results
    }

    /** The title named [name], from [year] if known. [movie] null searches both movies and shows */
    suspend fun find(name: String, year: Int?, movie: Boolean?): TmdbTitle? {
        val results = when (movie) {
            true -> page("search/movie", mapOf("query" to name) + (year?.let { mapOf("year" to "$it") } ?: emptyMap())).map { it.toTitle(true) }
            false -> page("search/tv", mapOf("query" to name) + (year?.let { mapOf("first_air_date_year" to "$it") } ?: emptyMap())).map { it.toTitle(false) }
            null -> page("search/multi", mapOf("query" to name))
                .filter { it.mediaType == "movie" || it.mediaType == "tv" }
                .map { it.toTitle(it.mediaType == "movie") }
        }
        // The year can be off by one between sites
        return results.firstOrNull { year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1 } ?: results.firstOrNull()
    }

    suspend fun recommendations(title: TmdbTitle): List<TmdbTitle> =
        page((if (title.movie) "movie/" else "tv/") + "${title.id}/recommendations").map { it.toTitle(title.movie) }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Named(val name: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Credits(val cast: List<Named> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Season(@JsonProperty("season_number") val number: Int = 0, @JsonProperty("episode_count") val episodes: Int = 0)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Details(
        val overview: String? = null,
        val genres: List<Named> = emptyList(),
        val runtime: Int? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
        @JsonProperty("vote_average") val rating: Double? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        val credits: Credits = Credits(),
        val seasons: List<Season> = emptyList(),
    )

    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, TmdbDetails>()

    suspend fun details(title: TmdbTitle): TmdbDetails {
        val cacheKey = title.key + "|" + language
        detailsCache[cacheKey]?.let { return it }
        val text = app.get(
            "$HOST/${if (title.movie) "movie" else "tv"}/${title.id}",
            params = mapOf("api_key" to API_KEY, "language" to language, "append_to_response" to "credits"),
        ).text
        val details = mapper.readValue<Details>(text)
        return TmdbDetails(
            title.copy(backdropPath = title.backdropPath ?: details.backdropPath),
            details.overview?.takeIf { it.isNotBlank() },
            details.genres.map { it.name },
            details.runtime ?: details.episodeRunTime.firstOrNull(),
            details.rating?.takeIf { it > 0 },
            details.credits.cast.take(8).map { it.name },
            details.seasons.filter { it.number > 0 && it.episodes > 0 }.associate { it.number to it.episodes },
        ).also { detailsCache[cacheKey] = it }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SeasonEpisode(@JsonProperty("episode_number") val number: Int = 0, val name: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SeasonDetails(val episodes: List<SeasonEpisode> = emptyList())

    suspend fun episodes(show: TmdbTitle, season: Int): List<TmdbEpisode> {
        val text = app.get(
            "$HOST/tv/${show.id}/season/$season",
            params = mapOf("api_key" to API_KEY, "language" to language),
        ).text
        return mapper.readValue<SeasonDetails>(text).episodes.map { TmdbEpisode(it.number, it.name) }
    }
}
