package com.saltreplica.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** 全屏歌词页：滚动高亮，右滑 / 返回键回到播放页 */
class LyricsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: LyricsAdapter
    private lateinit var titleView: TextView
    private lateinit var subView: TextView

    private var lines: List<LrcLine> = emptyList()
    private var currentLine = -1
    private var lyricKey: String? = null
    private var attached = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 400)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = sync()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = sync()
    }

    private val controllerListener: (MediaController?) -> Unit = { controller ->
        if (controller != null && !attached) {
            attached = true
            controller.addListener(playerListener)
        }
        sync()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_lyrics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.lyrics)
        titleView = view.findViewById(R.id.title)
        subView = view.findViewById(R.id.sub)

        adapter = LyricsAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<View>(R.id.btn_back).setOnClickListener { close() }
        // 右滑返回播放页：在 GestureLayout 的 dispatchTouchEvent 层判断
        (view as? GestureLayout)?.onSwipeRight = { close() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    close()
                }
            }
        )

        PlaybackConnection.connect(requireContext())
        PlaybackConnection.addListener(controllerListener)
        sync()
    }

    private fun close() {
        handler.removeCallbacks(ticker)
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(ticker)
        PlaybackConnection.removeListener(controllerListener)
        PlaybackConnection.controller?.let { c ->
            if (attached) {
                c.removeListener(playerListener)
                attached = false
            }
        }
        super.onDestroyView()
    }

    // ---------------- 数据 ----------------

    private fun sync() {
        if (!isAdded || view == null) return
        val item = PlaybackConnection.controller?.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
        val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
        titleView.text = title.ifBlank { "未在播放" }
        subView.text = artist

        val id = item?.mediaId?.toLongOrNull() ?: -1L
        val track = TrackCache.findById(id)
        val key = if (track != null) "t:${track.id}" else "o:$title|$artist"
        if (key == lyricKey) return
        lyricKey = key
        lines = emptyList()
        currentLine = -1
        adapter.submitLines(emptyList())

        if (title.isBlank() && track == null) {
            adapter.submitHint(getString(R.string.no_lyrics))
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (track != null) {
                LyricsRepository.load(track)
            } else {
                LyricsRepository.load(
                    key,
                    title,
                    artist,
                    item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                    PlaybackConnection.controller?.duration ?: 0L
                )
            }
            if (!isAdded || lyricKey != key) return@launch
            lines = result
            if (result.isEmpty()) {
                adapter.submitHint(getString(R.string.no_lyrics))
            } else {
                adapter.submitLines(result)
                tick()
            }
        }
    }

    private fun tick() {
        if (!isAdded || view == null || lines.isEmpty()) return
        val position = PlaybackConnection.controller?.currentPosition ?: return
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= position + 300) index = i else break
        }
        if (index == currentLine) return
        currentLine = index
        adapter.highlight(index)
        if (index >= 0) centerOn(index)
    }

    /** 把第 index 行定位到 RecyclerView 正中间：先粗定位，再按真实行高校正 */
    private fun centerOn(index: Int) {
        if (!isAdded || view == null || index < 0) return
        val height = recycler.height
        if (height <= 0) return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val itemHeight = recycler.getChildAt(0)?.height?.takeIf { it > 0 } ?: dp(36)
        // offset 相对 padding 起点；paddingTop 已留白，这里减去它让目标行落在中线
        lm.scrollToPositionWithOffset(index, (height - itemHeight) / 2 - recycler.paddingTop)
        // 高亮行比普通行高一点，粗定位会差几十像素，下一帧用真实位置补平
        recycler.post {
            if (!isAdded) return@post
            val holder = recycler.findViewHolderForAdapterPosition(index) ?: return@post
            val dy = holder.itemView.top - (height - holder.itemView.height) / 2
            if (dy != 0) recycler.scrollBy(0, dy)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
