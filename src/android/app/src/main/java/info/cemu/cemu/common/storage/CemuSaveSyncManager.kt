package info.cemu.cemu.common.storage

import android.content.Context
import android.os.FileObserver
import info.cemu.cemu.common.settings.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

object CemuSaveSyncManager {
    private const val SYNC_DEBOUNCE_MS = 1500L
    private const val EVENT_MASK = FileObserver.CREATE or
        FileObserver.MODIFY or
        FileObserver.CLOSE_WRITE or
        FileObserver.DELETE or
        FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or
        FileObserver.DELETE_SELF or
        FileObserver.MOVE_SELF

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRelativePaths = linkedSetOf<String>()

    private var observer: RecursiveSaveObserver? = null
    private var flushJob: Job? = null

    fun start(context: Context) {
        synchronized(this) {
            val applicationContext = context.applicationContext
            observer?.stopWatching()

            val settings = runBlocking { AppSettingsStore.dataStore.data.first().storageSettings }
            if (settings.customRootUri.isNullOrBlank()) {
                observer = null
                return
            }

            val mirrorRoot = CemuDataStorage.getActiveRoot()
                ?: CemuDataStorage.resolveCurrentRoot(applicationContext, settings)
            val saveRoot = CemuDataStorage.saveRoot(mirrorRoot)
            saveRoot.mkdirs()
            observer = RecursiveSaveObserver(saveRoot) { relativePath ->
                enqueueChangedPath(applicationContext, relativePath)
            }.also { it.startWatching() }
        }
    }

    fun stopAndFlush(context: Context): Boolean {
        synchronized(this) {
            observer?.stopWatching()
            observer = null
        }
        return flushNow(context)
    }

    fun flushNow(context: Context): Boolean {
        val (paths, keepDirtyAfterFlush) = synchronized(this) {
            flushJob?.cancel()
            flushJob = null
            val snapshot = pendingRelativePaths.toSet()
            pendingRelativePaths.clear()
            snapshot to (observer != null)
        }

        return runBlocking(Dispatchers.IO) {
            val storageSettings = AppSettingsStore.dataStore.data.first().storageSettings
            if (paths.isEmpty() && !storageSettings.isSaveMirrorDirty) {
                return@runBlocking true
            }
            if (paths.isNotEmpty()) {
                CemuDataStorage.markSavesDirty(context.applicationContext)
            }
            if (paths.isEmpty()) {
                CemuDataStorage.syncSavesToCustomRoot(
                    context = context.applicationContext,
                    force = true,
                    clearDirty = !keepDirtyAfterFlush,
                )
            } else {
                CemuDataStorage.exportSaveChangesToCustomRoot(
                    context = context.applicationContext,
                    changedSaveRelativePaths = paths,
                    clearDirty = !keepDirtyAfterFlush,
                )
            }
        }
    }

    private fun enqueueChangedPath(context: Context, relativePath: String) {
        if (relativePath.isBlank()) {
            return
        }
        synchronized(this) {
            pendingRelativePaths += relativePath
            flushJob?.cancel()
            flushJob = scope.launch {
                CemuDataStorage.markSavesDirty(context.applicationContext)
                delay(SYNC_DEBOUNCE_MS)
                val paths = synchronized(this@CemuSaveSyncManager) {
                    val snapshot = pendingRelativePaths.toSet()
                    pendingRelativePaths.clear()
                    flushJob = null
                    snapshot
                }
                if (paths.isNotEmpty()) {
                    CemuDataStorage.exportSaveChangesToCustomRoot(
                        context = context.applicationContext,
                        changedSaveRelativePaths = paths,
                        clearDirty = false,
                    )
                }
            }
        }
    }

    private class RecursiveSaveObserver(
        private val root: File,
        private val onChanged: (String) -> Unit,
    ) {
        private val observers = mutableMapOf<String, FileObserver>()

        fun startWatching() {
            if (!root.exists()) {
                root.mkdirs()
            }
            root.walkTopDown()
                .filter { it.isDirectory }
                .forEach { watchDirectory(it) }
        }

        fun stopWatching() {
            observers.values.forEach { it.stopWatching() }
            observers.clear()
        }

        private fun watchDirectory(directory: File) {
            val canonicalPath = directory.absolutePath
            if (observers.containsKey(canonicalPath)) {
                return
            }

            val observer = object : FileObserver(directory, EVENT_MASK) {
                override fun onEvent(event: Int, path: String?) {
                    val changed = if (path.isNullOrBlank()) directory else directory.resolve(path)
                    if ((event and FileObserver.CREATE) != 0 && changed.isDirectory) {
                        watchDirectory(changed)
                    }
                    onChanged(changed.relativeToOrSelf(root).path.replace(File.separatorChar, '/'))
                }
            }
            observers[canonicalPath] = observer
            observer.startWatching()
        }
    }
}
