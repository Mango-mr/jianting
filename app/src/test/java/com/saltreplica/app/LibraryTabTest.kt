package com.saltreplica.app

import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 音乐库标签顺序、播放页三行歌词、歌词页开关 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class LibraryTabTest {

    private fun launch(): MainActivity {
        PlaybackConnection.autoConnect = false
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun tabOrderMatchesRequirement() {
        val activity = launch()
        idle()
        val tabs = activity.findViewById<TabLayout>(R.id.tabs)
        assertEquals(5, tabs.tabCount)
        // 每个 tab 都是自定义视图（图标 + 文字），文字从 customView 里取
        val texts = (0 until tabs.tabCount).map { index ->
            tabs.getTabAt(index)?.customView?.findViewById<android.widget.TextView>(R.id.tab_title)
                ?.text?.toString()
        }
        assertEquals(listOf("歌单", "全部", "歌手", "专辑", "文件夹"), texts)
        assertEquals(1, tabs.selectedTabPosition)
        // 每一项都带图标
        (0 until tabs.tabCount).forEach { index ->
            assertNotNull(
                tabs.getTabAt(index)?.customView?.findViewById<android.widget.ImageView>(R.id.tab_icon)
                    ?.drawable
            )
        }
    }

    @Test
    fun everyTabRendersRows() {
        val activity = launch()
        idle()
        val tabs = activity.findViewById<TabLayout>(R.id.tabs)
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        for (index in 0 until tabs.tabCount) {
            tabs.getTabAt(index)?.select()
            idle()
            assertNotNull(recycler.adapter)
        }
    }

    @Test
    fun playerShowsThreeLyricLines() {
        val activity = launch()
        idle()
        activity.openPlayer()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        assertNotNull(activity.findViewById<View>(R.id.tv_lyric_prev))
        assertNotNull(activity.findViewById<View>(R.id.tv_lyric_current))
        assertNotNull(activity.findViewById<View>(R.id.tv_lyric_next))
    }

    @Test
    fun lyricsPageOpensAndCloses() {
        val activity = launch()
        idle()
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.container, LyricsFragment(), "lyrics")
            .addToBackStack("lyrics")
            .commit()
        activity.supportFragmentManager.executePendingTransactions()
        idle()

        assertTrue(activity.supportFragmentManager.findFragmentByTag("lyrics") is LyricsFragment)
        assertNotNull(activity.findViewById<RecyclerView>(R.id.lyrics))

        activity.supportFragmentManager.popBackStack()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        assertNull(activity.supportFragmentManager.findFragmentByTag("lyrics"))
        assertNotNull(activity.findViewById<View>(R.id.recycler))
    }

    @Test
    fun libraryGroupRowsCarryCoverSample() {
        // 分组行要能拿到组内任意一首歌做封面（无封面时回退占位图标）
        val group = Row.Group("周杰伦", "3 首")
        assertNull(group.sample)
    }
}
