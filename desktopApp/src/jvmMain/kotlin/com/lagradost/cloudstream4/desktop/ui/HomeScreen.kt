package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mvvm.logError
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.MediaEntry
import com.lagradost.cloudstream4.desktop.data.toMediaEntry
import kotlinx.coroutines.withContext
import java.io.File
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.arrow_down
import com.lagradost.cloudstream4.desktop.generated.resources.refresh
import com.lagradost.cloudstream4.desktop.plugins.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.painterResource

/** A home row saved to disk, shown while the provider loads the current one */
data class CachedRow(val name: String, val items: List<MediaEntry>, val horizontal: Boolean = false)

data class HomeState(
    val provider: String? = null,
    val rows: List<HomePageList> = emptyList(),
    /** The rows of the last load, until the new ones are in */
    val cached: List<CachedRow> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var job: Job? = null

    fun currentApi(): MainAPI? {
        val providers = PluginManager.providers().filter { it.hasMainPage }
        val wanted = _state.value.provider ?: AppData.settings.value.homeProvider
        return providers.firstOrNull { it.name == wanted } ?: providers.firstOrNull()
    }

    fun select(name: String) {
        AppData.updateSettings { it.copy(homeProvider = name) }
        _state.value = HomeState(provider = name)
        load()
    }

    /** Reloads if the provider disappeared or nothing was loaded yet */
    fun ensureLoaded() {
        val api = currentApi()
        val current = _state.value
        if (api == null) {
            job?.cancel()
            _state.value = HomeState()
        } else if (current.provider != api.name || (current.rows.isEmpty() && !current.isLoading && current.error == null)) {
            _state.value = HomeState(provider = api.name)
            load()
        }
    }

    private fun cacheFile(provider: String) =
        File(AppDirs.cacheDir, "home/${provider.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun readCache(provider: String): List<CachedRow> = runCatching {
        cacheFile(provider).takeIf { it.exists() }?.let { mapper.readValue<List<CachedRow>>(it) }
    }.onFailure { logError(it) }.getOrNull().orEmpty()

    private fun writeCache(provider: String, rows: List<HomePageList>) = runCatching {
        val file = cacheFile(provider)
        file.parentFile.mkdirs()
        mapper.writeValue(file, rows.map { row -> CachedRow(row.name, row.list.map { it.toMediaEntry() }, row.isHorizontalImages) })
    }.onFailure { logError(it) }

    fun load() {
        val api = currentApi() ?: return
        job?.cancel()
        _state.value = HomeState(provider = api.name, isLoading = true)
        job = scope.launch {
            val cached = withContext(Dispatchers.IO) { readCache(api.name) }
            _state.update { if (it.rows.isEmpty()) it.copy(cached = cached) else it }
            val lock = Mutex()
            val errors = mutableListOf<Throwable>()
            suspend fun fetch(data: MainPageData) {
                try {
                    val response = withTimeout(api.getMainPageTimeoutMs ?: 60_000L) {
                        api.getMainPage(1, MainPageRequest(data.name, data.data, data.horizontalImages))
                    }
                    val rows = response?.items.orEmpty().filter { it.list.isNotEmpty() }
                    lock.withLock { _state.update { it.copy(rows = it.rows + rows) } }
                } catch (t: Throwable) {
                    if (t is CancellationException && t !is TimeoutCancellationException) throw t
                    logError(t)
                    lock.withLock { errors += t }
                }
            }

            if (api.sequentialMainPage) {
                api.mainPage.forEach {
                    fetch(it)
                    delay(api.sequentialMainPageDelay)
                }
            } else {
                api.mainPage.map { launch(Dispatchers.IO) { fetch(it) } }.forEach { it.join() }
            }

            if (_state.value.rows.isNotEmpty()) withContext(Dispatchers.IO) { writeCache(api.name, _state.value.rows) }
            _state.update {
                it.copy(
                    isLoading = false,
                    cached = if (it.rows.isEmpty()) it.cached else emptyList(),
                    error = if (it.rows.isEmpty() && it.cached.isEmpty()) errors.firstOrNull()?.let { e -> e.message ?: e.javaClass.simpleName }
                        ?: tr("Nothing found") else null
                )
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val navigator = LocalNavigator.current
    val providersVersion by PluginManager.providersVersion.collectAsState()
    val state by viewModel.state.collectAsState()
    val history by AppData.history.collectAsState()
    val positions by AppData.positions.collectAsState()
    val bookmarks by AppData.bookmarks.collectAsState()
    val episodeChecks by AppData.episodeChecks.collectAsState()
    val newEpisodesOn = AppData.settings.collectAsState().value.newEpisodeChecks
    val recommendationsOn = AppData.settings.collectAsState().value.recommendations
    val recommendations by Recommendations.items.collectAsState()
    val dismissed by AppData.dismissedRecommendations.collectAsState()

    LaunchedEffect(providersVersion) { viewModel.ensureLoaded() }
    val providers = remember(providersVersion) { PluginManager.providers().filter { it.hasMainPage } }

    val withNew = if (newEpisodesOn) history.filter { (episodeChecks[it.media.url]?.newCount ?: 0) > 0 } else emptyList()
    // Finished movies are done, finished episodes still lead to the next one. Shows with new episodes are listed above
    val watching = history.filter {
        (it.episodeName != null || it.episodeData?.let { data -> positions[data]?.isFinished } != true) && it !in withNew
    }
    val suggested = if (recommendationsOn) recommendations.filter { it.title.key !in dismissed } else emptyList()

    // The spotlight: new episodes first, then what is being watched, then recommendations, or the Home source on a fresh install
    val spotlight = remember(withNew, watching, suggested, state.rows.isEmpty(), episodeChecks) {
        val items = withNew.take(3).map { spotlightFromProgress(it, episodeChecks[it.media.url]?.newCount ?: 0) } +
            watching.take(4).map { spotlightFromProgress(it, 0) } +
            suggested.take(6).map { spotlightFromRecommendation(it) }
        items.ifEmpty {
            state.rows.firstOrNull()?.let { row -> row.list.take(8).map { spotlightFromSearch(it, row.name) } }.orEmpty()
        }.distinctBy { it.key }.take(12)
    }

    if (providers.isEmpty() && spotlight.isEmpty()) {
        val anyProviders = remember(providersVersion) { PluginManager.providers().isNotEmpty() }
        MessageBox(
            tr("No extensions with a home page installed"),
            if (anyProviders) tr("Your installed extensions only support search, use the Search tab or install more extensions.")
            else tr("Add a repository and install an extension (a site provider, not a repository helper) to start browsing."),
            action = tr("Open extensions") to { navigator.select(Tab.Extensions) },
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val spotlightHeight = (maxHeight * 0.86f).coerceIn(480.dp, 900.dp)
        val listState = rememberLazyListState()
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 } }
        LaunchedEffect(scrolled) { navigator.heroScrolled = scrolled }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (spotlight.isNotEmpty()) {
                item(key = "spotlight") { Spotlight(spotlight, spotlightHeight) }
            } else {
                // Leaves room for the navigation bar over the top of Home
                item { Spacer(Modifier.height(72.dp)) }
            }

            if (withNew.isNotEmpty()) {
                item {
                    SectionTitle(tr("New episodes"))
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(withNew, key = { it.media.url }) { progress ->
                            val count = episodeChecks[progress.media.url]?.newCount ?: 0
                            ContinueWatchingCard(
                                progress,
                                null,
                                subtitle = if (count == 1) tr("1 new episode") else tr("{0} new episodes", count),
                                extraMenu = { listOf(ContextMenuItem(tr("Dismiss")) { AppData.clearNewEpisodes(progress.media.url) }) },
                            )
                        }
                    }
                }
            }

            if (watching.isNotEmpty()) {
                item {
                    SectionTitle(tr("Continue watching"))
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(watching.take(20), key = { it.media.url }) { progress ->
                            ContinueWatchingCard(progress, progress.episodeData?.let { positions[it]?.fraction })
                        }
                    }
                }
            }

            if (suggested.isNotEmpty()) {
                item {
                    SectionTitle(tr("Recommended for you"))
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggested, key = { it.title.key }) { RecommendationCard(it) }
                    }
                }
            }

            if (bookmarks.isNotEmpty()) {
                item {
                    SectionTitle(tr("My list")) { TitleLink(tr("See all")) { navigator.select(Tab.Library) } }
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bookmarks.take(20), key = { it.url }) { MediaEntryCard(it) }
                    }
                }
            }

            if (providers.isNotEmpty()) {
                item { SourceHeader(state, providers, viewModel) }
            }

            // Grey rows while nothing of this source is in yet
            if (state.isLoading && state.rows.isEmpty() && state.cached.isEmpty()) {
                items(3) { PlaceholderRow(horizontal = it == 1) }
            }

            // The saved rows stand in until the provider has returned as many
            if (state.rows.size < state.cached.size) {
                items(state.cached, key = { "cached" + it.name + it.items.firstOrNull()?.url }) { row ->
                    SectionTitle(row.name)
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(row.items, key = { it.url + it.apiName }) {
                            MediaEntryCard(it, subtitle = it.type?.name ?: "", horizontal = row.horizontal)
                        }
                    }
                }
            } else {
                items(state.rows, key = { it.name + it.list.firstOrNull()?.url }) { row ->
                    SearchResponseRow(row.name, row.list, row.isHorizontalImages)
                }
            }

            if (state.error != null) {
                item {
                    MessageBox(
                        tr("Could not load {0}", state.provider),
                        state.error,
                        Modifier.fillMaxWidth(),
                        action = tr("Retry") to viewModel::load,
                    )
                }
            }
        }
    }
}

/** The rows' source: "From" and a pill choosing it, with a reload button */
@Composable
private fun SourceHeader(state: HomeState, providers: List<MainAPI>, viewModel: HomeViewModel) {
    val content = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 32.dp, top = 40.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("From"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        var expanded by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(content.copy(alpha = 0.08f)).clickable { expanded = true }
                    .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(state.provider ?: tr("Select provider"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(painterResource(Res.drawable.arrow_down), null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                providers.sortedBy { it.name.lowercase() }.forEach { api ->
                    DropdownMenuItem(
                        text = { Text("${api.name}  ·  ${api.lang}") },
                        onClick = {
                            expanded = false
                            viewModel.select(api.name)
                        },
                    )
                }
            }
        }
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content.copy(alpha = 0.6f))
        } else {
            Icon(
                painterResource(Res.drawable.refresh), tr("Reload"),
                Modifier.size(34.dp).clip(RoundedCornerShape(50)).clickable(onClick = viewModel::load).padding(7.dp),
                tint = content.copy(alpha = 0.7f),
            )
        }
    }
}

/** A TMDB title, opening it goes to its page on the default source, or searches for it */
@Composable
private fun RecommendationCard(item: Recommendation) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    fun open() {
        if (opening) return
        opening = true
        scope.launch {
            openOnDefaultSource(item.title.name, item.title.year, navigator)
            opening = false
        }
    }
    PosterCard(
        title = item.title.name,
        posterUrl = item.title.posterUrl,
        headers = null,
        subtitle = item.because.takeIf { it.isNotEmpty() }?.let { tr("Because of {0}", it) } ?: item.title.year?.toString(),
        isPreparing = opening,
        menu = {
            listOf(
                ContextMenuItem(tr("Open")) { open() },
                ContextMenuItem(tr("Search all extensions")) { navigator.searchFor(item.title.name) },
                ContextMenuItem(tr("Not interested")) { Recommendations.dismiss(item) },
            )
        },
        onClick = ::open,
    )
}
