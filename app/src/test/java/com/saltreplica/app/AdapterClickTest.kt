package com.saltreplica.app

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：列表行必须真的可点击。
 * 之前 onBindViewHolder 完全没绑 clickListener，点歌毫无反应，
 * 用户感知就是"点了没用、音乐放不了"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class AdapterClickTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Recorder : LibraryAdapter.Listener {
        var songClicked = -1
        var songLongClicked = -1
        var groupClicked = -1
        var groupLongClicked = -1
        var playlistClicked = -1
        var playlistLongClicked = -1

        override fun onSongClick(position: Int) { songClicked = position }
        override fun onSongLongClick(position: Int) { songLongClicked = position }
        override fun onGroupClick(position: Int) { groupClicked = position }
        override fun onGroupLongClick(position: Int) { groupLongClicked = position }
        override fun onPlaylistClick(position: Int) { playlistClicked = position }
        override fun onPlaylistLongClick(position: Int) { playlistLongClicked = position }
    }

    @Test
    fun songRowIsClickable() {
        val recorder = Recorder()
        val adapter = LibraryAdapter(recorder)
        val track = Track(1, "晴天", "周杰伦", "叶惠美", 270000, "/Music/a.mp3")
        adapter.submit(listOf(Row.Song(track)))

        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        holder.itemView.performClick()

        assertEquals(0, recorder.songClicked)
    }

    @Test
    fun songRowLongPressWorks() {
        val recorder = Recorder()
        val adapter = LibraryAdapter(recorder)
        val track = Track(2, "稻香", "周杰伦", "魔杰座", 220000, "/Music/b.mp3")
        adapter.submit(listOf(Row.Song(track)))

        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        holder.itemView.performLongClick()

        assertEquals(0, recorder.songLongClicked)
    }

    @Test
    fun groupRowIsClickable() {
        val recorder = Recorder()
        val adapter = LibraryAdapter(recorder)
        adapter.submit(listOf(Row.Group("周杰伦", "12 首")))

        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        holder.itemView.performClick()

        assertEquals(0, recorder.groupClicked)
    }

    @Test
    fun playlistRowIsClickable() {
        val recorder = Recorder()
        val adapter = LibraryAdapter(recorder)
        adapter.submit(listOf(Row.PlaylistRow(7, "我喜欢", 3)))

        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        holder.itemView.performClick()

        assertEquals(0, recorder.playlistClicked)
    }
}
