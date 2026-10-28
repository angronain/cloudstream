package com.lagradost.cloudstream4.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.AppLog
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

data class Track(val id: Int, val name: String)

object Vlc {
    /** Only finds the LibVLC libraries, without starting VLC itself, so screens can check it cheaply */
    val isInstalled: Boolean by lazy {
        runCatching {
            val discovery = NativeDiscovery()
            discovery.discover().also { found ->
                AppLog.log("VLC: ${if (found) "found at ${discovery.discoveredPath()}" else "not found"}")
            }
        }.onFailure { logError(it) }.getOrDefault(false)
    }

    /** Why the VLC that is installed cannot be used, null if it is not installed or works */
    val problem: String? by lazy {
        if (isInstalled) null else MacArch.vlcProblem()?.also { AppLog.log("VLC: $it") }
    }

    // Kept so it is not garbage collected while VLC still calls it
    private var nativeLog: NativeLog? = null

    /** Null if LibVLC (the VLC app) is not installed. Starting it loads every VLC plugin, so only the player uses it */
    private val factoryLazy = lazy {
        try {
            if (isInstalled) {
                AppLog.log("VLC: starting")
                val settings = AppData.settings.value
                val subtitleOptions = buildList {
                    add("--sub-text-scale=${settings.subtitleScale.coerceIn(10, 500)}")
                    if (settings.subtitleBackground) addAll(listOf("--freetype-background-opacity=160", "--freetype-background-color=0"))
                }
                MediaPlayerFactory("--no-video-title-show", "--network-caching=3000", "--no-metadata-network-access", *subtitleOptions.toTypedArray()).also { factory ->
                    AppLog.log("VLC: started, version ${factory.application().version()}")
                    // VLC's own warnings and errors end up in the log next to the app's
                    nativeLog = factory.application().newLog().apply {
                        level = LogLevel.WARNING
                        // Warnings are frequent, after the first few only errors are kept so long sessions stay readable
                        var warnings = 0
                        addLogListener { level, module, _, _, _, _, _, message ->
                            if (level == LogLevel.ERROR || warnings++ < MAX_VLC_WARNINGS) AppLog.log("VLC $level [$module] $message")
                        }
                    }
                }
            } else null
        } catch (t: Throwable) {
            AppLog.log("VLC: could not start")
            logError(t)
            null
        }
    }

    val factory: MediaPlayerFactory? by factoryLazy

    /** If [factory] was already created, reading it is then instant */
    val isStarted: Boolean get() = factoryLazy.isInitialized()

    val isAvailable: Boolean get() = factory != null

    private const val MAX_VLC_WARNINGS = 300
}

/**
 * Wraps a LibVLC media player that renders frames into a Compose [ImageBitmap], so the video
 * can be drawn with regular Compose UI layered on top, on every desktop platform.
 */
class VlcEngine(factory: MediaPlayerFactory) {
    init {
        AppLog.log("Player: opening")
    }

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(true)
        private set
    var timeMs by mutableStateOf(0L)
        private set
    var lengthMs by mutableStateOf(0L)
        private set
    var volume by mutableStateOf(AppData.settings.value.playerVolume)
        private set
    var rate by mutableStateOf(AppData.settings.value.playerRate)
        private set
    var audioTracks by mutableStateOf(emptyList<Track>())
        private set
    var audioTrack by mutableStateOf(-1)
        private set
    var subtitleTracks by mutableStateOf(emptyList<Track>())
        private set
    var subtitleTrack by mutableStateOf(-1)
        private set
    var hasError by mutableStateOf(false)
        private set
    /**
     * Width divided by height of the picture as it should be shown, null until VLC knows it.
     * Not the stored pixel size: anamorphic videos have non-square pixels and streams are often padded (1920x1088).
     */
    var videoAspect by mutableStateOf<Float?>(null)
        private set
    /** Positive plays the audio later, used to line up audio taken from another source */
    var audioDelayMs by mutableStateOf(0L)
        private set
    /** Positive shows subtitles later */
    var subtitleDelayMs by mutableStateOf(0L)
        private set
    var hasFinished by mutableStateOf(false)
        private set

    /** Only touched on the UI thread */
    private var bitmap = Bitmap()
    private var bitmapInfo: ImageInfo? = null

    /** Written by the VLC threads */
    @Volatile
    private var imageInfo: ImageInfo? = null
    @Volatile
    private var frameBytes = ByteArray(0)
    private val framePending = AtomicBoolean(false)
    @Volatile
    private var released = false

    /**
     * Off while nothing shows the video (window minimized or app hidden). Every frame is copied and
     * uploaded to the GPU, skipping that saves most of the CPU and battery the player uses, audio keeps playing.
     */
    @Volatile
    var renderFrames = true

    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    init {
        val formatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                // Never block on the UI thread here, release() on the UI thread waits for VLC threads
                val (width, height) = frameSize(sourceWidth, sourceHeight)
                AppLog.log("Player: video is ${sourceWidth}x$sourceHeight, drawn at ${width}x$height")
                // A first guess until the track info arrives
                if (sourceHeight > 0) ui { if (videoAspect == null) videoAspect = sourceWidth.toFloat() / sourceHeight }
                frameBytes = ByteArray(width * height * 4)
                imageInfo = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
                return RV32BufferFormat(width, height)
            }

            // Its display size ignores non-square pixels, the shape comes from the track info instead
            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }

        val renderCallback = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit

            override fun display(
                mediaPlayer: MediaPlayer,
                nativeBuffers: Array<out ByteBuffer>,
                bufferFormat: BufferFormat,
                displayWidth: Int,
                displayHeight: Int,
            ) {
                if (!renderFrames) return
                // Drop frames instead of queueing them if the UI thread falls behind
                if (!framePending.compareAndSet(false, true)) return
                val buffer = nativeBuffers[0]
                buffer.rewind()
                if (buffer.remaining() < frameBytes.size) {
                    framePending.set(false)
                    return
                }
                val bytes = frameBytes
                val info = imageInfo
                buffer.get(bytes)
                SwingUtilities.invokeLater {
                    try {
                        if (info != null && !released) {
                            if (bitmapInfo != info) {
                                bitmap.close()
                                bitmap = Bitmap().apply { allocPixels(info) }
                                bitmapInfo = info
                            }
                            bitmap.installPixels(bytes)
                            frame = bitmap.asComposeImageBitmap()
                        }
                    } finally {
                        framePending.set(false)
                    }
                }
            }
        }

        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(formatCallback, renderCallback, true))
        AppLog.log("Player: video output ready")
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                ui {
                    isPlaying = true
                    isBuffering = false
                }
                if (!aspectFromTrack) refreshAspect()
            }

            override fun paused(mediaPlayer: MediaPlayer) = ui { isPlaying = false }
            override fun stopped(mediaPlayer: MediaPlayer) = ui { isPlaying = false }
            override fun finished(mediaPlayer: MediaPlayer) = ui {
                isPlaying = false
                hasFinished = true
            }

            override fun error(mediaPlayer: MediaPlayer) {
                AppLog.log("Player: VLC could not play this link")
                ui { hasError = true }
            }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) = refreshAspect()
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { isBuffering = newCache < 100f }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = ui { timeMs = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ui { lengthMs = newLength }
            override fun elementaryStreamAdded(
                mediaPlayer: MediaPlayer,
                type: uk.co.caprica.vlcj.media.TrackType,
                id: Int,
            ) = refreshTracks()

            override fun elementaryStreamSelected(
                mediaPlayer: MediaPlayer,
                type: uk.co.caprica.vlcj.media.TrackType,
                id: Int,
            ) = refreshTracks()
        })
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    /**
     * Runs a LibVLC call on vlcj's own thread, in order. Calls like play() or setTime() can wait until a
     * stalled stream's network read gives up, which froze the whole UI when made on the UI thread.
     */
    private fun vlc(block: () -> Unit) {
        if (released) return
        player.submit {
            if (released) return@submit
            try {
                block()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    /**
     * Frames are never shown larger than the biggest screen, so bigger videos (4K on a laptop) are scaled
     * down by VLC while it converts them anyway. Fewer pixels to copy and upload for every frame.
     */
    private fun frameSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val screen = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val config = device.defaultConfiguration
                val bounds = config.bounds
                (bounds.width * config.defaultTransform.scaleX).toInt() to (bounds.height * config.defaultTransform.scaleY).toInt()
            }.maxByOrNull { (w, h) -> w.toLong() * h }?.let { (w, h) -> maxOf(w, h) to minOf(w, h) }
        }.getOrNull() ?: return sourceWidth to sourceHeight
        val scale = minOf(1.0, screen.first.toDouble() / sourceWidth, screen.second.toDouble() / sourceHeight)
        if (scale >= 1.0) return sourceWidth to sourceHeight
        // Even sizes keep VLC's chroma conversion happy
        fun scaled(value: Int) = ((value * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return scaled(sourceWidth) to scaled(sourceHeight)
    }

    @Volatile
    private var aspectFromTrack = false

    /** The real display shape: pixel size corrected by the sample aspect ratio of the video track */
    private fun refreshAspect() {
        player.submit {
            val track = player.media().info()?.videoTracks()?.firstOrNull { it.width() > 0 && it.height() > 0 } ?: return@submit
            val sar = if (track.sampleAspectRatio() > 0 && track.sampleAspectRatioBase() > 0) {
                track.sampleAspectRatio().toFloat() / track.sampleAspectRatioBase()
            } else 1f
            val aspect = track.width() * sar / track.height()
            aspectFromTrack = true
            ui { videoAspect = aspect }
        }
    }

    private fun refreshTracks() {
        // Track queries are not allowed from the native callback thread
        player.submit {
            val audio = player.audio().trackDescriptions().map { Track(it.id(), it.description()) }
            val selectedAudio = player.audio().track()
            val subs = player.subpictures().trackDescriptions().map { Track(it.id(), it.description()) }
            val selectedSub = player.subpictures().track()
            ui {
                audioTracks = audio
                audioTrack = selectedAudio
                subtitleTracks = subs
                subtitleTrack = selectedSub
            }
        }
    }

    fun play(url: String, startMs: Long = 0) {
        // Only the kind of link and its host, links often carry personal tokens
        AppLog.log("Player: playing ${runCatching { java.net.URI(url).let { "${it.scheme}://${it.host ?: ""}" } }.getOrDefault("?")} from ${startMs / 1000}s")
        hasError = false
        hasFinished = false
        isBuffering = true
        timeMs = startMs
        lengthMs = 0
        audioDelayMs = 0
        subtitleDelayMs = 0
        videoAspect = null
        aspectFromTrack = false
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        val options = buildList {
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }.toTypedArray()
        val volume = volume
        val rate = rate
        vlc {
            player.media().play(url, *options)
            player.audio().setVolume(volume)
            if (rate != 1f) player.controls().setRate(rate)
        }
    }

    fun togglePause() {
        if (hasFinished) {
            hasFinished = false
            vlc {
                player.controls().setTime(0)
                player.controls().play()
            }
        } else {
            vlc { player.controls().pause() }
        }
    }

    fun setPaused(paused: Boolean) = vlc { player.controls().setPause(paused) }

    fun seekTo(ms: Long) {
        val clamped = if (lengthMs > 0) ms.coerceIn(0, lengthMs) else ms.coerceAtLeast(0)
        timeMs = clamped
        vlc { player.controls().setTime(clamped) }
    }

    fun skip(ms: Long) = seekTo(timeMs + ms)

    fun changeVolume(value: Int) {
        volume = value.coerceIn(0, 200)
        val volume = volume
        vlc { player.audio().setVolume(volume) }
        // Muting is for now, the next launch starts with sound
        if (volume > 0) AppData.updateSettings { it.copy(playerVolume = volume) }
    }

    fun changeRate(value: Float) {
        rate = value
        vlc { player.controls().setRate(value) }
        AppData.updateSettings { it.copy(playerRate = value) }
    }

    fun selectAudio(id: Int) {
        vlc { player.audio().setTrack(id) }
        refreshTracks()
    }

    /**
     * Adds the audio of another stream or file to the current video, VLC reads it next to the video.
     * The other stream is downloaded in full, only its audio is used.
     */
    fun addAudioSource(url: String, select: Boolean) {
        vlc { player.media().addSlave(MediaSlaveType.AUDIO, url, select) }
        refreshTracks()
    }

    fun changeAudioDelay(ms: Long) {
        audioDelayMs = ms
        vlc { player.audio().setDelay(ms * 1000) }
    }

    fun changeSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms
        vlc { player.subpictures().setDelay(ms * 1000) }
    }

    fun selectSubtitle(id: Int) {
        vlc { player.subpictures().setTrack(id) }
        refreshTracks()
    }

    fun addSubtitleFile(file: File) {
        vlc { player.subpictures().setSubTitleFile(file) }
        refreshTracks()
    }

    fun stop() = vlc { player.controls().stop() }

    fun release() {
        if (released) return
        released = true
        frame = null
        // Releasing blocks until LibVLC is done, keep that off the UI thread
        Thread {
            player.controls().stop()
            player.release()
            SwingUtilities.invokeLater { bitmap.close() }
        }.start()
    }
}
