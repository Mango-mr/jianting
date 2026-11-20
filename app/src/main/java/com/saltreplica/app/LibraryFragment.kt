package com.saltreplica.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import coil.load
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private lateinit var adapter: LibraryAdapter
    private lateinit var db: PlaylistDb
    private lateinit var tabs: TabLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tvEmpty: TextView
    private lateinit var btnGrant: TextView
    private lateinit var etSearch: EditText
    private lateinit var miniBar: View
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var tvSort: View
    private lateinit var tvSettings: View
    private lateinit var detailBar: View
    private lateinit var tvDetailTitle: TextView

    private var allSongs: List<Track> = emptyList()
    private var currentTab = TAB_SONGS
    private var query = ""
    private var scanning = false
    private var attachedToController = false

    /** 进入分组/歌单详情时的状态 */
    private var detailTitle: String? = null
    private var detailSongs: List<Track> = emptyList()
    private var currentPlaylistId = -1L
    private var currentPlaylistName = ""

    private var lastErrorShown = 0L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateMiniBar()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateMiniBar()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateMiniBar()

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!isAdded) return
            val now = System.currentTimeMillis()
            if (now - lastErrorShown < 3000) return
            lastErrorShown = now
            val detail = playbackErrorText(error)
            Tip.show(activity, "播放失败：$detail")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.any { it }) loadLibrary() else showPermissionEmpty()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = PlaylistDb(requireContext())

        tabs = view.findViewById(R.id.tabs)
        recycler = view.findViewById(R.id.recycler)
        emptyView = view.findViewById(R.id.empty_view)
        tvEmpty = view.findViewById(R.id.tv_empty)
        btnGrant = view.findViewById(R.id.btn_grant)
        etSearch = view.findViewById(R.id.et_search)
        miniBar = view.findViewById(R.id.mini_bar)
        miniCover = view.findViewById(R.id.mini_cover)
        miniTitle = view.findViewById(R.id.mini_title)
        miniSub = view.findViewById(R.id.mini_sub)
        miniPlay = view.findViewById(R.id.mini_play)
        tvSort = view.findViewById(R.id.tv_sort)
        tvSettings = view.findViewById(R.id.tv_settings)
        detailBar = view.findViewById(R.id.detail_bar)
        tvDetailTitle = view.findViewById(R.id.tv_detail_title)

        view.findViewById<View>(R.id.btn_detail_back).setOnClickListener { exitDetail() }

        // 深色强调主题下，迷你条的上下一曲也跟随主题色
        if (Prefs.themeMode >= 2) {
            val tint = ColorStateList.valueOf(SettingsUi.primary(requireContext()))
            view.findViewById<ImageView>(R.id.mini_prev)?.imageTintList = tint
            view.findViewById<ImageView>(R.id.mini_next)?.imageTintList = tint
        }

        tvSort.setOnClickListener { showSortDialog() }
        tvSettings.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment(), "settings")
                .addToBackStack("settings")
                .commit()
        }

        adapter = LibraryAdapter(object : LibraryAdapter.Listener {
            override fun onSongClick(position: Int) = playFrom(position)
            override fun onSongLongClick(position: Int) = onSongLongPressed(position)
            override fun onGroupClick(position: Int) = onGroupClicked(position)
            override fun onGroupLongClick(position: Int) = onGroupLongPressed(position)
            override fun onPlaylistClick(position: Int) = onPlaylistClicked(position)
            override fun onPlaylistLongClick(position: Int) = onPlaylistLongPressed(position)
        })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        setupTabs()
        setupSearch()
        setupMiniBar()

        btnGrant.setOnClickListener {
            permissionLauncher.launch(audioPermissions())
        }

        view.findViewById<View>(R.id.btn_scan).setOnClickListener {
            if (hasAudioPermission()) loadLibrary() else permissionLauncher.launch(audioPermissions())
        }

        view.findViewById<View>(R.id.btn_source).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, OnlineFragment(), "online")
                .addToBackStack("online")
                .commit()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (detailTitle != null) {
                        exitDetail()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        runCatching { PlaybackConnection.connect(requireContext()) }
        runCatching { PlaybackConnection.addListener(controllerListener) }

        // 延后到首帧之后再申请权限 / 扫描，避免首帧阶段做重活
        view.post {
            if (!isAdded) return@post
            // 先显示上次扫描的缓存，列表秒出
            if (allSongs.isEmpty()) {
                val cached = runCatching { TrackStore.load(requireContext()) }.getOrDefault(emptyList())
                    .filterNot { Prefs.isHidden(it.id) }
                if (cached.isNotEmpty()) {
                    allSongs = cached
                    TrackCache.songs = cached
                    render()
                }
            }
            if (hasAudioPermission()) {
                // 自动扫描只在 App 本次启动后跑一次；从播放页 / 设置页返回时不再重扫
                // 曲库为空时无论开关如何都扫一次，否则列表永远是空的
                if (!autoScanDone && (allSongs.isEmpty() || Prefs.autoScan)) {
                    autoScanDone = true
                    loadLibrary()
                }
            } else {
                if (allSongs.isEmpty()) showPermissionEmpty()
                runCatching { permissionLauncher.launch(audioPermissions()) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded) return
        // 从设置页「立即扫描」回来时曲库可能已经换了，引用不同就同步过来，不必重扫
        if (TrackCache.songs !== allSongs) {
            allSongs = TrackCache.songs
            render()
        }
        updateMiniBar()
    }

    private val controllerListener: (MediaController?) -> Unit = { controller ->
        if (controller != null && !attachedToController) {
            attachedToController = true
            controller.addListener(playerListener)
        }
        updateMiniBar()
    }

    // ---------------- 权限与扫描 ----------------

    private fun audioPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasAudioPermission(): Boolean {
        val permission = audioPermissions()[0]
        return ContextCompat.checkSelfPermission(
            requireContext(), permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionEmpty() {
        emptyView.visibility = View.VISIBLE
        tvEmpty.text = getString(R.string.permission_needed)
        btnGrant.visibility = View.VISIBLE
        adapter.submit(emptyList())
    }

    private fun loadLibrary() {
        if (scanning) return
        val ctx = context ?: return
        scanning = true
        emptyView.visibility = View.VISIBLE
        tvEmpty.text = getString(R.string.scanning)
        btnGrant.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val songs = MusicRepository.queryAsync(ctx)
            // 已「从曲库移除」的歌曲不再进列表，文件本身仍在手机里
            allSongs = songs.filterNot { Prefs.isHidden(it.id) }
            TrackCache.songs = allSongs
            scanning = false
            runCatching { TrackStore.save(requireContext(), songs) }
            if (!isAdded) return@launch
            render()
            val tip = if (songs.isEmpty())
                "没找到音频文件，请确认权限已授予，且手机里有 mp3/flac/m4a 等文件"
            else
                "找到 ${songs.size} 首歌曲"
            Tip.show(activity, tip)
        }
    }

    // ---------------- 导航 ----------------

    private fun setupTabs() {
        // 顺序：歌单 | 全部 | 歌手 | 专辑 | 文件夹，每项都是「图标 + 文字」
        val specs = listOf(
            R.string.tab_playlists to R.drawable.ic_tab_playlist,
            R.string.tab_songs to R.drawable.ic_tab_all,
            R.string.tab_artists to R.drawable.ic_tab_artist,
            R.string.tab_albums to R.drawable.ic_tab_album,
            R.string.tab_folders to R.drawable.ic_tab_folder
        )
        specs.forEach { (titleRes, iconRes) ->
            val tab = tabs.newTab()
            val custom = layoutInflater.inflate(R.layout.tab_item, tabs, false)
            custom.findViewById<TextView>(R.id.tab_title).text = getString(titleRes)
            custom.findViewById<ImageView>(R.id.tab_icon).setImageResource(iconRes)
            tab.customView = custom
            tabs.addTab(tab)
        }
        tabs.getTabAt(TAB_SONGS)?.select()
        currentTab = TAB_SONGS
        styleTabs()

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                detailTitle = null
                detailSongs = emptyList()
                currentPlaylistId = -1
                currentPlaylistName = ""
                styleTabs()
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    /** 自定义 tab 不跟随 tabTextColor，选中态要自己刷 */
    private fun styleTabs() {
        if (!isAdded) return
        for (i in 0 until tabs.tabCount) {
            val custom = tabs.getTabAt(i)?.customView ?: continue
            val color = ContextCompat.getColor(
                requireContext(),
                if (i == currentTab) R.color.on_bg else R.color.on_bg_sub
            )
            custom.findViewById<TextView>(R.id.tab_title)?.setTextColor(color)
            custom.findViewById<ImageView>(R.id.tab_icon)?.setColorFilter(color)
        }
    }

    private fun setupSearch() {
        etSearch.doAfterTextChanged {
            query = it?.toString()?.trim() ?: ""
            render()
        }
    }

    private fun setupMiniBar() {
        miniBar.setOnClickListener {
            if (PlaybackConnection.controller?.currentMediaItem != null) {
                (requireActivity() as MainActivity).openPlayer()
            }
        }
        miniPlay.setOnClickListener {
            val c = PlaybackConnection.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        view?.findViewById<View>(R.id.mini_prev)?.setOnClickListener {
            PlaybackConnection.controller?.seekToPreviousMediaItem()
            updateMiniBar()
        }
        view?.findViewById<View>(R.id.mini_next)?.setOnClickListener {
            PlaybackConnection.controller?.seekToNextMediaItem()
        }
        updateMiniBar()
    }

    private fun updateMiniBar() {
        if (!isAdded || view == null) return
        val c = PlaybackConnection.controller
        val item = c?.currentMediaItem
        if (item == null) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = item.mediaMetadata.title ?: "未知歌曲"
        val artist = item.mediaMetadata.artist?.toString() ?: ""
        miniSub.text = artist.ifBlank { "正在播放" }
        item.mediaMetadata.artworkUri?.let { miniCover.load(it) { crossfade(true) } }
        miniPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---------------- 列表 ----------------

    private fun render() {
        if (!isAdded) return
        val q = query.lowercase()
        val inDetail = detailTitle != null

        detailBar.visibility = if (inDetail) View.VISIBLE else View.GONE
        tabs.visibility = if (inDetail) View.GONE else View.VISIBLE
        tvDetailTitle.text = detailTitle.orEmpty()
        tvSort.visibility = if (currentTab == TAB_SONGS && !inDetail) View.VISIBLE else View.GONE

        if (inDetail) {
            val list = if (q.isBlank()) detailSongs else detailSongs.filter { matches(it, q) }
            adapter.submit(list.map { Row.Song(it) })
            showEmptyIfNeeded(if (list.isEmpty()) getString(R.string.empty_search) else null)
            return
        }

        when (currentTab) {
            TAB_PLAYLISTS -> {
                val rows = mutableListOf<Row>()
                rows.add(Row.Group("＋ 新建歌单", "创建一个新歌单"))
                val sample = allSongs.firstOrNull()
                db.playlists().forEach {
                    rows.add(Row.PlaylistRow(it.id, it.name, it.count, sample))
                }
                adapter.submit(rows)
                showEmptyIfNeeded(null)
            }
            TAB_SONGS -> {
                val base = sortSongs(allSongs)
                val list = if (q.isBlank()) base else base.filter { matches(it, q) }
                adapter.submit(list.map { Row.Song(it) })
                showEmptyIfNeeded(
                    when {
                        allSongs.isEmpty() -> getString(R.string.empty_songs)
                        list.isEmpty() -> getString(R.string.empty_search)
                        else -> null
                    }
                )
            }
            else -> {
                val keyOf: (Track) -> String = when (currentTab) {
                    TAB_ALBUMS -> { t -> t.albumSafe }
                    TAB_ARTISTS -> { t -> t.artistSafe }
                    else -> { t -> t.folder }
                }
                var groups = allSongs.groupBy(keyOf)
                    .map { (name, songs) ->
                        val sorted = songs.sortedBy { it.titleSafe.lowercase() }
                        Row.Group(name, "${songs.size} 首", sorted.firstOrNull(), songs)
                    }
                    .sortedBy { it.name.lowercase() }
                if (q.isNotBlank()) {
                    groups = groups.filter { it.name.lowercase().contains(q) }
                }
                adapter.submit(groups)
                showEmptyIfNeeded(
                    when {
                        groups.isEmpty() -> getString(R.string.empty_search)
                        else -> null
                    }
                )
            }
        }
    }

    private fun matches(track: Track, q: String): Boolean =
        track.titleSafe.lowercase().contains(q) ||
                track.artistSafe.lowercase().contains(q) ||
                track.albumSafe.lowercase().contains(q)

    private fun sortSongs(list: List<Track>): List<Track> = when (Prefs.sortMode) {
        1 -> list.sortedBy { it.artistSafe.lowercase() }
        2 -> list.sortedByDescending { it.durationMs }
        3 -> list.sortedByDescending { it.dateAdded }
        else -> list.sortedBy { it.titleSafe.lowercase() }
    }

    private fun showSortDialog() {
        val names = listOf("按标题", "按歌手", "按时长（长→短）", "按加入时间（新→旧）")
        AppDialog.list(
            requireContext(),
            "排序方式",
            names.map { it to null },
            onPick = { which ->
                Prefs.sortMode = which
                render()
            },
            currentIndex = Prefs.sortMode
        )
    }

    private fun showEmptyIfNeeded(message: String?) {
        if (message == null) {
            emptyView.visibility = View.GONE
        } else {
            emptyView.visibility = View.VISIBLE
            tvEmpty.text = message
            btnGrant.visibility = View.GONE
        }
    }

    // ---------------- 点击处理 ----------------

    /**
     * 文件夹 tab 长按整组隐藏：只是不在列表里显示，手机里的音频文件不动。
     * 其它 tab 的分组（歌手 / 专辑）不做整组删除，避免误操作。
     */
    private fun onGroupLongPressed(position: Int) {
        val row = adapterRow(position) as? Row.Group ?: return
        if (currentTab != TAB_FOLDERS) return
        if (row.tracks.isEmpty()) return
        AppDialog.confirm(
            requireContext(),
            "隐藏这个文件夹？",
            "「${row.name}」下的 ${row.tracks.size} 首歌将不再显示在简听里，手机里的文件不会被删除。",
            positive = "隐藏"
        ) {
            Prefs.hideAll(row.tracks.map { it.id })
            allSongs = allSongs.filterNot { Prefs.isHidden(it.id) }
            TrackCache.songs = allSongs
            render()
            Tip.show(activity, "已隐藏「${row.name}」")
        }
    }

    private fun onGroupClicked(position: Int) {
        if (currentTab == TAB_PLAYLISTS) {
            showCreatePlaylistDialog { name ->
                db.createPlaylist(name)
                render()
            }
            return
        }
        val row = adapterRow(position) as? Row.Group ?: return
        val keyOf: (Track) -> String = when (currentTab) {
            TAB_ALBUMS -> { t -> t.albumSafe }
            TAB_ARTISTS -> { t -> t.artistSafe }
            else -> { t -> t.folder }
        }
        detailTitle = row.name
        detailSongs = allSongs.filter { keyOf(it) == row.name }
            .sortedBy { it.titleSafe.lowercase() }
        currentPlaylistId = -1
        currentPlaylistName = ""
        render()
    }

    private fun onPlaylistClicked(position: Int) {
        val row = adapterRow(position) as? Row.PlaylistRow ?: return
        val ids = db.trackIds(row.id)
        val ordered = ids.mapNotNull { id -> allSongs.firstOrNull { it.id == id } }
        detailTitle = row.name
        detailSongs = ordered
        currentPlaylistId = row.id
        currentPlaylistName = row.name
        render()
    }

    private fun onPlaylistLongPressed(position: Int) {
        val row = adapterRow(position) as? Row.PlaylistRow ?: return
        AppDialog.list(
            requireContext(),
            row.name,
            listOf("重命名" to null, "删除歌单" to null),
            onPick = { which ->
                if (which == 0) {
                    showCreatePlaylistDialog(initial = row.name) { name ->
                        db.renamePlaylist(row.id, name)
                        render()
                    }
                } else {
                    AppDialog.confirm(
                        requireContext(),
                        "删除歌单",
                        "「${row.name}」会被删除，里面的歌曲不会被删掉。",
                        positive = "删除"
                    ) {
                        db.deletePlaylist(row.id)
                        render()
                    }
                }
            }
        )
    }

    /**
     * 长按歌曲：操作行带图标，「添加到歌单」排第一行并加粗，
     * 歌曲信息不再进二级弹框，直接摊在弹窗最后一块。
     */
    private fun onSongLongPressed(position: Int) {
        val track = (adapterRow(position) as? Row.Song)?.track ?: return
        val actions = mutableListOf<AppDialog.Action>()

        if (currentPlaylistId >= 0) {
            actions += AppDialog.Action(
                "添加到其他歌单", R.drawable.ic_menu_playlist, bold = true
            ) { addToPlaylistDialog(track) }
            actions += AppDialog.Action("从当前歌单移除", R.drawable.ic_menu_remove) {
                db.removeTrack(currentPlaylistId, track.id)
                detailSongs = detailSongs.filter { it.id != track.id }
                render()
                Tip.show(activity, "已从「$currentPlaylistName」移除")
            }
        } else {
            actions += AppDialog.Action(
                "添加到歌单", R.drawable.ic_menu_playlist, bold = true
            ) { addToPlaylistDialog(track) }
        }
        actions += AppDialog.Action("从曲库移除", R.drawable.ic_menu_remove) {
            confirmRemoveFromLibrary(track)
        }

        AppDialog.songMenu(
            requireContext(),
            track.titleSafe,
            track.artistSafe,
            actions,
            trackInfoLines(track)
        )
    }

    /** 摊在弹窗底部的歌曲信息 */
    private fun trackInfoLines(track: Track): List<Pair<String, String>> {
        val format = track.path.substringAfterLast('.', "").uppercase().ifBlank { "未知" }
        return listOf(
            "歌手" to track.artistSafe,
            "专辑" to track.albumSafe,
            "时长" to formatDuration(track.durationMs),
            "格式" to format,
            "大小" to if (track.size > 0) formatSize(track.size) else "未知",
            "文件夹" to track.folder
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 长按删除：只把这首歌移出曲库列表，不动手机里的音频文件 */
    private fun confirmRemoveFromLibrary(track: Track) {
        AppDialog.confirm(
            requireContext(),
            "从曲库移除",
            "「${track.titleSafe}」会从列表里消失，手机里的音频文件不会被删除。",
            positive = "移除"
        ) {
            Prefs.hide(track.id)
            allSongs = allSongs.filter { it.id != track.id }
            TrackCache.songs = allSongs
            if (detailTitle != null) detailSongs = detailSongs.filter { it.id != track.id }
            render()
            Tip.show(activity, "已从曲库移除")
        }
    }

    private fun adapterRow(position: Int): Row? = adapter.rowAt(position)

    private fun exitDetail() {
        detailTitle = null
        detailSongs = emptyList()
        currentPlaylistId = -1
        currentPlaylistName = ""
        render()
    }

    // ---------------- 歌单 ----------------

    private fun addToPlaylistDialog(track: Track) {
        val lists = db.playlists()
        if (lists.isEmpty()) {
            showCreatePlaylistDialog { name ->
                val id = db.createPlaylist(name)
                db.addTrack(id, track.id)
                Tip.show(activity, "已添加到「$name」")
                render()
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
                val added = db.addTrack(list.id, track.id)
                Tip.show(
                    activity,
                    if (added) "已添加到「${list.name}」" else "「${list.name}」里已经有这首了"
                )
                render()
            },
            extraLabel = "＋ 新建歌单",
            onExtra = {
                showCreatePlaylistDialog { name ->
                    val id = db.createPlaylist(name)
                    db.addTrack(id, track.id)
                    Tip.show(activity, "已添加到「$name」")
                    render()
                }
            }
        )
    }

    private fun showCreatePlaylistDialog(initial: String = "", onCreated: (String) -> Unit) {
        AppDialog.input(
            requireContext(),
            if (initial.isBlank()) "新建歌单" else "重命名歌单",
            "歌单名称",
            initial,
            positive = "确定"
        ) { name ->
            onCreated(name.ifBlank { "新歌单" })
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ---------------- 播放 ----------------

    private fun playFrom(position: Int) {
        val clicked = adapter.rowAt(position) as? Row.Song ?: return
        val songs = adapter.currentSongs()
        val index = songs.indexOfFirst { it.id == clicked.track.id }.coerceAtLeast(0)
        val items = songs.map { toMediaItem(it) }
        PlaybackConnection.request(requireContext()) { controller ->
            if (!isAdded) return@request
            if (controller == null) {
                val reason = PlaybackConnection.lastError
                Tip.show(
                    activity,
                    if (reason != null) "播放服务启动失败：$reason" else "播放服务还没就绪，请再点一次"
                )
                return@request
            }
            controller.setMediaItems(items, index, 0L)
            PlaybackConnection.applyMode(controller)
            runCatching { controller.setPlaybackSpeed(Prefs.speed) }
            controller.prepare()
            controller.play()
            Prefs.lastTrackId = clicked.track.id
            (requireActivity() as? MainActivity)?.openPlayer()
        }
    }

    private fun toMediaItem(track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.titleSafe)
                    .setArtist(track.artistSafe)
                    .setAlbumTitle(track.albumSafe)
                    .setArtworkUri(track.albumArtUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    override fun onDestroyView() {
        PlaybackConnection.removeListener(controllerListener)
        PlaybackConnection.controller?.let { c ->
            if (attachedToController) {
                c.removeListener(playerListener)
                attachedToController = false
            }
        }
        super.onDestroyView()
    }

    companion object {
        private const val TAB_PLAYLISTS = 0
        private const val TAB_SONGS = 1
        private const val TAB_ARTISTS = 2
        private const val TAB_ALBUMS = 3
        private const val TAB_FOLDERS = 4

        /** 本次进程是否已经自动扫描过，避免每次返回音乐库都重扫 */
        private var autoScanDone = false
    }
}
