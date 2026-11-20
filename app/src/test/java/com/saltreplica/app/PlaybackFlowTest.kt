package com.saltreplica.app

import android.Manifest
import android.net.Uri
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 端到端：音乐库有歌 → 点歌 → 真的发起播放请求。
 * 这是上一版"点了没反应/放不出声"的回归防线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class PlaybackFlowTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun seedLibrary(): List<Track> {
        val songs = listOf(
            Track(11, "晴天", "周杰伦", "叶惠美", 270000, "/storage/emulated/0/Music/qing.mp3"),
            Track(12, "稻香", "周杰伦", "魔杰座", 220000, "/storage/emulated/0/Music/dao.mp3")
        )
        TrackStore.save(ApplicationProvider.getApplicationContext(), songs)
        return songs
    }

    @Test
    fun clickingSongStartsPlaybackRequest() {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        Prefs.init(app)
        Prefs.autoScan = false
        PlaybackConnection.autoConnect = false

        var requested = false
        PlaybackConnection.requestObserver = { requested = true }

        seedLibrary()

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()

        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        assertTrue("列表应有歌曲", recycler.adapter!!.itemCount > 0)

        val holder = recycler.findViewHolderForAdapterPosition(0)
            ?: recycler.adapter!!.let { adapter ->
                // 没有布局时直接绑定再点击
                val parent = android.widget.FrameLayout(activity)
                val vh = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
                adapter.onBindViewHolder(vh, 0)
                vh
            }
        holder.itemView.performClick()
        idle()

        assertTrue("点击歌曲后应发起播放请求", requested)
    }

    @Test
    fun libraryShowsSongsFromCache() {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        Prefs.init(app)
        Prefs.autoScan = false
        PlaybackConnection.autoConnect = false

        seedLibrary()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()

        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        assertTrue("缓存里的歌应立刻显示", recycler.adapter!!.itemCount >= 2)
    }

    @Test
    fun playlistDbHasDefaultFavorite() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val db = PlaylistDb(app)
        val lists = db.playlists()
        assertTrue("默认应有「我喜欢」歌单", lists.any { it.name == "我喜欢" })
    }
}
