package info.cemu.cemu.emulation

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager

class PadPresentation(
    context: Context,
    display: Display,
    private val rotateLeft: Boolean,
    private val holderCallback: SurfaceHolder.Callback,
    private val touchListener: CanvasOnTouchListener,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        val mode = display.mode
        val (surfaceWidth, surfaceHeight) = computeSurfaceSize(
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            rotateLeft = rotateLeft,
        )

        val surfaceView = SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            holder.setFixedSize(surfaceWidth, surfaceHeight)
            holder.addCallback(holderCallback)
            setOnTouchListener(touchListener)
        }

        setContentView(surfaceView)
    }

    private fun computeSurfaceSize(width: Int, height: Int, rotateLeft: Boolean): Pair<Int, Int> {
        var surfaceWidth = width
        var surfaceHeight = height

        if (surfaceWidth < surfaceHeight) {
            val tmp = surfaceWidth
            surfaceWidth = surfaceHeight
            surfaceHeight = tmp
        }

        if (rotateLeft) {
            val tmp = surfaceWidth
            surfaceWidth = surfaceHeight
            surfaceHeight = tmp
        }

        return surfaceWidth to surfaceHeight
    }
}
