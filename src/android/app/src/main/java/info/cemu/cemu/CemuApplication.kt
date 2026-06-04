package info.cemu.cemu

import android.app.Application
import android.app.Activity
import android.os.Bundle
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.storage.CemuSaveSyncManager
import info.cemu.cemu.common.storage.CemuDataStorage
import info.cemu.cemu.common.ui.localization.setLanguage
import info.cemu.cemu.common.ui.localization.setTranslations
import info.cemu.cemu.emulation.EmulationSessionState
import info.cemu.cemu.nativeinterface.NativeActiveSettings.initializeActiveSettings
import info.cemu.cemu.nativeinterface.NativeActiveSettings.setInternalDir
import info.cemu.cemu.nativeinterface.NativeActiveSettings.setNativeLibDir
import info.cemu.cemu.nativeinterface.NativeEmulation.initializeEmulation
import info.cemu.cemu.nativeinterface.NativeEmulation.setDPI
import info.cemu.cemu.nativeinterface.NativeFiles
import info.cemu.cemu.nativeinterface.NativeGraphicPacks.refreshGraphicPacks
import info.cemu.cemu.nativeinterface.NativeLogging.crashLog
import info.cemu.cemu.nativeinterface.NativeSwkbd.initializeSwkbd
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicInteger

class CemuApplication : Application() {
    private lateinit var cemuUserFolder: File
    private val startedActivities = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()

        configureExceptionHandler()

        AppSettingsStore.init(this)

        registerActivityLifecycleCallbacks(storageSyncLifecycleCallbacks)

        NativeFiles.initialize(contentResolver)

        initializeTranslations()

        initializeCemu()

        saveDataFiles()
    }

    private fun initializeTranslations() {
        setTranslations(this)

        val language = runBlocking {
            AppSettingsStore.dataStore.data.map { it.guiSettings.language }.first()
        }

        setLanguage(language, this)
    }

    private fun saveDataFiles() {
        val dataFolder = cemuDataFolder

        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            return
        }

        val hashFileName = "hash.txt"
        val hashFile = dataFolder.resolve(hashFileName)
        val oldHash = if (hashFile.isFile) hashFile.readText() else "invalid"

        val newHash = try {
            assets.open(hashFileName).use { it.reader().readText() }
        } catch (_: IOException) {
            return
        }

        if (oldHash == newHash) {
            return
        }

        dataFolder.deleteRecursively()
        dataFolder.mkdirs()
        dataFolder.resolve(hashFileName).writeText(newHash)

        fun traverseAssets(path: String = ""): Iterator<String> = iterator {
            val assetFiles = assets.list(path) ?: return@iterator

            if (assetFiles.isEmpty()) {
                yield(path)
            }

            for (assetFile in assetFiles) {
                val assetPath = path + (if (path == "") "" else "/") + assetFile
                for (file in traverseAssets(assetPath)) {
                    yield(file)
                }
            }
        }

        val filePatterns = arrayOf(
            Pattern.compile("gameProfiles/.*"),
            Pattern.compile("resources/.*"),
        )

        fun isFileValid(file: String): Boolean {
            return filePatterns.any { pattern -> pattern.matcher(file).matches() }
        }

        for (assetFile in traverseAssets()) {
            if (!isFileValid(assetFile)) {
                continue
            }

            val outFile = dataFolder.resolve(assetFile)
            outFile.parentFile?.mkdirs()
            assets.open(assetFile)
                .use { asset -> outFile.outputStream().use { out -> asset.copyTo(out) } }
        }
    }

    private fun configureExceptionHandler() {
        if (DefaultUncaughtExceptionHandler == null) {
            DefaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        }
        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, exception: Throwable ->
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            exception.printStackTrace(printWriter)
            val stacktrace = stringWriter.toString()
            crashLog(stacktrace)
            DefaultUncaughtExceptionHandler!!.uncaughtException(
                thread,
                exception,
            )
        }
    }

    private fun initializeCemu() {
        cemuUserFolder = runBlocking { CemuDataStorage.prepareActiveRoot(this@CemuApplication) }
        CemuDataStorage.setActiveRoot(cemuUserFolder)

        val displayMetrics = resources.displayMetrics
        setDPI(displayMetrics.density)
        initializeActiveSettings(
            userDataPath = cemuUserFolder.absolutePath,
            configPath = cemuUserFolder.absolutePath,
            dataPath = cemuDataFolder.absolutePath,
            cachePath = cemuUserFolder.absolutePath,
        )
        setNativeLibDir(applicationInfo.nativeLibraryDir)
        setInternalDir(dataDir.absolutePath)
        initializeEmulation()
        initializeSwkbd()
        refreshGraphicPacks()
    }

    private val cemuDataFolder: File
        get() = cemuUserFolder.resolve("data")

    private val storageSyncLifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities.incrementAndGet()
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) } == 0 &&
                !EmulationSessionState.isEmulationRunning
            ) {
                CemuSaveSyncManager.flushNow(activity.applicationContext)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        init {
            System.loadLibrary("CemuAndroid")
        }

        private var DefaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    }
}
