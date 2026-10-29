package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.I18n
import com.lagradost.cloudstream4.desktop.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A subtitle file on OpenSubtitles */
data class OnlineSubtitle(val fileId: Int, val release: String, val language: String, val downloads: Int, val hearingImpaired: Boolean)

/** OpenSubtitles.com, with the Android app's key. Downloads without an account are limited to a few a day */
object OpenSubtitles {
    private const val HOST = "https://api.opensubtitles.com/api/v1"
    private val headers = mapOf("Api-Key" to "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2", "User-Agent" to "Cloudstream3 v0.2")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class FileInfo(@JsonProperty("file_id") val fileId: Int, @JsonProperty("file_name") val fileName: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Attributes(
        val language: String? = null,
        val release: String? = null,
        @JsonProperty("download_count") val downloadCount: Int = 0,
        @JsonProperty("hearing_impaired") val hearingImpaired: Boolean = false,
        val files: List<FileInfo> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Item(val attributes: Attributes)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Results(val data: List<Item> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Download(val link: String? = null, val message: String? = null, val remaining: Int? = null)

    suspend fun search(query: String, language: String, season: Int?, episode: Int?): List<OnlineSubtitle> {
        // The api redirects unless the parameters are sorted and lowercase
        val params = sortedMapOf(
            "languages" to language.lowercase(),
            "query" to query.lowercase(),
        )
        if (episode != null) params["episode_number"] = "$episode"
        if (season != null) params["season_number"] = "$season"
        val text = app.get("$HOST/subtitles", headers = headers, params = params).text
        return mapper.readValue<Results>(text).data.mapNotNull { item ->
            val a = item.attributes
            val file = a.files.firstOrNull() ?: return@mapNotNull null
            OnlineSubtitle(file.fileId, a.release ?: file.fileName ?: "", a.language ?: language, a.downloadCount, a.hearingImpaired)
        }.sortedByDescending { it.downloads }
    }

    /** Downloads the file to the cache, an error says why it could not, usually the daily limit */
    suspend fun download(subtitle: OnlineSubtitle): File = withContext(Dispatchers.IO) {
        val file = File(File(AppDirs.cacheDir, "subtitles").also { it.mkdirs() }, "opensubtitles-${subtitle.fileId}.srt")
        if (file.exists()) return@withContext file
        val response = app.post(
            "$HOST/download",
            headers = headers + mapOf("Accept" to "application/json"),
            json = mapOf("file_id" to subtitle.fileId),
        )
        val download = runCatching { mapper.readValue<Download>(response.text) }.getOrNull()
        val link = download?.link ?: error(download?.message ?: tr("OpenSubtitles did not answer"))
        file.apply { writeText(app.get(link).text) }
    }
}

private val subtitleLanguages = listOf(
    "en" to "English", "fr" to "Français", "zh-cn" to "中文", "es" to "Español", "de" to "Deutsch",
    "it" to "Italiano", "pt-br" to "Português (BR)", "ja" to "日本語", "ko" to "한국어", "ar" to "العربية", "ru" to "Русский",
)

/** Search and pick a subtitle on OpenSubtitles, from the player's Subtitles menu */
@Composable
fun SubtitleSearchPanel(
    title: String,
    season: Int?,
    episode: Int?,
    onPicked: (File) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(title) }
    var language by remember { mutableStateOf(if (I18n.current == "zh") "zh-cn" else subtitleLanguages.firstOrNull { it.first == I18n.current }?.first ?: "en") }
    var results by remember { mutableStateOf<List<OnlineSubtitle>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        loading = true
        message = null
        scope.launch {
            results = try {
                OpenSubtitles.search(query, language, season, episode)
            } catch (t: Throwable) {
                logError(t)
                message = tr("Could not search: {0}", t.message)
                emptyList()
            }
            loading = false
        }
    }
    LaunchedEffect(language) { search() }

    Surface(modifier.width(560.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(tr("Search subtitles online"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(tr("Close")) }
            }
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                label = { Text(listOfNotNull(tr("Title"), season?.let { tr("S{0} E{1}", it, episode ?: "") }).joinToString(" · ")) },
                modifier = Modifier.fillMaxWidth().onEnter { search() },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subtitleLanguages.forEach { (code, name) ->
                    FilterChip(language == code, { language = code }, label = { Text(name) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val list = results
            if (list != null && list.isEmpty() && !loading && message == null) Text(tr("No results"))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(list.orEmpty(), key = { it.fileId }) { subtitle ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            message = null
                            loading = true
                            scope.launch {
                                try {
                                    onPicked(OpenSubtitles.download(subtitle))
                                    onClose()
                                } catch (t: Throwable) {
                                    logError(t)
                                    message = t.message ?: t.javaClass.simpleName
                                }
                                loading = false
                            }
                        }.padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        Text(subtitle.release, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                subtitle.language,
                                tr("{0} downloads", subtitle.downloads),
                                if (subtitle.hearingImpaired) tr("Hearing impaired") else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                tr("From OpenSubtitles.com, a few downloads a day without an account."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
