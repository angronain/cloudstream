package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.desktop.data.Tmdb
import com.lagradost.cloudstream4.desktop.data.TmdbPerson
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.search
import com.lagradost.cloudstream4.desktop.plugins.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.painterResource

data class ProviderResults(val provider: String, val items: List<SearchResponse>, val error: String? = null)

enum class SearchFilter(private val english: String, val types: Set<TvType>?) {
    All("All", null),
    Movies("Movies", setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent)),
    Series("TV Series", setOf(TvType.TvSeries, TvType.Cartoon, TvType.AsianDrama, TvType.Documentary)),
    Anime("Anime", setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)),
    Live("Live", setOf(TvType.Live));

    val title: String get() = tr(english)
}

data class SearchState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.All,
    val results: List<ProviderResults> = emptyList(),
    val pending: Int = 0,
    /** Actors named like the search, found on TMDB */
    val people: List<TmdbPerson> = emptyList(),
)

class SearchViewModel(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun onFilterChange(filter: SearchFilter) {
        _state.update { it.copy(filter = filter) }
        if (_state.value.results.isNotEmpty()) search()
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        val filter = _state.value.filter
        val providers = PluginManager.providers().filter { api ->
            filter.types == null || api.supportedTypes.any { it in filter.types }
        }
        job?.cancel()
        _state.update { it.copy(results = emptyList(), pending = providers.size, people = emptyList()) }
        job = scope.launch {
            if (filter in setOf(SearchFilter.All, SearchFilter.Movies, SearchFilter.Series)) launch(Dispatchers.IO) {
                val people = runCatching { Tmdb.searchPeople(query).take(MAX_PEOPLE) }.onFailure { logError(it) }.getOrDefault(emptyList())
                _state.update { it.copy(people = people) }
            }
            providers.forEach { api ->
                launch(Dispatchers.IO) {
                    val result = try {
                        val items = withTimeout(api.searchTimeoutMs ?: 30_000L) { api.search(query, 1) }?.items.orEmpty()
                        ProviderResults(api.name, items.filter { item ->
                            filter.types == null || item.type == null || item.type in filter.types
                        })
                    } catch (t: Throwable) {
                        if (t is CancellationException && t !is TimeoutCancellationException) throw t
                        logError(t)
                        ProviderResults(api.name, emptyList(), t.message ?: t.javaClass.simpleName)
                    }
                    _state.update {
                        it.copy(
                            // Providers with results first, keep the order they arrive in
                            results = (it.results + result).sortedBy { r -> r.items.isEmpty() },
                            pending = it.pending - 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val state by viewModel.state.collectAsState()
    val focus = remember { FocusRequester() }
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(navigator.pendingSearch) {
        val query = navigator.pendingSearch ?: return@LaunchedEffect
        navigator.pendingSearch = null
        viewModel.onQueryChange(query)
        viewModel.search()
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(tr("Search all extensions")) },
                leadingIcon = { Icon(painterResource(Res.drawable.search), null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.titleMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                    focusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                    unfocusedBorderColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().focusRequester(focus)
                    .onEnter { viewModel.search() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchFilter.entries.forEach { filter ->
                    ChoicePill(filter.title, state.filter == filter) { viewModel.onFilterChange(filter) }
                }
            }
        }
        if (state.pending > 0) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (PluginManager.providers().isEmpty()) {
            MessageBox(tr("No extensions installed"), tr("Install extensions from the Extensions tab to search."))
            return
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (state.people.isNotEmpty()) item(key = "people") { PeopleRow(state.people) }
            items(state.results, key = { it.provider }) { result ->
                when {
                    result.items.isNotEmpty() -> SearchResponseRow(result.provider, result.items)
                    else -> Text(
                        "${result.provider}: ${result.error ?: tr("No results")}",
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private const val MAX_PEOPLE = 12

/** Actors matching the search, each opening their page with everything they played in */
@Composable
private fun PeopleRow(people: List<TmdbPerson>) {
    val navigator = LocalNavigator.current
    Column {
        SectionTitle(tr("Actors"))
        LazyRow(contentPadding = PaddingValues(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(people, key = { it.id }) { person ->
                Column(
                    Modifier.width(128.dp).clip(RoundedCornerShape(16.dp))
                        .clickable { navigator.push(Route.Person(person.name, person.id, person.photoUrl)) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RemoteImage(person.photoUrl, Modifier.size(96.dp).clip(RoundedCornerShape(50)), contentDescription = person.name)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        person.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    person.knownFor.firstOrNull()?.let {
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
