package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetAddress

/** Another computer seen in the sync folder */
data class SyncDevice(val name: String, val savedAt: Long)

data class SyncStatus(
    val lastSync: Long = 0,
    val devices: List<SyncDevice> = emptyList(),
    val error: String? = null,
)

/**
 * Syncs the library between computers through a folder that a cloud drive keeps in sync (iCloud Drive, Dropbox,
 * Google Drive, OneDrive, Syncthing...). Each computer writes its own file and reads the others', so two
 * computers never write the same file and the cloud drive never has conflicting copies.
 */
object Sync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private const val INTERVAL_MS = 30_000L
    private var job: Job? = null
    private val seen = HashMap<String, Long>()
    private var lastWritten: Int? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val deviceName: String by lazy {
        runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name") ?: AppDirs.os.name
    }

    private fun folder(): File? = AppData.settings.value.syncFolder.takeIf { it.isNotBlank() }?.let { File(it, "CloudStream sync") }

    /** Starts syncing in the background if a folder is set, call again after changing the folder */
    fun start() {
        job?.cancel()
        seen.clear()
        lastWritten = null
        _status.value = SyncStatus()
        if (folder() == null) return
        job = scope.launch {
            while (isActive) {
                syncOnce()
                delay(INTERVAL_MS)
            }
        }
    }

    fun syncNow() {
        scope.launch {
            seen.clear()
            lastWritten = null
            syncOnce()
        }
    }

    private suspend fun syncOnce() = mutex.withLock {
        val dir = folder() ?: return@withLock
        try {
            dir.mkdirs()
            require(dir.isDirectory) { "Cannot use ${dir.absolutePath}" }
            val own = File(dir, "${AppData.deviceId}.json")
            val devices = mutableListOf<SyncDevice>()
            dir.listFiles { file -> file.extension == "json" && file != own }.orEmpty().forEach { file ->
                val modified = file.lastModified()
                val remote = runCatching { mapper.readValue<SyncSnapshot>(file) }.onFailure { logError(it) }.getOrNull()
                    ?: return@forEach
                devices += SyncDevice(remote.deviceName, remote.savedAt)
                if (seen[file.name] == modified) return@forEach
                AppData.mergeSync(remote)
                seen[file.name] = modified
            }
            // Written after merging, so the others get what this computer just took in too
            val snapshot = AppData.syncSnapshot(deviceName)
            val content = snapshot.copy(savedAt = 0).hashCode()
            if (content != lastWritten || !own.exists()) {
                // A hidden temporary file first, so the cloud drive never uploads half a file
                val temp = File(dir, ".${own.name}.tmp")
                mapper.writeValue(temp, snapshot)
                if (!temp.renameTo(own)) {
                    own.delete()
                    temp.renameTo(own)
                }
                lastWritten = content
            }
            _status.value = SyncStatus(System.currentTimeMillis(), devices.sortedByDescending { it.savedAt })
        } catch (t: Throwable) {
            logError(t)
            _status.value = _status.value.copy(error = t.message ?: t.javaClass.simpleName)
        }
    }
}
