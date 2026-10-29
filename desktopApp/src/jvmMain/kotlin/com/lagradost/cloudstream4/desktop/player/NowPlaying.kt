package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.OperatingSystem
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.lang.reflect.Method
import javax.swing.SwingUtilities

/** What the media keys, headphones and Control Center can do to the video playing */
interface NowPlayingControls {
    fun play()
    fun pause()
    fun togglePause()
    fun next()
    fun skip(ms: Long)
    fun seekTo(ms: Long)
}

/**
 * Shows the video playing in macOS's Now Playing (Control Center, the lock screen, AirPods) and takes
 * the media keys while it plays. Other systems do nothing for now.
 */
object NowPlaying {
    private val mac: MacNowPlaying? by lazy {
        if (AppDirs.os != OperatingSystem.MacOS) null
        else runCatching { MacNowPlaying() }.onFailure { logError(it) }.getOrNull()
    }

    @Volatile
    var controls: NowPlayingControls? = null

    fun update(title: String, subtitle: String?, positionMs: Long, durationMs: Long, playing: Boolean, hasNext: Boolean) {
        runCatching { mac?.update(title, subtitle, positionMs, durationMs, playing, hasNext) }.onFailure { logError(it) }
    }

    fun clear() {
        controls = null
        runCatching { mac?.clear() }.onFailure { logError(it) }
    }

    internal fun onCommand(block: NowPlayingControls.() -> Unit) {
        SwingUtilities.invokeLater { controls?.block() }
    }
}

/** The Objective-C calls, all going to objc_msgSend with fixed arguments as Apple Silicon needs */
@Suppress("FunctionName")
private interface ObjC : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer
    fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
    fun objc_registerClassPair(cls: Pointer)
    fun class_addMethod(cls: Pointer, sel: Pointer, imp: Callback, types: String): Boolean

    fun msg(receiver: Pointer?, sel: Pointer): Pointer?
    fun msgPtr(receiver: Pointer?, sel: Pointer, a: Pointer?): Pointer?
    fun msgPtrPtr(receiver: Pointer?, sel: Pointer, a: Pointer?, b: Pointer?): Pointer?
    fun msgPtrSel(receiver: Pointer?, sel: Pointer, target: Pointer?, action: Pointer): Pointer?
    fun msgString(receiver: Pointer?, sel: Pointer, value: String): Pointer?
    fun msgDouble(receiver: Pointer?, sel: Pointer, value: Double): Pointer?
    fun msgLong(receiver: Pointer?, sel: Pointer, value: Long): Pointer?
    fun msgBool(receiver: Pointer?, sel: Pointer, value: Boolean): Pointer?
    fun msgReturnDouble(receiver: Pointer?, sel: Pointer): Double

    companion object {
        val instance: ObjC = Native.load("objc", ObjC::class.java, mapOf(Library.OPTION_FUNCTION_MAPPER to com.sun.jna.FunctionMapper { _, method: Method ->
            if (method.name.startsWith("msg")) "objc_msgSend" else method.name
        }))
    }
}

/** An Objective-C method implementation receiving an MPRemoteCommandEvent, returning an MPRemoteCommandHandlerStatus */
private fun interface CommandImp : Callback {
    fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long
}

private class MacNowPlaying {
    private val objc = ObjC.instance
    private val mediaPlayer = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
    private val center: Pointer = objc.msg(cls("MPNowPlayingInfoCenter"), sel("defaultCenter"))!!
    private val commands: Pointer = objc.msg(cls("MPRemoteCommandCenter"), sel("sharedCommandCenter"))!!
    private val target: Pointer
    // Kept so the JVM does not free what Objective-C calls
    private val imps = mutableListOf<CommandImp>()
    private var nextEnabled: Boolean? = null

    init {
        val handlerClass = objc.objc_allocateClassPair(cls("NSObject")!!, "CloudStreamNowPlayingTarget", 0)!!
        val pendingTargets = mutableListOf<Pair<String, String>>()
        fun handle(command: String, selector: String, action: (Pointer?) -> Unit) {
            val imp = CommandImp { _, _, event ->
                runCatching { action(event) }.onFailure { logError(it) }
                0L // MPRemoteCommandHandlerStatusSuccess
            }
            imps += imp
            objc.class_addMethod(handlerClass, sel(selector), imp, "q@:@")
            pendingTargets += command to selector
        }
        handle("playCommand", "onPlay:") { NowPlaying.onCommand { play() } }
        handle("pauseCommand", "onPause:") { NowPlaying.onCommand { pause() } }
        handle("togglePlayPauseCommand", "onToggle:") { NowPlaying.onCommand { togglePause() } }
        handle("nextTrackCommand", "onNext:") { NowPlaying.onCommand { next() } }
        handle("skipForwardCommand", "onSkipForward:") { event ->
            val seconds = objc.msgReturnDouble(event, sel("interval")).takeIf { it > 0 } ?: 10.0
            NowPlaying.onCommand { skip((seconds * 1000).toLong()) }
        }
        handle("skipBackwardCommand", "onSkipBackward:") { event ->
            val seconds = objc.msgReturnDouble(event, sel("interval")).takeIf { it > 0 } ?: 10.0
            NowPlaying.onCommand { skip(-(seconds * 1000).toLong()) }
        }
        handle("changePlaybackPositionCommand", "onPosition:") { event ->
            val seconds = objc.msgReturnDouble(event, sel("positionTime"))
            NowPlaying.onCommand { seekTo((seconds * 1000).toLong()) }
        }
        objc.objc_registerClassPair(handlerClass)
        target = objc.msg(objc.msg(handlerClass, sel("alloc")), sel("init"))!!
        pendingTargets.forEach { (command, selector) ->
            objc.msgPtrSel(objc.msg(commands, sel(command)), sel("addTarget:action:"), target, sel(selector))
        }
        // The other commands would show buttons that do nothing
        listOf("previousTrackCommand", "seekForwardCommand", "seekBackwardCommand").forEach {
            objc.msgBool(objc.msg(commands, sel(it)), sel("setEnabled:"), false)
        }
    }

    private fun cls(name: String) = objc.objc_getClass(name)
    private fun sel(name: String) = objc.sel_registerName(name)
    private fun key(name: String): Pointer = mediaPlayer.getGlobalVariableAddress(name).getPointer(0)
    private fun string(value: String) = objc.msgString(cls("NSString"), sel("stringWithUTF8String:"), value)
    private fun number(value: Double) = objc.msgDouble(cls("NSNumber"), sel("numberWithDouble:"), value)

    private inline fun <T> pool(block: () -> T): T {
        val pool = objc.msg(objc.msg(cls("NSAutoreleasePool"), sel("alloc")), sel("init"))
        try {
            return block()
        } finally {
            objc.msg(pool, sel("drain"))
        }
    }

    @Synchronized
    fun update(title: String, subtitle: String?, positionMs: Long, durationMs: Long, playing: Boolean, hasNext: Boolean) = pool {
        if (nextEnabled != hasNext) {
            nextEnabled = hasNext
            objc.msgBool(objc.msg(commands, sel("nextTrackCommand")), sel("setEnabled:"), hasNext)
        }
        val info = objc.msg(cls("NSMutableDictionary"), sel("dictionary"))
        fun put(name: String, value: Pointer?) = objc.msgPtrPtr(info, sel("setObject:forKey:"), value, key(name))
        put("MPMediaItemPropertyTitle", string(subtitle ?: title))
        if (subtitle != null) put("MPMediaItemPropertyArtist", string(title))
        if (durationMs > 0) {
            put("MPMediaItemPropertyPlaybackDuration", number(durationMs / 1000.0))
            put("MPNowPlayingInfoPropertyElapsedPlaybackTime", number(positionMs / 1000.0))
        }
        put("MPNowPlayingInfoPropertyPlaybackRate", number(if (playing) 1.0 else 0.0))
        objc.msgPtr(center, sel("setNowPlayingInfo:"), info)
        // MPNowPlayingPlaybackStatePlaying = 1, Paused = 2
        objc.msgLong(center, sel("setPlaybackState:"), if (playing) 1L else 2L)
    }

    @Synchronized
    fun clear() = pool {
        objc.msgPtr(center, sel("setNowPlayingInfo:"), null)
        // MPNowPlayingPlaybackStateStopped = 3
        objc.msgLong(center, sel("setPlaybackState:"), 3L)
    }
}
