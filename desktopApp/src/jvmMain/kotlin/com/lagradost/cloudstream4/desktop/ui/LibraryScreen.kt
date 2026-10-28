package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.MediaEntry
import com.lagradost.cloudstream4.desktop.data.WatchList

private enum class LibraryTab(private val english: String) {
    Bookmarks("Bookmarks"), History("History");

    val title: String get() = tr(english)
}

private enum class BookmarkSort(private val english: String) {
    Added("Recently added"), Watched("Recently watched"), Name("A–Z"), Rating("Rating");

    val title: String get() = tr(english)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen() {
    val bookmarks by AppData.bookmarks.collectAsState()
    val history by AppData.history.collectAsState()
    val positions by AppData.positions.collectAsState()
    val watchLists by AppData.watchLists.collectAsState()
    val ratings by AppData.ratings.collectAsState()
    var tab by remember { mutableStateOf(LibraryTab.Bookmarks) }
    // Null shows every list
    var list by remember { mutableStateOf<WatchList?>(null) }
    var sort by remember { mutableStateOf(BookmarkSort.Added) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryTab.entries.forEach {
                FilterChip(tab == it, onClick = { tab = it }, label = { Text(it.title) })
            }
            if (tab == LibraryTab.History && history.isNotEmpty()) {
                TextButton(onClick = AppData::clearHistory) { Text(tr("Clear history")) }
            }
            if (tab == LibraryTab.Bookmarks) {
                Spacer(Modifier.weight(1f))
                Box {
                    var sortMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { sortMenu = true }) { Text(tr("Sort: {0}", sort.title)) }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        BookmarkSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.title) }, onClick = {
                                sortMenu = false
                                sort = option
                            })
                        }
                    }
                }
            }
        }

        val listFor = { entry: MediaEntry -> watchLists[entry.url] ?: WatchList.PlanToWatch }
        if (tab == LibraryTab.Bookmarks && bookmarks.isNotEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(list == null, onClick = { list = null }, label = { Text(tr("All ({0})", bookmarks.size)) })
                WatchList.entries.forEach { option ->
                    val count = bookmarks.count { listFor(it) == option }
                    FilterChip(list == option, onClick = { list = option }, label = { Text(tr("{0} ({1})", option.title, count)) })
                }
            }
        }
        val lastWatched = history.associate { it.media.url to it.updatedAt }
        val shownBookmarks = bookmarks.filter { list == null || listFor(it) == list }.let { entries ->
            when (sort) {
                BookmarkSort.Added -> entries
                BookmarkSort.Watched -> entries.sortedByDescending { lastWatched[it.url] ?: 0L }
                BookmarkSort.Name -> entries.sortedBy { it.name.lowercase() }
                BookmarkSort.Rating -> entries.sortedByDescending { ratings[it.url]?.stars ?: 0f }
            }
        }

        val empty = if (tab == LibraryTab.Bookmarks) shownBookmarks.isEmpty() else history.isEmpty()
        if (empty) {
            MessageBox(
                when {
                    tab == LibraryTab.History -> tr("Nothing watched yet")
                    list != null && bookmarks.isNotEmpty() -> tr("Nothing in {0}", list?.title)
                    else -> tr("No bookmarks yet")
                },
                if (tab == LibraryTab.Bookmarks) tr("Bookmark something from its page to find it here.") else null,
            )
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab) {
                LibraryTab.Bookmarks -> items(shownBookmarks, key = { it.url }) { MediaEntryCard(it, subtitle = listOfNotNull(listFor(it).title, ratings[it.url]?.stars?.let(::formatStars)).joinToString(" · ")) }
                LibraryTab.History -> items(history, key = { it.media.url }) { progress ->
                    ContinueWatchingCard(progress, progress.episodeData?.let { positions[it]?.fraction })
                }
            }
        }
    }
}
