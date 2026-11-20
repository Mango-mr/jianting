package com.saltreplica.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 在 dispatchTouchEvent 这一层判断左右滑。
 * 之前把 OnTouchListener 挂在根布局，手指落在子 View 上时事件被拦截，手势收不到，
 * 这里父容器最先拿到事件，子 View 无论怎么消费都不影响判断。
 */
class GestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var tracking = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downAt = ev.eventTime
                tracking = true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false

            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    tracking = false
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val cost = ev.eventTime - downAt
                    if (abs(dy) < dp(60) && abs(dx) > dp(100) && cost in 60..700) {
                        if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
