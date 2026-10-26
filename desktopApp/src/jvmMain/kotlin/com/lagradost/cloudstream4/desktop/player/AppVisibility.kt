package com.lagradost.cloudstream4.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.mvvm.logError
import java.awt.Desktop
import java.awt.desktop.AppHiddenEvent
import java.awt.desktop.AppHiddenListener

/** Whether the whole app is hidden (Cmd+H on macOS), in which case no window shows the video */
object AppVisibility {
    var isHidden by mutableStateOf(false)
        private set

    fun install() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_HIDDEN)) return
            Desktop.getDesktop().addAppEventListener(object : AppHiddenListener {
                override fun appHidden(e: AppHiddenEvent?) {
                    isHidden = true
                }

                override fun appUnhidden(e: AppHiddenEvent?) {
                    isHidden = false
                }
            })
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
