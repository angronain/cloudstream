package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.forward
import com.lagradost.cloudstream4.desktop.generated.resources.pause
import com.lagradost.cloudstream4.desktop.generated.resources.pip
import com.lagradost.cloudstream4.desktop.generated.resources.play
import com.lagradost.cloudstream4.desktop.generated.resources.replay
import com.lagradost.cloudstream4.desktop.player.VlcEngine
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

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
    val state = rememberWindowState(width = 480.dp, height = 270.dp, position = WindowPosition(Alignment.BottomEnd))
    Window(
        onCloseRequest = onClose,
        state = state,
        title = title,
        alwaysOnTop = true,
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Window false
            when (event.key) {
                Key.Spacebar, Key.K -> engine.togglePause()
                Key.DirectionLeft, Key.J -> engine.skip(-seekMs)
                Key.DirectionRight, Key.L -> engine.skip(seekMs)
                Key.P -> onExpand()
                Key.Escape -> onClose()
                else -> return@Window false
            }
            true
        },
    ) {
        window.minimumSize = Dimension(240, 135)
        var hovered by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
        ) {
            engine.frame?.let { frame ->
                Image(
                    frame,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = engine::togglePause,
                    ),
                )
            }
            if (engine.isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

            AnimatedVisibility(hovered || !engine.isPlaying, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                    PipButton(Res.drawable.pip, "Back to the player (P)", Modifier.align(Alignment.TopEnd).padding(4.dp), onExpand)
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
        }
    }
}

@Composable
private fun PipButton(icon: DrawableResource, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), description, tint = Color.White)
    }
}
