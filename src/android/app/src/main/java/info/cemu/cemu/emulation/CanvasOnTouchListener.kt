package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import info.cemu.cemu.nativeinterface.NativeInput
import kotlin.math.roundToInt

class CanvasOnTouchListener : View.OnTouchListener {
    private var currentPointerId: Int = -1
    private var isTV: Boolean = false
    private var rotateLeft: Boolean = false
    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1

    fun updateConfiguration(
        isTv: Boolean,
        surfaceWidth: Int,
        surfaceHeight: Int,
        rotateLeft: Boolean,
    ) {
        currentPointerId = -1
        isTV = isTv
        this.rotateLeft = rotateLeft
        this.surfaceWidth = surfaceWidth.coerceAtLeast(1)
        this.surfaceHeight = surfaceHeight.coerceAtLeast(1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val pointerIndex = when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (currentPointerId == -1) {
                    return false
                }
                val index = event.findPointerIndex(currentPointerId)
                if (index == -1) {
                    currentPointerId = -1
                    return false
                }
                index
            }

            else -> event.actionIndex
        }
        val pointerId = event.getPointerId(pointerIndex)

        val viewWidth = v.width.toFloat()
        val viewHeight = v.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) {
            return false
        }

        val normalizedX = (event.getX(pointerIndex) / viewWidth).coerceIn(0f, 1f)
        val normalizedY = (event.getY(pointerIndex) / viewHeight).coerceIn(0f, 1f)

        var targetX = normalizedX * surfaceWidth
        var targetY = normalizedY * surfaceHeight
        if (rotateLeft) {
            val rotatedX = normalizedY * surfaceWidth
            val rotatedY = (1f - normalizedX) * surfaceHeight
            targetX = rotatedX
            targetY = rotatedY
        }

        val x = targetX.roundToInt().coerceIn(0, surfaceWidth - 1)
        val y = targetY.roundToInt().coerceIn(0, surfaceHeight - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (currentPointerId != -1 && pointerId != currentPointerId) {
                    return false
                }
                currentPointerId = pointerId
                NativeInput.onTouchDown(x, y, isTV)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                NativeInput.onTouchMove(x, y, isTV)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId != currentPointerId && currentPointerId != -1) {
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                        return true
                    }
                }
                currentPointerId = -1
                NativeInput.onTouchUp(x, y, isTV)
                return true
            }
        }
        return false
    }
}
