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
) {
    @get:JsonIgnore
    val key: String get() = (if (movie) "movie:" else "tv:") + id

    @get:JsonIgnore
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

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
    ) {
        fun toTitle(movie: Boolean) = TmdbTitle(
            id, movie, title ?: name ?: "", (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(), posterPath,
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
}

