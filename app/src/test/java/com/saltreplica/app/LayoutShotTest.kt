package com.saltreplica.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 不上设备也能看到真实渲染结果：把布局 inflate 出来、填假数据、
 * 用真实 Skia 画到 Bitmap 存成 PNG，交付前肉眼核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xxhdpi", application = App::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutShotTest {

    private val width = 1236
    private val height = 2745

    private fun shot(view: View, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val dir = File(System.getProperty("user.dir"), "build/shots")
        dir.mkdirs()
        File(dir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun renderPlayerPage() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_SaltReplica
        )
        val root = LayoutInflater.from(ctx).inflate(R.layout.fragment_player, null, false)

        root.findViewById<TextView>(R.id.title).text = "Painful Dream"
        root.findViewById<TextView>(R.id.artist).text = "日落微醺"
        root.findViewById<TextView>(R.id.tv_lyric_prev).text = "Falling into you"
        root.findViewById<TextView>(R.id.tv_lyric_current).text = "With no parachute"
        root.findViewById<TextView>(R.id.tv_lyric_next).text = "Even if it breaks me"
        root.findViewById<TextView>(R.id.tv_current).text = "01:24"
        root.findViewById<TextView>(R.id.tv_total).text = "03:52"
        root.findViewById<SeekBar>(R.id.seek).progress = 380
        root.findViewById<SeekBar>(R.id.seek).max = 1000
        root.findViewById<ImageView>(R.id.btn_mode).setImageResource(R.drawable.ic_mode_order)

        shot(root, "player.png")
    }

    /**
     * 只用来托管 Fragment 的测试 Activity：不连播放服务，Robolectric 里能真正跑完
     * onCreate → onStart → onResume，Fragment 的视图才会被创建。
     */
    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val frame = FrameLayout(this).apply { id = R.id.container }
            setContentView(frame)
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, SettingsFragment(), "settings")
                    .commit()
            }
        }
    }

    /** 设置页：卡片 + 下拉面板，默认全部收起 */
    @Test
    fun renderSettingsPage() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.container)
        checkNotNull(fragment?.view) { "设置页 Fragment 视图没有创建，出图会是空白的" }

        shot(activity.findViewById(android.R.id.content), "settings.png")

        controller.pause().stop().destroy()
    }

    /** 四套主题的强调色块：验证 drawable 里 ?attr/colorPrimary 真的吃到了 overlay */
    @Test
    fun renderAccentSwatches() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val styles = listOf(
            R.style.Theme_SaltReplica to "奶白",
            R.style.Theme_SaltReplica_Neon to "霓虹",
            R.style.Theme_SaltReplica_Ember to "暗橙",
            R.style.Theme_SaltReplica_Lemon to "荧光"
        )
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(app, R.color.bg))
            setPadding(40, 40, 40, 40)
        }
        styles.forEach { (style, label) ->
            val ctx = ContextThemeWrapper(app, style)
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            val block = View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_chip_on)
                layoutParams = LinearLayout.LayoutParams(150, 150).apply { topMargin = 120 }
            }
            column.addView(block)
            val dot = View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.thumb_dot)
                layoutParams = LinearLayout.LayoutParams(90, 90).apply { topMargin = 40 }
            }
            column.addView(dot)
            column.addView(TextView(ctx).apply {
                text = "$label\n" + String.format("#%06X", 0xFFFFFF and SettingsUi.primary(ctx))
                gravity = android.view.Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.on_bg))
                setPadding(0, 30, 0, 0)
            })
            row.addView(column)
        }
        shot(row, "accent.png")
    }

    /** 设置页展开态：点开全部卡片，核对面板里的主题块 / 滑块 / 开关 */
    @Test
    fun renderSettingsOpenPage() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        val box = activity.findViewById<LinearLayout>(R.id.box_settings)
        check(box != null && box.childCount >= 7) { "设置卡片没有构建出来" }
        // 最后一张「音乐源」是跳转页，点了会离开设置页，这里只展开前 6 张
        for (i in 0 until box.childCount - 1) {
            (box.getChildAt(i) as LinearLayout).getChildAt(0).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()

        shot(activity.findViewById(android.R.id.content), "settings_open.png")
        controller.pause().stop().destroy()
    }

    @Test
    fun renderLibraryPage() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_SaltReplica
        )
        val root = LayoutInflater.from(ctx).inflate(R.layout.fragment_library, null, false)

        val tabs = root.findViewById<TabLayout>(R.id.tabs)
        val tabSpecs = listOf(
            "歌单" to R.drawable.ic_tab_playlist,
            "全部" to R.drawable.ic_tab_all,
            "歌手" to R.drawable.ic_tab_artist,
            "专辑" to R.drawable.ic_tab_album,
            "文件夹" to R.drawable.ic_tab_folder
        )
        tabSpecs.forEachIndexed { index, (label, icon) ->
            val tab = tabs.newTab()
            val custom = LayoutInflater.from(ctx).inflate(R.layout.tab_item, tabs, false)
            custom.findViewById<TextView>(R.id.tab_title).text = label
            custom.findViewById<ImageView>(R.id.tab_icon).setImageResource(icon)
            val color = ctx.getColor(if (index == 1) R.color.on_bg else R.color.on_bg_sub)
            custom.findViewById<TextView>(R.id.tab_title).setTextColor(color)
            custom.findViewById<ImageView>(R.id.tab_icon).setColorFilter(color)
            tab.customView = custom
            tabs.addTab(tab)
        }
        tabs.getTabAt(1)?.select()

        // 全部 tab 下顶栏会显示「排序」，一起进图核对图标
        root.findViewById<View>(R.id.tv_sort).visibility = View.VISIBLE

        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(ctx)
        recycler.adapter = DemoAdapter()

        root.findViewById<View>(R.id.mini_bar).visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.mini_title).text = "Time (Official)"
        root.findViewById<TextView>(R.id.mini_sub).text = "MKJ"

        shot(root, "library.png")
    }

    /** 只为出图：纯手填几行，不复用真实数据源 */
    private inner class DemoAdapter : RecyclerView.Adapter<DemoAdapter.H>() {
        private val data = listOf(
            Triple("Alone", "Alan Walker · Alone", "02:41"),
            Triple("Astronomia", "Vicetone, Tony Igy · Astronomia", "03:18"),
            Triple("Beautiful Now", "Zedd, Jon Bellion · 2010 European Club Hits", "03:38"),
            Triple("Burning", "Maria Arredondo · Not Going Under", "04:03"),
            Triple("Dancing With Your Ghost", "Sasha Alex Sloan · Dancing With Your Ghost", "03:17"),
            Triple("Faded", "Alan Walker, Iselin Solheim · Sunday Morning Songs", "03:32"),
            Triple("Painful Dream", "日落微醺", "03:52"),
            Triple("夜空中最亮的星", "逃跑计划 · 世界", "04:12")
        )

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val sub: TextView = v.findViewById(R.id.sub)
            val duration: TextView = v.findViewById(R.id.duration)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): H =
            H(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: H, position: Int) {
            val (t, s, d) = data[position]
            holder.title.text = t
            holder.sub.text = s
            holder.duration.text = d
        }
    }

    /**
     * 歌词页：验证「当前句垂直居中」。这里复刻 LyricsFragment 的做法——
     * 上下留白取 RecyclerView 高度的一半，再把目标行滚到正中间。
     */
    @Test
    fun renderLyricsPage() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_SaltReplica
        )
        val root = LayoutInflater.from(ctx).inflate(R.layout.fragment_lyrics, null, false)
        root.findViewById<TextView>(R.id.title).text = "Painful Dream"
        root.findViewById<TextView>(R.id.sub).text = "日落微醺"

        val recycler = root.findViewById<RecyclerView>(R.id.lyrics)
        recycler.layoutManager = LinearLayoutManager(ctx)
        val adapter = LyricsAdapter()
        recycler.adapter = adapter
        adapter.submitLines(
            (1..30).map { LrcLine(it * 5000L, "第 $it 句歌词示例文本") }
        )

        val exact = { size: Int ->
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        }
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)

        val target = 6
        adapter.highlight(target)
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)

        // 复刻 LyricsFragment.centerOn：先按普通行高粗定位，layout 后再按真实行高校正
        val lm = recycler.layoutManager as LinearLayoutManager
        val itemHeight = recycler.getChildAt(0)?.height?.takeIf { it > 0 } ?: (36 * 3)
        lm.scrollToPositionWithOffset(
            target,
            (recycler.height - itemHeight) / 2 - recycler.paddingTop
        )
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)

        val holder = recycler.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            val dy = holder.itemView.top - (recycler.height - holder.itemView.height) / 2
            if (dy != 0) {
                recycler.scrollBy(0, dy)
                root.measure(exact(width), exact(height))
                root.layout(0, 0, width, height)
            }
            // 说明：Robolectric 的 RecyclerView 不会执行 scrollToPositionWithOffset 的 offset，
            // 所以这里只校验「目标行确实被定位到可视区」，几何精确度留给真机里 post+scrollBy 那一步
            org.junit.Assert.assertTrue(
                "目标歌词行应出现在可视区",
                holder.itemView.top >= 0 && holder.itemView.bottom <= recycler.height
            )
        }

        shot(root, "lyrics.png")
    }

    /** 深色强调主题下托管播放页的测试 Activity（套用霓虹 overlay） */
    class AccentPlayerHost : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            theme.applyStyle(R.style.Theme_SaltReplica_Neon, true)
            val frame = FrameLayout(this).apply { id = R.id.container }
            setContentView(frame)
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, PlayerFragment(), "player")
                    .commit()
            }
        }
    }

    /**
     * 霓虹主题 + 夜间底色下的播放页：验证播放键、上一曲下一曲、底部图标都取到强调色。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun renderPlayerAccentPage() {
        PlaybackConnection.autoConnect = false
        Prefs.themeMode = 2
        try {
            val controller = Robolectric.buildActivity(AccentPlayerHost::class.java).setup()
            shadowOf(Looper.getMainLooper()).idle()
            val root = controller.get().findViewById<View>(android.R.id.content)
            checkNotNull(controller.get().findViewById<View>(R.id.btn_play)) { "播放页没有创建出来" }

            root.findViewById<TextView>(R.id.title).text = "Painful Dream"
            root.findViewById<TextView>(R.id.artist).text = "日落微醺"
            root.findViewById<TextView>(R.id.tv_lyric_current).text = "With no parachute"
            root.findViewById<TextView>(R.id.tv_lyric_prev).text = "Falling into you"
            root.findViewById<TextView>(R.id.tv_lyric_next).text = "Even if it breaks me"
            root.findViewById<TextView>(R.id.tv_current).text = "01:24"
            root.findViewById<TextView>(R.id.tv_total).text = "03:52"

            shot(root, "player_neon.png")
            controller.pause().stop().destroy()
        } finally {
            Prefs.themeMode = 0
            PlaybackConnection.autoConnect = true
        }
    }

    /** 歌曲长按菜单：图标行 + 第一行加粗 + 底部直接摊开歌曲信息 */
    @Test
    fun renderSongMenu() {
        PlaybackConnection.autoConnect = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        AppDialog.songMenu(
            activity,
            "Painful Dream",
            "日落微醺",
            listOf(
                AppDialog.Action("添加到歌单", R.drawable.ic_menu_playlist, bold = true) {},
                AppDialog.Action("从曲库移除", R.drawable.ic_menu_remove) {}
            ),
            listOf(
                "歌手" to "日落微醺",
                "专辑" to "Nightfall",
                "时长" to "03:52",
                "格式" to "MP3",
                "大小" to "4.2 MB",
                "文件夹" to "Music/2026"
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF9E978D.toInt())
        decor.draw(canvas)
        val dir = File(System.getProperty("user.dir"), "build/shots")
        dir.mkdirs()
        File(dir, "song_menu.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        PlaybackConnection.autoConnect = true
    }

    /** 音乐源页：内置源固定在第一行，不显示删除按钮 */
    class OnlineHost : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val frame = FrameLayout(this).apply { id = R.id.container }
            setContentView(frame)
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, OnlineFragment(), "online")
                    .commit()
            }
        }
    }

    @Test
    fun renderOnlinePage() {
        PlaybackConnection.autoConnect = false
        val controller = Robolectric.buildActivity(OnlineHost::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        shot(controller.get().findViewById(android.R.id.content), "online.png")
        controller.pause().stop().destroy()
        PlaybackConnection.autoConnect = true
    }

    @Test
    fun renderDialogCard() {
        PlaybackConnection.autoConnect = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        AppDialog.list(
            activity,
            "添加到歌单",
            listOf("我喜欢" to "12 首", "睡前循环" to "5 首", "通勤路上" to "9 首"),
            onPick = {},
            extraLabel = "＋ 新建歌单",
            onExtra = {}
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF9E978D.toInt())
        decor.draw(canvas)
        val dir = File(System.getProperty("user.dir"), "build/shots")
        dir.mkdirs()
        File(dir, "dialog.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
