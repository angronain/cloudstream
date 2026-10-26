package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.close
import com.lagradost.cloudstream4.desktop.generated.resources.forward
import com.lagradost.cloudstream4.desktop.generated.resources.pause
import com.lagradost.cloudstream4.desktop.generated.resources.pip
import com.lagradost.cloudstream4.desktop.generated.resources.play
import com.lagradost.cloudstream4.desktop.generated.resources.replay
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.OperatingSystem
import com.lagradost.cloudstream4.desktop.player.AppVisibility
import com.lagradost.cloudstream4.desktop.player.VlcEngine
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Small always-on-top window that keeps playing [engine] while the main window is used for browsing */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PipWindow(
    engine: VlcEngine,
    title: String,
    seekMs: Long,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    // The window is created here instead of by Compose, its type has to be set before it exists natively
    val initialAspect = remember { engine.videoAspect ?: DEFAULT_ASPECT }
    Window(
        create = {
            ComposeWindow().apply {
                // On macOS a popup window is a non-activating panel: clicking it does not bring the rest of CloudStream
                // to the front. Elsewhere it would lose its normal window behavior, so it stays a normal window there.
                if (AppDirs.os == OperatingSystem.MacOS) type = java.awt.Window.Type.POPUP
                this.title = title
                isAlwaysOnTop = true
                // No title bar, the window draws its own rounded corners and handles moving and resizing
                isUndecorated = true
                isTransparent = true
                // The corner grips resize it while keeping the video's shape
                isResizable = false
                // Never takes the keyboard: typing keeps going to the main window or whatever app is in front
                focusableWindowState = false
                // Opens in the bottom right corner of the screen, at the video's shape when it is already known
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                val height = (PIP_WIDTH / initialAspect).toInt()
                setBounds(screen.x + screen.width - PIP_WIDTH - PIP_MARGIN, screen.y + screen.height - height - PIP_MARGIN, PIP_WIDTH, height)
            }
        },
        dispose = ComposeWindow::dispose,
    ) {
        // The window takes the shape of the video (16:9, 4:3, 2.39:1...) and keeps it when resized
        val aspect = engine.videoAspect ?: DEFAULT_ASPECT
        // Only when the shape changes: each change is a blocking call into macOS, this content redraws every frame
        LaunchedEffect(aspect) {
            window.minimumSize = Dimension(PIP_MIN_WIDTH, (PIP_MIN_WIDTH / aspect).toInt())
            // Waits for the window to be on screen, a size set before that is replaced by the initial one
            while (!window.isShowing) delay(50)
            val bounds = window.bounds
            val height = (bounds.width / aspect).toInt()
            // Grows or shrinks from the top, the window sits in the bottom corner
            if (height != bounds.height) window.setBounds(bounds.x, bounds.y + bounds.height - height, bounds.width, height)
        }
        val visible = !AppVisibility.isHidden
        SideEffect { engine.renderFrames = visible }
        var hovered by remember { mutableStateOf(false) }
        // Dragging anywhere moves the window, a drag must not also count as a click on the video
        val drag = remember { WindowDrag() }
        val resize = remember { WindowResize() }
        Box(
            Modifier
                .fillMaxSize()
                .clip(PipShape)
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .onPointerEvent(PointerEventType.Press) { if (!resize.active) drag.start(window.location) }
                .onPointerEvent(PointerEventType.Move) { event ->
                    if (event.buttons.isPrimaryPressed && !resize.active) drag.move(window)
                }
                // Also ends a resize whose release landed outside the grip
                .onPointerEvent(PointerEventType.Release) { resize.stop() }
        ) {
            engine.frame?.let { frame ->
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (!drag.moved) engine.togglePause() },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    VideoFrame(frame, engine.videoAspect)
                }
            }
            if (engine.isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

            AnimatedVisibility(hovered || !engine.isPlaying, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                    // Inset from the corners, which are the resize grips
                    PipButton(Res.drawable.close, "Close", Modifier.align(Alignment.TopStart).padding(10.dp), onClose)
                    PipButton(Res.drawable.pip, "Back to the player", Modifier.align(Alignment.TopEnd).padding(10.dp), onExpand)
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PipButton(Res.drawable.replay, "Back") { engine.skip(-seekMs) }
                        PipButton(if (engine.isPlaying) Res.drawable.pause else Res.drawable.play, "Play/Pause", onClick = engine::togglePause)
                        PipButton(Res.drawable.forward, "Forward") { engine.skip(seekMs) }
                    }
                    if (engine.lengthMs > 0) {
                        LinearProgressIndicator(
                            progress = { engine.timeMs.toFloat() / engine.lengthMs },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        )
                    }
                }
            }

            // Undecorated windows have no resizable edges, every corner resizes and keeps the video's shape
            listOf(
                Triple(Alignment.TopStart, Corner(-1, -1), Cursor.NW_RESIZE_CURSOR),
                Triple(Alignment.TopEnd, Corner(1, -1), Cursor.NE_RESIZE_CURSOR),
                Triple(Alignment.BottomStart, Corner(-1, 1), Cursor.SW_RESIZE_CURSOR),
                Triple(Alignment.BottomEnd, Corner(1, 1), Cursor.SE_RESIZE_CURSOR),
            ).forEach { (alignment, corner, cursor) ->
                Box(
                    Modifier
                        .align(alignment)
                        .size(14.dp)
                        .pointerHoverIcon(PointerIcon(Cursor(cursor)))
                        .onPointerEvent(PointerEventType.Press) { resize.start(window, corner) }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            if (event.buttons.isPrimaryPressed) resize.move(window, aspect)
                        }
                        .onPointerEvent(PointerEventType.Release) { resize.stop() }
                )
            }
        }
    }
}

private val PipShape = RoundedCornerShape(12.dp)
private const val PIP_WIDTH = 480
private const val PIP_MIN_WIDTH = 240
private const val PIP_MARGIN = 24
private const val DEFAULT_ASPECT = 16f / 9f

/** Which corner is dragged, -1 for left/top and 1 for right/bottom, the opposite corner stays in place */
private data class Corner(val x: Int, val y: Int)

/**
 * Resizes a window from a corner while keeping the video's shape. Uses screen coordinates, the grip itself
 * moves with the window corner, so distances measured from it would feed back into each other.
 */
private class WindowResize {
    private var pointerStart: Point? = null
    private var boundsStart: java.awt.Rectangle? = null
    private var corner = Corner(1, 1)
    var active = false
        private set

    fun start(window: java.awt.Window, corner: Corner) {
        pointerStart = MouseInfo.getPointerInfo()?.location
        boundsStart = window.bounds
        this.corner = corner
        active = true
    }

    fun move(window: java.awt.Window, aspect: Float) {
        if (!active) return
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        val startPointer = pointerStart ?: return
        val start = boundsStart ?: return
        // Growth along each axis, positive when dragging away from the opposite corner
        val growX = (pointer.x - startPointer.x) * corner.x
        val growY = (pointer.y - startPointer.y) * corner.y
        // Follows whichever way the pointer moved more, the other side keeps the video's shape
        val wanted = if (abs(growX) >= abs(growY * aspect)) start.width + growX else ((start.height + growY) * aspect).toInt()
        val width = maxOf(PIP_MIN_WIDTH, wanted)
        val height = (width / aspect).toInt()
        val x = if (corner.x < 0) start.x + start.width - width else start.x
        val y = if (corner.y < 0) start.y + start.height - height else start.y
        window.setBounds(x, y, width, height)
    }

    fun stop() {
        active = false
    }
}

/** Moves a window with the mouse, using screen coordinates so the window moving under the cursor does not matter */
private class WindowDrag {
    private var pointerStart: Point? = null
    private var windowStart: Point? = null
    var moved = false
        private set

    fun start(windowLocation: Point) {
        pointerStart = MouseInfo.getPointerInfo()?.location
        windowStart = windowLocation
        moved = false
    }

    fun move(window: java.awt.Window) {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        val startPointer = pointerStart ?: return
        val startWindow = windowStart ?: return
        val dx = pointer.x - startPointer.x
        val dy = pointer.y - startPointer.y
        if (!moved && abs(dx) < 4 && abs(dy) < 4) return
        moved = true
        window.setLocation(startWindow.x + dx, startWindow.y + dy)
    }
}

@Composable
private fun PipButton(icon: DrawableResource, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), description, tint = Color.White)
    }
}
