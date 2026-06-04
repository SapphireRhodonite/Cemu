package info.cemu.cemu.common.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import info.cemu.cemu.common.android.context.internalFolder
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.StorageSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class CemuCustomRootMigrationResult(
    val previousRootPath: String,
    val mirrorRootPath: String,
)

data class CemuDefaultRootMigrationResult(
    val previousMirrorRootPath: String?,
    val defaultRootPath: String,
)

object CemuDataStorage {
    private const val ROOT_MARKER_FILE = ".cemu-root"
    private const val STAGING_PREFIX = ".cemu-staging-"
    private const val BACKUP_PREFIX = ".cemu-backup-"
    private const val SAVE_RELATIVE_PATH = "mlc01/usr/save"
    private val MANUAL_DATA_EXCLUDED_PATHS = setOf(
        "dump",
        "dumps",
        "graphicPacks",
        "log",
        "logs",
        "mlc01",
        "shaderCache",
    )
    private val MANUAL_DATA_TOP_LEVEL_NAMES = setOf(
        "controllerProfiles",
        "keys.txt",
        "log.txt",
        "settings.xml",
    )

    @Volatile
    private var activeRootPath: String? = null

    fun setActiveRoot(root: File) {
        activeRootPath = root.absolutePath
    }

    fun getActiveRoot(): File? = activeRootPath?.let(::File)

    fun legacyDefaultRoot(context: Context): File = context.internalFolder()

    fun defaultMirrorRoot(context: Context): File = File(context.noBackupFilesDir, "cemu-saf-mirror")

    fun resolveCurrentRoot(context: Context, storageSettings: StorageSettings): File {
        val customRootUri = storageSettings.customRootUri
        if (!customRootUri.isNullOrBlank()) {
            return (storageSettings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context))
                .also { it.mkdirs() }
        }

        val configuredPath = storageSettings.dataRootPath
        if (!configuredPath.isNullOrBlank()) {
            val configuredRoot = File(configuredPath)
            if (isUsableRoot(configuredRoot)) {
                return configuredRoot
            }
        }
        return legacyDefaultRoot(context).also { it.mkdirs() }
    }

    fun saveRoot(root: File): File = root.resolve(SAVE_RELATIVE_PATH)

    suspend fun prepareActiveRoot(context: Context): File = withContext(Dispatchers.IO) {
        var storageSettings = AppSettingsStore.dataStore.data.first().storageSettings
        val customRootUri = storageSettings.customRootUri
        if (customRootUri.isNullOrBlank()) {
            return@withContext resolveCurrentRoot(context, storageSettings)
        }

        val mirrorRoot = (storageSettings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context))
            .canonicalFile
        if (!mirrorRoot.exists() && !mirrorRoot.mkdirs()) {
            setLastStorageError("Unable to create custom root mirror: ${mirrorRoot.absolutePath}")
            return@withContext mirrorRoot
        }

        @Suppress("DEPRECATION")
        val migratedDirty = storageSettings.isSaveMirrorDirty || storageSettings.isMirrorDirty
        if (storageSettings.mirrorRootPath != mirrorRoot.absolutePath ||
            storageSettings.isSaveMirrorDirty != migratedDirty
        ) {
            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        mirrorRootPath = mirrorRoot.absolutePath,
                        isSaveMirrorDirty = migratedDirty,
                    )
                )
            }
            storageSettings = storageSettings.copy(
                mirrorRootPath = mirrorRoot.absolutePath,
                isSaveMirrorDirty = migratedDirty,
            )
        }

        val documentRoot = documentRootOrNull(context, customRootUri)
        if (documentRoot == null) {
            setLastStorageError("Custom root is not available. Cemu is using the local mirror until SAF access returns.")
            return@withContext mirrorRoot
        }
        cleanupDocumentScratchDirectories(documentRoot)

        runCatching {
            if (storageSettings.isSaveMirrorDirty) {
                syncSavesToCustomRoot(context, force = true)
            } else {
                importSavesFromCustomRoot(context)
            }
        }.onFailure { throwable ->
            setLastStorageError(
                throwable.message ?: "Save sync failed. Cemu is using the local mirror."
            )
        }

        mirrorRoot
    }

    suspend fun migrateToCustomRoot(
        context: Context,
        customRootUri: String,
    ): CemuCustomRootMigrationResult = withContext(Dispatchers.IO) {
        val documentRoot = documentRootOrNull(context, customRootUri)
            ?: throw IllegalStateException("Selected custom root is not available.")
        require(documentRoot.canRead() && documentRoot.canWrite()) {
            "Selected custom root must allow read and write access."
        }
        cleanupDocumentScratchDirectories(documentRoot)

        val rootState = classifyDocumentRoot(documentRoot)
        require(rootState == DocumentRootState.EMPTY || rootState == DocumentRootState.CEMU_ROOT) {
            "Selected folder is not empty and does not look like a Cemu data root."
        }

        val settings = AppSettingsStore.dataStore.data.first().storageSettings
        val configuredRoot = resolveCurrentRoot(context, settings)
        val currentRoot = getActiveRoot() ?: configuredRoot
        val mirrorRoot = defaultMirrorRoot(context).canonicalFile
        require(!isNestedPath(currentRoot.canonicalFile, mirrorRoot)) {
            "Custom root mirror cannot be nested inside the current data root."
        }

        if (rootState == DocumentRootState.EMPTY) {
            syncFileRootToFileRoot(currentRoot, mirrorRoot)
            ensureDocumentRootMarker(context.contentResolver, documentRoot)
            syncFileDirectoryToDocumentDirectory(
                resolver = context.contentResolver,
                source = saveRoot(mirrorRoot),
                targetParent = ensureDocumentDirectory(documentRoot, "mlc01/usr"),
                targetName = "save",
            )
        } else {
            syncFileRootToFileRoot(currentRoot, mirrorRoot)
            importSavesFromDocumentRoot(context, documentRoot, mirrorRoot)
        }

        AppSettingsStore.dataStore.updateData {
            it.copy(
                storageSettings = it.storageSettings.copy(
                    dataRootPath = null,
                    customRootUri = customRootUri,
                    mirrorRootPath = mirrorRoot.absolutePath,
                    pendingDeleteDataRootPath = currentRoot.absolutePath,
                    isSaveMirrorDirty = false,
                    lastSaveSyncAtMillis = System.currentTimeMillis(),
                    lastStorageError = null,
                )
            )
        }

        CemuCustomRootMigrationResult(
            previousRootPath = currentRoot.absolutePath,
            mirrorRootPath = mirrorRoot.absolutePath,
        )
    }

    suspend fun migrateToDefaultRoot(context: Context): CemuDefaultRootMigrationResult =
        withContext(Dispatchers.IO) {
            val settings = AppSettingsStore.dataStore.data.first().storageSettings
            if (!settings.customRootUri.isNullOrBlank()) {
                syncSavesToCustomRoot(context, force = true)
            }

            val currentRoot = getActiveRoot() ?: resolveCurrentRoot(context, settings)
            val defaultRoot = legacyDefaultRoot(context).canonicalFile
            if (currentRoot.canonicalFile != defaultRoot) {
                syncFileRootToFileRoot(currentRoot, defaultRoot)
            }

            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        dataRootPath = null,
                        customRootUri = null,
                        mirrorRootPath = null,
                        pendingDeleteDataRootPath = settings.mirrorRootPath,
                        isSaveMirrorDirty = false,
                        lastStorageError = null,
                    )
                )
            }

            CemuDefaultRootMigrationResult(
                previousMirrorRootPath = settings.mirrorRootPath,
                defaultRootPath = defaultRoot.absolutePath,
            )
        }

    suspend fun markSavesDirty(context: Context) = withContext(Dispatchers.IO) {
        AppSettingsStore.dataStore.updateData {
            val storageSettings = it.storageSettings
            if (storageSettings.customRootUri.isNullOrBlank() || storageSettings.isSaveMirrorDirty) {
                it
            } else {
                val mirrorRootPath = storageSettings.mirrorRootPath
                    ?: defaultMirrorRoot(context).absolutePath
                it.copy(
                    storageSettings = storageSettings.copy(
                        mirrorRootPath = mirrorRootPath,
                        isSaveMirrorDirty = true,
                    )
                )
            }
        }
    }

    suspend fun syncSavesToCustomRoot(
        context: Context,
        force: Boolean = false,
        clearDirty: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = AppSettingsStore.dataStore.data.first().storageSettings
        val customRootUri = settings.customRootUri ?: return@withContext true
        if (!force && !settings.isSaveMirrorDirty) {
            return@withContext true
        }

        val mirrorRoot = settings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context)
        val documentRoot = documentRootOrNull(context, customRootUri)
        if (documentRoot == null) {
            setLastStorageError("Custom root is not available. Save data remains in the local mirror.")
            return@withContext false
        }

        runCatching {
            ensureDocumentRootMarker(context.contentResolver, documentRoot)
            syncFileDirectoryToDocumentDirectory(
                resolver = context.contentResolver,
                source = saveRoot(mirrorRoot),
                targetParent = ensureDocumentDirectory(documentRoot, "mlc01/usr"),
                targetName = "save",
            )
            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        isSaveMirrorDirty = if (clearDirty) false else it.storageSettings.isSaveMirrorDirty,
                        lastSaveSyncAtMillis = System.currentTimeMillis(),
                        lastStorageError = null,
                    )
                )
            }
            true
        }.getOrElse { throwable ->
            setLastStorageError(throwable.message ?: "Unable to sync saves to the custom root.")
            false
        }
    }

    suspend fun exportSaveChangesToCustomRoot(
        context: Context,
        changedSaveRelativePaths: Set<String>,
        clearDirty: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = AppSettingsStore.dataStore.data.first().storageSettings
        val customRootUri = settings.customRootUri ?: return@withContext true
        val mirrorRoot = settings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context)
        val documentRoot = documentRootOrNull(context, customRootUri)
        if (documentRoot == null) {
            setLastStorageError("Custom root is not available. Save data remains in the local mirror.")
            return@withContext false
        }

        runCatching {
            ensureDocumentRootMarker(context.contentResolver, documentRoot)
            val documentSaveRoot = ensureDocumentDirectory(documentRoot, SAVE_RELATIVE_PATH)
            val mirrorSaveRoot = saveRoot(mirrorRoot)
            changedSaveRelativePaths
                .map { it.trim('/').replace(File.separatorChar, '/') }
                .filter { it.isNotBlank() && !shouldIgnoreRelativePath(it) }
                .forEach { relativePath ->
                    val source = mirrorSaveRoot.resolve(relativePath)
                    if (source.exists()) {
                        copyFilePathToDocument(
                            resolver = context.contentResolver,
                            source = source,
                            sourceRoot = mirrorSaveRoot,
                            targetRoot = documentSaveRoot,
                        )
                    } else {
                        deleteDocumentPath(documentSaveRoot, relativePath)
                    }
                }

            val currentDocumentSaveRoot = findDocumentPath(documentRoot, SAVE_RELATIVE_PATH)
            if (currentDocumentSaveRoot == null ||
                directoryFingerprint(mirrorSaveRoot) != directoryFingerprint(currentDocumentSaveRoot, context.contentResolver)
            ) {
                syncFileDirectoryToDocumentDirectory(
                    resolver = context.contentResolver,
                    source = mirrorSaveRoot,
                    targetParent = ensureDocumentDirectory(documentRoot, "mlc01/usr"),
                    targetName = "save",
                )
            }

            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        isSaveMirrorDirty = if (clearDirty) false else it.storageSettings.isSaveMirrorDirty,
                        lastSaveSyncAtMillis = System.currentTimeMillis(),
                        lastStorageError = null,
                    )
                )
            }
            true
        }.getOrElse { throwable ->
            setLastStorageError(throwable.message ?: "Unable to sync save changes to the custom root.")
            false
        }
    }

    suspend fun importSavesFromCustomRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = AppSettingsStore.dataStore.data.first().storageSettings
        val customRootUri = settings.customRootUri ?: return@withContext true
        val mirrorRoot = settings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context)
        val documentRoot = documentRootOrNull(context, customRootUri)
        if (documentRoot == null) {
            setLastStorageError("Custom root is not available. Unable to import saves.")
            return@withContext false
        }

        runCatching {
            importSavesFromDocumentRoot(context, documentRoot, mirrorRoot)
            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        isSaveMirrorDirty = false,
                        lastSaveSyncAtMillis = System.currentTimeMillis(),
                        lastStorageError = null,
                    )
                )
            }
            true
        }.getOrElse { throwable ->
            setLastStorageError(throwable.message ?: "Unable to import saves from the custom root.")
            false
        }
    }

    suspend fun exportManualData(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = AppSettingsStore.dataStore.data.first().storageSettings
        val customRootUri = settings.customRootUri ?: return@withContext true
        val mirrorRoot = settings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context)
        val documentRoot = documentRootOrNull(context, customRootUri)
        if (documentRoot == null) {
            setLastStorageError("Custom root is not available. Unable to export manual data.")
            return@withContext false
        }

        runCatching {
            cleanupDocumentScratchDirectories(documentRoot)
            syncManualFileRootToDocumentRoot(context.contentResolver, mirrorRoot, documentRoot)
            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        lastManualSyncAtMillis = System.currentTimeMillis(),
                        lastStorageError = null,
                    )
                )
            }
            true
        }.getOrElse { throwable ->
            setLastStorageError(throwable.message ?: "Unable to export manual data.")
            false
        }
    }

    suspend fun importManualData(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = AppSettingsStore.dataStore.data.first().storageSettings
        val customRootUri = settings.customRootUri ?: return@withContext true
        val mirrorRoot = settings.mirrorRootPath?.let(::File) ?: defaultMirrorRoot(context)
        val documentRoot = documentRootOrNull(context, customRootUri)
        if (documentRoot == null) {
            setLastStorageError("Custom root is not available. Unable to import manual data.")
            return@withContext false
        }

        runCatching {
            cleanupDocumentScratchDirectories(documentRoot)
            syncManualDocumentRootToFileRoot(context, documentRoot, mirrorRoot)
            AppSettingsStore.dataStore.updateData {
                it.copy(
                    storageSettings = it.storageSettings.copy(
                        lastManualSyncAtMillis = System.currentTimeMillis(),
                        lastStorageError = null,
                    )
                )
            }
            true
        }.getOrElse { throwable ->
            setLastStorageError(throwable.message ?: "Unable to import manual data.")
            false
        }
    }

    suspend fun deleteRoot(path: String): Boolean = withContext(Dispatchers.IO) {
        val root = File(path)
        !root.exists() || root.deleteRecursively()
    }

    private fun importSavesFromDocumentRoot(context: Context, documentRoot: DocumentFile, mirrorRoot: File) {
        val documentSaveRoot = findDocumentPath(documentRoot, SAVE_RELATIVE_PATH)
        if (documentSaveRoot == null) {
            saveRoot(mirrorRoot).mkdirs()
            return
        }
        syncDocumentDirectoryToFileRoot(
            context = context,
            sourceRoot = documentSaveRoot,
            target = saveRoot(mirrorRoot),
        )
    }

    private suspend fun setLastStorageError(message: String?) {
        AppSettingsStore.dataStore.updateData {
            it.copy(storageSettings = it.storageSettings.copy(lastStorageError = message))
        }
    }

    private fun documentRootOrNull(context: Context, customRootUri: String): DocumentFile? {
        val uri = Uri.parse(customRootUri)
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) {
            return null
        }
        return DocumentFile.fromTreeUri(context, uri)
            ?.takeIf { it.exists() && it.isDirectory && it.canRead() && it.canWrite() }
    }

    private fun classifyDocumentRoot(root: DocumentFile): DocumentRootState {
        val children = root.listFiles()
            .filterNot {
                it.name?.startsWith(STAGING_PREFIX) == true ||
                    it.name?.startsWith(BACKUP_PREFIX) == true
            }
        if (children.isEmpty()) {
            return DocumentRootState.EMPTY
        }
        return if (isCemuRoot(root)) DocumentRootState.CEMU_ROOT else DocumentRootState.OTHER
    }

    private fun isCemuRoot(root: DocumentFile): Boolean {
        return root.findFile(ROOT_MARKER_FILE)?.isFile == true ||
            root.findFile("mlc01")?.isDirectory == true ||
            root.findFile("settings.xml")?.isFile == true
    }

    private fun ensureDocumentRootMarker(resolver: ContentResolver, root: DocumentFile) {
        createOrReplaceDocumentFile(root, ROOT_MARKER_FILE).openOutputStream(resolver).use { output ->
            output.write("Cemu Android custom root\n".encodeToByteArray())
        }
    }

    private fun syncFileDirectoryToDocumentDirectory(
        resolver: ContentResolver,
        source: File,
        targetParent: DocumentFile,
        targetName: String,
    ) {
        if (!source.exists()) {
            source.mkdirs()
        }
        require(source.isDirectory) { "Source is not a directory: ${source.absolutePath}" }

        val staging = createDocumentDirectory(targetParent, "$STAGING_PREFIX$targetName-${System.currentTimeMillis()}")
        var backup: DocumentFile? = null
        var movedExistingTargetToBackup = false
        var installedNewTarget = false
        try {
            copyFileDirectoryToDocument(resolver, source, staging)
            verifyEquivalent(source, staging, resolver)
            val backupName = "$BACKUP_PREFIX$targetName-${System.currentTimeMillis()}"
            backup = targetParent.findFile(targetName)?.let { existingTarget ->
                if (!existingTarget.renameTo(backupName)) {
                    throw IllegalStateException("Unable to stage existing SAF directory: $targetName")
                }
                movedExistingTargetToBackup = true
                targetParent.findFile(backupName) ?: existingTarget
            }
            if (!staging.renameTo(targetName)) {
                val target = createDocumentDirectory(targetParent, targetName)
                copyDocumentDirectoryToDocument(resolver, staging, target)
                verifyEquivalent(source, target, resolver)
                staging.deleteRecursively()
                installedNewTarget = true
            } else {
                installedNewTarget = true
                val target = targetParent.findFile(targetName)
                    ?: throw IllegalStateException("Unable to find synced directory: $targetName")
                verifyEquivalent(source, target, resolver)
            }
            backup?.deleteRecursively()
        } catch (throwable: Throwable) {
            if (movedExistingTargetToBackup || installedNewTarget) {
                targetParent.findFile(targetName)?.deleteRecursively()
            }
            if (movedExistingTargetToBackup) {
                val backupTarget = backup ?: targetParent.listFiles()
                    .firstOrNull { it.name?.startsWith("$BACKUP_PREFIX$targetName-") == true }
                backupTarget?.renameTo(targetName)
            }
            staging.deleteRecursively()
            throw throwable
        }
    }

    private fun syncDocumentDirectoryToFileRoot(
        context: Context,
        sourceRoot: DocumentFile,
        target: File,
    ) {
        val targetParent = target.parentFile ?: throw IllegalArgumentException("Target root has no parent")
        if (!targetParent.exists() && !targetParent.mkdirs()) {
            throw IllegalStateException("Unable to create target parent: ${targetParent.absolutePath}")
        }

        val staging = File(targetParent, "${target.name}.staging-${System.currentTimeMillis()}")
        val backup = File(targetParent, "${target.name}.backup-${System.currentTimeMillis()}")
        staging.deleteRecursively()
        backup.deleteRecursively()
        val hadExistingTarget = target.exists()

        try {
            copyDocumentDirectoryToFile(context, sourceRoot, staging)
            verifyEquivalent(sourceRoot, staging, context.contentResolver)
            replaceFileRoot(staging, target, backup)
            verifyEquivalent(sourceRoot, target, context.contentResolver)
        } catch (throwable: Throwable) {
            staging.deleteRecursively()
            if (backup.exists()) {
                target.deleteRecursively()
                backup.renameTo(target)
            } else if (!hadExistingTarget) {
                target.deleteRecursively()
            }
            throw throwable
        } finally {
            backup.deleteRecursively()
        }
    }

    private fun syncFileRootToFileRoot(source: File, target: File) {
        val targetParent = target.parentFile ?: throw IllegalArgumentException("Target root has no parent")
        if (!targetParent.exists() && !targetParent.mkdirs()) {
            throw IllegalStateException("Unable to create target parent: ${targetParent.absolutePath}")
        }

        val staging = File(targetParent, "${target.name}.staging-${System.currentTimeMillis()}")
        val backup = File(targetParent, "${target.name}.backup-${System.currentTimeMillis()}")
        staging.deleteRecursively()
        backup.deleteRecursively()
        val hadExistingTarget = target.exists()

        try {
            copyFileDirectoryToFile(source, staging)
            verifyEquivalent(source, staging)
            replaceFileRoot(staging, target, backup)
            verifyEquivalent(source, target)
        } catch (throwable: Throwable) {
            staging.deleteRecursively()
            if (backup.exists()) {
                target.deleteRecursively()
                backup.renameTo(target)
            } else if (!hadExistingTarget) {
                target.deleteRecursively()
            }
            throw throwable
        } finally {
            backup.deleteRecursively()
        }
    }

    private fun syncManualFileRootToDocumentRoot(
        resolver: ContentResolver,
        source: File,
        targetRoot: DocumentFile,
    ) {
        if (!source.exists()) {
            source.mkdirs()
        }
        copyFileDirectoryToDocument(
            resolver = resolver,
            sourceRoot = source,
            targetRoot = targetRoot,
            includedRelativePath = ::isManualDataRelativePath,
        )
        ensureDocumentRootMarker(resolver, targetRoot)
    }

    private fun syncManualDocumentRootToFileRoot(
        context: Context,
        sourceRoot: DocumentFile,
        targetRoot: File,
    ) {
        val targetParent = targetRoot.parentFile ?: throw IllegalArgumentException("Target root has no parent")
        if (!targetParent.exists() && !targetParent.mkdirs()) {
            throw IllegalStateException("Unable to create target parent: ${targetParent.absolutePath}")
        }

        copyDocumentDirectoryToFile(
            context = context,
            sourceRoot = sourceRoot,
            targetRoot = targetRoot,
            includedRelativePath = ::isManualDataRelativePath,
        )
    }

    private fun replaceFileRoot(staging: File, target: File, backup: File) {
        var movedExistingTargetToBackup = false
        var startedInstallingTarget = false
        try {
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw IllegalStateException("Unable to stage existing target data: ${target.absolutePath}")
                }
                movedExistingTargetToBackup = true
            }
            startedInstallingTarget = true
            if (!staging.renameTo(target)) {
                copyFileDirectoryToFile(staging, target)
                staging.deleteRecursively()
            }
        } catch (throwable: Throwable) {
            if (movedExistingTargetToBackup) {
                target.deleteRecursively()
                backup.renameTo(target)
            } else if (startedInstallingTarget) {
                target.deleteRecursively()
            }
            throw throwable
        }
    }

    private fun copyFilePathToDocument(
        resolver: ContentResolver,
        source: File,
        sourceRoot: File,
        targetRoot: DocumentFile,
    ) {
        val relativePath = source.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
        if (source.isDirectory) {
            syncFileDirectoryToDocumentDirectory(
                resolver = resolver,
                source = source,
                targetParent = ensureDocumentDirectory(targetRoot, relativePath.substringBeforeLast('/', "")),
                targetName = relativePath.substringAfterLast('/'),
            )
        } else {
            val destination = ensureDocumentFile(targetRoot, relativePath)
            source.inputStream().use { input ->
                destination.openOutputStream(resolver).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun copyFileDirectoryToDocument(
        resolver: ContentResolver,
        sourceRoot: File,
        targetRoot: DocumentFile,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        if (!sourceRoot.exists()) {
            sourceRoot.mkdirs()
        }
        sourceRoot.walkTopDown()
            .onEnter { directory ->
                directory == sourceRoot ||
                    shouldCopyRelativePath(
                        relativePath = directory.relativeTo(sourceRoot).path,
                        excludedRelativePath = excludedRelativePath,
                        includedRelativePath = includedRelativePath,
                    )
            }
            .forEach { source ->
                if (source == sourceRoot) {
                    return@forEach
                }
                val relativePath = source.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
                if (!shouldCopyRelativePath(relativePath, excludedRelativePath, includedRelativePath)) {
                    return@forEach
                }
                if (source.isDirectory) {
                    ensureDocumentDirectory(targetRoot, relativePath)
                } else {
                    val destination = ensureDocumentFile(targetRoot, relativePath)
                    source.inputStream().use { input ->
                        destination.openOutputStream(resolver).use { output -> input.copyTo(output) }
                    }
                }
            }
    }

    private fun copyDocumentDirectoryToDocument(
        resolver: ContentResolver,
        sourceRoot: DocumentFile,
        targetRoot: DocumentFile,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        sourceRoot.listFiles().forEach { source ->
            copyDocumentChildToDocument(
                resolver = resolver,
                source = source,
                targetRoot = targetRoot,
                relativePath = source.name ?: return@forEach,
                excludedRelativePath = excludedRelativePath,
                includedRelativePath = includedRelativePath,
            )
        }
    }

    private fun copyDocumentChildToDocument(
        resolver: ContentResolver,
        source: DocumentFile,
        targetRoot: DocumentFile,
        relativePath: String,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        if (!shouldCopyRelativePath(relativePath, excludedRelativePath, includedRelativePath)) {
            return
        }
        if (source.isDirectory) {
            ensureDocumentDirectory(targetRoot, relativePath)
            source.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                copyDocumentChildToDocument(
                    resolver = resolver,
                    source = child,
                    targetRoot = targetRoot,
                    relativePath = "$relativePath/$childName",
                    excludedRelativePath = excludedRelativePath,
                    includedRelativePath = includedRelativePath,
                )
            }
        } else if (source.isFile) {
            val destination = ensureDocumentFile(targetRoot, relativePath)
            source.openInputStream(resolver).use { input ->
                destination.openOutputStream(resolver).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun copyDocumentDirectoryToFile(
        context: Context,
        sourceRoot: DocumentFile,
        targetRoot: File,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        if (!targetRoot.exists() && !targetRoot.mkdirs()) {
            throw IllegalStateException("Unable to create target directory: ${targetRoot.absolutePath}")
        }
        sourceRoot.listFiles().forEach { source ->
            val sourceName = source.name ?: return@forEach
            copyDocumentChildToFile(
                context = context,
                source = source,
                destination = targetRoot.resolve(sourceName),
                relativePath = sourceName,
                excludedRelativePath = excludedRelativePath,
                includedRelativePath = includedRelativePath,
            )
        }
    }

    private fun copyDocumentChildToFile(
        context: Context,
        source: DocumentFile,
        destination: File,
        relativePath: String,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        if (!shouldCopyRelativePath(relativePath, excludedRelativePath, includedRelativePath)) {
            return
        }
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException("Unable to create directory: ${destination.absolutePath}")
            }
            source.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                copyDocumentChildToFile(
                    context = context,
                    source = child,
                    destination = destination.resolve(childName),
                    relativePath = "$relativePath/$childName",
                    excludedRelativePath = excludedRelativePath,
                    includedRelativePath = includedRelativePath,
                )
            }
        } else if (source.isFile) {
            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(source.uri).use { input ->
                requireNotNull(input) { "Unable to read ${source.name}" }
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun copyFileDirectoryToFile(
        sourceRoot: File,
        targetRoot: File,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        if (!sourceRoot.exists()) {
            sourceRoot.mkdirs()
        }
        sourceRoot.walkTopDown()
            .onEnter { directory ->
                directory == sourceRoot ||
                    shouldCopyRelativePath(
                        relativePath = directory.relativeTo(sourceRoot).path,
                        excludedRelativePath = excludedRelativePath,
                        includedRelativePath = includedRelativePath,
                    )
            }
            .forEach { source ->
                if (source == sourceRoot) {
                    return@forEach
                }
                val relativePath = source.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
                if (!shouldCopyRelativePath(relativePath, excludedRelativePath, includedRelativePath)) {
                    return@forEach
                }
                val destination = targetRoot.resolve(relativePath)
                if (source.isDirectory) {
                    if (!destination.exists() && !destination.mkdirs()) {
                        throw IllegalStateException("Unable to create directory: ${destination.absolutePath}")
                    }
                } else {
                    destination.parentFile?.mkdirs()
                    source.inputStream().use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
    }

    private fun ensureDocumentDirectory(root: DocumentFile, relativePath: String): DocumentFile {
        if (relativePath.isBlank()) {
            return root
        }
        return relativePath.split(File.separatorChar, '/')
            .filter { it.isNotBlank() }
            .fold(root) { parent, name ->
                val existing = parent.findFile(name)
                when {
                    existing?.isDirectory == true -> existing
                    existing != null -> {
                        existing.deleteRecursively()
                        createDocumentDirectory(parent, name)
                    }
                    else -> createDocumentDirectory(parent, name)
                }
            }
    }

    private fun ensureDocumentFile(root: DocumentFile, relativePath: String): DocumentFile {
        val parts = relativePath.split(File.separatorChar, '/').filter { it.isNotBlank() }
        val parent = parts.dropLast(1).fold(root) { current, name ->
            val existing = current.findFile(name)
            when {
                existing?.isDirectory == true -> existing
                existing != null -> {
                    existing.deleteRecursively()
                    createDocumentDirectory(current, name)
                }
                else -> createDocumentDirectory(current, name)
            }
        }
        return createOrReplaceDocumentFile(parent, parts.last())
    }

    private fun findDocumentPath(root: DocumentFile, relativePath: String): DocumentFile? {
        return relativePath.split(File.separatorChar, '/')
            .filter { it.isNotBlank() }
            .fold(root as DocumentFile?) { current, name ->
                current?.findFile(name)
            }
    }

    private fun deleteDocumentPath(root: DocumentFile, relativePath: String): Boolean {
        return findDocumentPath(root, relativePath)?.deleteRecursively() ?: true
    }

    private fun createDocumentDirectory(parent: DocumentFile, name: String): DocumentFile =
        parent.createDirectory(name)
            ?: throw IllegalStateException("Unable to create directory: $name")

    private fun createOrReplaceDocumentFile(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing?.isFile == true) {
            return existing
        }
        existing?.deleteRecursively()
        return parent.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("Unable to create file: $name")
    }

    private fun DocumentFile.openInputStream(resolver: ContentResolver): InputStream {
        return requireNotNull(resolver.openInputStream(uri)) {
            "Unable to open input stream for ${name ?: uri}"
        }
    }

    private fun DocumentFile.openOutputStream(resolver: ContentResolver) =
        requireNotNull(resolver.openOutputStream(uri, "wt")) {
            "Unable to open output stream for ${name ?: uri}"
        }

    private fun verifyEquivalent(
        source: File,
        target: File,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        require(
            directoryFingerprint(source, excludedRelativePath, includedRelativePath) ==
                directoryFingerprint(target, excludedRelativePath, includedRelativePath)
        ) {
            "Migration verification failed."
        }
    }

    private fun verifyEquivalent(
        source: File,
        target: DocumentFile,
        resolver: ContentResolver,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        require(
            directoryFingerprint(source, excludedRelativePath, includedRelativePath) ==
                directoryFingerprint(target, resolver, excludedRelativePath, includedRelativePath)
        ) {
            "Migration verification failed."
        }
    }

    private fun verifyEquivalent(
        source: DocumentFile,
        target: File,
        resolver: ContentResolver,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        require(
            directoryFingerprint(source, resolver, excludedRelativePath, includedRelativePath) ==
                directoryFingerprint(target, excludedRelativePath, includedRelativePath)
        ) {
            "Migration verification failed."
        }
    }

    private fun verifyEquivalent(
        source: DocumentFile,
        target: DocumentFile,
        resolver: ContentResolver,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        require(
            directoryFingerprint(source, resolver, excludedRelativePath, includedRelativePath) ==
                directoryFingerprint(target, resolver, excludedRelativePath, includedRelativePath)
        ) {
            "Migration verification failed."
        }
    }

    private fun directoryFingerprint(
        root: File,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!root.exists()) {
            return digest.digest().toHex()
        }
        root.walkTopDown()
            .onEnter { directory ->
                directory == root ||
                    shouldCopyRelativePath(
                        relativePath = directory.relativeTo(root).path,
                        excludedRelativePath = excludedRelativePath,
                        includedRelativePath = includedRelativePath,
                    )
            }
            .filter { it.isFile }
            .map { it to it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filterNot { (_, relativePath) ->
                !shouldCopyRelativePath(relativePath, excludedRelativePath, includedRelativePath)
            }
            .sortedBy { (_, relativePath) -> relativePath }
            .forEach { (file, relativePath) ->
                digest.update(relativePath.encodeToByteArray())
                digest.update(0.toByte())
                file.inputStream().use { input -> digestInput(input, digest) }
                digest.update(0.toByte())
            }
        return digest.digest().toHex()
    }

    private fun directoryFingerprint(
        root: DocumentFile,
        resolver: ContentResolver,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fingerprintDocumentChildren(root, "", digest, resolver, excludedRelativePath, includedRelativePath)
        return digest.digest().toHex()
    }

    private fun fingerprintDocumentChildren(
        root: DocumentFile,
        prefix: String,
        digest: MessageDigest,
        resolver: ContentResolver,
        excludedRelativePath: String? = null,
        includedRelativePath: ((String) -> Boolean)? = null,
    ) {
        root.listFiles()
            .sortedBy { it.name ?: "" }
            .forEach { child ->
                val childName = child.name ?: return@forEach
                val relativePath = if (prefix.isBlank()) childName else "$prefix/$childName"
                if (!shouldCopyRelativePath(relativePath, excludedRelativePath, includedRelativePath)) {
                    return@forEach
                }
                if (child.isDirectory) {
                    fingerprintDocumentChildren(
                        root = child,
                        prefix = relativePath,
                        digest = digest,
                        resolver = resolver,
                        excludedRelativePath = excludedRelativePath,
                        includedRelativePath = includedRelativePath,
                    )
                } else if (child.isFile) {
                    digest.update(relativePath.encodeToByteArray())
                    digest.update(0.toByte())
                    child.openInputStream(resolver).use { input -> digestInput(input, digest) }
                    digest.update(0.toByte())
                }
            }
    }

    private fun digestInput(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }

    private fun DocumentFile.deleteRecursively(): Boolean {
        if (isDirectory) {
            listFiles().forEach { it.deleteRecursively() }
        }
        return delete()
    }

    private fun cleanupDocumentScratchDirectories(root: DocumentFile) {
        root.listFiles()
            .filter { document ->
                document.name?.startsWith(STAGING_PREFIX) == true ||
                    document.name?.startsWith(BACKUP_PREFIX) == true
            }
            .forEach { it.deleteRecursively() }
    }

    private fun isUsableRoot(root: File): Boolean = root.isDirectory && canWriteToRoot(root)

    private fun canWriteToRoot(root: File): Boolean {
        if (!root.exists() && !root.mkdirs()) {
            return false
        }
        if (!root.isDirectory) {
            return false
        }

        val probe = File(root, ".cemu_write_test")
        return try {
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isNestedPath(parent: File, child: File): Boolean {
        val parentPath = parent.absolutePath.trimEnd(File.separatorChar) + File.separator
        return child.absolutePath.startsWith(parentPath)
    }

    private fun shouldIgnoreRelativePath(relativePath: String): Boolean {
        val normalized = relativePath.replace(File.separatorChar, '/')
        return normalized == ROOT_MARKER_FILE ||
            normalized.startsWith("$STAGING_PREFIX") ||
            normalized.contains("/$STAGING_PREFIX") ||
            normalized.startsWith("$BACKUP_PREFIX") ||
            normalized.contains("/$BACKUP_PREFIX")
    }

    private fun shouldCopyRelativePath(
        relativePath: String,
        excludedRelativePath: String?,
        includedRelativePath: ((String) -> Boolean)? = null,
    ): Boolean {
        val normalized = relativePath.trim('/').replace(File.separatorChar, '/')
        return normalized.isNotBlank() &&
            !shouldIgnoreRelativePath(normalized) &&
            !isPathWithin(normalized, excludedRelativePath) &&
            (includedRelativePath == null || includedRelativePath(normalized))
    }

    private fun isManualDataRelativePath(relativePath: String): Boolean {
        val normalized = relativePath.trim('/').replace(File.separatorChar, '/')
        if (MANUAL_DATA_EXCLUDED_PATHS.any { isPathWithin(normalized, it) }) {
            return false
        }
        val topLevelName = normalized.substringBefore('/')
        return topLevelName in MANUAL_DATA_TOP_LEVEL_NAMES
    }

    private fun isPathWithin(relativePath: String, parentPath: String?): Boolean {
        if (parentPath.isNullOrBlank()) {
            return false
        }
        val normalizedRelative = relativePath.trim('/').replace(File.separatorChar, '/')
        val normalizedParent = parentPath.trim('/').replace(File.separatorChar, '/')
        return normalizedRelative == normalizedParent ||
            normalizedRelative.startsWith("$normalizedParent/")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private enum class DocumentRootState {
        EMPTY,
        CEMU_ROOT,
        OTHER,
    }
}
