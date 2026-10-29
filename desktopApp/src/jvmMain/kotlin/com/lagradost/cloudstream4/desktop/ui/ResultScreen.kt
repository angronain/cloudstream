package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.style.TextAlign
import com.lagradost.cloudstream4.desktop.data.Tmdb
import com.lagradost.cloudstream4.desktop.data.TmdbTitle
import com.lagradost.cloudstream4.desktop.generated.resources.add
import com.lagradost.cloudstream4.desktop.generated.resources.check
import com.lagradost.cloudstream4.desktop.generated.resources.star_border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.MediaEntry
import com.lagradost.cloudstream4.desktop.data.WatchList
import com.lagradost.cloudstream4.desktop.data.WatchProgress
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.arrow_back
import com.lagradost.cloudstream4.desktop.generated.resources.bookmark
import com.lagradost.cloudstream4.desktop.generated.resources.bookmark_border
import com.lagradost.cloudstream4.desktop.generated.resources.favorite
import com.lagradost.cloudstream4.desktop.generated.resources.favorite_border
import com.lagradost.cloudstream4.desktop.generated.resources.open_in_new
import com.lagradost.cloudstream4.desktop.generated.resources.play
import com.lagradost.cloudstream4.desktop.generated.resources.star
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.painterResource
import java.awt.Desktop
import java.net.URI

private sealed interface ResultState {
    data object Loading : ResultState
    data class Error(val message: String) : ResultState
    data class Loaded(val response: LoadResponse) : ResultState
}

/** Episodes grouped by dub status and season, as shown in the season/dub selectors */
private data class EpisodeGroups(
    val byDub: Map<DubStatus, Map<Int?, List<Episode>>>,
    val seasonNames: Map<Int, String>,
)

private fun LoadResponse.episodeGroups(): EpisodeGroups? {
    val episodes: Map<DubStatus, List<Episode>> = when (this) {
        is TvSeriesLoadResponse -> mapOf(DubStatus.None to episodes)
        is AnimeLoadResponse -> episodes.filterValues { it.isNotEmpty() }
        else -> return null
    }
    val names = (this as? EpisodeResponse)?.seasonNames.orEmpty().associate { data ->
        data.season to (data.name ?: tr("Season {0}", data.displaySeason ?: data.season))
    }
    return EpisodeGroups(
        byDub = episodes.mapValues { (_, list) ->
            list.groupBy { it.season }.toSortedMap(compareBy(nullsFirst()) { it })
        },
        seasonNames = names,
    )
}

private fun Episode.label(index: Int): String {
    val number = episode ?: (index + 1)
    val prefix = if (season != null) tr("S{0} E{1}", season, number) else tr("Episode {0}", number)
    return if (name.isNullOrBlank()) prefix else "$prefix · $name"
}

private suspend fun load(media: MediaEntry): ResultState {
    val api = APIHolder.getApiFromNameNull(media.apiName)
        ?: return ResultState.Error(tr("The extension {0} is not installed or failed to load", media.apiName))
    return try {
        val response = withContext(Dispatchers.IO) {
            withTimeout(api.loadTimeoutMs ?: 60_000L) { api.load(media.url) }
        }
        response?.let { ResultState.Loaded(it) } ?: ResultState.Error(tr("Nothing returned by {0}", api.name))
    } catch (t: Throwable) {
        logError(t)
        ResultState.Error(t.message ?: t.javaClass.simpleName)
    }
}

private fun LoadResponse.playlist(episodes: List<Episode>): List<Playable> {
    val malId = if (this is AnimeLoadResponse) getMalId()?.toIntOrNull() else null
    return episodes.mapIndexed { i, episode ->
        Playable(episode.data, "$name · ${episode.label(i)}", episode.season, episode.episode, episode.posterUrl, malId)
    }
}

/** Episodes this far in count as watched, the next one is played instead */
private const val WATCHED_FRACTION = 0.9f

/**
 * The player for what [progress] was watching: the same episode where it stopped, or the next one if it was finished.
 * Null if the show is finished or could not be loaded.
 */
suspend fun continueRoute(progress: WatchProgress): Route.Player? {
    val media = progress.media
    val data = progress.episodeData ?: return null
    // Movies are saved without an episode name, they need no episode list
    if (progress.episodeName == null) return Route.Player(media, listOf(Playable(data, media.name, posterUrl = media.posterUrl)), 0)

    val response = (load(media) as? ResultState.Loaded)?.response ?: return null
    val seasons = response.episodeGroups()?.byDub?.values
        ?.firstOrNull { seasons -> seasons.values.any { episodes -> episodes.any { it.data == data } } }
        ?.values?.toList() ?: return null
    var season = seasons.indexOfFirst { episodes -> episodes.any { it.data == data } }
    var index = seasons[season].indexOfFirst { it.data == data }
    if ((AppData.positions.value[data]?.fraction ?: 0f) >= WATCHED_FRACTION) {
        when {
            index + 1 < seasons[season].size -> index++
            season + 1 < seasons.size -> { season++; index = 0 }
            else -> return null
        }
    }
    return Route.Player(media, response.playlist(seasons[season]), index)
}

/**
 * How many episodes the show of [progress] has, in the dub of the last watched episode.
 * Null if it could not be loaded or is not a show.
 */
suspend fun episodeCount(progress: WatchProgress): Int? {
    val response = (load(progress.media) as? ResultState.Loaded)?.response ?: return null
    val groups = response.episodeGroups()?.byDub?.values ?: return null
    val dub = groups.firstOrNull { seasons -> seasons.values.any { episodes -> episodes.any { it.data == progress.episodeData } } }
        ?: groups.maxByOrNull { seasons -> seasons.values.sumOf { it.size } }
    return dub?.values?.sumOf { it.size }
}

fun openInBrowser(url: String) {
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (t: Throwable) {
        logError(t)
    }
}

@Composable
fun ResultScreen(media: MediaEntry) {
    val navigator = LocalNavigator.current
    var state by remember(media) { mutableStateOf<ResultState>(ResultState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(media, reloadKey) {
        state = ResultState.Loading
        state = load(media)
    }
    // Only the loaded page has artwork under the navigation bar
    LaunchedEffect(state is ResultState.Loaded) { if (state !is ResultState.Loaded) navigator.heroScrolled = true }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            ResultState.Loading -> LoadingBox(tr("Loading {0}", media.name), Modifier.fillMaxSize().padding(top = 64.dp))
            is ResultState.Error -> Box(Modifier.fillMaxSize().padding(top = 64.dp)) {
                MessageBox(
                    tr("Could not load {0}", media.name),
                    current.message,
                    action = tr("Retry") to { reloadKey++ },
                )
            }

            is ResultState.Loaded -> ResultContent(media, current.response)
        }
    }
}

@Composable
private fun ResultContent(media: MediaEntry, response: LoadResponse) {
    val navigator = LocalNavigator.current
    val bookmarks by AppData.bookmarks.collectAsState()
    val watchLists by AppData.watchLists.collectAsState()
    val ratings by AppData.ratings.collectAsState()
    val positions by AppData.positions.collectAsState()
    val entry = remember(response) {
        media.copy(
            name = response.name,
            posterUrl = response.posterUrl ?: media.posterUrl,
            type = response.type,
            posterHeaders = response.posterHeaders ?: media.posterHeaders,
        )
    }
    val groups = remember(response) { response.episodeGroups() }
    var dub by remember(groups) {
        mutableStateOf(groups?.byDub?.keys?.let { keys -> keys.firstOrNull { it == DubStatus.Subbed } ?: keys.firstOrNull() })
    }
    val seasons = groups?.byDub?.get(dub).orEmpty()
    // The season of the last episode watched, else the first
    var season by remember(groups, dub) {
        mutableStateOf(
            seasons.entries.lastOrNull { (_, list) -> list.any { (positions[it.data]?.positionMs ?: 0L) > 0L } }?.key
                ?: seasons.keys.firstOrNull()
        )
    }
    val episodes = seasons[season].orEmpty()

    fun playEpisodes(list: List<Episode>, index: Int) = navigator.push(Route.Player(entry, response.playlist(list), index))

    val movieData: String? = when (response) {
        is MovieLoadResponse -> response.dataUrl
        is LiveStreamLoadResponse -> response.dataUrl
        else -> null
    }

    // Providers often only have a poster, TMDB has wide artwork for most titles
    val tmdb by produceState<TmdbTitle?>(null, response) {
        if (response.backgroundPosterUrl == null) {
            value = runCatching { Tmdb.find(response.name, response.year, response.type.isMovie()) }.getOrNull()
        }
    }
    var ambient by remember(media) { mutableStateOf(DefaultAmbient) }
    val background by animateColorAsState(ambient, tween(600))

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 300 } }
    LaunchedEffect(scrolled) { navigator.heroScrolled = scrolled }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.78f).coerceIn(460.dp, 820.dp)
        val wide = maxWidth > 900.dp
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                Box(Modifier.fillMaxWidth().height(heroHeight).background(background)) {
                    val tmdbArt = tmdb?.backdropUrl
                    AmbientArtwork(
                        response.backgroundPosterUrl ?: tmdbArt ?: response.posterUrl,
                        if (response.backgroundPosterUrl == null && tmdbArt != null) null else response.posterHeaders,
                        background, wide,
                    ) { ambient = it }
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
                    )
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 24.dp, bottom = 40.dp).widthIn(max = 620.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(response.apiName, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                        Text(
                            response.name, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            response.year?.toString(),
                            response.type.name,
                            response.duration?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                            response.contentRating,
                            response.score?.let { "★ $it" },
                        )
                        Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                        response.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                            Text(tags.take(5).joinToString(" · "), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                        }
                        if (response.comingSoon) Text(tr("Coming soon"), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        response.plot?.let {
                            Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }

                        // Where the user is in this season
                        val resumeIndex = episodes.indexOfFirst { (positions[it.data]?.fraction ?: 0f) < WATCHED_FRACTION }.coerceAtLeast(0)
                        val started = episodes.any { (positions[it.data]?.positionMs ?: 0L) > 0L }
                        if (started && episodes.size in 2..40) {
                            SeasonLine(episodes.size, resumeIndex + 1, positions[episodes[resumeIndex].data]?.fraction ?: 0f)
                        } else if (movieData != null) {
                            positions[movieData]?.fraction?.takeIf { it > 0f }?.let { SeasonLine(1, 1, it) }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            when {
                                movieData != null -> PrimaryPill(
                                    if (positions[movieData]?.positionMs?.let { it > 0 } == true) tr("Resume") else tr("Play"),
                                    Res.drawable.play, false,
                                ) {
                                    navigator.push(Route.Player(entry, listOf(Playable(movieData, response.name, posterUrl = response.posterUrl)), 0))
                                }

                                response is TorrentLoadResponse -> PrimaryPill(tr("Open in torrent client"), Res.drawable.open_in_new, false) {
                                    (response.magnet ?: response.torrent)?.let(::openInBrowser)
                                }

                                episodes.isNotEmpty() -> {
                                    val next = episodes[resumeIndex]
                                    val label = when {
                                        !started -> tr("Play")
                                        next.season != null && next.episode != null -> tr("Resume S{0}E{1}", next.season, next.episode)
                                        else -> tr("Resume")
                                    }
                                    PrimaryPill(label, Res.drawable.play, false) { playEpisodes(episodes, resumeIndex) }
                                }
                            }
                            if (movieData != null && response !is LiveStreamLoadResponse) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                                        DownloadButton(entry, Playable(movieData, response.name, posterUrl = response.posterUrl))
                                    }
                                }
                            }
                            val saved = bookmarks.any { it.url == media.url }
                            val list = watchLists[media.url] ?: WatchList.PlanToWatch
                            Box {
                                var listMenu by remember { mutableStateOf(false) }
                                GhostPill(if (saved) list.title else tr("My list"), if (saved) Res.drawable.bookmark else Res.drawable.add) { listMenu = true }
                                DropdownMenu(listMenu, onDismissRequest = { listMenu = false }) {
                                    WatchList.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.title, fontWeight = if (saved && option == list) FontWeight.Bold else null) },
                                            onClick = {
                                                listMenu = false
                                                AppData.setWatchList(entry, option)
                                            },
                                        )
                                    }
                                    if (saved) {
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text(tr("Remove bookmark")) }, onClick = {
                                            listMenu = false
                                            AppData.toggleBookmark(entry)
                                        })
                                    }
                                }
                            }
                            val rating = ratings[media.url]
                            var rate by remember { mutableStateOf(false) }
                            if (rate) {
                                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                                    RatingBar(rating?.stars, { AppData.rate(entry, it, response.year); rate = false }, starSize = 22.dp)
                                }
                            } else {
                                GhostPill(rating?.stars?.let { formatStars(it) } ?: tr("Rate"), Res.drawable.star_border) { rate = true }
                            }
                            GhostPill(
                                if (rating?.liked == true) tr("Unlike") else tr("Like"),
                                if (rating?.liked == true) Res.drawable.favorite else Res.drawable.favorite_border,
                            ) { AppData.toggleLike(entry, response.year) }
                            GhostPill(tr("Website"), Res.drawable.open_in_new) { openInBrowser(response.url) }
                        }
                    }
                }
            }

            if (groups != null) {
                item {
                    Column(Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Episodes"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (groups.byDub.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                groups.byDub.keys.forEach { status -> ChoicePill(status.name, dub == status) { dub = status } }
                            }
                        }
                        if (seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(seasons.keys.toList()) { key ->
                                    ChoicePill(key?.let { groups.seasonNames[it] ?: tr("Season {0}", it) } ?: tr("Episodes"), season == key) { season = key }
                                }
                            }
                        }
                        if (episodes.isEmpty()) Text(tr("No episodes found"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(episodes.size, key = { episodes[it].data + it }) { index ->
                    val episode = episodes[index]
                    EpisodeRow(
                        download = {
                            DownloadButton(
                                entry,
                                Playable(episode.data, "${response.name} · ${episode.label(index)}", episode.season, episode.episode, episode.posterUrl),
                            )
                        },
                        number = episode.episode ?: (index + 1),
                        title = episode.name?.takeIf { it.isNotBlank() } ?: episode.label(index),
                        description = episode.description,
                        posterUrl = episode.posterUrl ?: response.posterUrl,
                        headers = response.posterHeaders,
                        progress = positions[episode.data]?.fraction,
                        onClick = { playEpisodes(episodes, index) },
                    )
                }
            }

            response.actors?.takeIf { it.isNotEmpty() }?.let { actors ->
                item {
                    Text(
                        tr("Cast"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 40.dp, top = 24.dp, bottom = 12.dp),
                    )
                    LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(actors.take(30)) { data ->
                            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                RemoteImage(data.actor.image, Modifier.size(84.dp).clip(RoundedCornerShape(50)))
                                Spacer(Modifier.height(6.dp))
                                Text(data.actor.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                data.roleString?.let {
                                    Text(
                                        it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            response.recommendations?.takeIf { it.isNotEmpty() }?.let { recommendations ->
                item {
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.padding(horizontal = 24.dp)) { SearchResponseRow(tr("More like this"), recommendations) }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(
    download: @Composable () -> Unit,
    number: Int,
    title: String,
    description: String?,
    posterUrl: String?,
    headers: Map<String, String>?,
    progress: Float?,
    onClick: () -> Unit,
) {
    val watched = (progress ?: 0f) >= WATCHED_FRACTION
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(200.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(posterUrl, Modifier.fillMaxSize(), headers)
            if (watched) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Icon(
                painterResource(if (watched) Res.drawable.check else Res.drawable.play),
                null,
                Modifier.align(Alignment.Center).size(36.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(6.dp),
                tint = Color.White,
            )
            if (progress != null && progress > 0f && !watched) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        download()
    }
}
