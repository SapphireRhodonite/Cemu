package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.EmulatedUSBDevicesDialog
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurface
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.DEFAULT
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_POSITION
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_SIZE
import info.cemu.cemu.nativeinterface.NativeEmulation
import kotlinx.coroutines.launch

@Composable
fun EmulationScreen(
    gamePath: String,
    setMotionSensorEnabled: (Boolean) -> Unit,
    setInputListeningEnabled: (Boolean) -> Unit,
    onQuit: () -> Unit,
    viewModel: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory, extras = MutableCreationExtras().apply {
            set(EmulationViewModel.LAUNCH_PATH_KEY, gamePath)
        }),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuitConfirmationDialog by remember { mutableStateOf(false) }
    var inputOverlayInputMode by rememberSaveable { mutableStateOf(DEFAULT) }
    var showEmulatedUSBDevices by remember { mutableStateOf(false) }

    val emulationError by viewModel.emulationError.collectAsState()
    val isEmulationInitialized by viewModel.isEmulationInitialized.collectAsState()
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val isInputOverlayVisible by viewModel.isInputOverlayVisible.collectAsState()
    val inputOverlaySettings by viewModel.inputOverlaySettings.collectAsState()


    fun closeDrawer() {
        scope.launch {
            drawerState.close()
        }
    }

    suspend fun toggleMenu() {
        drawerState.apply {
            if (isClosed) {
                open()
            } else {
                close()
            }
        }
    }

    BackHandler {
        if (drawerState.isAnimationRunning) {
            return@BackHandler
        }

        scope.launch {
            toggleMenu()
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        setInputListeningEnabled(drawerState.isClosed)
    }

    LaunchedEffect(sideMenuState.areScreensSwapped) {
        NativeEmulation.setSwapScreens(sideMenuState.areScreensSwapped)
    }

    LaunchedEffect(sideMenuState.isExternalScreenRotatedLeft) {
        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)
    }

    LaunchedEffect(Unit) {
        HotkeyManager.actions.collect { action ->
            when (action) {
                HotkeyAction.QUIT -> showQuitConfirmationDialog = true
                HotkeyAction.TOGGLE_MENU -> toggleMenu()
                HotkeyAction.SHOW_EMULATED_USB_DEVICES_DIALOG -> showEmulatedUSBDevices = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState())
                ) {
                    EmulationSideMenuContent(
                        sideMenuState = sideMenuState,
                        updateState = {
                            viewModel.updateSideMenuState(it)
                            setMotionSensorEnabled(it.isMotionEnabled)
                            NativeEmulation.setReplaceTVWithPadView(it.isTVReplacedWithPad)
                            closeDrawer()
                        },
                        onEditInputOverlay = {
                            snackbarHostState.showMessage(scope, tr("Edit input positions"))
                            inputOverlayInputMode = EDIT_POSITION
                            closeDrawer()
                        },
                        onResetInputOverlay = {
                            viewModel.resetInputOverlayLayout()
                            closeDrawer()
                        },
                        onQuit = {
                            showQuitConfirmationDialog = true
                            closeDrawer()
                        },
                        onShowEmulatedUSBDevices = {
                            showEmulatedUSBDevices = true
                            closeDrawer()
                        },
                    )
                }
            }
        },
    ) {
        EmulationSurfaces(
            viewModel = viewModel,
            isEmulationInitialized = isEmulationInitialized,
        )

        InputOverlaySurface(
            isVisible = isInputOverlayVisible,
            inputOverlaySettings = inputOverlaySettings,
            inputMode = inputOverlayInputMode,
            onEditFinished = { viewModel.saveInputOverlayRectangles(it) },
        )

        if (inputOverlayInputMode != DEFAULT) {
            EditInputsLayout(
                inputMode = inputOverlayInputMode,
                onFinishClick = {
                    snackbarHostState.showMessage(scope, tr("Exited input edit mode"))
                    inputOverlayInputMode = DEFAULT
                },
                onMoveClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input positions"))
                    inputOverlayInputMode = EDIT_POSITION
                },
                onResizeClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input size"))
                    inputOverlayInputMode = EDIT_SIZE
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    emulationError?.let {
        EmulationErrorDialog(it, onQuit)
    }

    if (!isEmulationInitialized) {
        EmulationLoadingDialog()
    }

    if (showQuitConfirmationDialog) {
        EmulationQuitConfirmationDialog(
            onQuit = onQuit,
            onDismiss = { showQuitConfirmationDialog = false },
        )
    }

    if (showEmulatedUSBDevices) {
        EmulatedUSBDevicesDialog(
            onDismiss = { showEmulatedUSBDevices = false },
        )
    }

    EmulationTextInputDialog()
}

@Composable
private fun EditInputsLayout(
    inputMode: InputOverlaySurfaceView.InputMode,
    onFinishClick: () -> Unit,
    onMoveClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onFinishClick) { Text(tr("Done")) }

            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(enabled = inputMode != EDIT_POSITION, onClick = onMoveClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_move),
                        contentDescription = tr("Move")
                    )
                }

                FilledIconButton(enabled = inputMode != EDIT_SIZE, onClick = onResizeClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_resize),
                        contentDescription = tr("Resize"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmulationSideMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
    onShowEmulatedUSBDevices: () -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
    onQuit: () -> Unit,
) {
    CheckboxItem(
        label = tr("Enable motion"),
        checked = sideMenuState.isMotionEnabled,
        onCheckedChange = { updateState(sideMenuState.copy(isMotionEnabled = it)) },
    )

    CheckboxItem(
        label = tr("Replace TV with PAD"),
        checked = sideMenuState.isTVReplacedWithPad,
        onCheckedChange = { updateState(sideMenuState.copy(isTVReplacedWithPad = it)) },
    )

    CheckboxItem(
        label = tr("Show PAD"),
        checked = sideMenuState.isPadVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isPadVisible = it)) },
    )

    CheckboxItem(
        label = tr("External PAD screen"),
        checked = sideMenuState.isPadOnExternalDisplay,
        onCheckedChange = { updateState(sideMenuState.copy(isPadOnExternalDisplay = it)) },
        enabled = sideMenuState.isPadVisible,
    )

    CheckboxItem(
        label = tr("Swap screens"),
        checked = sideMenuState.areScreensSwapped,
        onCheckedChange = { updateState(sideMenuState.copy(areScreensSwapped = it)) },
    )

    CheckboxItem(
        label = tr("Rotate external screen left"),
        checked = sideMenuState.isExternalScreenRotatedLeft,
        onCheckedChange = { updateState(sideMenuState.copy(isExternalScreenRotatedLeft = it)) },
        enabled = sideMenuState.isPadOnExternalDisplay,
    )

    TextButtonItem(
        label = tr("Emulated USB Devices"),
        onClick = onShowEmulatedUSBDevices,
    )

    CheckboxItem(
        label = tr("Show input overlay"),
        checked = sideMenuState.isInputOverlayVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isInputOverlayVisible = it)) },
    )

    TextButtonItem(
        label = tr("Edit inputs"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onEditInputOverlay,
    )

    TextButtonItem(
        label = tr("Reset input overlay"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onResetInputOverlay,
    )

    TextButtonItem(
        label = tr("Exit"),
        onClick = onQuit,
    )
}

@Composable
private fun CheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled) { onCheckedChange(!checked) }
            .padding(8.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            fontSize = 16.sp,
        )

        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun TextButtonItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .heightIn(min = 48.dp)
            .wrapContentHeight(align = Alignment.CenterVertically),
        fontSize = 16.sp,
    )
}

@Composable
private fun EmulationSurfaces(
    viewModel: EmulationViewModel,
    isEmulationInitialized: Boolean,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val gamePadPosition by viewModel.gamePadPosition.collectAsState()
    val mainSurfaceDimensions by viewModel.mainSurfaceDimensions.collectAsState()
    val padSurfaceDimensions by viewModel.padSurfaceDimensions.collectAsState()

    if (gamePadPosition == null) {
        return
    }

    val padDisplay = if (activity != null) rememberPadDisplay(activity) else null
    val isPadVisibleEffective = sideMenuState.isPadVisible && isEmulationInitialized
    val usePadPresentation =
        isPadVisibleEffective && sideMenuState.isPadOnExternalDisplay && padDisplay != null

    val mainTouchListener = remember { CanvasOnTouchListener() }
    val padTouchListener = remember { CanvasOnTouchListener() }
    val padPresentationTouchListener = remember { CanvasOnTouchListener() }

    val isMainTargetingTV = !sideMenuState.areScreensSwapped
    val mainTargetDimensions =
        if (isMainTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val isPadTargetingTV = sideMenuState.areScreensSwapped
    val padTargetDimensions =
        if (isPadTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val rotatePresentationTouch = usePadPresentation && sideMenuState.isExternalScreenRotatedLeft

    LaunchedEffect(isPadTargetingTV, padTargetDimensions, rotatePresentationTouch) {
        padPresentationTouchListener.updateConfiguration(
            isTv = isPadTargetingTV,
            surfaceWidth = padTargetDimensions.width,
            surfaceHeight = padTargetDimensions.height,
            rotateLeft = rotatePresentationTouch,
        )
    }

    DisposableEffect(activity, padDisplay, usePadPresentation, sideMenuState.isExternalScreenRotatedLeft) {
        val activityNonNull = activity ?: return@DisposableEffect onDispose {}
        if (!usePadPresentation) {
            return@DisposableEffect onDispose {}
        }
        val padDisplayNonNull = padDisplay ?: return@DisposableEffect onDispose {}

        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)

        val padPresentation = PadPresentation(
            context = activityNonNull,
            display = padDisplayNonNull,
            rotateLeft = sideMenuState.isExternalScreenRotatedLeft,
            holderCallback = viewModel.padHolderCallback,
            touchListener = padPresentationTouchListener,
        )

        padPresentation.show()

        onDispose { padPresentation.dismiss() }
    }

    LinearLayout(gamePadPosition) { itemModifier ->
        EmulationSurface(
            modifier = itemModifier,
            holderCallback = viewModel.mainHolderCallback,
            touchListener = mainTouchListener,
            touchIsTv = isMainTargetingTV,
            touchSurfaceWidth = mainTargetDimensions.width,
            touchSurfaceHeight = mainTargetDimensions.height,
            afterInit = { viewModel.initializeEmulation() },
        )

        if (isPadVisibleEffective && !usePadPresentation) {
            EmulationSurface(
                modifier = itemModifier,
                holderCallback = viewModel.padHolderCallback,
                touchListener = padTouchListener,
                touchIsTv = isPadTargetingTV,
                touchSurfaceWidth = padTargetDimensions.width,
                touchSurfaceHeight = padTargetDimensions.height,
            )
        }
    }
}

@Composable
private fun LinearLayout(
    gamePadPosition: GamePadPosition,
    content: @Composable (itemModifier: Modifier) -> Unit,
) {
    if (gamePadPosition.isVertical()) {
        val arrangement =
            if (gamePadPosition.appearsAfterTV()) Arrangement.Top else Arrangement.Bottom

        Column(
            modifier = Modifier.fillMaxSize(), verticalArrangement = arrangement
        ) {
            content(Modifier.weight(1f))
        }
    } else {
        val arrangement =
            if (gamePadPosition.appearsAfterTV()) Arrangement.Start else Arrangement.End

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = arrangement,
            ) {
                content(Modifier.weight(1f))
            }
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
private fun EmulationSurface(
    modifier: Modifier,
    holderCallback: SurfaceHolder.Callback,
    touchListener: CanvasOnTouchListener,
    touchIsTv: Boolean,
    touchSurfaceWidth: Int,
    touchSurfaceHeight: Int,
    rotateLeft: Boolean = false,
    afterInit: () -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                var firstChange = true

                setOnTouchListener(touchListener)

                holder.addCallback(holderCallback)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        if (firstChange) {
                            afterInit()
                            firstChange = false
                        }
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        },
        update = {
            touchListener.updateConfiguration(
                isTv = touchIsTv,
                surfaceWidth = touchSurfaceWidth,
                surfaceHeight = touchSurfaceHeight,
                rotateLeft = rotateLeft,
            )
        },
    )
}

@Composable
private fun rememberPadDisplay(activity: Activity): Display? {
    val displayManager =
        remember(activity) { activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    var padDisplay by remember { mutableStateOf<Display?>(null) }

    fun updatePadDisplay() {
        padDisplay =
            if ((activity.display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY) {
                DisplayUtils.getExternalDisplay(activity)
            } else {
                DisplayUtils.getInternalDisplay(activity)
            }
    }

    DisposableEffect(displayManager, activity) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updatePadDisplay()
            override fun onDisplayRemoved(displayId: Int) = updatePadDisplay()
            override fun onDisplayChanged(displayId: Int) = updatePadDisplay()
        }

        updatePadDisplay()
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return padDisplay
}

@Composable
private fun EmulationQuitConfirmationDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(tr("Exit confirmation")) },
        text = { Text(tr("Are you sure you want to exit?")) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Yes")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("No")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationLoadingDialog() {
    AlertDialog(
        title = { Text(tr("Initializing emulation")) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        onDismissRequest = {},
    )
}

@Composable
private fun EmulationErrorDialog(error: NativeError, onQuit: () -> Unit) {
    val errorMessage = remember(error) {
        when (error) {
            is NativeError.SystemInitializationError -> tr("Failed to initialize")

            is NativeError.RendererInitializationError ->
                tr("Failed creating renderer: {0}", error.message)

            is NativeError.SurfaceCreationError -> tr("Failed creating surface: {0}", error.message)

            NativeError.GameFilesNotFoundError -> tr("Unable to launch game because the base files were not found.")
            NativeError.NoDiscKeysError -> tr("Could not decrypt title. Make sure that keys.txt contains the correct disc key for this title.")
            NativeError.NoTitleTikError -> tr("Could not decrypt title because title.tik is missing.")
            is NativeError.UnknownTilePrepareError ->
                tr("Unable to launch game\nPath: {0}", error.launchPath)

            NativeError.LaunchingTitleError -> tr("Failed to launch title")
        }
    }
    AlertDialog(
        title = { Text(tr("Error")) },
        text = { Text(errorMessage) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Quit")) } },
        onDismissRequest = {},
    )
}
