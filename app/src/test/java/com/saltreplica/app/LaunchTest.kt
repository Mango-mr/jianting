package com.saltreplica.app

import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 首启冒烟测试：Activity 创建 + 全部布局 inflate + 各界面切换 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class LaunchTest {

    private fun launch(): MainActivity {
        PlaybackConnection.autoConnect = false
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun mainActivityLaunches() {
        val activity = launch()
        idle()
        assertNotNull(activity.supportFragmentManager.findFragmentById(R.id.container))
    }

    @Test
    fun inflatesAllLayouts() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val inflater = LayoutInflater.from(activity)
        val layouts = listOf(
            R.layout.activity_main,
            R.layout.fragment_library,
            R.layout.fragment_settings,
            R.layout.fragment_player,
            R.layout.fragment_online,
            R.layout.item_song,
            R.layout.item_group,
            R.layout.item_lyric,
            R.layout.item_source,
            R.layout.item_online
        )
        layouts.forEach { inflater.inflate(it, null, false) }
    }

    @Test
    fun libraryTabsRender() {
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
    fun playerFragmentOpens() {
        val activity = launch()
        idle()
        activity.openPlayer()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.container)
        assertTrue(fragment is PlayerFragment)
    }

    @Test
    fun settingsFragmentOpens() {
        val activity = launch()
        idle()
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.container, SettingsFragment(), "settings")
            .commit()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        val box = activity.findViewById<android.widget.LinearLayout>(R.id.box_settings)
        assertNotNull(box)
        // 主题 / 播放页 / 歌词 / 自动扫描 / 睡眠定时 / 均衡器 / 音乐源
        assertTrue("设置卡片数量应 >= 7，实际 " + box.childCount, box.childCount >= 7)
    }

    @Test
    fun onlineFragmentOpens() {        val activity = launch()
        idle()
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.container, OnlineFragment(), "online")
            .commit()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        assertNotNull(activity.findViewById<View>(R.id.et_search))
    }
}
