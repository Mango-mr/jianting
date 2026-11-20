package com.saltreplica.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 在线搜索结果行渲染：有封面 / 无封面 / 下载中三种状态 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xxhdpi", application = App::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnlineRowShotTest {

    @Test
    fun renderOnlineRows() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val ctx = android.view.ContextThemeWrapper(app, R.style.Theme_SaltReplica)
        val adapter = OnlineAdapter({}, {})
        val tracks = listOf(
            OnlineTrack("夜空中最亮的星", "逃跑计划", "世界", "http://a/1", 252_000, "网易云",
                "wy", "1", "https://p2.music.126.net/x.jpg"),
            OnlineTrack("Faded", "Alan Walker", "Alone", "http://a/2", 212_000, "酷我", "kw", "2"),
            OnlineTrack("Dancing With Your Ghost", "Sasha Alex Sloan", "", "http://a/3", 197_000,
                "酷狗", "kg", "3")
        )
        adapter.submit(tracks)
        adapter.setDownloading(tracks[2], true)

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(0xFFF6F5F3.toInt())
        tracks.forEachIndexed { index, track ->
            val holder = adapter.onCreateViewHolder(box, 0)
            adapter.onBindViewHolder(holder, index)
            box.addView(holder.itemView)
        }
        // 每条下面加一条极细的分隔，仅为截图辨识用
        val width = 900
        val height = 600
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        box.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        box.draw(Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/shots")
        dir.mkdirs()
        File(dir, "online_rows.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
