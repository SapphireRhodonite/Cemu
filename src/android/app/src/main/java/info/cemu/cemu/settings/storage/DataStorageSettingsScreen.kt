package info.cemu.cemu.settings.storage

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.common.ui.components.Button
import info.cemu.cemu.common.ui.components.ScreenContent
import info.cemu.cemu.common.ui.localization.tr

@Composable
fun DataStorageSettingsScreen(
    navigateBack: () -> Unit,
    viewModel: DataStorageSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showDefaultConfirmation by remember { mutableStateOf(false) }
    var showImportManualConfirmation by remember { mutableStateOf(false) }
    val isBlocked = uiState.isWorking || uiState.isEmulationRunning
    val hasCustomRoot = uiState.customRootUri != null

    val customRootLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.chooseCustomRoot(uri.toString())
            } catch (_: SecurityException) {
                viewModel.reportError("Unable to keep SAF permission for the selected custom root.")
            }
        }

    ScreenContent(
        appBarText = tr("Data storage"),
        navigateBack = navigateBack,
    ) {
        InfoLabel(label = "Status", value = uiState.storageStatus)
        InfoLabel(label = "Current storage", value = uiState.activeLocation)
        uiState.configuredLocation?.let { InfoLabel(label = "Storage after restart", value = it) }
        uiState.customRootUri?.let { InfoLabel(label = "Custom folder", value = it) }
        InfoLabel(label = "Save sync", value = uiState.saveSyncSummary)
        InfoLabel(label = "Manual data sync", value = uiState.manualSyncSummary)
        InfoLabel(label = "Last save sync", value = uiState.lastSaveSync)
        InfoLabel(label = "Last manual sync", value = uiState.lastManualSync)

        if (uiState.restartRequiredForStorage) {
            Text(
                text = tr("Restart Cemu to activate the configured storage location."),
                modifier = Modifier.padding(8.dp),
            )
        }

        if (uiState.isSaveMirrorDirty) {
            Text(
                text = tr("Unsynced save changes"),
                modifier = Modifier.padding(8.dp),
            )
        }

        if (uiState.isEmulationRunning) {
            Text(
                text = tr("Close emulation before changing data storage or importing manual data."),
                modifier = Modifier.padding(8.dp),
            )
        }

        if (uiState.isWorking) {
            uiState.workingMessage?.let {
                Text(text = tr(it), modifier = Modifier.padding(8.dp))
            }
            LinearProgressIndicator(modifier = Modifier.padding(8.dp))
        }

        uiState.message?.let {
            Text(text = tr(it), modifier = Modifier.padding(8.dp))
        }

        uiState.error?.let {
            Text(text = tr(it), modifier = Modifier.padding(8.dp))
        }

        Button(
            label = tr("Choose custom root"),
            description = tr("Choose a folder for portable Cemu data. Game saves sync automatically; other data syncs manually."),
            enabled = !isBlocked,
            onClick = {
                customRootLauncher.launch(null)
            },
        )

        if (hasCustomRoot) {
            Button(
                label = tr("Sync saves now"),
                description = tr("Copy the latest game saves to the custom folder."),
                enabled = !uiState.isWorking,
                onClick = {
                    viewModel.syncSavesNow()
                },
            )

            Button(
                label = tr("Import saves from custom root"),
                description = tr("Copy game saves from the custom folder into Cemu."),
                enabled = !isBlocked,
                onClick = {
                    viewModel.importSavesFromCustomRoot()
                },
            )

            Button(
                label = tr("Export manual data"),
                description = tr("Copy settings, controller profiles, and keys to the custom folder. Saves, games, updates, graphic packs, logs, and shader cache are not copied."),
                enabled = !isBlocked,
                onClick = {
                    viewModel.exportManualData()
                },
            )

            Button(
                label = tr("Import manual data"),
                description = tr("Copy settings, controller profiles, and keys from the custom folder. Saves, games, updates, graphic packs, logs, and shader cache are not copied."),
                enabled = !isBlocked,
                onClick = {
                    showImportManualConfirmation = true
                },
            )
        }

        if (hasCustomRoot) {
            Button(
                label = tr("Use default app storage"),
                description = tr("Migrate data back to default app-specific storage. Restart Cemu after migration."),
                enabled = !isBlocked,
                onClick = {
                    showDefaultConfirmation = true
                },
            )
        }

        uiState.pendingDeleteRootPath?.let { pendingDeletePath ->
            Button(
                label = tr("Delete previous data location"),
                description = pendingDeletePath,
                enabled = !isBlocked,
                onClick = {
                    showDeleteConfirmation = true
                },
            )
        }
    }

    if (showImportManualConfirmation) {
        AlertDialog(
            title = { Text(tr("Import manual data")) },
            text = {
                Text(
                    tr("This updates local settings, controller profiles, and keys from the custom folder. Saves, games, updates, graphic packs, logs, account data, and shader cache are not replaced by this action.")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportManualConfirmation = false
                        viewModel.importManualData()
                    },
                ) {
                    Text(tr("Import"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportManualConfirmation = false }) {
                    Text(tr("Cancel"))
                }
            },
            onDismissRequest = { showImportManualConfirmation = false },
        )
    }

    if (showDefaultConfirmation) {
        AlertDialog(
            title = { Text(tr("Use default app storage")) },
            text = {
                Text(
                    tr("Only do this when emulation is closed. Cemu will migrate current local data back to default app storage.")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDefaultConfirmation = false
                        viewModel.useDefaultAppStorage()
                    },
                ) {
                    Text(tr("Use default"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDefaultConfirmation = false }) {
                    Text(tr("Cancel"))
                }
            },
            onDismissRequest = { showDefaultConfirmation = false },
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            title = { Text(tr("Delete previous data location")) },
            text = {
                Text(
                    tr("Only do this after restarting Cemu and confirming the new data location works.")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deletePendingRoot()
                    },
                ) {
                    Text(tr("Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(tr("Cancel"))
                }
            },
            onDismissRequest = { showDeleteConfirmation = false },
        )
    }
}

@Composable
private fun InfoLabel(label: String, value: String) {
    Text(
        text = tr(label),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
    Text(
        text = value,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
