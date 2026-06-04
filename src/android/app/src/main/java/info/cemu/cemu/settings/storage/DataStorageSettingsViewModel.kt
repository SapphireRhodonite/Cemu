package info.cemu.cemu.settings.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.storage.CemuDataStorage
import info.cemu.cemu.common.storage.CemuSaveSyncManager
import info.cemu.cemu.emulation.EmulationSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

data class DataStorageUiState(
    val activeLocation: String = "",
    val configuredLocation: String? = null,
    val customRootUri: String? = null,
    val mirrorRootPath: String? = null,
    val pendingDeleteRootPath: String? = null,
    val isSaveMirrorDirty: Boolean = false,
    val restartRequiredForStorage: Boolean = false,
    val saveSyncSummary: String = "Default app storage",
    val manualSyncSummary: String = "Manual data is stored locally",
    val lastSaveSync: String = "Never",
    val lastManualSync: String = "Never",
    val isEmulationRunning: Boolean = false,
    val isWorking: Boolean = false,
    val workingMessage: String? = null,
    val storageStatus: String = "Using default app storage.",
    val message: String? = null,
    val error: String? = null,
)

class DataStorageSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val dataStore = AppSettingsStore.dataStore

    private val _uiState = MutableStateFlow(DataStorageUiState())
    val uiState: StateFlow<DataStorageUiState> = _uiState.asStateFlow()

    init {
        refresh(AppSettings())
        viewModelScope.launch {
            dataStore.data.collect { refresh(it) }
        }
    }

    fun chooseCustomRoot(customRootUri: String) {
        if (!ensureNoEmulation("Close emulation before changing the data storage location.")) {
            return
        }

        runStorageAction(
            workingMessage = "Configuring custom root...",
            successMessage = "Custom root configured. Restart Cemu before launching games from the new location.",
            failureMessage = "Unable to configure custom root.",
        ) {
            CemuDataStorage.migrateToCustomRoot(appContext, customRootUri)
        }
    }

    fun reportError(error: String) {
        _uiState.update { it.copy(error = error, message = null) }
    }

    fun syncSavesNow() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    workingMessage = "Syncing saves...",
                    error = null,
                    message = null,
                )
            }
            val synced = if (EmulationSessionState.isEmulationRunning) {
                CemuSaveSyncManager.flushNow(appContext)
            } else {
                CemuDataStorage.syncSavesToCustomRoot(appContext, force = true)
            }
            _uiState.update {
                it.copy(
                    isWorking = false,
                    workingMessage = null,
                    message = if (synced) "Saves synced." else null,
                    error = if (synced) null else "Unable to sync saves. Check SAF access.",
                )
            }
        }
    }

    fun importSavesFromCustomRoot() {
        if (!ensureNoEmulation("Close emulation before importing saves.")) {
            return
        }

        runStorageAction(
            workingMessage = "Importing saves...",
            successMessage = "Saves imported from custom root.",
            failureMessage = "Unable to import saves.",
        ) {
            CemuDataStorage.importSavesFromCustomRoot(appContext)
        }
    }

    fun exportManualData() {
        if (!ensureNoEmulation("Close emulation before exporting manual data.")) {
            return
        }

        runStorageAction(
            workingMessage = "Exporting manual data...",
            successMessage = "Manual data exported.",
            failureMessage = "Unable to export manual data.",
        ) {
            CemuDataStorage.exportManualData(appContext)
        }
    }

    fun importManualData() {
        if (!ensureNoEmulation("Close emulation before importing manual data.")) {
            return
        }

        runStorageAction(
            workingMessage = "Importing manual data...",
            successMessage = "Manual data imported. Restart Cemu before launching games.",
            failureMessage = "Unable to import manual data.",
        ) {
            CemuDataStorage.importManualData(appContext)
        }
    }

    fun useDefaultAppStorage() {
        if (!ensureNoEmulation("Close emulation before changing the data storage location.")) {
            return
        }

        runStorageAction(
            workingMessage = "Switching to default app storage...",
            successMessage = "Default app storage configured. Restart Cemu before launching games from the new location.",
            failureMessage = "Unable to use default app storage.",
        ) {
            CemuDataStorage.migrateToDefaultRoot(appContext)
        }
    }

    fun deletePendingRoot() {
        if (!ensureNoEmulation("Close emulation before deleting the previous data location.")) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    workingMessage = "Deleting previous data location...",
                    error = null,
                    message = null,
                )
            }
            try {
                val settings = dataStore.data.first().storageSettings
                val pendingPath = settings.pendingDeleteDataRootPath
                    ?: throw IllegalStateException("There is no previous data location to delete.")
                val activeRoot = CemuDataStorage.getActiveRoot()
                    ?: CemuDataStorage.resolveCurrentRoot(appContext, settings)
                val pendingRoot = File(pendingPath)

                if (pendingRoot.canonicalFile == activeRoot.canonicalFile) {
                    throw IllegalStateException("Restart Cemu before deleting the previous data location.")
                }

                if (!CemuDataStorage.deleteRoot(pendingPath)) {
                    throw IllegalStateException("Unable to delete previous data location.")
                }

                dataStore.updateData {
                    it.copy(
                        storageSettings = it.storageSettings.copy(
                            pendingDeleteDataRootPath = null,
                        )
                    )
                }
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = null,
                        message = "Previous data location deleted.",
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = null,
                        error = exception.message ?: "Unable to delete previous data location.",
                    )
                }
            }
        }
    }

    private fun runStorageAction(
        workingMessage: String,
        successMessage: String,
        failureMessage: String,
        action: suspend () -> Any?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    workingMessage = workingMessage,
                    error = null,
                    message = null,
                )
            }
            try {
                val result = action()
                val successful = result !is Boolean || result
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = null,
                        message = if (successful) successMessage else null,
                        error = if (successful) null else failureMessage,
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = null,
                        error = exception.message ?: failureMessage,
                    )
                }
            }
        }
    }

    private fun ensureNoEmulation(errorMessage: String): Boolean {
        if (!EmulationSessionState.isEmulationRunning) {
            return true
        }

        _uiState.update {
            it.copy(
                isEmulationRunning = true,
                error = errorMessage,
                message = null,
            )
        }
        return false
    }

    private fun refresh(appSettings: AppSettings) {
        val settings = appSettings.storageSettings
        val activeRoot = CemuDataStorage.getActiveRoot()
            ?: CemuDataStorage.resolveCurrentRoot(appContext, settings)
        val configuredRoot = CemuDataStorage.resolveCurrentRoot(appContext, settings)
        val restartRequiredForStorage = runCatching {
            activeRoot.canonicalFile != configuredRoot.canonicalFile
        }.getOrDefault(activeRoot.absolutePath != configuredRoot.absolutePath)
        val pendingDeletePath = settings.pendingDeleteDataRootPath
            ?.takeIf {
                runCatching { File(it).canonicalFile != activeRoot.canonicalFile }
                    .getOrDefault(File(it).absolutePath != activeRoot.absolutePath)
            }
        val hasCustomRoot = settings.customRootUri != null
        @Suppress("DEPRECATION")
        val isSaveMirrorDirty = settings.isSaveMirrorDirty || settings.isMirrorDirty
        _uiState.update {
            it.copy(
                activeLocation = activeRoot.absolutePath,
                configuredLocation = configuredRoot.absolutePath.takeIf { restartRequiredForStorage },
                customRootUri = settings.customRootUri,
                mirrorRootPath = settings.mirrorRootPath,
                pendingDeleteRootPath = pendingDeletePath,
                isSaveMirrorDirty = isSaveMirrorDirty,
                restartRequiredForStorage = restartRequiredForStorage,
                storageStatus = when {
                    restartRequiredForStorage -> "Restart Cemu to finish changing the storage location."
                    hasCustomRoot -> "Game saves sync automatically with the custom folder."
                    else -> "Using default app storage."
                },
                saveSyncSummary = if (hasCustomRoot) {
                    "Game saves sync automatically"
                } else {
                    "Choose a custom folder to enable save sync"
                },
                manualSyncSummary = if (hasCustomRoot) {
                    "Settings, controller profiles, and keys sync only when you tap import or export"
                } else {
                    "Choose a custom folder to enable manual import and export"
                },
                lastSaveSync = formatTimestamp(settings.lastSaveSyncAtMillis),
                lastManualSync = formatTimestamp(settings.lastManualSyncAtMillis),
                isEmulationRunning = EmulationSessionState.isEmulationRunning,
                error = settings.lastStorageError ?: it.error,
            )
        }
    }

    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null) {
            return "Never"
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestamp))
    }
}
