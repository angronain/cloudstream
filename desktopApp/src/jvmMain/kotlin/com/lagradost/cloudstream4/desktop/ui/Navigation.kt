package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream4.desktop.data.MediaEntry

enum class Tab(val title: String) {
    Home("Home"),
    Search("Search"),
    Library("Library"),
    Downloads("Downloads"),
    Extensions("Extensions"),
    Settings("Settings"),
}

/** A single episode or movie that can be played */
data class Playable(
    val data: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
)

sealed interface Route {
    data class Result(val media: MediaEntry) : Route

    /** [index] is the item in [playlist] to start with, so next/previous episode works */
    data class Player(val media: MediaEntry, val playlist: List<Playable>, val index: Int) : Route
}

class Navigator {
    var tab by mutableStateOf(Tab.Home)
        private set
    val stack = mutableStateListOf<Route>()

    val current: Route? get() = stack.lastOrNull()

    fun select(tab: Tab) {
        this.tab = tab
        stack.clear()
    }

    /** Playback shown in the floating picture-in-picture window while the user browses */
    var pip by mutableStateOf<Route.Player?>(null)
        private set

    fun push(route: Route) {
        // Playing something else replaces the picture-in-picture playback
        if (route is Route.Player) pip = null
        stack.add(route)
    }

    /** Moves the open player into the picture-in-picture window and goes back to the previous screen */
    fun enterPip(route: Route.Player) {
        pip = route
        if (current == route) back()
    }

    /** Brings the picture-in-picture playback back into the main window */
    fun expandPip() {
        val route = pip ?: return
        stack.add(route)
        pip = null
    }

    fun closePip() {
        pip = null
    }

    /** Switches episodes in picture-in-picture, where the player is not on the stack */
    fun replacePip(route: Route.Player) {
        if (pip != null) pip = route
    }

    /** Replace the top of the stack, used when switching episodes in the player */
    fun replace(route: Route) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(route)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

val LocalNavigator = compositionLocalOf<Navigator> { error("No navigator") }
