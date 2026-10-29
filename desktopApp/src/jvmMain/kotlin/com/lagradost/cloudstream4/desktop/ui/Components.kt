package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.material3.Icon
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.MediaEntry
import com.lagradost.cloudstream4.desktop.data.WatchList
import com.lagradost.cloudstream4.desktop.data.WatchProgress
import com.lagradost.cloudstream4.desktop.data.toMediaEntry
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.play
import com.lagradost.cloudstream4.desktop.generated.resources.star
import com.lagradost.cloudstream4.desktop.generated.resources.star_border
import com.lagradost.cloudstream4.desktop.generated.resources.star_half
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val context = LocalPlatformContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .apply {
                if (!headers.isNullOrEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                }
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    horizontal: Boolean = false,
    subtitle: String? = null,
    progress: Float? = null,
    /** Shows a play button over the poster on hover */
    onPlay: (() -> Unit)? = null,
    /** Shows a spinner over the poster, while [onPlay] prepares playback */
    isPreparing: Boolean = false,
    /** Right-click menu, built when it opens so it shows the current state */
    menu: () -> List<ContextMenuItem> = { emptyList() },
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ContextMenuArea(items = menu) {
        Column(
            modifier = modifier
                .width(if (horizontal) width * 1.6f else width)
                .clip(RoundedCornerShape(10.dp))
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(4.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (horizontal) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                RemoteImage(posterUrl, Modifier.fillMaxSize(), headers, contentDescription = title)
                if (hovered) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)))
                }
                if (isPreparing) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else if (hovered && onPlay != null) {
                    Icon(
                        painterResource(Res.drawable.play),
                        tr("Play"),
                        Modifier.align(Alignment.Center).size(48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable(onClick = onPlay)
                            .padding(10.dp),
                        tint = Color.White,
                    )
                }
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                        drawStopIndicator = {},
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (hovered) FontWeight.Bold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Opening the page and bookmarking, the right-click menu of every card */
fun mediaMenu(entry: MediaEntry, navigator: Navigator): List<ContextMenuItem> {
    val current = AppData.watchList(entry.url)
    return listOf(ContextMenuItem(tr("Open page")) { navigator.push(Route.Result(entry)) }) +
        WatchList.entries.filter { it != current }.map { list ->
            ContextMenuItem(if (current == null) tr("Add to {0}", list.title) else tr("Move to {0}", list.title)) { AppData.setWatchList(entry, list) }
        } +
        listOfNotNull(current?.let { ContextMenuItem(tr("Remove bookmark")) { AppData.toggleBookmark(entry) } })
}

@Composable
fun SearchResponseCard(item: SearchResponse, horizontal: Boolean = false, width: Dp = 140.dp) {
    val navigator = LocalNavigator.current
    PosterCard(
        title = item.name,
        posterUrl = item.posterUrl,
        headers = item.posterHeaders,
        horizontal = horizontal,
        width = width,
        subtitle = listOfNotNull(item.type?.name, item.quality?.name).joinToString(" • ").ifBlank { null },
        menu = { mediaMenu(item.toMediaEntry(), navigator) },
        onClick = { navigator.push(Route.Result(item.toMediaEntry())) },
    )
}

@Composable
fun MediaEntryCard(entry: MediaEntry, subtitle: String? = null, progress: Float? = null, horizontal: Boolean = false) {
    val navigator = LocalNavigator.current
    PosterCard(
        title = entry.name,
        posterUrl = entry.posterUrl,
        headers = entry.posterHeaders,
        horizontal = horizontal,
        subtitle = subtitle ?: entry.apiName,
        progress = progress,
        menu = { mediaMenu(entry, navigator) },
        onClick = { navigator.push(Route.Result(entry)) },
    )
}

/** A show or movie being watched: plays from where it stopped without opening its page */
@Composable
fun ContinueWatchingCard(
    progress: WatchProgress,
    fraction: Float?,
    subtitle: String? = null,
    extraMenu: () -> List<ContextMenuItem> = { emptyList() },
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var isPreparing by remember { mutableStateOf(false) }
    fun play() {
        if (isPreparing) return
        isPreparing = true
        scope.launch {
            // Nothing left to play or the show failed to load, its page shows why
            val route = continueRoute(progress)
            isPreparing = false
            navigator.push(route ?: Route.Result(progress.media))
        }
    }
    PosterCard(
        title = progress.media.name,
        posterUrl = progress.media.posterUrl,
        headers = progress.media.posterHeaders,
        subtitle = subtitle ?: progress.episodeName ?: progress.media.apiName,
        progress = fraction,
        onPlay = ::play,
        isPreparing = isPreparing,
        menu = {
            listOf(ContextMenuItem(tr("Play"), ::play)) + mediaMenu(progress.media, navigator) + listOfNotNull(
                progress.episodeData?.let { data ->
                    ContextMenuItem(if (progress.episodeName != null) tr("Mark episode as watched") else tr("Mark as watched")) {
                        AppData.markWatched(data, progress.durationMs)
                    }
                },
                ContextMenuItem(tr("Remove from history")) { AppData.removeHistory(progress.media.url) },
            ) + extraMenu()
        },
        onClick = { navigator.push(Route.Result(progress.media)) },
    )
}

/** Five stars rated in half steps: the left half of a star gives half of it. Clicking the current rating clears it */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RatingBar(stars: Float?, onRate: (Float?) -> Unit, starSize: Dp = 28.dp) {
    var hover by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1) }
    fun valueAt(x: Float) = (kotlin.math.ceil(x / widthPx * 10) / 2).toFloat().coerceIn(0.5f, 5f)
    Row(
        Modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .onPointerEvent(PointerEventType.Move) { event -> hover = valueAt(event.changes.first().position.x) }
            .onPointerEvent(PointerEventType.Exit) { hover = null }
            .pointerInput(stars) { detectTapGestures { offset -> valueAt(offset.x).let { onRate(if (it == stars) null else it) } } }
    ) {
        val shown = hover ?: stars ?: 0f
        for (i in 1..5) {
            Icon(
                painterResource(
                    when {
                        shown >= i -> Res.drawable.star
                        shown >= i - 0.5f -> Res.drawable.star_half
                        else -> Res.drawable.star_border
                    }
                ),
                null,
                Modifier.size(starSize),
                tint = if (hover != null || stars != null) Color(0xFF00C030) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "3.5 ★" style text for a rating */
fun formatStars(stars: Float): String = (if (stars % 1f == 0f) stars.toInt().toString() else stars.toString()) + " ★"

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SearchResponseRow(title: String, items: List<SearchResponse>, horizontal: Boolean = false) {
    Column {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.url + it.apiName }) { SearchResponseCard(it, horizontal) }
        }
    }
}

@Composable
fun LoadingBox(text: String? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (text != null) {
                Spacer(Modifier.height(12.dp))
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MessageBox(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    action: Pair<String, () -> Unit>? = null,
) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                WhiteButton(action.first, onClick = action.second)
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))

/** Desktop text fields do not reliably fire IME actions, so handle Enter directly */
fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        action()
        true
    } else false
}

/** A rounded choice among others, filled when [selected] */
@Composable
fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val content = MaterialTheme.colorScheme.onBackground
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) content else content.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (selected) MaterialTheme.colorScheme.background else content.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}
