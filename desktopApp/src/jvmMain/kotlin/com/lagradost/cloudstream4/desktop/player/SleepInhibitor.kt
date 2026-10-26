package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.OperatingSystem
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import java.util.concurrent.Executors

/**
 * Keeps the display on and the computer awake while a video plays, so a movie is not interrupted
 * by the screen dimming or the laptop going to sleep. Released as soon as playback pauses or stops.
 */
object SleepInhibitor {
    private var process: Process? = null
    private var active = false

    // SetThreadExecutionState applies to the calling thread, so Windows needs the same thread every time
    private val windowsThread by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "SleepInhibitor").apply { isDaemon = true } }
    }

    @Synchronized
    fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        try {
            when (AppDirs.os) {
                OperatingSystem.Windows -> windowsThread.execute {
                    val flags = if (value) {
                        WinBase.ES_CONTINUOUS or WinBase.ES_SYSTEM_REQUIRED or WinBase.ES_DISPLAY_REQUIRED
                    } else {
                        WinBase.ES_CONTINUOUS
                    }
                    Kernel32.INSTANCE.SetThreadExecutionState(flags)
                }

                else -> if (value) {
                    val pid = ProcessHandle.current().pid().toString()
                    // Both tools exit by themselves if CloudStream dies
                    val command = if (AppDirs.os == OperatingSystem.MacOS) {
                        listOf("/usr/bin/caffeinate", "-d", "-i", "-w", pid)
                    } else {
                        val waitForApp = listOf("sh", "-c", "while kill -0 $pid 2>/dev/null; do sleep 5; done")
                        // GNOME only keeps the screen on for its own session inhibitor, other desktops follow logind
                        if (onPath("gnome-session-inhibit")) {
                            listOf("gnome-session-inhibit", "--inhibit", "idle:suspend", "--app-id", "CloudStream", "--reason", "Playing a video") + waitForApp
                        } else {
                            listOf("systemd-inhibit", "--what=idle:sleep", "--who=CloudStream", "--why=Playing a video") + waitForApp
                        }
                    }
                    process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                } else {
                    process?.destroy()
                    process = null
                }
            }
        } catch (t: Throwable) {
            // Missing tool (no systemd), playback works the same without it
            logError(t)
        }
    }

    private fun onPath(tool: String) = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
        .any { java.io.File(it, tool).canExecute() }
}
