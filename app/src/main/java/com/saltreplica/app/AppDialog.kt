package com.saltreplica.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 全 App 统一的自绘弹框。
 *
 * 系统 AlertDialog 在奶白界面里是白底直角 + 黑色标题，割裂感很强，
 * 这里用同一套卡片：大圆角、surface 底、低饱和文字、强调色文字按钮。
 */
object AppDialog {

    /** 纯提示，一个「知道了」 */
    fun message(
        context: Context,
        title: String,
        message: String,
        positive: String = "知道了",
        onPositive: (() -> Unit)? = null
    ): Dialog {
        val dialog = create(context, title, message)
        val btn = dialog.findViewById<TextView>(R.id.dialog_positive)
        btn.text = positive
        btn.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }
        dialog.findViewById<View>(R.id.dialog_negative).visibility = View.GONE
        dialog.show()
        return dialog
    }

    /** 二选一确认 */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        positive: String = "确定",
        negative: String = "取消",
        onPositive: () -> Unit
    ): Dialog {
        val dialog = create(context, title, message)
        dialog.findViewById<TextView>(R.id.dialog_positive).apply {
            text = positive
            setOnClickListener {
                dialog.dismiss()
                onPositive()
            }
        }
        dialog.findViewById<TextView>(R.id.dialog_negative).apply {
            text = negative
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        return dialog
    }

    /** 选择列表：items 为「主标题 / 副标题」 */
    fun list(
        context: Context,
        title: String,
        items: List<Pair<String, String?>>,
        onPick: (Int) -> Unit,
        extraLabel: String? = null,
        onExtra: (() -> Unit)? = null,
        currentIndex: Int = -1
    ): Dialog {
        val dialog = create(context, title, null)
        val container = dialog.findViewById<LinearLayout>(R.id.dialog_list)
        container.visibility = View.VISIBLE

        items.forEachIndexed { index, item ->
            container.addView(buildRow(context, item.first, item.second, index == currentIndex) {
                dialog.dismiss()
                onPick(index)
            })
        }
        if (extraLabel != null && onExtra != null) {
            container.addView(buildRow(context, extraLabel, null, false) {
                dialog.dismiss()
                onExtra()
            })
        }

        dialog.findViewById<View>(R.id.dialog_negative).apply {
            visibility = View.VISIBLE
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<View>(R.id.dialog_positive).visibility = View.GONE
        dialog.show()
        return dialog
    }

    /** 输入框（新建 / 重命名歌单） */
    fun input(
        context: Context,
        title: String,
        hint: String,
        initial: String,
        positive: String = "确定",
        onOk: (String) -> Unit
    ): Dialog {
        val dialog = create(context, title, null)
        val input = dialog.findViewById<EditText>(R.id.dialog_input)
        input.visibility = View.VISIBLE
        input.hint = hint
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(initial)
        input.setSelection(input.text.length)

        dialog.findViewById<TextView>(R.id.dialog_positive).apply {
            text = positive
            setOnClickListener {
                val value = input.text.toString().trim()
                dialog.dismiss()
                onOk(value)
            }
        }
        dialog.findViewById<TextView>(R.id.dialog_negative).setOnClickListener { dialog.dismiss() }
        dialog.show()
        return dialog
    }

    /** 歌曲长按菜单：操作行带图标，最后一块直接把歌曲信息摊开（不再跳二级弹框） */
    fun songMenu(
        context: Context,
        title: String,
        subtitle: String?,
        actions: List<Action>,
        infoLines: List<Pair<String, String>>
    ): Dialog {
        val dialog = create(context, title, null)
        if (!subtitle.isNullOrBlank()) {
            dialog.findViewById<TextView>(R.id.dialog_message).apply {
                visibility = View.VISIBLE
                text = subtitle
            }
        }
        val container = dialog.findViewById<LinearLayout>(R.id.dialog_list)
        container.visibility = View.VISIBLE

        actions.forEach { action ->
            container.addView(buildRow(context, action.label, null, false, action.iconRes, action.bold) {
                dialog.dismiss()
                action.onClick()
            })
        }
        container.addView(buildInfoBlock(context, infoLines))

        dialog.findViewById<TextView>(R.id.dialog_negative).apply {
            visibility = View.VISIBLE
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<View>(R.id.dialog_positive).visibility = View.GONE
        dialog.show()
        return dialog
    }

    /** 弹窗里的一行操作 */
    data class Action(
        val label: String,
        val iconRes: Int,
        val bold: Boolean = false,
        val onClick: () -> Unit
    )

    // ---------------- 内部 ----------------

    private fun create(context: Context, title: String, message: String?): Dialog {
        val dialog = Dialog(context, R.style.AppDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_app)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.34f)

        dialog.setOnShowListener {
            applyWidth(context, dialog)
        }

        dialog.findViewById<TextView>(R.id.dialog_title).text = title
        dialog.findViewById<TextView>(R.id.dialog_message).apply {
            if (message.isNullOrBlank()) visibility = View.GONE else {
                visibility = View.VISIBLE
                text = message
            }
        }
        // 输入框与列表默认收起，各方法按需打开，避免弹框里多出不属于当前用法的控件
        dialog.findViewById<View>(R.id.dialog_input).visibility = View.GONE
        dialog.findViewById<View>(R.id.dialog_list).visibility = View.GONE
        dialog.findViewById<View>(R.id.dialog_negative).setOnClickListener { dialog.dismiss() }
        return dialog
    }

    private fun applyWidth(context: Context, dialog: Dialog) {
        val window = dialog.window ?: return
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels - 64 * metrics.density).toInt()
            .coerceAtMost((380 * metrics.density).toInt())
            .coerceAtLeast((240 * metrics.density).toInt())
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
    }

    private fun buildRow(
        context: Context,
        label: String,
        sub: String?,
        highlighted: Boolean,
        iconRes: Int? = null,
        bold: Boolean = false,
        onClick: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_item)
            setPadding(
                (14 * density).toInt(), (12 * density).toInt(),
                (14 * density).toInt(), (12 * density).toInt()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (2 * density).toInt()
        outer.layoutParams = lp

        if (iconRes != null) {
            outer.addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(ContextCompat.getColor(context, R.color.on_bg))
                layoutParams = LinearLayout.LayoutParams(
                    (18 * density).toInt(), (18 * density).toInt()
                ).apply { marginEnd = (10 * density).toInt() }
            })
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(
                if (highlighted) SettingsUi.primary(context)
                else ContextCompat.getColor(context, R.color.on_bg)
            )
            if (highlighted || bold) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        })
        if (!sub.isNullOrBlank()) {
            texts.addView(TextView(context).apply {
                text = sub
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.on_bg_sub))
                setPadding(0, (3 * density).toInt(), 0, 0)
            })
        }
        outer.addView(texts)
        return outer
    }

    /** 歌曲信息块：小标题 + 字段，压成一张浅色卡片放在弹窗底部 */
    private fun buildInfoBlock(context: Context, lines: List<Pair<String, String>>): View {
        val density = context.resources.displayMetrics.density
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_item)
            setPadding(
                (14 * density).toInt(), (11 * density).toInt(),
                (14 * density).toInt(), (12 * density).toInt()
            )
        }
        block.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_menu_info)
            setColorFilter(ContextCompat.getColor(context, R.color.on_bg_sub))
            layoutParams = LinearLayout.LayoutParams(
                (14 * density).toInt(), (14 * density).toInt()
            ).apply { marginEnd = (7 * density).toInt() }
        })
        head.addView(TextView(context).apply {
            text = "歌曲信息"
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(context, R.color.on_bg_sub))
        })
        block.addView(head)

        lines.forEach { (key, value) ->
            block.addView(TextView(context).apply {
                text = "$key：$value"
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(context, R.color.on_bg_sub))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, (5 * density).toInt(), 0, 0)
            })
        }
        return block
    }
}
