package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.OperatingSystem
import java.io.File
import java.io.RandomAccessFile

/**
 * The Intel build of the app runs on Apple Silicon through Rosetta, but it can only load Intel libraries, and VLC's
 * Apple Silicon download has none, so VLC is then installed yet cannot be used.
 */
object MacArch {
    private val appIsArm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }

    /** The Intel build of the app running on an Apple Silicon Mac, which reports itself even to code under Rosetta */
    val isTranslated: Boolean by lazy {
        AppDirs.os == OperatingSystem.MacOS && !appIsArm && runCatching {
            val process = ProcessBuilder("sysctl", "-n", "hw.optional.arm64").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText().trim() == "1"
        }.getOrDefault(false)
    }

    private val vlcLibraries = listOf("/Applications", "${System.getProperty("user.home")}/Applications")
        .map { File(it, "VLC.app/Contents/MacOS/lib/libvlc.dylib") }

    /** Why the installed VLC cannot be used, or null if it is missing or the reason is not this */
    fun vlcProblem(): String? {
        if (AppDirs.os != OperatingSystem.MacOS) return null
        val archs = vlcLibraries.firstOrNull { it.exists() }?.let(::archs) ?: return null
        val appArch = if (appIsArm) "arm64" else "x86_64"
        if (archs.isEmpty() || appArch in archs) return null
        return if (isTranslated) {
            "This is the Intel version of CloudStream, but this Mac and its VLC are Apple Silicon. " +
                "Install the Apple Silicon (macos-arm64) version of CloudStream to use the built-in player."
        } else {
            "VLC is installed, but it is not the version for this Mac (it is for ${archs.joinToString()}). " +
                "Install the VLC version for this Mac to use the built-in player."
        }
    }

    /** The architectures in a Mach-O file, read from its header */
    private fun archs(file: File): Set<String> = runCatching {
        RandomAccessFile(file, "r").use { input ->
            fun name(cpuType: Int) = when (cpuType) {
                0x01000007 -> "x86_64"
                0x0100000C -> "arm64"
                else -> "other"
            }
            when (val magic = input.readInt()) {
                // Universal: big endian count, then cpu type, subtype, offset, size, align for each
                0xCAFEBABE.toInt() -> (0 until input.readInt()).map { i ->
                    input.seek(8L + 20 * i)
                    name(input.readInt())
                }.toSet()
                // Single architecture, written little endian
                0xCFFAEDFE.toInt() -> setOf(name(Integer.reverseBytes(input.readInt())))
                else -> error("Not a Mach-O file: ${magic.toString(16)}")
            }
        }
    }.getOrDefault(emptySet())
}
