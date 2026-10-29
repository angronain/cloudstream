package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.material3.IconButton
import com.lagradost.cloudstream4.desktop.generated.resources.arrow_back
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream4.desktop.player.MacArch
import com.lagradost.cloudstream4.desktop.generated.resources.Res
import com.lagradost.cloudstream4.desktop.generated.resources.download
import com.lagradost.cloudstream4.desktop.generated.resources.extension
import com.lagradost.cloudstream4.desktop.generated.resources.home
import com.lagradost.cloudstream4.desktop.generated.resources.library
import com.lagradost.cloudstream4.desktop.generated.resources.search
import com.lagradost.cloudstream4.desktop.generated.resources.settings
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.Sync
import com.lagradost.cloudstream4.desktop.plugins.PluginManager
import com.lagradost.cloudstream4.desktop.update.UpdateState
import com.lagradost.cloudstream4.desktop.update.Updater
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private val Tab.icon: DrawableResource
    get() = when (this) {
        Tab.Home -> Res.drawable.home
        Tab.Search -> Res.drawable.search
        Tab.Library -> Res.drawable.library
        Tab.Downloads -> Res.drawable.download
        Tab.Extensions -> Res.drawable.extension
        Tab.Settings -> Res.drawable.settings
    }

@Composable
fun App(navigator: Navigator) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    // Screen state that should survive switching tabs
    val home = remember { HomeViewModel(scope) }
    val search = remember { SearchViewModel(scope) }
    val extensions = remember { ExtensionsViewModel(scope, notify) }

    // Extensions show toasts through CommonActivity
    LaunchedEffect(Unit) { CommonActivity.toasts.collect { notify(it) } }

    var loadingPlugins by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        Sync.start()
        PluginManager.loadAll()
        loadingPlugins = false
        val failed = PluginManager.errors.value.size
        if (failed > 0) notify("$failed extensions failed to load, see the Extensions tab")
        // Also with update checks off, since the Intel build on Apple Silicon only gets the right build through them
        if (AppData.settings.value.checkForUpdates || MacArch.isTranslated) Updater.check()
        NewEpisodes.checkIfDue()
        Recommendations.refreshIfDue()
    }

    // Offer the update once it is downloaded (or found, when automatic updates are off)
    LaunchedEffect(Unit) {
        Updater.state.collect { update ->
            val (message, action, onAction) = when (update) {
                is UpdateState.Ready -> Triple(tr("CloudStream {0} is ready to install", update.update.label), tr("Restart"), Updater::installAndRestart)
                is UpdateState.Available -> Triple(tr("CloudStream {0} is available", update.update.label), tr("Download")) {
                    if (Updater.canInstall) Updater.download(update.update) else openInBrowser(update.update.asset.downloadUrl)
                }
                else -> return@collect
            }
            scope.launch {
                val result = snackbar.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Indefinite, withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) onAction()
            }
        }
    }

    val stateHolder = rememberSaveableStateHolder()
    val route = navigator.current

    CompositionLocalProvider(LocalNavigator provides navigator, LocalSnackbar provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            if (route !is Route.Player) Box(Modifier.fillMaxSize()) {
                // Home's spotlight and a title's artwork run under the navigation bar, the other screens start below it
                val overHome = (route == null && navigator.tab == Tab.Home) || route is Route.Result
                Box(Modifier.fillMaxSize().padding(top = if (overHome) 0.dp else TopBarHeight)) {
                    when (route) {
                        is Route.Result -> stateHolder.SaveableStateProvider(route.toString()) { ResultScreen(route.media) }
                        null -> stateHolder.SaveableStateProvider(navigator.tab.name) {
                            when (navigator.tab) {
                                Tab.Home -> HomeScreen(home)
                                Tab.Search -> SearchScreen(search)
                                Tab.Library -> LibraryScreen()
                                Tab.Downloads -> DownloadsScreen()
                                Tab.Extensions -> ExtensionsScreen(extensions)
                                Tab.Settings -> SettingsScreen()
                            }
                        }

                        is Route.Player -> Unit
                    }
                }
                TopBar(navigator, overHome && !navigator.heroScrolled)
                if (loadingPlugins) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
            // The player takes over the whole window, or plays in its own window in picture-in-picture.
            // It stays at this one call site so moving between the two keeps the same playback going.
            val playing = route as? Route.Player ?: navigator.pip
            if (playing != null) PlayerScreen(playing, inPip = route !is Route.Player)
        }
    }
}

private val TopBarHeight = 64.dp

/** The tabs across the top of the window, see-through over Home's spotlight */
@Composable
private fun TopBar(navigator: Navigator, overHome: Boolean) {
    val content = if (overHome) Color.White else MaterialTheme.colorScheme.onBackground
    Row(
        Modifier.fillMaxWidth().height(TopBarHeight)
            .background(
                if (overHome) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                else SolidColor(MaterialTheme.colorScheme.background)
            )
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (navigator.current != null) {
            IconButton(onClick = { navigator.back() }, modifier = Modifier.padding(end = 8.dp)) {
                Icon(painterResource(Res.drawable.arrow_back), tr("Back"), tint = content)
            }
        }
        Text(
            "CloudStream",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (overHome) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 24.dp).clip(RoundedCornerShape(6.dp)).clickable { navigator.select(Tab.Home) },
        )
        Tab.entries.forEach { tab ->
            val selected = navigator.tab == tab
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (selected) content.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { navigator.select(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(tab.icon), null, tint = content.copy(alpha = if (selected) 1f else 0.7f), modifier = Modifier.size(18.dp))
                Text(
                    tab.title,
                    color = content.copy(alpha = if (selected) 1f else 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
