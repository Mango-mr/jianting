package com.saltreplica.app

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.android.material.imageview.ShapeableImageView
import coil.load
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {

    private lateinit var cover: ShapeableImageView
    private lateinit var bgCover: ImageView
    private lateinit var bgScrim: View
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var tvCurrent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var btnPrev: ImageView
    private lateinit var btnPlay: ImageView
    private lateinit var btnNext: ImageView
    private lateinit var btnMode: ImageView
    private lateinit var btnAddPlaylist: ImageView
    private lateinit var btnHeart: ImageView
    private lateinit var btnQueue: ImageView
    private lateinit var lyricBox: LinearLayout
    private lateinit var tvPrev: TextView
    private lateinit var tvCur: TextView
    private lateinit var tvNext: TextView

    private var lines: List<LrcLine> = emptyList()
    private var lyricLoaded = false
    private var currentLine = -1
    private var dragSeeking = false
    private var lyricKey: String? = null
    private var attached = false
    private var hearted = false
    private var db: PlaylistDb? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncAll()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = syncAll()
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayButton()
        override fun onPlaybackStateChanged(state: Int) {
            updatePlayButton()
            updateProgress()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = updateModeIcon()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateModeIcon()

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!isAdded) return
            Tip.show(activity, "播放失败：${playbackErrorText(error)}")
        }
    }

    private val controllerListener: (MediaController?) -> Unit = { controller ->
        if (controller != null && !attached) {
            attached = true
            controller.addListener(playerListener)
            syncAll()
        }
        updatePlayButton()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = PlaylistDb(requireContext().applicationContext)

        cover = view.findViewById(R.id.cover)
        bgCover = view.findViewById(R.id.bg_cover)
        bgScrim = view.findViewById(R.id.bg_scrim)
        title = view.findViewById(R.id.title)
        artist = view.findViewById(R.id.artist)
        seek = view.findViewById(R.id.seek)
        // 细线进度条本身只有几 dp 高，把可拖拽区域上下各扩 20dp，手指更容易按中
        view.post { expandSeekTouch(view) }
        tvCurrent = view.findViewById(R.id.tv_current)
        tvTotal = view.findViewById(R.id.tv_total)
        btnPrev = view.findViewById(R.id.btn_prev)
        btnPlay = view.findViewById(R.id.btn_play)
        btnNext = view.findViewById(R.id.btn_next)
        btnMode = view.findViewById(R.id.btn_mode)
        btnAddPlaylist = view.findViewById(R.id.btn_add_playlist)
        btnHeart = view.findViewById(R.id.btn_heart)
        btnQueue = view.findViewById(R.id.btn_queue)
        lyricBox = view.findViewById(R.id.lyric_box)
        tvPrev = view.findViewById(R.id.tv_lyric_prev)
        tvCur = view.findViewById(R.id.tv_lyric_current)
        tvNext = view.findViewById(R.id.tv_lyric_next)
        applyLyricsStyle()
        applyAccent()

        view.findViewById<View>(R.id.btn_back).setOnClickListener { closePlayer() }

        btnPrev.setOnClickListener {
            pulse(it)
            PlaybackConnection.controller?.seekToPreviousMediaItem()
        }
        btnNext.setOnClickListener {
            pulse(it)
            PlaybackConnection.controller?.seekToNextMediaItem()
        }

        btnPlay.setOnClickListener {
            pulse(it)
            val c = PlaybackConnection.controller
            if (c == null) {
                PlaybackConnection.connect(requireContext(), force = true)
                Tip.show(activity, "播放器还没就绪，请稍等再点")
                return@setOnClickListener
            }
            if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
        }

        btnMode.setOnClickListener {
            Prefs.playMode = (Prefs.playMode + 1) % 3
            PlaybackConnection.controller?.let { PlaybackConnection.applyMode(it) }
            updateModeIcon()
            pulse(it)
            rotate(it)
            Tip.show(activity, modeLabel())
        }

        btnAddPlaylist.setOnClickListener {
            pulse(it)
            showAddToPlaylist()
        }

        btnHeart.setOnClickListener {
            pulse(it)
            toggleHeart()
        }

        btnQueue.setOnClickListener {
            pulse(it)
            if (parentFragmentManager.findFragmentByTag("queue") == null) {
                QueueSheet().show(parentFragmentManager, "queue")
            }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCurrent.text = formatDuration(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragSeeking = false
                PlaybackConnection.controller?.seekTo(seekBar.progress.toLong())
            }
        })

        // 左滑进歌词页（在 GestureLayout 的 dispatchTouchEvent 层判断，子 View 消费事件也不影响）
        view.findViewById<GestureLayout>(R.id.gesture_area).onSwipeLeft = { openLyrics() }
        // 再给一个明确可点的入口，避免手势在新机型上判不出来
        lyricBox.setOnClickListener { openLyrics() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closePlayer()
                }
            }
        )

        PlaybackConnection.connect(requireContext())
        PlaybackConnection.addListener(controllerListener)
        updateModeIcon()
        syncAll()
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

    private fun closePlayer() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        }
    }

    private fun openLyrics() {
        if (parentFragmentManager.findFragmentByTag("lyrics") != null) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, LyricsFragment(), "lyrics")
            .addToBackStack("lyrics")
            .commit()
    }

    // ---------------- 动效 ----------------

    /** 按下缩放：所有可点图标统一的手感 */
    private fun pulse(view: View) {
        view.animate().cancel()
        view.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            .start()
    }

    /** 模式切换时图标转一点，让人看到"换了一档" */
    private fun rotate(view: View) {
        view.animate().cancel()
        view.rotation = 0f
        view.animate().rotation(150f).setDuration(260)
            .withEndAction { view.animate().rotation(0f).setDuration(0).start() }
            .start()
    }

    // ---------------- 同步状态 ----------------

    private fun syncAll() {
        if (!isAdded || view == null) return
        val c = PlaybackConnection.controller
        val item = c?.currentMediaItem

        title.text = item?.mediaMetadata?.title?.toString() ?: "未在播放"
        artist.text = item?.mediaMetadata?.artist?.toString() ?: ""

        val mediaId = item?.mediaId?.toLongOrNull() ?: -1L
        bindCover(item, mediaId)
        updateHeartState(mediaId)

        val duration = c?.duration?.takeIf { it > 0 } ?: 0L
        seek.max = duration.toInt()
        seek.progress = (c?.currentPosition ?: 0L).toInt()
        tvCurrent.text = formatDuration((c?.currentPosition ?: 0L))
        tvTotal.text = formatDuration(duration)

        loadLyrics(item, mediaId)
        updatePlayButton()
        updateModeIcon()
    }

    private fun bindCover(item: MediaItem?, mediaId: Long) {
        bindBackground(item, mediaId)
        val artwork: Uri? = item?.mediaMetadata?.artworkUri
        if (artwork != null) {
            cover.load(artwork) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            return
        }
        val track = TrackCache.findById(mediaId)
        if (track == null) {
            cover.load(R.drawable.bg_cover_placeholder)
            return
        }
        val cached = CoverStore.peek(track)
        if (cached != null) {
            cover.load(cached) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        } else {
            cover.load(R.drawable.bg_cover_placeholder)
            CoverStore.request(track) { uri ->
                if (isAdded && view != null) {
                    cover.load(uri) { crossfade(true) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || view == null) return
        applyLyricsStyle()
        applyAccent()
        val item = PlaybackConnection.controller?.currentMediaItem
        bindBackground(item, item?.mediaId?.toLongOrNull() ?: -1L)
    }

    /**
     * 强调色：霓虹 / 暗橙 / 荧光三套深色主题下，播放键与整页图标取主题色；
     * 奶白与深灰保持原来的黑色播放键与深色图标。
     */
    /** 进度条细，命中区域放大到上下各 20dp */
    private fun expandSeekTouch(rootView: View) {
        if (!isAdded) return
        val root = rootView.findViewById<View>(R.id.player_root) ?: return
        if (seek.width <= 0) return
        val seekLoc = IntArray(2)
        val rootLoc = IntArray(2)
        seek.getLocationOnScreen(seekLoc)
        root.getLocationOnScreen(rootLoc)
        val pad = SettingsUi.dp(requireContext(), 20)
        val left = seekLoc[0] - rootLoc[0]
        val top = seekLoc[1] - rootLoc[1]
        root.touchDelegate = TouchDelegate(
            Rect(left, top - pad, left + seek.width, top + seek.height + pad),
            seek
        )
    }

    private fun applyAccent() {
        if (!isAdded || view == null || !::btnPlay.isInitialized) return
        val ctx = requireContext()
        val accent = Prefs.themeMode >= 2
        val iconColor = if (accent) SettingsUi.primary(ctx)
        else ContextCompat.getColor(ctx, R.color.on_bg)
        val tint = ColorStateList.valueOf(iconColor)

        listOf(btnPrev, btnNext, btnMode, btnAddPlaylist, btnQueue).forEach {
            it.imageTintList = tint
        }
        view?.findViewById<ImageView>(R.id.btn_back)?.imageTintList = tint

        if (accent) {
            btnPlay.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(SettingsUi.primary(ctx))
            }
            btnPlay.imageTintList = ColorStateList.valueOf(SettingsUi.onPrimary(ctx))
        } else {
            btnPlay.background = ContextCompat.getDrawable(ctx, R.drawable.bg_play_button)
            btnPlay.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.play_icon)
            )
        }
    }

    /** 歌词颜色 / 字号跟随设置页 */
    private fun applyLyricsStyle() {
        if (!isAdded || view == null || !::tvCur.isInitialized) return
        val color = LyricsStyle.color(requireContext())
        tvCur.setTextColor(color)
        tvCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, LyricsStyle.focusSize())
        tvPrev.setTextColor(color)
        tvNext.setTextColor(color)
        val side = (Prefs.lyricsSize - 2).coerceAtLeast(10).toFloat()
        tvPrev.setTextSize(TypedValue.COMPLEX_UNIT_SP, side)
        tvNext.setTextSize(TypedValue.COMPLEX_UNIT_SP, side)
    }

    /** 封面铺底 + 按设置模糊；Android 12 以下只叠封面，不做实时模糊 */
    private fun bindBackground(item: MediaItem?, mediaId: Long) {
        if (!isAdded || !::bgCover.isInitialized) return
        if (!Prefs.coverBackground) {
            bgCover.visibility = View.GONE
            bgScrim.visibility = View.GONE
            return
        }
        bgCover.visibility = View.VISIBLE
        bgScrim.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = Prefs.coverBlur
            bgCover.setRenderEffect(
                if (blur <= 0) null
                else RenderEffect.createBlurEffect(blur * 1.6f, blur * 1.6f, Shader.TileMode.CLAMP)
            )
        }
        val artwork = item?.mediaMetadata?.artworkUri
        val track = TrackCache.findById(mediaId)
        val source: Any? = artwork ?: track?.let { CoverStore.peek(it) } ?: track?.albumArtUri
        if (source != null) {
            bgCover.load(source) { crossfade(true) }
        } else if (track != null) {
            CoverStore.request(track) { uri ->
                if (isAdded && view != null) bgCover.load(uri) { crossfade(true) }
            }
        }
    }

    private fun updatePlayButton() {
        if (!isAdded || view == null) return
        val playing = PlaybackConnection.controller?.isPlaying == true
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun modeLabel(): String = when (Prefs.playMode) {
        1 -> "随机播放"
        2 -> "单曲循环"
        else -> "顺序播放"
    }

    private fun updateModeIcon() {
        if (!isAdded || view == null) return
        btnMode.setImageResource(
            when (Prefs.playMode) {
                1 -> R.drawable.ic_mode_shuffle
                2 -> R.drawable.ic_mode_repeat_one
                else -> R.drawable.ic_mode_order
            }
        )
    }

    private fun updateProgress() {
        if (!isAdded || view == null) return
        val c = PlaybackConnection.controller ?: return
        val duration = c.duration
        if (duration > 0 && seek.max != duration.toInt()) {
            seek.max = duration.toInt()
            tvTotal.text = formatDuration(duration)
        }
        if (!dragSeeking) {
            val pos = c.currentPosition.coerceAtLeast(0)
            seek.progress = pos.toInt()
            tvCurrent.text = formatDuration(pos)
        }
        highlightLyrics(c.currentPosition)
    }

    // ---------------- 收藏 / 加入歌单 ----------------

    private fun favoritePlaylistId(): Long? =
        db?.playlists()?.firstOrNull { it.name == "我喜欢" }?.id
            ?: db?.playlists()?.firstOrNull()?.id

    private fun updateHeartState(mediaId: Long) {
        if (!isAdded || view == null) return
        val favId = favoritePlaylistId()
        hearted = mediaId > 0 && favId != null &&
                (db?.trackIds(favId)?.contains(mediaId) == true)
        btnHeart.setImageResource(if (hearted) R.drawable.ic_heart_on else R.drawable.ic_heart)
        btnHeart.imageTintList = ColorStateList.valueOf(
            if (hearted) SettingsUi.primary(requireContext())
            else ContextCompat.getColor(requireContext(), R.color.on_bg_sub)
        )
    }

    private fun toggleHeart() {
        val mediaId = PlaybackConnection.controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val favId = favoritePlaylistId()
        if (mediaId == null || mediaId <= 0 || favId == null || db == null) {
            Tip.show(activity, "还没有可收藏的歌曲")
            return
        }
        if (hearted) {
            db?.removeTrack(favId, mediaId)
            hearted = false
            Tip.show(activity, "已取消收藏")
        } else {
            db?.addTrack(favId, mediaId)
            hearted = true
            Tip.show(activity, "已收藏到「我喜欢」")
        }
        btnHeart.setImageResource(if (hearted) R.drawable.ic_heart_on else R.drawable.ic_heart)
        btnHeart.imageTintList = ColorStateList.valueOf(
            if (hearted) SettingsUi.primary(requireContext())
            else ContextCompat.getColor(requireContext(), R.color.on_bg_sub)
        )
        btnHeart.animate().cancel()
        btnHeart.scaleX = if (hearted) 1.25f else 0.9f
        btnHeart.scaleY = if (hearted) 1.25f else 0.9f
        btnHeart.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun showAddToPlaylist() {
        val mediaId = PlaybackConnection.controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val lists = db?.playlists().orEmpty()
        if (mediaId == null || mediaId <= 0) {
            Tip.show(activity, "还没有可添加的歌曲")
            return
        }
        if (lists.isEmpty()) {
            AppDialog.input(requireContext(), "新建歌单", "歌单名称", "", "创建") { name ->
                val id = db?.createPlaylist(name.ifBlank { "新歌单" }) ?: return@input
                db?.addTrack(id, mediaId)
                Tip.show(activity, "已添加到「${name.ifBlank { "新歌单" }}」")
            }
            return
        }
        val items = lists.map { it.name to "${it.count} 首" }
        AppDialog.list(
            requireContext(),
            "添加到歌单",
            items,
            onPick = { index ->
                val list = lists[index]
                val added = db?.addTrack(list.id, mediaId) == true
                Tip.show(
                    activity,
                    if (added) "已添加到「${list.name}」" else "「${list.name}」里已经有这首了"
                )
                if (list.name == "我喜欢") updateHeartState(mediaId)
            },
            extraLabel = "＋ 新建歌单",
            onExtra = {
                AppDialog.input(requireContext(), "新建歌单", "歌单名称", "", "创建") { name ->
                    val id = db?.createPlaylist(name.ifBlank { "新歌单" }) ?: return@input
                    db?.addTrack(id, mediaId)
                    Tip.show(activity, "已添加到「${name.ifBlank { "新歌单" }}」")
                }
            }
        )
    }

    // ---------------- 歌词 ----------------

    private fun loadLyrics(item: MediaItem?, mediaId: Long) {
        val titleText = item?.mediaMetadata?.title?.toString().orEmpty()
        val artistText = item?.mediaMetadata?.artist?.toString().orEmpty()
        val track = TrackCache.findById(mediaId)
        val key = if (track != null) "t:${track.id}" else "o:$titleText|$artistText"
        if (key == lyricKey) return
        lyricKey = key
        lines = emptyList()
        lyricLoaded = false
        currentLine = -1
        renderLines(animate = false)

        if (track == null && titleText.isBlank()) {
            lyricLoaded = true
            renderLines(animate = false)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (track != null) {
                LyricsRepository.load(track)
            } else {
                LyricsRepository.load(
                    key,
                    titleText,
                    artistText,
                    item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                    PlaybackConnection.controller?.duration ?: 0L
                )
            }
            if (!isAdded || lyricKey != key) return@launch
            lines = result
            lyricLoaded = true
            currentLine = -1
            renderLines(animate = false)
            highlightLyrics(PlaybackConnection.controller?.currentPosition ?: 0L, force = true)
        }
    }

    private fun highlightLyrics(positionMs: Long, force: Boolean = false) {
        if (lines.isEmpty()) return
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs + 300) index = i else break
        }
        if (!force && index == currentLine) return
        val changed = index != currentLine
        currentLine = index
        renderLines(animate = changed)
    }

    /** 主播放页只显示「上一行 / 当前行 / 下一行」；切句时上下滚动淡入 */
    private fun renderLines(animate: Boolean) {
        if (!::tvPrev.isInitialized) return
        if (!lyricLoaded) {
            tvPrev.text = ""
            tvCur.text = ""
            tvNext.text = ""
            return
        }
        if (lines.isEmpty()) {
            tvPrev.text = ""
            tvCur.text = getString(R.string.no_lyrics)
            tvCur.alpha = 0.6f
            tvNext.text = ""
            return
        }
        val i = currentLine
        tvPrev.text = if (i - 1 in lines.indices) lines[i - 1].text else ""
        tvCur.text = if (i in lines.indices) lines[i].text else ""
        tvNext.text = when {
            i + 1 in lines.indices -> lines[i + 1].text
            i < 0 -> lines.first().text
            else -> ""
        }
        // 还没到第一句时，把第一句当"下一句"显示，中间留白
        if (i < 0) {
            tvCur.text = ""
            tvNext.text = lines.first().text
        }

        if (animate) {
            animateLines()
        } else {
            resetLineVisuals()
        }
    }

    private fun resetLineVisuals() {
        tvPrev.alpha = ALPHA_PREV
        tvCur.alpha = 1f
        tvNext.alpha = ALPHA_NEXT
        tvPrev.translationY = 0f
        tvCur.translationY = 0f
        tvNext.translationY = 0f
        tvCur.scaleX = 1f
        tvCur.scaleY = 1f
        lyricBox.translationY = 0f
    }

    /** 切句：新句从下方浮起放大，旧句上移淡出，整块歌词有一小段滚动位移 */
    private fun animateLines() {
        val shift = dp(8).toFloat()
        tvPrev.alpha = 0f
        tvPrev.translationY = -shift
        tvCur.alpha = 0.2f
        tvCur.translationY = dp(14).toFloat()
        tvCur.scaleX = 0.94f
        tvCur.scaleY = 0.94f
        tvNext.alpha = 0f
        tvNext.translationY = dp(10).toFloat()

        tvPrev.animate().alpha(ALPHA_PREV).translationY(0f).setDuration(300).start()
        tvCur.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320).start()
        tvNext.animate().alpha(ALPHA_NEXT).translationY(0f).setDuration(300).start()

        lyricBox.animate().cancel()
        lyricBox.translationY = shift
        lyricBox.animate().translationY(0f).setDuration(340).start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ALPHA_PREV = 0.55f
        const val ALPHA_NEXT = 0.42f
    }
}
