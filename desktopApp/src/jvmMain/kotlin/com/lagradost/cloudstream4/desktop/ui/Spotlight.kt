package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.toBitmap
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.MediaEntry
import com.lagradost.cloudstream4.desktop.data.Tmdb
import com.lagradost.cloudstream4.desktop.data.TmdbDetails
import com.lagradost.cloudstream4.desktop.data.TmdbEpisode
import com.lagradost.cloudstream4.desktop.data.TmdbTitle
import com.lagradost.cloudstream4.desktop.data.WatchList
import com.lagradost.cloudstream4.desktop.data.WatchProgress
import com.lagradost.cloudstream4.desktop.data.toMediaEntry
import com.lagradost.cloudstream4.desktop.data.tr
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.add
import com.lagradost.cloudstream4.desktop.generated.resources.bookmark
import com.lagradost.cloudstream4.desktop.generated.resources.check
import com.lagradost.cloudstream4.desktop.generated.resources.close
import com.lagradost.cloudstream4.desktop.generated.resources.open_in_new
import com.lagradost.cloudstream4.desktop.generated.resources.play
import com.lagradost.cloudstream4.desktop.generated.resources.star_border
import com.lagradost.cloudstream4.desktop.plugins.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.util.concurrent.ConcurrentHashMap

/** One title of the Home spotlight: something being watched, with new episodes, recommended or from the Home source */
data class SpotlightItem(
    val key: String,
    val name: String,
    /** Why it is shown, the small line above the name */
    val reason: String?,
    /** The longer explanation of the Why tab */
    val why: String?,
    val movie: Boolean?,
    val year: Int? = null,
    /** The extension's poster, until TMDB's backdrop is found */
    val imageUrl: String? = null,
    val imageHeaders: Map<String, String>? = null,
    val progress: WatchProgress? = null,
    val media: MediaEntry? = null,
    val recommendation: Recommendation? = null,
)

internal fun TvType?.isMovie(): Boolean? = when (this) {
    null, TvType.Others -> null
    TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> true
    else -> false
}

fun spotlightFromProgress(progress: WatchProgress, newEpisodes: Int): SpotlightItem = SpotlightItem(
    key = "watch:" + progress.media.url,
    name = progress.media.name,
    reason = when {
        newEpisodes == 1 -> tr("1 new episode")
        newEpisodes > 1 -> tr("{0} new episodes", newEpisodes)
        else -> tr("Continue watching")
    },
    why = when {
        newEpisodes > 0 -> tr("New episodes came out since you last watched, on {0}.", progress.media.apiName)
        progress.episodeName != null -> tr("You stopped at {0}, on {1}.", progress.episodeName, progress.media.apiName)
        else -> tr("You started it on {0}.", progress.media.apiName)
    },
    movie = progress.media.type.isMovie() ?: (progress.episodeName == null),
    imageUrl = progress.media.posterUrl,
    imageHeaders = progress.media.posterHeaders,
    progress = progress,
    media = progress.media,
)

fun spotlightFromRecommendation(item: Recommendation): SpotlightItem = SpotlightItem(
    key = "tmdb:" + item.title.key,
    name = item.title.name,
    reason = item.because.takeIf { it.isNotEmpty() }?.let { tr("Because you liked {0}", it) } ?: tr("Recommended for you"),
    why = item.because.takeIf { it.isNotEmpty() }?.let {
        tr("People who liked {0} often like this too. The better you rate what you watch, the better these get.", it)
    },
    movie = item.title.movie,
    year = item.title.year,
    imageUrl = item.title.posterUrl,
    recommendation = item,
)

fun spotlightFromSearch(item: SearchResponse, row: String): SpotlightItem = SpotlightItem(
    key = "home:" + item.url,
    name = item.name,
    reason = row,
    why = null,
    movie = item.type.isMovie(),
    year = item.year,
    imageUrl = item.posterUrl,
    imageHeaders = item.posterHeaders,
    media = item.toMediaEntry(),
)

private val SearchResponse.year: Int?
    get() = (this as? MovieSearchResponse)?.year ?: (this as? TvSeriesSearchResponse)?.year ?: (this as? AnimeSearchResponse)?.year

/** TMDB titles and details found for spotlight items, kept while the app runs */
private object SpotlightCache {
    val details = ConcurrentHashMap<String, TmdbDetails>()
    val missing = ConcurrentHashMap.newKeySet<String>()
    val limit = Semaphore(3)

    suspend fun load(item: SpotlightItem): TmdbDetails? {
        details[item.key]?.let { return it }
        if (item.key in missing) return null
        return limit.withPermit {
            runCatching {
                val title = item.recommendation?.title ?: Tmdb.find(item.name, item.year, item.movie)
                title?.let { Tmdb.details(it) }
            }.onFailure { logError(it) }.getOrNull()
        }.also { if (it != null) details[item.key] = it else missing += item.key }
    }
}

private fun normalized(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

/**
 * Opens [name] on the default source (the one chosen in Settings, or the Home source), straight on its page.
 * Searches every extension instead when that source does not have it.
 */
suspend fun openOnDefaultSource(name: String, year: Int?, navigator: Navigator) {
    val providers = PluginManager.providers()
    val settings = AppData.settings.value
    val api = providers.firstOrNull { it.name == (settings.recommendationProvider ?: settings.homeProvider) }
        ?: providers.firstOrNull { it.hasMainPage }
    val found = api?.let {
        runCatching {
            withContext(Dispatchers.IO) { withTimeout(it.searchTimeoutMs ?: 30_000L) { it.search(name, 1) } }?.items.orEmpty()
        }.onFailure { e -> logError(e) }.getOrNull()
    }.orEmpty()
    val wanted = normalized(name)
    fun yearFits(item: SearchResponse) = year == null || item.year == null || kotlin.math.abs(item.year!! - year) <= 1
    val match = found.firstOrNull { normalized(it.name) == wanted && yearFits(it) }
        ?: found.firstOrNull { normalized(it.name) == wanted }
        ?: found.firstOrNull { wanted.length > 3 && (normalized(it.name).startsWith(wanted) || wanted.startsWith(normalized(it.name))) && yearFits(it) }
    if (match != null) navigator.push(Route.Result(match.toMediaEntry())) else navigator.searchFor(name)
}

/** A dark color of the image, for the background around it */
internal fun ambientOf(image: coil3.Image): Color? = runCatching {
    val bitmap = image.toBitmap()
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    // The left part of the image, where it fades into the background
    for (yi in 0 until 12) for (xi in 0 until 10) {
        val c = bitmap.getColor(xi * bitmap.width / 20, yi * bitmap.height / 12)
        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
    }
    val hsb = java.awt.Color.RGBtoHSB((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), null)
    val saturation = if (hsb[1] < 0.08f) hsb[1] else (hsb[1] * 1.2f).coerceIn(0.3f, 0.6f)
    Color(java.awt.Color.HSBtoRGB(hsb[0], saturation, 0.2f))
}.getOrNull()

internal val DefaultAmbient = Color(0xFF15122B)

/**
 * The top of Home: one title over the whole width, with its artwork, why it is here, its episodes and cast,
 * and a strip of the other titles to switch between them.
 */
@Composable
fun Spotlight(items: List<SpotlightItem>, height: androidx.compose.ui.unit.Dp) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = items.firstOrNull { it.key == selectedKey } ?: items.first()
    val details = remember { mutableStateMapOf<String, TmdbDetails?>() }
    LaunchedEffect(items.map { it.key }) {
        items.forEach { item -> if (item.key !in details) launch { details[item.key] = SpotlightCache.load(item) } }
    }
    var ambient by remember { mutableStateOf(DefaultAmbient) }
    val background by animateColorAsState(ambient, tween(600))
    val info = details[selected.key]

    BoxWithConstraints(Modifier.fillMaxWidth().height(height).background(background)) {
        val wide = maxWidth > 900.dp
        AmbientArtwork(
            info?.title?.backdropUrl ?: selected.imageUrl,
            if (info?.title?.backdropUrl != null) null else selected.imageHeaders,
            background, wide,
        ) { ambient = it }

        Column(
            Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 88.dp, end = 24.dp).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpotlightDetails(selected, info)
        }

        // Fades into the page, so the rows below continue it
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
        )

        LazyRow(
            Modifier.align(Alignment.BottomEnd).fillMaxWidth(if (wide) 0.6f else 1f).padding(bottom = 24.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { _, item ->
                StripCard(item, details[item.key], item.key == selected.key) { selectedKey = item.key }
            }
        }
    }
}

/** Artwork filling the right side that fades into [background] on its left and bottom, [onAmbient] gets its dark color */
@Composable
internal fun BoxScope.AmbientArtwork(url: String?, headers: Map<String, String>?, background: Color, wide: Boolean, onAmbient: (Color) -> Unit) {
    Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(if (wide) 0.66f else 1f).fillMaxHeight()) {
        val context = LocalPlatformContext.current
        Crossfade(url to headers, animationSpec = tween(500)) { (imageUrl, imageHeaders) ->
            val request = remember(imageUrl, imageHeaders) {
                ImageRequest.Builder(context).data(imageUrl).crossfade(true).apply {
                    if (!imageHeaders.isNullOrEmpty()) httpHeaders(NetworkHeaders.Builder().apply { imageHeaders.forEach { (k, v) -> set(k, v) } }.build())
                }.build()
            }
            AsyncImage(
                request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onSuccess = { state -> ambientOf(state.result.image)?.let(onAmbient) },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background, (if (wide) 0.45f else 0.8f) to background.copy(alpha = if (wide) 0f else 0.55f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to background)))
        if (!wide) Box(Modifier.fillMaxSize().background(background.copy(alpha = 0.35f)))
    }
}

@Composable
private fun StripCard(item: SpotlightItem, info: TmdbDetails?, selected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Resting on a title shows it, passing over it on the way to another does not
    LaunchedEffect(hovered) {
        if (hovered && !selected) {
            delay(450)
            onSelect()
        }
    }
    val width = if (selected) 200.dp else 168.dp
    Box(
        Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .hoverable(interaction)
            .clickable(role = Role.Button, onClick = onSelect)
    ) {
        val thumb = info?.title?.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        RemoteImage(thumb ?: item.imageUrl, Modifier.fillMaxSize(), if (thumb != null) null else item.imageHeaders, contentDescription = item.name)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!selected && !hovered) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        Text(
            item.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private enum class SpotlightTab(val english: String) { Episodes("Episodes"), About("About"), Why("Why"), Cast("Cast") }

@Composable
private fun SpotlightDetails(item: SpotlightItem, info: TmdbDetails?) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val positions by AppData.positions.collectAsState()
    val ratings by AppData.ratings.collectAsState()
    val bookmarks by AppData.bookmarks.collectAsState()
    val progress = item.progress
    val season = progress?.season ?: 1
    val isShow = info?.title?.movie == false || item.movie == false

    item.reason?.let { Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge) }
    Text(
        item.name, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    val meta = listOfNotNull(
        (info?.title?.year ?: item.year)?.toString(),
        info?.genres?.take(2)?.joinToString(", ")?.ifEmpty { null },
        info?.runtime?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
        info?.rating?.let { "★ " + String.format("%.1f", it) },
    )
    if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)

    // Where the user is: one segment per episode of the season, or one bar for a movie
    val fraction = progress?.episodeData?.let { positions[it]?.fraction } ?: 0f
    val episodeCount = info?.seasons?.get(season)
    if (progress != null && progress.episode != null && episodeCount != null && episodeCount in 2..40) {
        SeasonLine(episodeCount, progress.episode, fraction)
    } else if (progress != null && fraction > 0f) {
        SeasonLine(1, 1, fraction)
    }

    // Actions
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        var busy by remember(item.key) { mutableStateOf(false) }
        val finished = progress?.episodeData?.let { positions[it]?.isFinished } == true
        val label = when {
            progress == null && item.media == null -> tr("Open")
            progress == null -> tr("Open")
            progress.episodeName == null -> tr("Resume")
            finished -> tr("Next episode")
            progress.season != null && progress.episode != null -> tr("Resume S{0}E{1}", progress.season, progress.episode)
            else -> tr("Resume")
        }
        PrimaryPill(label, if (progress != null) Res.drawable.play else Res.drawable.open_in_new, busy) {
            if (busy) return@PrimaryPill
            busy = true
            scope.launch {
                when {
                    progress != null -> navigator.push(continueRoute(progress) ?: Route.Result(progress.media))
                    item.media != null -> navigator.push(Route.Result(item.media))
                    else -> openOnDefaultSource(item.name, item.year, navigator)
                }
                busy = false
            }
        }
        val media = item.media
        if (media != null) {
            val listed = bookmarks.any { it.url == media.url }
            GhostPill(if (listed) tr("In my list") else tr("My list"), if (listed) Res.drawable.bookmark else Res.drawable.add) {
                if (listed) AppData.toggleBookmark(media) else AppData.setWatchList(media, WatchList.PlanToWatch)
            }
            var rating by remember(item.key) { mutableStateOf(false) }
            val stars = ratings[media.url]?.stars
            if (rating) {
                RatingBar(stars, { AppData.rate(media, it, info?.title?.year); rating = false }, starSize = 22.dp)
            } else {
                GhostPill(stars?.let { formatStars(it) } ?: tr("Rate"), Res.drawable.star_border) { rating = true }
            }
        }
        item.recommendation?.let { recommendation ->
            GhostPill(tr("Not interested"), Res.drawable.close) { Recommendations.dismiss(recommendation) }
        }
    }

    // Episodes, about, why and cast
    val tabs = listOfNotNull(
        SpotlightTab.Episodes.takeIf { isShow && info != null && info.seasons.isNotEmpty() },
        SpotlightTab.About.takeIf { info?.overview != null },
        SpotlightTab.Why.takeIf { item.why != null },
        SpotlightTab.Cast.takeIf { !info?.cast.isNullOrEmpty() },
    )
    if (tabs.isEmpty()) return
    // Chosen again once TMDB's details arrive, so a show opens on its episodes
    var tab by remember(item.key, info != null) { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 14.dp)) {
        tabs.forEach { t ->
            Column(Modifier.clip(RoundedCornerShape(4.dp)).clickable { tab = t }.padding(vertical = 4.dp)) {
                Text(tr(t.english), color = if (t == tab) Color.White else Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(2.dp).width(24.dp).background(if (t == tab) Color.White else Color.Transparent))
            }
        }
    }
    Box(Modifier.height(130.dp)) {
        val body = MaterialTheme.typography.bodyMedium
        val soft = Color.White.copy(alpha = 0.8f)
        when (tab) {
            SpotlightTab.Episodes -> info?.title?.let { title -> EpisodeList(item, title, season, if (info.seasons.containsKey(season)) season else info.seasons.keys.first()) }
            SpotlightTab.About -> Text(info?.overview ?: "", color = soft, style = body, maxLines = 6, overflow = TextOverflow.Ellipsis)
            SpotlightTab.Why -> Text(item.why ?: "", color = soft, style = body)
            SpotlightTab.Cast -> Text(info?.cast?.joinToString("\n") ?: "", color = soft, style = body, maxLines = 6)
        }
    }
}

/** Episodes around the one being watched, opening one goes to the title's page */
@Composable
private fun EpisodeList(item: SpotlightItem, title: TmdbTitle, watchingSeason: Int, season: Int) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val episodes by produceState<List<TmdbEpisode>?>(null, title.id, season) {
        value = runCatching { Tmdb.episodes(title, season) }.onFailure { logError(it) }.getOrNull()
    }
    val list = episodes ?: return
    val current = if (season == watchingSeason) item.progress?.episode else null
    val start = ((current ?: 1) - 2).coerceIn(0, (list.size - 4).coerceAtLeast(0))
    Column {
        if (season != watchingSeason || current == null) {
            Text(tr("Season {0}", season), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium)
        }
        list.drop(start).take(5).forEach { episode ->
            val watched = current != null && episode.number < current
            val isCurrent = episode.number == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                    val media = item.media
                    if (media != null) navigator.push(Route.Result(media)) else scope.launch { openOnDefaultSource(item.name, item.year, navigator) }
                }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        watched -> Icon(painterResource(Res.drawable.check), null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        isCurrent -> Icon(painterResource(Res.drawable.play), null, tint = Color.White, modifier = Modifier.size(16.dp))
                        else -> Box(Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.5f)))
                    }
                }
                Text(
                    "E${episode.number} · ${episode.name}",
                    color = if (isCurrent) Color.White else Color.White.copy(alpha = if (watched) 0.55f else 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One segment per episode, the watched ones filled, the current one filled as far as it was watched */
@Composable
internal fun SeasonLine(count: Int, current: Int, fraction: Float) {
    Row(Modifier.width(if (count == 1) 260.dp else minOf(420, count * 28).dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..count) {
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                val filled = when {
                    i < current -> 1f
                    i == current -> fraction
                    else -> 0f
                }
                if (filled > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(Color.White))
            }
        }
    }
}

@Composable
internal fun PrimaryPill(text: String, icon: DrawableResource, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
        else Icon(painterResource(icon), null, tint = Color.Black, modifier = Modifier.size(20.dp))
        Text(text, color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun GhostPill(text: String, icon: DrawableResource, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
