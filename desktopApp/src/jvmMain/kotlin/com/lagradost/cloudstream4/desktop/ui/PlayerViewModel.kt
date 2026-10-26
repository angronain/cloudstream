package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.downloads.DownloadManager
import com.lagradost.cloudstream4.desktop.downloads.DownloadStatus
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.sortUrls
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.SavedSource
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class PlayerState(
    val links: List<ExtractorLink> = emptyList(),
    val subtitles: List<SubtitleFile> = emptyList(),
    val isLoadingLinks: Boolean = true,
    val selectedLink: ExtractorLink? = null,
    val selectedSubtitle: SubtitleFile? = null,
    val error: String? = null,
)

/** Links that a desktop player can open */
private fun ExtractorLink.isPlayable(): Boolean =
    this !is DrmExtractorLink && type in setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)

fun ExtractorLink.label(): String {
    val quality = when {
        quality <= 0 -> null
        quality >= 2160 -> "4K"
        else -> "${quality}p"
    }
    return listOfNotNull(name, quality, type.name.takeIf { type != ExtractorLinkType.VIDEO }).distinct().joinToString(" · ")
}

/** The url the player should open, going through the header proxy when the extension needs headers */
fun ExtractorLink.playbackUrl(): String =
    if (headers.isEmpty() && referer.isBlank()) url else StreamProxy.proxy(url, headers, referer)

class PlayerViewModel(
    private val scope: CoroutineScope,
    private val apiName: String,
    private val data: String,
    /** The show or movie, its last used source is preferred for episodes without one of their own */
    private val mediaUrl: String,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Plays the downloaded copy if there is one, so offline viewing needs no extension or network */
    private suspend fun loadDownloaded(): Boolean {
        val item = DownloadManager.forEpisode(data)?.takeIf { it.status == DownloadStatus.Completed && it.file.exists() }
            ?: return false
        val link = newExtractorLink("Downloaded", "Downloaded", item.file.toURI().toString(), ExtractorLinkType.VIDEO)
        val subtitles = item.subtitlePaths.map { File(it) }.filter { it.exists() }.map { file ->
            // Files are named video.lang.ext
            newSubtitleFile(file.name.removeSuffix(".${file.extension}").substringAfterLast('.'), file.toURI().toString())
        }
        _state.value = PlayerState(links = listOf(link), subtitles = subtitles, isLoadingLinks = false, selectedLink = link)
        return true
    }

    /**
     * Source used last time for this episode, or else for the show's previous episode.
     * Preferred among the new links, so the same source keeps playing from one episode to the next.
     */
    private var remembered: SavedSource? = null

    /**
     * Play the remembered source as soon as the extension finds it, without waiting for every link.
     * Set when the episode's own remembered link failed, or when only the show's source is known.
     */
    private var waitingForRemembered = false

    fun loadLinks() {
        scope.launch {
            if (loadDownloaded()) return@launch
            val own = AppData.positions.value[data]?.source
            remembered = own ?: AppData.showSource(mediaUrl)
            waitingForRemembered = own == null && remembered != null
            // The episode's own link starts right away, the links are still loaded in case it no longer works
            val link = own?.let { runCatching { it.toLink() }.getOrNull() }
            loadOnlineLinks(PlayerState(selectedLink = link))
        }
    }

    /** Same source and quality, else the same source in another quality, else the best link */
    private fun preferredLink(links: List<ExtractorLink>): ExtractorLink? = remembered?.let { saved ->
        links.firstOrNull(saved::matches) ?: links.firstOrNull { it.source == saved.source && it.name == saved.name }
    } ?: links.firstOrNull()

    private fun loadOnlineLinks(initial: PlayerState = PlayerState()) {
        val api = APIHolder.getApiFromNameNull(apiName)
        if (api == null) {
            _state.value = PlayerState(isLoadingLinks = false, error = "The extension $apiName is not loaded")
            return
        }
        _state.value = initial
        scope.launch(Dispatchers.IO) {
            try {
                withTimeout(api.loadLinksTimeoutMs ?: 120_000L) {
                    api.loadLinks(
                        data,
                        false,
                        subtitleCallback = { subtitle ->
                            _state.update { s ->
                                if (s.subtitles.any { it.url == subtitle.url }) s else s.copy(subtitles = s.subtitles + subtitle)
                            }
                        },
                        callback = { link ->
                            if (link.isPlayable()) {
                                _state.update { s ->
                                    if (s.links.any { it.url == link.url }) s
                                    else {
                                        val found = waitingForRemembered && remembered?.matches(link) == true
                                        if (found) waitingForRemembered = false
                                        s.copy(
                                            links = sortUrls((s.links + link).toSet()),
                                            selectedLink = if (found) link else s.selectedLink,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException && t !is TimeoutCancellationException) throw t
                logError(t)
            }
            _state.update { s ->
                s.copy(
                    isLoadingLinks = false,
                    selectedLink = s.selectedLink ?: preferredLink(s.links),
                    error = if (s.links.isEmpty()) "No playable links found" else null,
                )
            }
        }
    }

    fun selectLink(link: ExtractorLink) {
        waitingForRemembered = false
        _state.update { it.copy(selectedLink = link, error = null) }
    }

    /** Called when the current link fails, moves on to the next one */
    fun nextLink(): Boolean {
        val s = _state.value
        if (s.selectedLink != null && s.selectedLink !in s.links) {
            // The remembered link failed, probably expired: use the same source from the new links
            val next = remembered?.let { saved -> s.links.firstOrNull(saved::matches) }
                ?: if (s.isLoadingLinks) null else s.links.firstOrNull()
            waitingForRemembered = next == null && s.isLoadingLinks
            _state.update {
                it.copy(
                    selectedLink = next,
                    error = if (next == null && !it.isLoadingLinks) "None of the links could be played" else null,
                )
            }
            return next != null || s.isLoadingLinks
        }
        val index = s.links.indexOf(s.selectedLink)
        val next = s.links.getOrNull(index + 1)
        _state.update {
            if (next != null) it.copy(selectedLink = next)
            else it.copy(error = if (it.isLoadingLinks) null else "None of the links could be played")
        }
        return next != null
    }

    fun clearSubtitle() = _state.update { it.copy(selectedSubtitle = null) }

    /** VLC handles local files far better than remote ones, so subtitles are downloaded first */
    suspend fun downloadSubtitle(subtitle: SubtitleFile): File? = withContext(Dispatchers.IO) {
        try {
            if (subtitle.url.startsWith("file:")) {
                return@withContext File(java.net.URI(subtitle.url)).also { _state.update { s -> s.copy(selectedSubtitle = subtitle) } }
            }
            val text = app.get(subtitle.url, headers = subtitle.headers.orEmpty()).text
            val extension = when {
                text.trimStart().startsWith("WEBVTT") -> "vtt"
                text.contains("[Script Info]") -> "ass"
                else -> subtitle.url.substringBefore('?').substringAfterLast('.', "srt").take(4).lowercase()
            }
            val dir = File(AppDirs.cacheDir, "subtitles").also { it.mkdirs() }
            File(dir, "${subtitle.url.hashCode()}.$extension").apply { writeText(text) }
                .also { _state.update { s -> s.copy(selectedSubtitle = subtitle) } }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }
}
