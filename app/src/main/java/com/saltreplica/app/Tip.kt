package com.saltreplica.app

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 轻提示：替代系统 Toast（系统 Toast 在奶白界面里是黑色直角盒子，割裂感强）。
 * 一条胶囊小卡片，淡入 → 停留 → 淡出，挂在 Activity 内容层底部。
 */
object Tip {

    fun show(activity: Activity?, text: String) {
        if (activity == null || activity.isFinishing) return
        if (text.isBlank()) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val density = activity.resources.displayMetrics.density

        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

        val tip = TextView(activity).apply {
            tag = TAG
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.tip_text))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_tip)
            setPadding(
                (18 * density).toInt(), (10 * density).toInt(),
                (18 * density).toInt(), (10 * density).toInt()
            )
            alpha = 0f
            translationY = (10 * density).toFloat()
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        params.bottomMargin = (112 * density).toInt()

        root.addView(tip, params)
        tip.animate().alpha(1f).translationY(0f).setDuration(180).start()
        tip.postDelayed({
            if (tip.parent === root) {
                tip.animate().alpha(0f).translationY((8 * density).toFloat())
                    .setDuration(220)
                    .withEndAction { root.removeView(tip) }
                    .start()
            }
        }, 1000)
    }

    private const val TAG = "app_tip"
}
