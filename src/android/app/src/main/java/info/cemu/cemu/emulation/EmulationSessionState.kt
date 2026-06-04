package info.cemu.cemu.emulation

import android.content.Context
import info.cemu.cemu.common.storage.CemuDataStorage
import info.cemu.cemu.common.storage.CemuSaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

object EmulationSessionState {
    private val activeSessions = AtomicInteger(0)

    val isEmulationRunning: Boolean
        get() = activeSessions.get() > 0

    fun onSessionStarted(context: Context) {
        runBlocking {
            CemuDataStorage.markSavesDirty(context.applicationContext)
        }
        CemuSaveSyncManager.start(context.applicationContext)
        activeSessions.incrementAndGet()
    }

    fun onSessionStopped(context: Context) {
        val remainingSessions = activeSessions.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        if (remainingSessions == 0) {
            syncSavesToCustomRoot(context)
        }
    }

    fun syncSavesToCustomRoot(context: Context) {
        CemuSaveSyncManager.stopAndFlush(context.applicationContext)
        runBlocking {
            withContext(Dispatchers.IO) {
                CemuDataStorage.syncSavesToCustomRoot(context.applicationContext)
            }
        }
    }
}
