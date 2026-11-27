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

/** An actor, or someone else who worked on movies and shows */
data class TmdbPerson(
    val id: Int,
    val name: String,
    val profilePath: String? = null,
    /** Acting, Directing... */
    val department: String? = null,
    /** A few of their best known titles */
    val knownFor: List<String> = emptyList(),
) {
    val photoUrl: String? get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

/** A title someone played in, with their role */
data class TmdbCredit(val title: TmdbTitle, val character: String?, val episodes: Int?)

data class TmdbPersonDetails(
    val person: TmdbPerson,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val birthplace: String?,
    /** Released titles they acted in, newest first */
    val credits: List<TmdbCredit>,
)

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PersonResult(
        val id: Int,
        val name: String = "",
        @JsonProperty("profile_path") val profilePath: String? = null,
        @JsonProperty("known_for_department") val department: String? = null,
        @JsonProperty("known_for") val knownFor: List<Result> = emptyList(),
        val popularity: Double = 0.0,
    ) {
        fun toPerson() = TmdbPerson(id, name, profilePath, department, knownFor.mapNotNull { (it.title ?: it.name)?.takeIf(String::isNotBlank) })
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PersonPage(val results: List<PersonResult> = emptyList())

    /** People named like [query], actors first, the ones without a photo or known title are left out */
    suspend fun searchPeople(query: String): List<TmdbPerson> {
        val text = app.get(
            "$HOST/search/person",
            params = mapOf("api_key" to API_KEY, "language" to language, "query" to query, "include_adult" to "false"),
        ).text
        return mapper.readValue<PersonPage>(text).results
            .filter { it.profilePath != null && it.knownFor.isNotEmpty() }
            .sortedByDescending { if (it.department == "Acting") it.popularity + 1_000 else it.popularity }
            .map { it.toPerson() }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CastCredit(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        val character: String? = null,
        @JsonProperty("episode_count") val episodes: Int? = null,
        @JsonProperty("genre_ids") val genres: List<Int> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CombinedCredits(val cast: List<CastCredit> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PersonDetails(
        val id: Int,
        val name: String = "",
        val biography: String? = null,
        val birthday: String? = null,
        val deathday: String? = null,
        @JsonProperty("place_of_birth") val birthplace: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
        @JsonProperty("known_for_department") val department: String? = null,
        @JsonProperty("combined_credits") val credits: CombinedCredits = CombinedCredits(),
    )

    /** Talk shows, news and award shows, where people appear as themselves */
    private val NOT_A_ROLE = setOf(10763, 10767)
    private val SELF = Regex("""^(self|himself|herself|themselves)\b""", RegexOption.IGNORE_CASE)

    suspend fun person(id: Int): TmdbPersonDetails {
        val text = app.get(
            "$HOST/person/$id",
            params = mapOf("api_key" to API_KEY, "language" to language, "append_to_response" to "combined_credits"),
        ).text
        val details = mapper.readValue<PersonDetails>(text)
        val today = java.time.LocalDate.now().toString()
        val credits = details.credits.cast
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .filter { it.genres.none(NOT_A_ROLE::contains) && !SELF.containsMatchIn(it.character.orEmpty()) }
            .filter { (it.releaseDate ?: it.firstAirDate).let { date -> !date.isNullOrBlank() && date <= today } }
            // Someone with several roles in one title is listed once
            .distinctBy { it.mediaType + it.id }
            .sortedByDescending { it.releaseDate ?: it.firstAirDate }
            .map { credit ->
                val movie = credit.mediaType == "movie"
                TmdbCredit(
                    TmdbTitle(
                        credit.id, movie, credit.title ?: credit.name ?: "",
                        (credit.releaseDate ?: credit.firstAirDate)?.take(4)?.toIntOrNull(), credit.posterPath, credit.backdropPath,
                    ),
                    credit.character?.takeIf { it.isNotBlank() },
                    credit.episodes.takeIf { !movie },
                )
            }
        return TmdbPersonDetails(
            TmdbPerson(details.id, details.name, details.profilePath, details.department),
            details.biography?.takeIf { it.isNotBlank() },
            details.birthday,
            details.deathday,
            details.birthplace,
            credits,
        )
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
