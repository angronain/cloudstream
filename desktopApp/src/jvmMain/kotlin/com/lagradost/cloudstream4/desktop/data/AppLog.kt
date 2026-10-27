package com.lagradost.cloudstream4.desktop.data

import com.lagradost.cloudstream4.desktop.update.Updater
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes everything the app prints (errors, extension output, VLC warnings) to a log file, so a problem on
 * someone else's computer can be shared from Settings. The last few sessions are kept: after a crash the app is
 * restarted, and the session that crashed must still be there. Java crash reports (hs_err files, written when
 * native code such as VLC takes the app down) are collected into the same folder on the next start.
 */
object AppLog {
    val dir: File get() = File(AppDirs.dataDir, "logs")
    private val current: File get() = File(dir, "cloudstream.log")
    private const val KEPT_SESSIONS = 3
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var stream: PrintStream? = null

    fun install() {
        try {
            dir.mkdirs()
            // cloudstream.log -> cloudstream.1.log -> cloudstream.2.log
            for (i in KEPT_SESSIONS - 1 downTo 1) {
                val from = if (i == 1) current else File(dir, "cloudstream.${i - 1}.log")
                if (from.exists()) from.renameTo(File(dir, "cloudstream.$i.log").also { it.delete() })
            }
            val file = PrintStream(FileOutputStream(current, true), true, Charsets.UTF_8)
            stream = file
            System.setOut(PrintStream(Tee(System.out, file), true, Charsets.UTF_8))
            System.setErr(PrintStream(Tee(System.err, file), true, Charsets.UTF_8))
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                log("Uncaught exception on thread ${thread.name}")
                throwable.printStackTrace()
            }
            log(
                "CloudStream ${Updater.currentVersion} on ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                    "(${System.getProperty("os.arch")}), Java ${System.getProperty("java.version")} ${System.getProperty("java.vm.name")}"
            )
            collectCrashReports()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun log(message: String) {
        val line = "${LocalDateTime.now().format(time)} $message"
        // Through System.out, so it also reaches the terminal when started from one
        System.out.println(line)
    }

    /** Java writes hs_err_pid*.log to the working directory, or the temp folder when that is not writable */
    private fun collectCrashReports() {
        val places = listOfNotNull(
            System.getProperty("user.dir"), System.getProperty("java.io.tmpdir"), "/tmp", System.getProperty("user.home"),
            System.getenv("TEMP"), System.getenv("TMP"),
        ).map(::File).distinct()
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        places.flatMap { it.listFiles { f -> f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }.orEmpty().toList() }
            .filter { it.lastModified() > weekAgo }
            .forEach { report ->
                runCatching {
                    // Only crash reports of CloudStream, other Java apps write the same files
                    if (report.useLines { lines -> lines.take(400).any { "cloudstream" in it.lowercase() } }) {
                        report.copyTo(File(dir, report.name), overwrite = true)
                        report.delete()
                        log("Found a crash report from a previous session: ${report.name}")
                    }
                }
            }
        // Keeps the newest few
        dir.listFiles { f -> f.name.startsWith("hs_err_pid") }.orEmpty().sortedByDescending { it.lastModified() }
            .drop(KEPT_SESSIONS).forEach { it.delete() }
    }

    /** Everything worth sending when reporting a problem: crash reports first, then sessions from newest */
    fun shareableText(): String = buildString {
        val crashes = dir.listFiles { f -> f.name.startsWith("hs_err_pid") }.orEmpty().sortedByDescending { it.lastModified() }
        val sessions = (listOf(current) + (1 until KEPT_SESSIONS).map { File(dir, "cloudstream.$it.log") }).filter { it.exists() }
        for (file in crashes.take(1) + sessions) {
            appendLine("===== ${file.name} =====")
            // The end of a long file is where the problem is
            val text = runCatching { file.readText() }.getOrDefault("")
            appendLine(if (text.length > MAX_FILE_CHARS) "[...]\n" + text.takeLast(MAX_FILE_CHARS) else text)
        }
    }

    private const val MAX_FILE_CHARS = 60_000

    private class Tee(private val first: OutputStream, private val second: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            first.write(b)
            second.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            first.write(b, off, len)
            second.write(b, off, len)
        }

        override fun flush() {
            first.flush()
            second.flush()
        }
    }
}
