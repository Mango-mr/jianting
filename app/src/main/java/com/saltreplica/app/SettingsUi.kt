package com.saltreplica.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

/** 设置页控件工厂：卡片 / 可展开标题行 / 滑块 / 开关 / 色点 */
object SettingsUi {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 一张设置卡片：标题行 + 隐藏的下拉面板 */
    class Card(
        val root: LinearLayout,
        val header: LinearLayout,
        val panel: LinearLayout,
        val value: TextView,
        val chevron: ImageView
    )

    fun card(
        context: Context,
        iconRes: Int,
        title: String,
        expandable: Boolean = true
    ): Card {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
            setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 12) }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(context, R.color.on_bg))
            layoutParams = LinearLayout.LayoutParams(dp(context, 21), dp(context, 21))
        }
        header.addView(icon)

        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.on_bg))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(context, 10) }
        }
        header.addView(titleView)

        val valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.on_bg_sub))
            maxLines = 1
        }
        header.addView(valueView)

        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(ContextCompat.getColor(context, R.color.on_bg_sub))
            layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18))
                .apply { marginStart = dp(context, 6) }
        }
        header.addView(chevron)

        root.addView(header)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(context, 8))
        }
        root.addView(panel)

        if (expandable) {
            header.setOnClickListener {
                val open = panel.visibility == View.VISIBLE
                panel.visibility = if (open) View.GONE else View.VISIBLE
                // 收起时箭头朝右，展开后转向下方
                chevron.animate().rotation(if (open) 0f else 90f).setDuration(160).start()
            }
        } else {
            // 不可展开 = 点进另一个页面，箭头保持「进入」方向
            chevron.rotation = 0f
        }

        return Card(root, header, panel, valueView, chevron)
    }

    /** 读主题属性色；取不到时用 fallback */
    fun attrColor(context: Context, attrRes: Int, fallback: Int): Int {
        val typed = context.obtainStyledAttributes(intArrayOf(attrRes))
        val color = typed.getColor(0, fallback)
        typed.recycle()
        return if (color == 0) fallback else color
    }

    /** 当前主题的强调色 */
    fun primary(context: Context): Int = attrColor(
        context,
        com.google.android.material.R.attr.colorPrimary,
        colorOf(context, R.color.primary)
    )

    /** 强调色上的文字色（亮黄这类浅底色要用深字） */
    fun onPrimary(context: Context): Int = attrColor(
        context,
        com.google.android.material.R.attr.colorOnPrimary,
        colorOf(context, R.color.white)
    )

    /** 面板里的分组小标题 */
    fun label(context: Context, text: String, topMargin: Int = 10): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ContextCompat.getColor(context, R.color.on_bg_sub))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = dp(context, topMargin) }
        }

    /** 一行开关 */
    fun switchRow(
        context: Context,
        title: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6) }
        }
        val text = TextView(context).apply {
            this.text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.on_bg))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(text)
        val switch = SwitchMaterial(context).apply {
            isChecked = checked
            // 开关状态用颜色区分：打开 = 主题色（奶白为近黑），关闭 = 灰
            val onColor = primary(context)
            val offTrack = ContextCompat.getColor(context, R.color.surface_deep)
            val offThumb = ContextCompat.getColor(context, R.color.on_bg_sub)
            // 轨道深色、滑块白色，打开时对比明显；关闭时整条变灰
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, offThumb)
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onColor, offTrack)
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(switch)
        return row
    }

    /**
     * 一条滑块：标题 + 当前值，下面一条 SeekBar。
     * progress 按 step 量化，取值 = min + progress * step。
     */
    fun sliderRow(
        context: Context,
        title: String,
        min: Int,
        max: Int,
        step: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ): View {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 10) }
        }

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.on_bg_sub))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.on_bg))
        }
        head.addView(name)
        head.addView(valueText)
        block.addView(head)

        val steps = ((max - min) / step).coerceAtLeast(1)
        val seek = SeekBar(context)
        seek.max = steps
        seek.progress = ((value - min) / step).coerceIn(0, steps)
        valueText.text = format(min + seek.progress * step)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val current = min + progress * step
                valueText.text = format(current)
                if (fromUser) onChange(current)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        block.addView(seek)
        return block
    }

    /** 横向可点的一组小按钮 */
    fun chips(context: Context, labels: List<String>, selected: Int, onClick: (Int) -> Unit): View {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 6) }
        }
        var index = 0
        while (index < labels.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 6) }
            }
            val end = minOf(index + 4, labels.size)
            for (i in index until end) {
                val chip = TextView(context).apply {
                    text = labels[i]
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(
                        if (i == selected) onPrimary(context) else ContextCompat.getColor(context, R.color.on_bg)
                    )
                    background = ContextCompat.getDrawable(
                        context, if (i == selected) R.drawable.bg_chip_on else R.drawable.bg_chip
                    )
                    setOnClickListener { onClick(i) }
                    layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f)
                        .apply { marginEnd = if (i < end - 1) dp(context, 6) else 0 }
                }
                row.addView(chip)
            }
            while (row.childCount < 4) {
                row.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        .apply { marginEnd = dp(context, 6) }
                })
            }
            wrap.addView(row)
            index = end
        }
        return wrap
    }

    /** 一个圆形色点，选中时带描边 */
    fun dot(context: Context, color: Int, selected: Boolean, sizeDp: Int = 30): TextView {
        val dot = TextView(context)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                dp(context, if (selected) 2 else 0),
                ContextCompat.getColor(context, if (selected) R.color.on_bg else R.color.outline)
            )
        }
        dot.layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            .apply { marginEnd = dp(context, 10) }
        return dot
    }

    /** 主题预览块：底色 + 强调色的斜切对角 */
    fun swatch(context: Context, bg: Int, accent: Int, selected: Boolean): View {
        val block = View(context)
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(bg, accent, accent)
        ).apply {
            cornerRadius = dp(context, 12f.toInt()).toFloat()
            setStroke(
                dp(context, if (selected) 2 else 1),
                if (selected) ContextCompat.getColor(context, R.color.on_bg)
                else ContextCompat.getColor(context, R.color.outline)
            )
        }
        block.background = drawable
        block.layoutParams = LinearLayout.LayoutParams(0, dp(context, 46), 1f)
            .apply { marginEnd = dp(context, 8) }
        return block
    }

    fun colorOf(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)

    fun mix(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color)
    )
}
