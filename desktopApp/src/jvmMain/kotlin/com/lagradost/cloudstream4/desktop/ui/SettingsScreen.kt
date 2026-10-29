package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.BuildInfo
import com.lagradost.cloudstream4.desktop.data.AppData
import com.lagradost.cloudstream4.desktop.data.I18n
import com.lagradost.cloudstream4.desktop.data.AppLog
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.Letterboxd
import com.lagradost.cloudstream4.desktop.data.DiscordPresence
import com.lagradost.cloudstream4.desktop.data.Trakt
import com.lagradost.cloudstream4.desktop.data.TraktState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lagradost.cloudstream4.desktop.data.OperatingSystem
import com.lagradost.cloudstream4.desktop.data.Sync
import com.lagradost.cloudstream4.desktop.data.PlayerKind
import com.lagradost.cloudstream4.desktop.downloads.DownloadManager
import com.lagradost.cloudstream4.desktop.player.ExternalPlayer
import com.lagradost.cloudstream4.desktop.player.Vlc
import com.lagradost.cloudstream4.desktop.update.UpdateState
import com.lagradost.cloudstream4.desktop.update.Updater
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDate

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val settings by AppData.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 800.dp)
    ) {
        Text(tr("Settings"), style = MaterialTheme.typography.headlineMedium)

        Section(tr("Appearance")) {
            Text(tr("Language"), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                I18n.languages.forEach { (code, name) ->
                    FilterChip(
                        settings.language == code,
                        onClick = { AppData.updateSettings { it.copy(language = code) } },
                        label = { Text(if (code.isEmpty()) tr(name) else name) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudStreamThemeMode.entries.filter { it != CloudStreamThemeMode.Dynamic }.forEach { mode ->
                    FilterChip(
                        settings.theme == mode,
                        onClick = { AppData.updateSettings { it.copy(theme = mode) } },
                        label = { Text(if (mode == CloudStreamThemeMode.FollowSystem) tr("System") else mode.name) },
                    )
                }
            }
            Text(tr("Accent color"), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudStreamPrimaryColor.entries.forEach { color ->
                    Box(
                        Modifier.size(32.dp).background(color.color, CircleShape)
                            .border(
                                if (settings.primaryColor == color) 3.dp else 0.dp,
                                if (settings.primaryColor == color) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                                CircleShape,
                            )
                            .clickable { AppData.updateSettings { it.copy(primaryColor = color) } }
                    )
                }
            }
        }

        Section(tr("Player")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.player == PlayerKind.Internal, { AppData.updateSettings { it.copy(player = PlayerKind.Internal) } },
                    label = { Text(tr("Built-in (VLC)")) })
                FilterChip(settings.player == PlayerKind.External, { AppData.updateSettings { it.copy(player = PlayerKind.External) } },
                    label = { Text(tr("External player")) })
            }
            // Looked up off the UI thread, null until known
            val vlcInstalled by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { Vlc.isInstalled.also { Vlc.problem } } }
            vlcInstalled?.let { installed ->
                Text(
                    if (installed) tr("VLC found, the built-in player is available.")
                    else Vlc.problem ?: tr("VLC was not found. Install VLC to use the built-in player."),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (vlcInstalled == false && Vlc.problem == null) TextButton(onClick = { openInBrowser("https://www.videolan.org/vlc/") }) { Text(tr("Download VLC")) }
            OutlinedTextField(
                value = settings.externalPlayer,
                onValueChange = { value -> AppData.updateSettings { it.copy(externalPlayer = value) } },
                label = { Text(tr("External player path")) },
                placeholder = { Text(ExternalPlayer.detect() ?: tr("Auto detect VLC, mpv or IINA")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(tr("Seek {0} seconds with the arrow keys", settings.seekSeconds), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { seconds ->
                    FilterChip(settings.seekSeconds == seconds, { AppData.updateSettings { it.copy(seekSeconds = seconds) } },
                        label = { Text("${seconds}s") })
                }
            }
        }

        Section(tr("Subtitles")) {
            Text(tr("Text size"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(75, 100, 125, 150, 200).forEach { scale ->
                    FilterChip(settings.subtitleScale == scale, { AppData.updateSettings { it.copy(subtitleScale = scale) } },
                        label = { Text("$scale%") })
                }
            }
            SwitchRow(tr("Dark background"), tr("A box behind the text, easier to read on bright scenes"), settings.subtitleBackground) { value ->
                AppData.updateSettings { it.copy(subtitleBackground = value) }
            }
            // VLC reads these once, when the player first opens
            if (Vlc.isStarted) {
                Text(
                    tr("Changes apply after restarting CloudStream."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(tr("Downloads")) {
            OutlinedTextField(
                value = settings.downloadDirectory,
                onValueChange = { value -> AppData.updateSettings { it.copy(downloadDirectory = value) } },
                label = { Text(tr("Download folder")) },
                placeholder = { Text(DownloadManager.defaultDirectory.absolutePath) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section(tr("Extensions")) {
            SwitchRow(
                tr("Use jsDelivr for GitHub"),
                tr("Downloads repositories and extensions through cdn.jsdelivr.net, useful if GitHub is blocked"),
                settings.jsdelivrProxy,
            ) { value -> AppData.updateSettings { it.copy(jsdelivrProxy = value) } }
        }

        Section(tr("Library")) {
            val checking by NewEpisodes.checking.collectAsState()
            SwitchRow(
                tr("Check for new episodes"),
                tr("Once a day, looks for new episodes of every show you watched at least one episode of and lists them on Home"),
                settings.newEpisodeChecks,
            ) { value -> AppData.updateSettings { it.copy(newEpisodeChecks = value) } }
            TextButton(onClick = NewEpisodes::check, enabled = !checking) { Text(if (checking) tr("Checking…") else tr("Check now")) }
        }

        Section(tr("Recommendations")) {
            val window = LocalMainWindow.current
            val loading by Recommendations.loading.collectAsState()
            val imported by AppData.importedRatings.collectAsState()
            var message by remember { mutableStateOf<String?>(null) }
            SwitchRow(
                tr("Recommended for you"),
                tr("A row on Home of titles like the ones you rated well, liked or watched, found on TMDB"),
                settings.recommendations,
            ) { value ->
                AppData.updateSettings { it.copy(recommendations = value) }
                if (value) Recommendations.refreshIfDue()
            }
            Text(
                tr("Import your Letterboxd ratings so the recommendations know your taste. In Letterboxd, go to Settings, Data, Export your data, then pick the zip file here."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val fromLetterboxd = imported.values.count { it.source == "Letterboxd" }
            if (fromLetterboxd > 0) Text(tr("{0} films imported from Letterboxd", fromLetterboxd), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = {
                    val dialog = FileDialog(window, tr("Import from Letterboxd"), FileDialog.LOAD).apply { isVisible = true }
                    val name = dialog.file ?: return@TextButton
                    message = runCatching {
                        val films = Letterboxd.read(File(dialog.directory, name))
                        require(films.isNotEmpty()) { tr("No films found in this file") }
                        AppData.importRatings(films)
                        Recommendations.refresh()
                        tr("{0} films imported", films.size)
                    }.getOrElse { tr("Could not import: {0}", it.message) }
                }) { Text(tr("Import from Letterboxd")) }
                TextButton(onClick = {
                    val dialog = FileDialog(window, tr("Export to Letterboxd"), FileDialog.SAVE).apply {
                        file = "CloudStream-letterboxd-${LocalDate.now()}.csv"
                        isVisible = true
                    }
                    val name = dialog.file ?: return@TextButton
                    message = runCatching {
                        val count = Letterboxd.write(File(dialog.directory, name))
                        tr("{0} films saved, import the file at letterboxd.com/import", count)
                    }.getOrElse { tr("Could not save: {0}", it.message) }
                }) { Text(tr("Export to Letterboxd")) }
                if (fromLetterboxd > 0) TextButton(onClick = { AppData.clearImportedRatings("Letterboxd") }) { Text(tr("Remove import")) }
                TextButton(onClick = Recommendations::refresh, enabled = !loading) {
                    Text(if (loading) tr("Updating…") else tr("Update recommendations"))
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Section(tr("Sync")) {
            val window = LocalMainWindow.current
            val status by Sync.status.collectAsState()
            Text(
                tr("Keeps bookmarks, lists, ratings, history and watched progress the same on your computers. Pick a folder that iCloud Drive, Dropbox, Google Drive or OneDrive syncs, and the same folder on each computer."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.syncFolder.isNotBlank()) Text(settings.syncFolder, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = {
                    val folder = chooseFolder(window, tr("Sync folder")) ?: return@TextButton
                    AppData.updateSettings { it.copy(syncFolder = folder.absolutePath) }
                    Sync.start()
                }) { Text(if (settings.syncFolder.isBlank()) tr("Choose folder") else tr("Change folder")) }
                if (settings.syncFolder.isNotBlank()) {
                    TextButton(onClick = Sync::syncNow) { Text(tr("Sync now")) }
                    TextButton(onClick = {
                        AppData.updateSettings { it.copy(syncFolder = "") }
                        Sync.start()
                    }) { Text(tr("Stop syncing")) }
                }
            }
            if (settings.syncFolder.isNotBlank()) {
                val text = when {
                    status.error != null -> tr("Could not sync: {0}", status.error)
                    status.lastSync == 0L -> tr("Syncing…")
                    status.devices.isEmpty() -> tr("Synced, no other computer in this folder yet")
                    else -> tr("Synced with {0}", status.devices.joinToString { it.name })
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(tr("Trakt")) {
            val trakt by Trakt.state.collectAsState()
            val traktMessage by Trakt.message.collectAsState()
            Text(
                tr("Marks what you watch as watched on Trakt, sends your ratings and Plan to watch list there, and uses your Trakt ratings for the recommendations."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val current = trakt) {
                is TraktState.SignedIn -> {
                    Text(tr("Signed in as {0}", current.username ?: "Trakt"), style = MaterialTheme.typography.bodyMedium)
                    SwitchRow(tr("Mark as watched on Trakt"), null, settings.traktScrobble) { value ->
                        AppData.updateSettings { it.copy(traktScrobble = value) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = Trakt::syncLibrary) { Text(tr("Sync ratings and list")) }
                        TextButton(onClick = Trakt::signOut) { Text(tr("Sign out")) }
                    }
                    traktMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                is TraktState.WaitingForCode -> {
                    Text(tr("Enter this code at {0}", current.url), style = MaterialTheme.typography.bodyMedium)
                    Text(current.code, style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { openInBrowser(current.url) }) { Text(tr("Open trakt.tv")) }
                        TextButton(onClick = Trakt::signOut) { Text(tr("Cancel")) }
                    }
                }

                else -> {
                    Text(
                        tr("Trakt needs an application of your own: at trakt.tv/oauth/applications, click New Application, name it CloudStream, enter http://localhost as the redirect URI, save, and paste its Client ID and Client Secret here."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { openInBrowser("https://trakt.tv/oauth/applications") }) { Text(tr("Open Trakt applications")) }
                    OutlinedTextField(
                        value = settings.traktClientId,
                        onValueChange = { value -> AppData.updateSettings { it.copy(traktClientId = value.trim()) } },
                        label = { Text(tr("Client ID")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.traktClientSecret,
                        onValueChange = { value -> AppData.updateSettings { it.copy(traktClientSecret = value.trim()) } },
                        label = { Text(tr("Client Secret")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { Trakt.signIn(::openInBrowser) },
                        enabled = settings.traktClientId.isNotBlank() && settings.traktClientSecret.isNotBlank(),
                    ) { Text(tr("Sign in to Trakt")) }
                    (current as? TraktState.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Section(tr("Discord")) {
            SwitchRow(
                tr("Show what you watch on Discord"),
                tr("Your Discord profile shows the title and episode while the Discord app is open on this computer"),
                settings.discordPresence,
            ) { value ->
                AppData.updateSettings { it.copy(discordPresence = value) }
                if (!value) DiscordPresence.clear()
            }
        }

        Section(tr("Storage")) {
            Text(tr("Data folder: {0}", AppDirs.dataDir.absolutePath), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.dataDir) } }) { Text(tr("Open data folder")) }
        }

        Section(tr("Backup")) {
            val window = LocalMainWindow.current
            var backupMessage by remember { mutableStateOf<String?>(null) }
            Text(
                tr("Bookmarks, lists, ratings, history, watched progress, repositories and settings. Extensions are installed again from the restored repositories."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = {
                    val dialog = FileDialog(window, tr("Save backup"), FileDialog.SAVE).apply {
                        file = "CloudStream-backup-${LocalDate.now()}.json"
                        isVisible = true
                    }
                    val name = dialog.file ?: return@TextButton
                    backupMessage = runCatching { AppData.exportBackup(File(dialog.directory, name)); tr("Backup saved to {0}", name) }
                        .getOrElse { tr("Could not save the backup: {0}", it.message) }
                }) { Text(tr("Save backup")) }
                TextButton(onClick = {
                    val dialog = FileDialog(window, tr("Restore backup"), FileDialog.LOAD).apply { isVisible = true }
                    val name = dialog.file ?: return@TextButton
                    backupMessage = runCatching { AppData.restoreBackup(File(dialog.directory, name)); tr("Backup restored") }
                        .getOrElse { tr("Could not restore the backup: {0}", it.message) }
                }) { Text(tr("Restore backup")) }
            }
            backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Section(tr("Updates")) {
            val update by Updater.state.collectAsState()
            Text(tr("Version {0}, updates from github.com/{1}", Updater.currentVersion, BuildInfo.UPDATE_REPOSITORY), style = MaterialTheme.typography.bodyMedium)
            SwitchRow(tr("Check for updates on startup"), null, settings.checkForUpdates) { value ->
                AppData.updateSettings { it.copy(checkForUpdates = value) }
            }
            SwitchRow(
                tr("Update automatically"),
                tr("Downloads new versions in the background and installs them when you restart or quit"),
                settings.autoUpdate,
            ) { value -> AppData.updateSettings { it.copy(autoUpdate = value) } }
            if (!Updater.canInstall) {
                Text(
                    tr("Running outside the installed app, updates can be checked but not installed."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (val current = update) {
                    is UpdateState.Ready -> TextButton(onClick = Updater::installAndRestart) { Text(tr("Restart and update to {0}", current.update.label)) }
                    is UpdateState.Available -> TextButton(onClick = {
                        if (Updater.canInstall) Updater.download(current.update) else openInBrowser(current.update.asset.downloadUrl)
                    }) { Text(tr("Download {0}", current.update.label)) }

                    is UpdateState.Downloading, UpdateState.Checking -> Unit
                    else -> TextButton(onClick = { Updater.check(userInitiated = true) }) { Text(tr("Check for updates")) }
                }
                Text(
                    when (val current = update) {
                        UpdateState.Idle -> ""
                        UpdateState.Checking -> tr("Checking...")
                        UpdateState.UpToDate -> tr("You have the latest version")
                        is UpdateState.Available -> tr("Version {0} is available", current.update.label)
                        is UpdateState.Downloading -> tr("Downloading {0}: {1}%", current.update.label, (current.progress * 100).toInt())
                        is UpdateState.Ready -> tr("Version {0} will be installed when you quit", current.update.label)
                        is UpdateState.Failed -> current.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (update is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(tr("Problems")) {
            var logStatus by remember { mutableStateOf("") }
            Text(
                tr("If something goes wrong or the app closes by itself, save the logs and send the file to whoever helps you. They include the last three sessions, so it also works after restarting."),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    logStatus = runCatching {
                        val folder = listOf("Desktop", "Downloads").map { File(System.getProperty("user.home"), it) }.firstOrNull { it.isDirectory }
                            ?: AppLog.dir
                        val file = File(folder, "CloudStream-logs-${LocalDate.now()}.txt")
                        file.writeText(AppLog.shareableText())
                        runCatching { Desktop.getDesktop().open(folder) }
                        tr("Saved {0}", file.absolutePath)
                    }.getOrElse { tr("Could not save the logs: {0}", it.message) }
                }) { Text(tr("Save logs to a file")) }
                TextButton(onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(AppLog.shareableText()), null)
                    logStatus = tr("Copied, paste it in a message or a text file")
                }) { Text(tr("Copy logs")) }
                TextButton(onClick = { runCatching { Desktop.getDesktop().open(AppLog.dir) } }) { Text(tr("Open logs folder")) }
            }
            if (logStatus.isNotEmpty()) {
                Text(logStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section(tr("About")) {
            Text(tr("CloudStream for desktop, running on {0}", AppDirs.os.name), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openInBrowser("https://github.com/recloudstream/cloudstream") }) { Text(tr("Source code")) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** A folder picker, the native one on macOS */
private fun chooseFolder(window: java.awt.Frame?, title: String): File? {
    if (AppDirs.os == OperatingSystem.MacOS) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(window, title, FileDialog.LOAD).apply { isVisible = true }
            return dialog.file?.let { File(dialog.directory, it) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
