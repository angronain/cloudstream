package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.Tmdb
import com.lagradost.cloudstream4.desktop.data.TmdbCredit
import com.lagradost.cloudstream4.desktop.data.TmdbPersonDetails
import com.lagradost.cloudstream4.desktop.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class CreditFilter(private val english: String) {
    All("All"),
    Movies("Movies"),
    Series("TV Series");

    val title: String get() = tr(english)

    fun matches(credit: TmdbCredit) = when (this) {
        All -> true
        Movies -> credit.title.movie
        Series -> !credit.title.movie
    }
}

/** An actor's photo, life and the titles they played in, each opening on the default source like a recommendation */
@Composable
fun PersonScreen(route: Route.Person) {
    var attempt by remember { mutableIntStateOf(0) }
    // Null while loading, a failure when TMDB could not be reached or does not know them
    val loaded by produceState<Result<TmdbPersonDetails>?>(null, route, attempt) {
        value = null
        value = runCatching {
            withContext(Dispatchers.IO) {
                // A cast list of an extension only has the name
                val id = route.id ?: Tmdb.searchPeople(route.name).let { people ->
                    people.firstOrNull { it.name.equals(route.name, ignoreCase = true) } ?: people.firstOrNull()
                }?.id ?: error(tr("{0} was not found on TMDB", route.name))
                Tmdb.person(id)
            }
        }.onFailure { logError(it) }
    }

    val result = loaded
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result.isFailure -> MessageBox(
            route.name,
            result.exceptionOrNull()?.message ?: tr("Could not load this page"),
            action = tr("Retry") to { attempt++ },
        )
        else -> PersonPage(result.getOrThrow())
    }
}

@Composable
private fun PersonPage(details: TmdbPersonDetails) {
    var filter by rememberSaveable { mutableStateOf(CreditFilter.All) }
    val shown = details.credits.filter(filter::matches)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { PersonHeader(details) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(start = 4.dp, top = 28.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A filter only shows when they played in both
                val kinds = CreditFilter.entries.filter { kind -> details.credits.any(kind::matches) }
                if (kinds.size > 2) kinds.forEach { kind ->
                    ChoicePill("${kind.title} (${details.credits.count(kind::matches)})", filter == kind) { filter = kind }
                } else {
                    Text(tr("Filmography"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(shown, key = { it.title.key }) { CreditCard(it) }
    }
}

@Composable
private fun PersonHeader(details: TmdbPersonDetails) {
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        RemoteImage(
            details.person.photoUrl,
            Modifier.size(width = 160.dp, height = 240.dp).clip(RoundedCornerShape(16.dp)),
            contentDescription = details.person.name,
        )
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(details.person.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            lifeLine(details)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            details.biography?.let { biography ->
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(4.dp))
                Text(
                    biography,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (biography.length > 500) {
                    Text(
                        if (expanded) tr("Less") else tr("More"),
                        Modifier.clip(RoundedCornerShape(50)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** "Born 12 March 1997 (28) · Spokane, Washington", or with the date they died */
private fun lifeLine(details: TmdbPersonDetails): String? {
    fun parse(date: String?) = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
    val born = parse(details.birthday)
    val died = parse(details.deathday)
    val life = born?.let {
        val age = Period.between(it, died ?: LocalDate.now()).years
        if (died != null) tr("{0} – {1} ({2})", it.format(format), died.format(format), age)
        else tr("Born {0} ({1})", it.format(format), age)
    }
    return listOfNotNull(life, details.birthplace?.takeIf { it.isNotBlank() }).joinToString("  ·  ").ifEmpty { null }
}

@Composable
private fun CreditCard(credit: TmdbCredit) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    fun open() {
        if (opening) return
        opening = true
        scope.launch {
            openOnDefaultSource(credit.title.name, credit.title.year, navigator)
            opening = false
        }
    }
    PosterCard(
        title = credit.title.name,
        posterUrl = credit.title.posterUrl,
        headers = null,
        subtitle = listOfNotNull(credit.title.year?.toString(), credit.character).joinToString(" · ").ifEmpty { null },
        isPreparing = opening,
        menu = {
            listOf(
                ContextMenuItem(tr("Open")) { open() },
                ContextMenuItem(tr("Search all extensions")) { navigator.searchFor(credit.title.name) },
            )
        },
        onClick = ::open,
    )
}
