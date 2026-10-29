package com.lagradost.cloudstream4.desktop.data

import com.lagradost.cloudstream3.mapper
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** What is playing, shown on the user's Discord profile */
data class Presence(
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val playing: Boolean,
    /** When playback would have started and will end at the current position, for Discord's time bar */
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    /** The same apart from a few seconds of drift in the time bar */
    fun sameAs(other: Presence?): Boolean =
        other != null && copy(startMs = null, endMs = null) == other.copy(startMs = null, endMs = null) &&
            kotlin.math.abs((startMs ?: 0) - (other.startMs ?: 0)) < 5_000
}

/**
 * Shows "Watching ..." on Discord through the Discord app's local connection (discord-ipc), no account access needed.
 * Discord shows the name of the Discord application whose id is in the settings.
 */
object DiscordPresence {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "discord-presence").apply { isDaemon = true } }
    private var connection: Pair<InputStream, OutputStream>? = null
    private var connectedId: String? = null
    private var lastFailure = 0L
    private var last: Presence? = null

    private val applicationId: String get() = AppData.settings.value.discordApplicationId.trim()

    fun update(presence: Presence?) {
        executor.execute {
            // The player reports every few seconds, Discord only needs to hear about changes
            if (presence == null && last == null || presence?.sameAs(last) == true) return@execute
            last = presence
            try {
                if (applicationId.isEmpty() || !AppData.settings.value.discordPresence) {
                    close()
                    return@execute
                }
                send(presence)
            } catch (t: Throwable) {
                close()
                lastFailure = System.currentTimeMillis()
            }
        }
    }

    fun clear() = update(null)

    private fun send(presence: Presence?) {
        val io = connect() ?: return
        val activity = presence?.let {
            buildMap {
                put("type", 3) // Watching
                put("details", it.title.take(128))
                it.subtitle?.takeIf { s -> s.isNotBlank() }?.let { s -> put("state", s.take(128)) }
                if (it.playing && it.startMs != null && it.endMs != null) put("timestamps", mapOf("start" to it.startMs, "end" to it.endMs))
                put(
                    "assets",
                    buildMap {
                        it.posterUrl?.takeIf { url -> url.startsWith("https://") }?.let { url -> put("large_image", url) }
                        put("large_text", it.title.take(128))
                        if (!it.playing) put("small_text", tr("Paused"))
                    },
                )
            }
        }
        val payload = mapOf(
            "cmd" to "SET_ACTIVITY",
            "args" to mapOf("pid" to ProcessHandle.current().pid(), "activity" to activity),
            "nonce" to UUID.randomUUID().toString(),
        )
        write(io.second, 1, mapper.writeValueAsBytes(payload))
        read(io.first)
    }

    private fun connect(): Pair<InputStream, OutputStream>? {
        if (connection != null && connectedId == applicationId) return connection
        close()
        // Discord not running, try again a little later rather than on every update
        if (System.currentTimeMillis() - lastFailure < 30_000) return null
        val io = open() ?: run {
            lastFailure = System.currentTimeMillis()
            return null
        }
        write(io.second, 0, mapper.writeValueAsBytes(mapOf("v" to 1, "client_id" to applicationId)))
        read(io.first)
        connection = io
        connectedId = applicationId
        return io
    }

    private fun open(): Pair<InputStream, OutputStream>? {
        for (i in 0..9) {
            try {
                if (AppDirs.os == OperatingSystem.Windows) {
                    val pipe = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
                    return Channels.newInputStream(pipe.channel) to Channels.newOutputStream(pipe.channel)
                }
                val dirs = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp")
                    .flatMap { listOf(it, "$it/app/com.discordapp.Discord", "$it/snap.discord") }
                val socket = dirs.map { File(it, "discord-ipc-$i") }.firstOrNull { it.exists() } ?: continue
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(socket.toPath()))
                return Channels.newInputStream(channel) to Channels.newOutputStream(channel)
            } catch (_: Throwable) {
                continue
            }
        }
        return null
    }

    private fun write(out: OutputStream, op: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(data.size).array()
        out.write(header + data)
        out.flush()
    }

    private fun read(input: InputStream): String {
        val header = ByteBuffer.wrap(input.readNBytes(8)).order(ByteOrder.LITTLE_ENDIAN)
        if (header.remaining() < 8) error("Discord closed the connection")
        val op = header.getInt()
        val length = header.getInt()
        val body = String(input.readNBytes(length))
        // 2 is Discord closing the connection, for example for a wrong application id
        if (op == 2) error(body)
        return body
    }

    private fun close() {
        connection?.let { (input, output) ->
            runCatching { input.close() }
            runCatching { output.close() }
        }
        connection = null
        connectedId = null
    }
}
