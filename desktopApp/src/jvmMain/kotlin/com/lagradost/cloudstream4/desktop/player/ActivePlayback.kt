package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.mvvm.logError

/**
 * Saves the position of whatever is playing when the app exits. Quitting can skip the player's own
 * cleanup (Cmd+Q, closing the main window while the video is in picture-in-picture, logging out),
 * the shutdown hook calls [saveNow] so the position is never lost.
 */
object ActivePlayback {
    @Volatile
    var save: (() -> Unit)? = null

    fun saveNow() {
        try {
            save?.invoke()
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
