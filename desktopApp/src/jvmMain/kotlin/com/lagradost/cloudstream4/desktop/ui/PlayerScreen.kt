package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlin.math.abs
import kotlinx.coroutines.Job
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.PlayerKind
import com.lagradost.cloudstream4.desktop.data.SavedSource
import com.lagradost.cloudstream4.desktop.data.WatchProgress
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.arrow_back
import com.lagradost.cloudstream4.desktop.generated.resources.audio
import com.lagradost.cloudstream4.desktop.generated.resources.forward
import com.lagradost.cloudstream4.desktop.generated.resources.fullscreen
import com.lagradost.cloudstream4.desktop.generated.resources.fullscreen_exit
import com.lagradost.cloudstream4.desktop.generated.resources.pause
import com.lagradost.cloudstream4.desktop.generated.resources.pip
import com.lagradost.cloudstream4.desktop.generated.resources.play
import com.lagradost.cloudstream4.desktop.generated.resources.replay
import com.lagradost.cloudstream4.desktop.generated.resources.skip_next
import com.lagradost.cloudstream4.desktop.generated.resources.sources
import com.lagradost.cloudstream4.desktop.generated.resources.speed
import com.lagradost.cloudstream4.desktop.generated.resources.subtitles
import com.lagradost.cloudstream4.desktop.generated.resources.volume_mute
import com.lagradost.cloudstream4.desktop.generated.resources.volume_up
import com.lagradost.cloudstream4.desktop.player.ActivePlayback
import com.lagradost.cloudstream4.desktop.player.AppVisibility
import com.lagradost.cloudstream4.desktop.player.ExternalPlayer
import com.lagradost.cloudstream4.desktop.player.SleepInhibitor
import com.lagradost.cloudstream4.desktop.player.Vlc
import com.lagradost.cloudstream4.desktop.player.VlcEngine
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

val LocalWindowState = compositionLocalOf<WindowState?> { null }
val LocalMainWindow = compositionLocalOf<ComposeWindow?> { null }

private val hiddenCursor by lazy {
    PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(
            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"
        )
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

@Composable
fun PlayerScreen(route: Route.Player, inPip: Boolean = false) {
    val navigator = LocalNavigator.current
    val settings by AppData.settings.collectAsState()
    val playable = route.playlist[route.index]
    val scope = rememberCoroutineScope()
    // The next episode's links, loaded before this one ends so it starts right away
    var prefetched by remember { mutableStateOf<PlayerViewModel?>(null) }
    val viewModel = remember(route) {
        prefetched?.takeIf { it.data == playable.data } ?: PlayerViewModel(scope, route.media.apiName, playable.data, route.media.url)
    }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.loadLinksOnce() }

    val hasNext = route.index + 1 < route.playlist.size
    val playNext = {
        val next = route.copy(index = route.index + 1)
        if (inPip) navigator.replacePip(next) else navigator.replace(next)
    }

    // Starting VLC takes a moment the first time, do it off the UI thread
    val vlcAvailable by produceState(if (Vlc.isStarted) Vlc.isAvailable else null) {
        if (value == null) value = withContext(Dispatchers.IO) { Vlc.isAvailable.also { Vlc.problem } }
    }
    if (settings.player == PlayerKind.Internal && vlcAvailable == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (settings.player == PlayerKind.External || vlcAvailable == false) {
        // Picture-in-picture needs the built-in player
        if (inPip) LaunchedEffect(Unit) { navigator.closePip() }
        else ExternalPlayerScreen(route, viewModel, state, hasNext, playNext)
        return
    }

    val engine = remember { VlcEngine(Vlc.factory!!) }
    val windowState = LocalWindowState.current
    DisposableEffect(engine) {
        onDispose {
            engine.release()
            if (windowState?.placement == WindowPlacement.Fullscreen) windowState.placement = WindowPlacement.Floating
        }
    }

    fun saveProgress() {
        if (engine.lengthMs <= 0) return
        AppData.saveProgress(
            WatchProgress(
                media = route.media,
                episodeName = if (route.playlist.size > 1 || playable.episode != null) playable.title else null,
                episodeData = playable.data,
                season = playable.season,
                episode = playable.episode,
                positionMs = engine.timeMs,
                durationMs = engine.lengthMs,
            ),
            // Downloads are found again without a source
            source = state.selectedLink?.takeUnless { it.url.startsWith("file:") }?.let(SavedSource::from),
        )
    }

    // Start (or switch) playback whenever the selected link changes, resuming where the user left off
    // The engine is kept between episodes, its current time only carries over when switching sources
    var engineEpisode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.selectedLink) {
        val link = state.selectedLink ?: return@LaunchedEffect
        val resume = AppData.positions.value[playable.data]?.takeIf { it.fraction < 0.95f }?.positionMs ?: 0L
        val sameEpisode = engineEpisode == playable.data
        engineEpisode = playable.data
        engine.play(link.playbackUrl(), if (sameEpisode && engine.timeMs > 0) engine.timeMs else resume)
        link.audioUrls().forEach { engine.addAudioSource(it, select = false) }
    }

    // Switches to the audio track the user picked before (for this show, else anywhere), once per link
    var audioAppliedFor by remember { mutableStateOf<ExtractorLink?>(null) }
    LaunchedEffect(engine.audioTracks, state.selectedLink) {
        val link = state.selectedLink ?: return@LaunchedEffect
        if (audioAppliedFor === link || engine.audioTracks.count { it.id >= 0 } < 2) return@LaunchedEffect
        audioAppliedFor = link
        val preferred = AppData.audioPreference(route.media.url) ?: return@LaunchedEffect
        pickAudioTrack(engine.audioTracks, preferred)?.takeIf { it.id != engine.audioTrack }?.let { engine.selectAudio(it.id) }
    }
    LaunchedEffect(engine.hasError) {
        if (engine.hasError) viewModel.nextLink()
    }
    // A stalled stream never reports an error, it just buffers forever: move on when the time stops advancing
    LaunchedEffect(state.selectedLink) {
        state.selectedLink ?: return@LaunchedEffect
        var lastTimeMs = -1L
        var stalledSeconds = 0
        var started = false
        while (true) {
            delay(1_000)
            // Paused by the user, not stuck
            if (!engine.isPlaying && !engine.isBuffering) {
                stalledSeconds = 0
                continue
            }
            if (engine.timeMs != lastTimeMs) {
                if (lastTimeMs >= 0) started = true
                lastTimeMs = engine.timeMs
                stalledSeconds = 0
            } else {
                stalledSeconds++
            }
            if (stalledSeconds >= if (started) STALL_MID_PLAYBACK_SECONDS else STALL_START_SECONDS) {
                viewModel.nextLink()
                break
            }
        }
    }
    val nearEnd = hasNext && engine.lengthMs > 0 && engine.lengthMs - engine.timeMs < PREFETCH_MS
    LaunchedEffect(nearEnd) {
        if (!nearEnd) return@LaunchedEffect
        val next = route.playlist[route.index + 1]
        if (prefetched?.data != next.data) {
            // Saved first, so the next episode prefers the source playing now
            saveProgress()
            prefetched = PlayerViewModel(scope, route.media.apiName, next.data, route.media.url).also { it.loadLinksOnce() }
        }
    }

    // Cancelling the next episode card keeps the player on this episode when it ends
    var nextCancelled by remember(route) { mutableStateOf(false) }
    LaunchedEffect(engine.hasFinished) {
        if (engine.hasFinished) {
            saveProgress()
            if (hasNext && !nextCancelled) playNext()
        }
    }
    // The effects below outlive episode changes, so they must save the episode playing now
    val currentSaveProgress by rememberUpdatedState { saveProgress() }
    LaunchedEffect(engine) {
        while (true) {
            delay(5_000)
            if (engine.isPlaying) currentSaveProgress()
        }
    }
    DisposableEffect(engine) {
        val save = { currentSaveProgress() }
        ActivePlayback.save = save
        onDispose {
            currentSaveProgress()
            if (ActivePlayback.save === save) ActivePlayback.save = null
        }
    }

    LaunchedEffect(engine.isPlaying) {
        // No screen dimming or sleep while the video plays, in the window or in picture-in-picture
        SleepInhibitor.setActive(engine.isPlaying)
        if (!engine.isPlaying) currentSaveProgress()
    }
    DisposableEffect(engine) { onDispose { SleepInhibitor.setActive(false) } }

    if (!inPip) {
        val visible = windowState?.isMinimized != true && !AppVisibility.isHidden
        SideEffect { engine.renderFrames = visible }
    }

    val mainWindow = LocalMainWindow.current
    if (inPip) {
        PipWindow(
            engine = engine,
            title = playable.title,
            seekMs = settings.seekSeconds * 1000L,
            onExpand = {
                navigator.expandPip()
                mainWindow?.let {
                    if (it.isMinimized) it.isMinimized = false
                    it.toFront()
                    it.requestFocus()
                }
            },
            onClose = navigator::closePip,
        )
        return
    }

    fun enterPip() {
        // A fullscreen main window would hide the floating one
        if (windowState?.placement == WindowPlacement.Fullscreen) windowState.placement = WindowPlacement.Floating
        navigator.enterPip(route)
    }

    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    // A menu is a separate popup, the mouse moving or scrolling in it does not count as interacting with the player
    val openMenus = remember { mutableIntStateOf(0) }
    val controlsVisible = !engine.isPlaying || openMenus.intValue > 0 || now - lastInteraction < 3_000
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val isFullscreen = windowState?.placement == WindowPlacement.Fullscreen
    fun toggleFullscreen() {
        windowState ?: return
        windowState.placement = if (isFullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }

    val seekMs = settings.seekSeconds * 1000L

    // Two-finger swipes: horizontal seeks, vertical changes the volume. One direction is kept per swipe.
    val swipe = remember { SwipeGesture() }
    var swipeLabel by remember { mutableStateOf<String?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var labelJob by remember { mutableStateOf<Job?>(null) }
    fun showSwipeLabel(text: String) {
        swipeLabel = text
        labelJob?.cancel()
        labelJob = scope.launch {
            delay(900)
            swipeLabel = null
        }
    }
    fun onSwipe(delta: Offset) {
        val now = System.currentTimeMillis()
        if (now - swipe.lastEventAt > 300) swipe.start(engine.timeMs, engine.volume)
        swipe.lastEventAt = now
        if (swipe.axis == SwipeAxis.None) {
            swipe.axis = if (abs(delta.x) > abs(delta.y)) SwipeAxis.Seek else SwipeAxis.Volume
        }
        when (swipe.axis) {
            SwipeAxis.Seek -> {
                if (engine.lengthMs <= 0) return
                swipe.amount += delta.x * SWIPE_SEEK_MS_PER_UNIT
                val target = (swipe.startTimeMs + swipe.amount.toLong()).coerceIn(0, engine.lengthMs)
                val change = (target - swipe.startTimeMs) / 1000
                showSwipeLabel("${if (change >= 0) "+" else "-"}${abs(change)}s  ${formatTime(target)}")
                // Seeking is slow on streams, only seek once the swipe settles
                seekJob?.cancel()
                seekJob = scope.launch {
                    delay(250)
                    engine.seekTo(target)
                }
            }

            SwipeAxis.Volume -> {
                swipe.amount += delta.y * SWIPE_VOLUME_PER_UNIT
                engine.changeVolume(swipe.startVolume + swipe.amount.toInt())
                showSwipeLabel("Volume ${engine.volume}%")
            }

            SwipeAxis.None -> Unit
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { lastInteraction = System.currentTimeMillis() }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                event.changes.firstOrNull()?.let { onSwipe(it.scrollDelta) }
            }
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else hiddenCursor)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                lastInteraction = System.currentTimeMillis()
                when (event.key) {
                    Key.Spacebar, Key.K -> engine.togglePause()
                    Key.DirectionLeft, Key.J -> engine.skip(-seekMs)
                    Key.DirectionRight, Key.L -> engine.skip(seekMs)
                    Key.DirectionUp -> engine.changeVolume(engine.volume + 5)
                    Key.DirectionDown -> engine.changeVolume(engine.volume - 5)
                    Key.M -> engine.changeVolume(if (engine.volume == 0) 100 else 0)
                    Key.F -> toggleFullscreen()
                    Key.N -> if (hasNext) playNext()
                    Key.P -> enterPip()
                    Key.Escape -> if (isFullscreen) toggleFullscreen() else navigator.back()
                    else -> return@onKeyEvent false
                }
                true
            }
    ) {
        engine.frame?.let { frame ->
            Box(
                Modifier.fillMaxSize().combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onDoubleClick = {
                        toggleFullscreen()
                        focus.requestFocus()
                    },
                ) {
                    engine.togglePause()
                    focus.requestFocus()
                },
                contentAlignment = Alignment.Center,
            ) {
                VideoFrame(frame, engine.videoAspect)
            }
        }

        swipeLabel?.let { label ->
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.selectedLink == null || engine.isBuffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.error == null) CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        state.error != null -> state.error!!
                        state.selectedLink == null -> "Loading links (${state.links.size} found)"
                        else -> "Buffering ${state.selectedLink?.label()}"
                    },
                    color = Color.White,
                )
                if (state.error != null) {
                    TextButton(onClick = viewModel::loadLinks) { Text("Retry") }
                }
            }
        }

        val remainingMs = engine.lengthMs - engine.timeMs
        if (hasNext && !nextCancelled && engine.lengthMs > NEXT_UP_MS * 4 && remainingMs in 1..NEXT_UP_MS) {
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 120.dp).widthIn(max = 320.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.8f),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Next episode in ${(remainingMs + 999) / 1000}s", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    Text(route.playlist[route.index + 1].title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveProgress(); playNext() }) { Text("Play now") }
                        TextButton(onClick = { nextCancelled = true; focus.requestFocus() }) { Text("Cancel", color = Color.White) }
                    }
                }
            }
        }

        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(BottomFadeReversed).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(painterResource(Res.drawable.arrow_back), "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(playable.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.selectedLink?.let {
                            Text(it.label(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BottomFade).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    val progress = if (engine.lengthMs > 0) engine.timeMs.toFloat() / engine.lengthMs else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(dragging?.let { (it * engine.lengthMs).toLong() } ?: engine.timeMs), color = Color.White)
                        Slider(
                            value = dragging ?: progress,
                            onValueChange = {
                                dragging = it
                                lastInteraction = System.currentTimeMillis()
                            },
                            onValueChangeFinished = {
                                dragging?.let { engine.seekTo((it * engine.lengthMs).toLong()) }
                                dragging = null
                                focus.requestFocus()
                            },
                            enabled = engine.lengthMs > 0,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(formatTime(engine.lengthMs), color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ControlButton(if (engine.isPlaying) Res.drawable.pause else Res.drawable.play, "Play/Pause") { engine.togglePause() }
                        ControlButton(Res.drawable.replay, "Back ${settings.seekSeconds}s") { engine.skip(-seekMs) }
                        ControlButton(Res.drawable.forward, "Forward ${settings.seekSeconds}s") { engine.skip(seekMs) }
                        if (hasNext) ControlButton(Res.drawable.skip_next, "Next episode") { playNext() }
                        ControlButton(if (engine.volume == 0) Res.drawable.volume_mute else Res.drawable.volume_up, "Mute") {
                            engine.changeVolume(if (engine.volume == 0) 100 else 0)
                        }
                        Slider(
                            value = engine.volume / 200f,
                            onValueChange = { engine.changeVolume((it * 200).toInt()) },
                            colors = SliderDefaults.colors(thumbColor = Color.White),
                            modifier = Modifier.width(120.dp),
                        )
                        Text("${engine.volume}%", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))

                        MenuButton(Res.drawable.sources, "Sources (${state.links.size})", openMenus) { close ->
                            if (state.isLoadingLinks) DropdownMenuItem(text = { Text("Still loading links...") }, onClick = {}, enabled = false)
                            state.links.forEach { link ->
                                DropdownMenuItem(
                                    text = { Text(link.label(), fontWeight = if (link == state.selectedLink) FontWeight.Bold else null) },
                                    onClick = {
                                        close()
                                        viewModel.selectLink(link)
                                    },
                                )
                            }
                        }
                        MenuButton(Res.drawable.subtitles, "Subtitles", openMenus) { close ->
                            DropdownMenuItem(text = { Text("Off") }, onClick = {
                                close()
                                engine.selectSubtitle(-1)
                                viewModel.clearSubtitle()
                            })
                            engine.subtitleTracks.filter { it.id >= 0 }.forEach { track ->
                                DropdownMenuItem(
                                    text = { Text(track.name, fontWeight = if (track.id == engine.subtitleTrack) FontWeight.Bold else null) },
                                    onClick = {
                                        close()
                                        engine.selectSubtitle(track.id)
                                    },
                                )
                            }
                            state.subtitles.forEach { subtitle ->
                                DropdownMenuItem(
                                    text = {
                                        Text(subtitle.lang, fontWeight = if (subtitle == state.selectedSubtitle) FontWeight.Bold else null)
                                    },
                                    onClick = {
                                        close()
                                        scope.launch { viewModel.downloadSubtitle(subtitle)?.let(engine::addSubtitleFile) }
                                    },
                                )
                            }
                            // Lines up subtitles made for another release, the menu stays open while adjusting
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Subtitle delay ${if (engine.subtitleDelayMs > 0) "+" else ""}${engine.subtitleDelayMs} ms") },
                                onClick = {},
                                enabled = false,
                            )
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                listOf(-500L, -100L, 100L, 500L).forEach { step ->
                                    TextButton(onClick = { engine.changeSubtitleDelay(engine.subtitleDelayMs + step) }) {
                                        Text(if (step > 0) "+$step" else "$step")
                                    }
                                }
                                TextButton(onClick = { engine.changeSubtitleDelay(0) }, enabled = engine.subtitleDelayMs != 0L) { Text("Reset") }
                            }
                        }
                        val otherSources = state.links.filter { it !== state.selectedLink && it.url != state.selectedLink?.url }
                        if (engine.audioTracks.size > 2 || otherSources.isNotEmpty() || engine.audioDelayMs != 0L) {
                            MenuButton(Res.drawable.audio, "Audio", openMenus) { close ->
                                engine.audioTracks.filter { it.id >= 0 }.forEach { track ->
                                    DropdownMenuItem(
                                        text = { Text(track.name, fontWeight = if (track.id == engine.audioTrack) FontWeight.Bold else null) },
                                        onClick = {
                                            close()
                                            engine.selectAudio(track.id)
                                            AppData.setAudioPreference(route.media.url, track.name)
                                        },
                                    )
                                }
                                // Lines up audio taken from another source, the menu stays open while adjusting
                                DropdownMenuItem(
                                    text = { Text("Audio delay ${if (engine.audioDelayMs > 0) "+" else ""}${engine.audioDelayMs} ms") },
                                    onClick = {},
                                    enabled = false,
                                )
                                Row(Modifier.padding(horizontal = 8.dp)) {
                                    listOf(-500L, -100L, 100L, 500L).forEach { step ->
                                        TextButton(onClick = { engine.changeAudioDelay(engine.audioDelayMs + step) }) {
                                            Text(if (step > 0) "+$step" else "$step")
                                        }
                                    }
                                    TextButton(onClick = { engine.changeAudioDelay(0) }, enabled = engine.audioDelayMs != 0L) { Text("Reset") }
                                }
                                if (otherSources.isNotEmpty()) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Audio from another source (also streams its video)") },
                                        onClick = {},
                                        enabled = false,
                                    )
                                    otherSources.forEach { link ->
                                        DropdownMenuItem(
                                            text = { Text(link.label()) },
                                            onClick = {
                                                close()
                                                engine.addAudioSource(link.playbackUrl(), select = true)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        MenuButton(Res.drawable.speed, "Speed ${engine.rate}x", openMenus) { close ->
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                                DropdownMenuItem(text = { Text("${rate}x") }, onClick = {
                                    close()
                                    engine.changeRate(rate)
                                })
                            }
                        }
                        ControlButton(Res.drawable.pip, "Picture-in-picture (P)") { enterPip() }
                        ControlButton(if (isFullscreen) Res.drawable.fullscreen_exit else Res.drawable.fullscreen, "Fullscreen") {
                            toggleFullscreen()
                        }
                    }
                }
            }
        }
    }
}

/** Draws [frame] at the video's display shape, as large as fits, which fixes non-square pixels and padded streams */
@Composable
fun VideoFrame(frame: ImageBitmap, aspect: Float?) {
    Image(
        frame,
        contentDescription = null,
        contentScale = if (aspect != null) ContentScale.FillBounds else ContentScale.Fit,
        modifier = if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
    )
}

/** Seconds without progress before giving up on a source that never starts */
private const val STALL_START_SECONDS = 20
/** Seconds without progress once playing, longer since streams can briefly rebuffer */
private const val STALL_MID_PLAYBACK_SECONDS = 30

private enum class SwipeAxis { None, Seek, Volume }

private class SwipeGesture {
    var axis = SwipeAxis.None
    var amount = 0f
    var startTimeMs = 0L
    var startVolume = 0
    var lastEventAt = 0L

    fun start(timeMs: Long, volume: Int) {
        axis = SwipeAxis.None
        amount = 0f
        startTimeMs = timeMs
        startVolume = volume
    }
}

/** A full trackpad swipe is around 20 to 40 scroll units */
private const val SWIPE_SEEK_MS_PER_UNIT = 1_000f
// Swiping up raises the volume
private const val SWIPE_VOLUME_PER_UNIT = 1f

private val BottomFadeReversed = Brush.verticalGradient(
    listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
)

@Composable
private fun ControlButton(icon: DrawableResource, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), description, tint = Color.White)
    }
}

@Composable
private fun MenuButton(
    icon: DrawableResource,
    description: String,
    openMenus: MutableIntState,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Keeps the controls, and so this menu, shown while it is open
    DisposableEffect(expanded) {
        if (expanded) openMenus.intValue++
        onDispose { if (expanded) openMenus.intValue-- }
    }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(description, color = Color.White)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

/** Used when VLC is not installed or the user prefers their own player */
@Composable
private fun ExternalPlayerScreen(
    route: Route.Player,
    viewModel: PlayerViewModel,
    state: PlayerState,
    hasNext: Boolean,
    playNext: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val playable = route.playlist[route.index]
    var launched by remember { mutableStateOf<String?>(null) }

    fun launch(link: ExtractorLink) {
        scope.launch {
            val subtitle: File? = state.subtitles.firstOrNull { it.langTag?.startsWith("en") == true }
                ?.let { viewModel.downloadSubtitle(it) }
            launched = if (ExternalPlayer.launch(link.playbackUrl(), playable.title, listOfNotNull(subtitle))) {
                "Playing ${link.label()} in ${ExternalPlayer.detect()?.let { File(it).nameWithoutExtension }}"
            } else {
                "No external player found, install VLC or mpv, or set one in Settings"
            }
        }
    }

    LaunchedEffect(state.isLoadingLinks) {
        if (!state.isLoadingLinks && launched == null) state.links.firstOrNull()?.let(::launch)
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigator.back() }) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
            Text(playable.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (hasNext) TextButton(onClick = playNext) { Text("Next episode") }
        }
        if (!Vlc.isAvailable && AppData.settings.value.player == PlayerKind.Internal) {
            Text(
                Vlc.problem ?: "The built-in player needs VLC installed. Install it from videolan.org to play inside CloudStream.",
                color = MaterialTheme.colorScheme.error,
            )
            if (Vlc.problem == null) TextButton(onClick = { openInBrowser("https://www.videolan.org/vlc/") }) { Text("Download VLC") }
        }
        launched?.let { Text(it) }
        if (state.isLoadingLinks) Text("Loading links (${state.links.size} found)...")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.links, key = { it.url }) { link ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clickable { launch(link) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(Res.drawable.play), null)
                    Spacer(Modifier.width(12.dp))
                    Text(link.label())
                }
            }
        }
    }
}

/** The next episode card shows this long before the end */
private const val NEXT_UP_MS = 30_000L

/** The next episode's links start loading this long before the end */
private const val PREFETCH_MS = 120_000L
