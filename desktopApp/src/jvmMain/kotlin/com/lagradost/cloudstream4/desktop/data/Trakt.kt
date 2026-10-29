package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** The signed in Trakt account */
data class TraktAccount(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val username: String? = null,
)

sealed interface TraktState {
    data object SignedOut : TraktState
    /** The user enters [code] at [url] */
    data class WaitingForCode(val code: String, val url: String) : TraktState
    data class SignedIn(val username: String?) : TraktState
    data class Failed(val message: String) : TraktState
}

/**
 * Trakt (trakt.tv): marks what is watched in the player as watched on Trakt, sends ratings and Plan to watch
 * there and takes Trakt's ratings for the recommendations. Titles are matched by their TMDB id, which Trakt knows.
 * Trakt needs an API application of the user's own, its client id and secret are in the settings.
 */
object Trakt {
    private const val HOST = "https://api.trakt.tv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signInJob: Job? = null

    private val _state = MutableStateFlow<TraktState>(AppData.traktAccount?.let { TraktState.SignedIn(it.username) } ?: TraktState.SignedOut)
    val state: StateFlow<TraktState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val clientId get() = AppData.settings.value.traktClientId.trim()
    private val clientSecret get() = AppData.settings.value.traktClientSecret.trim()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DeviceCode(
        @JsonProperty("device_code") val deviceCode: String,
        @JsonProperty("user_code") val userCode: String,
        @JsonProperty("verification_url") val verificationUrl: String,
        @JsonProperty("expires_in") val expiresIn: Int,
        val interval: Int,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Token(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("refresh_token") val refreshToken: String,
        @JsonProperty("expires_in") val expiresIn: Long,
    )

    /** Shows a code to enter on trakt.tv/activate and waits for the user to do it */
    fun signIn(openUrl: (String) -> Unit) {
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                require(clientId.isNotEmpty() && clientSecret.isNotEmpty()) { tr("Enter the client ID and secret of your Trakt application first") }
                val code = mapper.readValue<DeviceCode>(app.post("$HOST/oauth/device/code", json = mapOf("client_id" to clientId)).text)
                _state.value = TraktState.WaitingForCode(code.userCode, code.verificationUrl)
                openUrl(code.verificationUrl)
                val until = System.currentTimeMillis() + code.expiresIn * 1000L
                while (System.currentTimeMillis() < until) {
                    delay(code.interval * 1000L)
                    val response = app.post(
                        "$HOST/oauth/device/token",
                        json = mapOf("code" to code.deviceCode, "client_id" to clientId, "client_secret" to clientSecret),
                    )
                    // 400 means the user has not entered the code yet
                    if (response.code == 400) continue
                    if (response.code == 429) { delay(code.interval * 1000L); continue }
                    require(response.isSuccessful) { tr("Trakt refused the sign in ({0})", response.code) }
                    save(mapper.readValue<Token>(response.text))
                    val username = runCatching {
                        mapper.readTree(get("users/settings")).path("user").path("username").asText(null)
                    }.getOrNull()
                    AppData.traktAccount = AppData.traktAccount?.copy(username = username)
                    _state.value = TraktState.SignedIn(username)
                    syncLibrary()
                    return@launch
                }
                error(tr("The code expired, try again"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                logError(t)
                _state.value = TraktState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun signOut() {
        signInJob?.cancel()
        AppData.traktAccount = null
        _state.value = TraktState.SignedOut
    }

    private fun save(token: Token) {
        AppData.traktAccount = TraktAccount(
            token.accessToken, token.refreshToken, System.currentTimeMillis() + token.expiresIn * 1000,
            AppData.traktAccount?.username,
        )
    }

    private suspend fun accessToken(): String? {
        val account = AppData.traktAccount ?: return null
        if (System.currentTimeMillis() < account.expiresAt - 60_000) return account.accessToken
        val response = app.post(
            "$HOST/oauth/token",
            json = mapOf(
                "refresh_token" to account.refreshToken, "client_id" to clientId, "client_secret" to clientSecret,
                "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob", "grant_type" to "refresh_token",
            ),
        )
        if (!response.isSuccessful) {
            // The refresh token is gone too, the user has to sign in again
            if (response.code == 401 || response.code == 400) signOut()
            return null
        }
        save(mapper.readValue<Token>(response.text))
        return AppData.traktAccount?.accessToken
    }

    private suspend fun headers(): Map<String, String>? {
        val token = accessToken() ?: return null
        return mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId, "Authorization" to "Bearer $token")
    }

    private suspend fun get(path: String): String {
        val response = app.get("$HOST/$path", headers = headers() ?: error("Not signed in"))
        require(response.isSuccessful) { "Trakt: ${response.code}" }
        return response.text
    }

    private suspend fun post(path: String, body: Any): Boolean {
        val headers = headers() ?: return false
        val response = app.post("$HOST/$path", headers = headers, json = body)
        if (!response.isSuccessful) logError(Exception("Trakt $path: ${response.code} ${response.text.take(200)}"))
        return response.isSuccessful
    }

    private fun TvType?.isMovie(): Boolean? = when (this) {
        null, TvType.Others -> null
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> true
        else -> false
    }

    /** TMDB ids found for each title url, so a show is only searched once per launch */
    private val found = HashMap<String, TmdbTitle?>()

    private suspend fun tmdb(media: MediaEntry, year: Int? = null): TmdbTitle? {
        if (media.url in found) return found[media.url]
        return runCatching { Tmdb.find(media.name, year, media.type.isMovie()) }.getOrNull().also { found[media.url] = it }
    }

    private fun ids(title: TmdbTitle) = mapOf("ids" to mapOf("tmdb" to title.id))

    // Scrobbling: Trakt marks the movie or episode as watched when it stops past 80%

    private var scrobbling: Pair<String, String>? = null

    /** Tells Trakt what the player is doing, [action] is start, pause or stop */
    fun scrobble(action: String, media: MediaEntry, season: Int?, episode: Int?, progress: Float) {
        if (AppData.traktAccount == null || !AppData.settings.value.traktScrobble) return
        val key = "${media.url}|$season|$episode"
        // The same state twice in a row says nothing new
        if (scrobbling == key to action && action != "stop") return
        scrobbling = key to action
        scope.launch {
            try {
                val title = tmdb(media) ?: return@launch
                val body: Map<String, Any> = if (title.movie || episode == null) {
                    mapOf("movie" to ids(title), "progress" to progress * 100)
                } else {
                    mapOf("show" to ids(title), "episode" to mapOf("season" to (season ?: 1), "number" to episode), "progress" to progress * 100)
                }
                post("scrobble/$action", body)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    // Library

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TraktIds(val tmdb: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TraktTitle(val title: String? = null, val year: Int? = null, val ids: TraktIds = TraktIds())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TraktRating(val rating: Int, val type: String = "", val movie: TraktTitle? = null, val show: TraktTitle? = null)

    /** Sends the app's ratings and Plan to watch list to Trakt and takes Trakt's ratings for the recommendations */
    fun syncLibrary() {
        scope.launch {
            try {
                _message.value = tr("Syncing with Trakt…")
                val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }

                val ratings = AppData.ratings.value.values.filter { it.stars != null }.mapNotNull { rating ->
                    tmdb(rating.media, rating.year)?.let { title ->
                        title to ids(title) + mapOf("rating" to (rating.stars!! * 2).toInt().coerceIn(1, 10), "rated_at" to date.format(Date(rating.updatedAt)))
                    }
                }
                val sentRatings = ratings.isEmpty() || post(
                    "sync/ratings",
                    mapOf("movies" to ratings.filter { it.first.movie }.map { it.second }, "shows" to ratings.filter { !it.first.movie }.map { it.second }),
                )

                val planned = AppData.bookmarks.value.filter { AppData.watchList(it.url) == WatchList.PlanToWatch }.mapNotNull { tmdb(it) }
                val sentList = planned.isEmpty() || post(
                    "sync/watchlist",
                    mapOf("movies" to planned.filter { it.movie }.map(::ids), "shows" to planned.filter { !it.movie }.map(::ids)),
                )

                // Seasons and episodes are rated too, only whole titles help the recommendations
                val theirs = mapper.readValue<List<TraktRating>>(get("sync/ratings")).filter { it.type == "movie" || it.type == "show" }.mapNotNull { rating ->
                    val title = rating.movie ?: rating.show ?: return@mapNotNull null
                    ImportedRating(title.title ?: return@mapNotNull null, title.year, rating.rating / 2f, source = "Trakt", movie = rating.movie != null)
                }
                AppData.clearImportedRatings("Trakt")
                AppData.importRatings(theirs)

                _message.value = if (sentRatings && sentList) {
                    tr("Sent {0} ratings and {1} titles to watch, got {2} ratings from Trakt", ratings.size, planned.size, theirs.size)
                } else {
                    tr("Trakt did not accept everything, see the logs")
                }
            } catch (t: Throwable) {
                logError(t)
                _message.value = tr("Could not sync with Trakt: {0}", t.message)
            }
        }
    }
}
