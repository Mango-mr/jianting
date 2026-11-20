package com.saltreplica.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 音乐源：管理自定义音源，搜索 / 试听 / 下载在线歌曲 */
class OnlineFragment : Fragment() {

    companion object {
        private const val ID_KUWO = -2L
        private const val ID_NETEASE = -3L
        private const val ID_KUGOU = -4L
    }

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvHint: TextView
    private lateinit var etSearch: EditText

    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var onlineAdapter: OnlineAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var showingResults = false
    private val watchingDownloads = HashMap<Long, OnlineTrack>()
    private var downloadWatch: Runnable? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Accounts.init(requireContext())
        refreshSources()
        Tip.show(activity, "账号状态已更新")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_online, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MusicSourceStore.init(requireContext())
        Accounts.init(requireContext())

        recycler = view.findViewById(R.id.recycler)
        tvEmpty = view.findViewById(R.id.tv_empty)
        tvHint = view.findViewById(R.id.tv_hint)
        etSearch = view.findViewById(R.id.et_search)

        recycler.layoutManager = LinearLayoutManager(requireContext())

        sourceAdapter = SourceAdapter(object : SourceAdapter.Listener {
            override fun onAdd() = showAddSourceDialog()
            override fun onToggle(position: Int) = toggleSource(position)
            override fun onRow(position: Int) = openProviderOrSource(position)
            override fun onDelete(position: Int) = confirmDeleteSource(position)
            override fun onMore() = openMoreMusic()
        })

        onlineAdapter = OnlineAdapter(
            onPlay = { playOnline(it) },
            onDownload = { download(it) }
        )

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_help).setOnClickListener { showProtocolDialog() }

        etSearch.doAfterTextChanged { text ->
            pendingSearch?.let { handler.removeCallbacks(it) }
            val keyword = text?.toString()?.trim().orEmpty()
            pendingSearch = Runnable { runSearch(keyword) }.also {
                handler.postDelayed(it, 550)
            }
        }

        showSources()
    }

    override fun onDestroyView() {
        pendingSearch?.let { handler.removeCallbacks(it) }
        downloadWatch?.let { handler.removeCallbacks(it) }
        downloadWatch = null
        super.onDestroyView()
    }

    // ---------------- 音源管理 ----------------

    private fun refreshSources() {
        sourceAdapter.submit(listShown())
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            LxEngine.warmUp(ctx, MusicSourceStore.all())
            if (isAdded) sourceAdapter.submit(listShown())
        }
    }

    /** 列表 = 内置源标题 + 酷我/网易云/酷狗三个独立开关 + 用户自建源 */
    private fun listShown(): List<MusicSource> = listOf(
        BuiltinSource.builtinRow(),
        providerRow(Accounts.KUWO, "酷我", ID_KUWO),
        providerRow(Accounts.NETEASE, "网易云", ID_NETEASE),
        providerRow(Accounts.KUGOU, "酷狗", ID_KUGOU)
    ) + MusicSourceStore.all()

    private fun providerRow(site: String, label: String, id: Long): MusicSource {
        val on = Accounts.providerEnabled(site)
        val logged = Accounts.loggedIn(site)
        val nick = Accounts.nickname(site)
        val status = when {
            logged && nick.isNotBlank() -> "已登录：$nick"
            logged -> "已登录"
            else -> "未登录 · 点此登录（可听会员曲目）"
        }
        return MusicSource(
            id = id,
            name = label,
            url = if (on) status else "$status · 已关闭",
            enabled = on
        )
    }

    private fun showSources() {
        showingResults = false
        refreshSources()
        recycler.adapter = sourceAdapter
        tvHint.visibility = View.GONE
        // 内置源固定可用，列表页不需要再摆一段空态文案
        tvEmpty.visibility = View.GONE
    }

    private fun toggleSource(position: Int) {
        val source = listShown().getOrNull(position) ?: return
        when (source.id) {
            ID_KUWO, ID_NETEASE, ID_KUGOU -> {
                val site = when (source.id) {
                    ID_KUWO -> Accounts.KUWO
                    ID_KUGOU -> Accounts.KUGOU
                    else -> Accounts.NETEASE
                }
                Accounts.toggleProvider(site)
                refreshSources()
                Tip.show(
                    activity,
                    if (Accounts.providerEnabled(site)) "已开启${source.name}搜索" else "已关闭${source.name}搜索"
                )
            }
            else -> {
                if (source.id < 0) {
                    Tip.show(activity, "点行右侧的开关，单独开关它们")
                    return
                }
                MusicSourceStore.toggle(source.id)
                val now = MusicSourceStore.all().firstOrNull { it.id == source.id }?.enabled ?: !source.enabled
                if (source.isLx && !now) LxEngine.close(source.id)
                refreshSources()
                if (source.isLx) {
                    Tip.show(activity, if (now) "已启用「${source.name}」" else "已关闭「${source.name}」")
                }
            }
        }
    }

    /** 点整行：内置源打开登录/账号管理，自建源只做提示 */
    private fun openProviderOrSource(position: Int) {
        val source = listShown().getOrNull(position) ?: return
        when (source.id) {
            ID_KUWO -> showAccount(Accounts.KUWO)
            ID_NETEASE -> showAccount(Accounts.NETEASE)
            ID_KUGOU -> showAccount(Accounts.KUGOU)
            BuiltinSource.BUILTIN_ID -> Tip.show(activity, "下面的酷我、网易云、酷狗可以单独开关，也可以点行登录账号")
            else -> Tip.show(activity, "点右侧开关可启用/关闭该音源")
        }
    }

    private fun showAccount(site: String) {
        val label = when (site) {
            Accounts.KUWO -> "酷我"
            Accounts.KUGOU -> "酷狗"
            else -> "网易云"
        }
        if (!Accounts.loggedIn(site)) {
            AppDialog.list(
                requireContext(),
                "$label 登录",
                listOf(
                    "网页登录" to "在 App 内打开$label 登录页，登录后自动保存",
                    "粘贴 Cookie 登录" to "网页登录走不通时用这个：从浏览器复制整段 Cookie 粘进来"
                ),
                onPick = { index ->
                    if (index == 0) openLogin(site) else promptCookieImport(site)
                }
            )
            return
        }
        val nick = Accounts.nickname(site)
        AppDialog.list(
            requireContext(),
            "$label 账号",
            listOf(
                "重新登录" to "换一个账号，或登录已失效时重试",
                "粘贴 Cookie 登录" to "用浏览器里的 Cookie 覆盖当前登录",
                "退出登录" to "清除本机保存的 Cookie"
            ),
            onPick = { index ->
                when (index) {
                    0 -> openLogin(site)
                    1 -> promptCookieImport(site)
                    else -> {
                        Accounts.clear(site)
                        refreshSources()
                        Tip.show(activity, "已退出$label")
                    }
                }
            },
            extraLabel = nick.takeIf { it.isNotBlank() }
        )
    }

    /** 网页登录不可用时的兜底：直接粘浏览器 Cookie */
    private fun promptCookieImport(site: String) {
        val label = when (site) {
            Accounts.KUWO -> "酷我"
            Accounts.KUGOU -> "酷狗"
            else -> "网易云"
        }
        AppDialog.input(
            requireContext(),
            "$label 粘贴 Cookie",
            "例如 kw_token=xxx; userid=xxx，整段粘贴即可",
            ""
        ) { raw ->
            val cookie = raw.trim().replace("\n", "").replace("\r", "")
            if (!cookie.contains("=")) {
                Tip.show(activity, "没识别到 Cookie，复制时要把「名称=值」一起带上")
                return@input
            }
            val hasLogin = when (site) {
                Accounts.KUWO -> cookie.contains("kw_token=") || cookie.contains("userid=")
                Accounts.KUGOU -> cookie.contains("KuMing=") || cookie.contains("kugouID=") ||
                    cookie.contains("token=", true)
                else -> cookie.contains("MUSIC_U=")
            }
            if (!hasLogin) {
                Tip.show(activity, "这段 Cookie 里没有$label 的登录凭证，可能是复制不全或复制了别的站点")
                return@input
            }
            Accounts.save(site, cookie)
            refreshSources()
            Tip.show(activity, "已保存$label 登录")
        }
    }

    private fun openLogin(site: String) {
        loginLauncher.launch(Intent(requireContext(), LoginActivity::class.java).putExtra("site", site))
    }

    /** 音源列表最后一行：跳浏览器打开外部音乐站 */
    private fun openMoreMusic() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://xsou.top"))
        runCatching { startActivity(intent) }
            .onFailure { Tip.show(activity, "没有找到可用的浏览器") }
    }

    private fun confirmDeleteSource(position: Int) {
        val source = listShown().getOrNull(position) ?: return
        if (source.id < 0) return
        AppDialog.confirm(
            requireContext(),
            source.name,
            "删除这个音源？已添加的歌曲列表不受影响。",
            positive = "删除"
        ) {
            if (source.isLx) {
                LxEngine.close(source.id)
                runCatching { LxEngine.scriptFile(requireContext(), source.id).delete() }
            }
            MusicSourceStore.remove(source.id)
            refreshSources()
            Tip.show(activity, "已删除音源")
        }
    }

    private fun showAddSourceDialog(initial: String = "") {
        AppDialog.input(
            requireContext(),
            "添加音乐源",
            "接口地址，用 {keyword} 代表搜索词",
            initial,
            positive = "添加"
        ) { input ->
            val url = input.trim()
            when {
                url.isBlank() || !url.startsWith("http") -> {
                    Tip.show(activity, "请填写 http(s) 开头的接口地址")
                    showAddSourceDialog(url)
                }
                url.contains(".js", true) -> addLxSource(url)
                else -> {
                    MusicSourceStore.add(hostOf(url), url)
                    showSources()
                    Tip.show(activity, "已添加，可直接搜索试听")
                }
            }
        }
    }

    /** 添加 lx-music 脚本源：下载脚本 → 起 WebView 跑一遍 → 记住它支持哪些平台/音质 */
    private fun addLxSource(url: String) {
        val ctx = context ?: return
        Tip.show(activity, "正在读取脚本…")
        viewLifecycleOwner.lifecycleScope.launch {
            val script = withContext(Dispatchers.IO) {
                runCatching { SourceClient.downloadText(url) }.getOrNull()
            }
            if (!isAdded) return@launch
            if (script.isNullOrBlank()) {
                Tip.show(activity, "脚本下载失败，检查链接能否直接打开")
                return@launch
            }
            if (!script.contains("EVENT_NAMES") && !script.contains("lx")) {
                Tip.show(activity, "这不像是 lx-music 的脚本源")
                return@launch
            }
            val meta = LxEngine.readMeta(script)
            val displayName = meta.first.ifBlank { hostOf(url) }
            val id = MusicSourceStore.add(displayName, url, MusicSourceStore.KIND_LX)
            val source = MusicSourceStore.all().firstOrNull { it.id == id } ?: return@launch
            val info = LxEngine.install(ctx, source, script)
            if (!isAdded) return@launch
            MusicSourceStore.updateSummary(id, info.summary(), info.name)
            showSources()
            if (info.error != null) {
                Tip.show(activity, "已添加，但脚本初始化失败：${info.error}")
            } else {
                val platforms = info.platforms.entries.joinToString(" / ") {
                    LxEngine.platformLabel(it.key) + " " + it.value.joinToString(",")
                }
                val shown = info.name.ifBlank { displayName }
                Tip.show(
                    activity,
                    if (platforms.isBlank()) "已添加脚本源「$shown」"
                    else "已添加「$shown」：$platforms"
                )
            }
        }
    }

    /** 让已启用的 lx 源去换播放地址；拿到就返回 (地址, 源名) */
    private suspend fun resolveLx(track: OnlineTrack): Pair<String, String>? {
        if (track.platform.isBlank() || track.songId.isBlank()) return null
        val ctx = context ?: return null
        val candidates = MusicSourceStore.enabled()
            .filter { it.isLx && LxEngine.supportsCached(it.id, track.platform) }
        if (candidates.isEmpty()) return null
        for (source in candidates) {
            val url = runCatching {
                LxEngine.musicUrl(ctx, source, track.platform, track.songId, track.title, track.artist)
            }.getOrNull()
            if (!url.isNullOrBlank()) return url to source.name
        }
        return null
    }

    private fun showProtocolDialog() {
        AppDialog.message(
            requireContext(),
            "音乐源说明",
            """
            内置在线搜索：不用添加任何东西，顶部输入歌名即可搜索、试听、下载。
            下面「酷我」「网易云」两行可以单独开关；点行可以登录对应账号。

            登录后 Cookie 只存在本机：
            · 网易云：走 song/enhance/player/url，会员账号能拿到 lossless，非会员最多标准音质
            · 酷我：带 Cookie + csrf 走网页换链接口，账号权限决定能听哪一档

            lx 脚本源：直接粘贴脚本链接（形如 …/flower/latest.js），脚本跑起来后会显示它支持哪些平台和音质。
            播放时优先用脚本源换地址，能拿到 320k / 无损就用它；拿不到自动回退内置地址。
            脚本源只负责「把一首歌换成播放地址」，搜索仍由内置源完成。

            JSON 接口源：地址里用 {keyword} 代表搜索词，返回
            {"data":[{"name":"歌名","artist":"歌手","url":"https://…/a.mp3"}]} 即可。

            版权受限或需要会员的曲目可能拿不到音频，换个版本或关键词即可。
            """.trimIndent()
        )
    }

    // ---------------- 搜索 ----------------

    private fun runSearch(keyword: String) {
        if (!isAdded) return
        if (keyword.isBlank()) {
            showSources()
            return
        }
        val all = MusicSourceStore.enabled()
        val sources = all.filter { !it.isLx }
        val lxSources = all.filter { it.isLx }
        val ctx = requireContext().applicationContext

        showingResults = true
        recycler.adapter = onlineAdapter
        onlineAdapter.submit(emptyList())
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "正在搜索…"
        tvHint.visibility = View.VISIBLE
        tvHint.text = "搜索「$keyword」"

        viewLifecycleOwner.lifecycleScope.launch {
            val builtinJob = async { BuiltinSource.search(keyword) }
            val customJobs = sources.map { source ->
                async { source to SourceClient.search(source, keyword) }
            }
            // 脚本源也参与搜索：一个脚本可能支持多个平台，先试最常用的三个；最多等 4 秒，避免整页卡住
            val lxJobs = lxSources.flatMap { source ->
                listOf("kw", "wy", "kg").map { platform ->
                    async {
                        Triple(
                            source,
                            platform,
                            withTimeoutOrNull(4_000) { LxEngine.searchRaw(ctx, source, platform, keyword) }
                        )
                    }
                }
            }

            val results = mutableListOf<OnlineTrack>()
            val failures = mutableListOf<String>()
            var lxWithResults = 0

            lxJobs.awaitAll().forEach { (source, platform, payload) ->
                val parsed = parseLxResults(payload, source.name, platform)
                if (parsed.isNotEmpty()) {
                    results.addAll(parsed)
                    lxWithResults++
                }
            }

            val builtin = builtinJob.await()
            builtin.getOrNull()?.let { results.addAll(it) }
            builtin.exceptionOrNull()?.let { failures.add("内置源：${it.shortMessage()}") }

            customJobs.awaitAll().forEach { (source, outcome) ->
                outcome.getOrNull()?.let { results.addAll(it) }
                if (outcome.isFailure) {
                    failures.add("${source.name}：${outcome.exceptionOrNull()?.shortMessage()}")
                }
            }
            if (!isAdded) return@launch

            val unique = results.distinctBy { dedupeKey(it) }
            onlineAdapter.submit(unique)
            val sourceLabel = buildList {
                val builtins = buildList {
                    if (Accounts.providerEnabled(Accounts.KUWO)) add("酷我")
                    if (Accounts.providerEnabled(Accounts.NETEASE)) add("网易云")
                    if (Accounts.providerEnabled(Accounts.KUGOU)) add("酷狗")
                }
                if (builtins.isNotEmpty()) add(builtins.joinToString(" + "))
                if (sources.isNotEmpty()) add("${sources.size} 个搜索接口")
                if (lxSources.isNotEmpty()) add("${lxSources.size} 个脚本源")
            }.joinToString(" + ")
            tvHint.text = "「$keyword」· $sourceLabel · ${unique.size} 条结果"
            if (unique.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                val lxNote = if (lxSources.isNotEmpty() && lxWithResults == 0) {
                    "脚本源：这次没返回搜索结果（有些脚本只提供换链，不支持搜索）"
                } else {
                    null
                }
                tvEmpty.text = when {
                    failures.isNotEmpty() || lxNote != null ->
                        "这次没搜到结果：\n" +
                            (failures + listOfNotNull(lxNote)).joinToString("\n") +
                            "\n\n检查网络，或到「添加音乐源」确认接口可用。"
                    else -> "没有搜到「$keyword」，换个关键词或换个版本试试"
                }
            } else {
                tvEmpty.visibility = View.GONE
            }
        }
    }

    /** 脚本源返回的搜索结果：多数是数组，字段名各脚本略有差别 */
    private fun parseLxResults(payload: String?, sourceName: String, platform: String): List<OnlineTrack> {
        if (payload.isNullOrBlank()) return emptyList()
        val text = payload.trim().removePrefix("\"").removeSuffix("\"")
            .replace("\\\"", "\"")
        val root = runCatching {
            if (text.startsWith("[")) org.json.JSONArray(text) else org.json.JSONObject(text)
        }.getOrNull() ?: return emptyList()

        val array = runCatching {
            when (root) {
                is org.json.JSONArray -> root
                is org.json.JSONObject -> {
                    val keys = listOf("data", "list", "songs", "result", "items")
                    keys.mapNotNull { key ->
                        when (val child = root.opt(key)) {
                            is org.json.JSONArray -> child
                            is org.json.JSONObject -> child.optJSONArray("list")
                                ?: child.optJSONArray("songs")
                                ?: child.optJSONArray("data")
                            else -> null
                        }
                    }.firstOrNull()
                }
                else -> null
            }
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = item.pick("name", "songname", "songName", "title")
            val id = item.pick("songmid", "hash", "musicId", "songId", "id", "rid")
            if (title.isBlank() || id.isBlank()) continue
            val seconds = item.pick("interval", "duration", "dt").toLongOrNull() ?: 0L
            out.add(
                OnlineTrack(
                    title = title,
                    artist = item.pick("singer", "singername", "artist", "author"),
                    album = item.pick("albumName", "albumname", "album"),
                    url = "",
                    duration = if (seconds > 0 && seconds < 100_000) seconds * 1000L else seconds,
                    sourceName = sourceName,
                    platform = platform,
                    songId = id
                )
            )
        }
        return out
    }

    private fun Throwable.shortMessage(): String {
        val msg = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
        return msg.take(70)
    }

    private fun org.json.JSONObject.pick(vararg keys: String): String {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val value = opt(key)) {
                is String -> if (value.isNotBlank()) return value.trim()
                is Number -> return value.toString()
            }
        }
        return ""
    }

    // ---------------- 播放 / 下载 ----------------

    /** 统一取流：lx 脚本源优先，其次网易云 / 酷我的换链接口 */
    private suspend fun resolveStream(track: OnlineTrack): Pair<String, String>? {
        resolveLx(track)?.let { return it }
        return when {
            track.platform == "wy" && track.songId.isNotBlank() -> {
                val url = BuiltinSource.resolveNetease(track.songId)
                url?.takeIf { it.startsWith("http") }?.let { it to "网易云" }
            }
            track.platform == "kw" && track.songId.any { it.isDigit() } -> {
                val rid = "kuwo://MUSIC_" + track.songId.filter { it.isDigit() }
                val url = BuiltinSource.resolveKuwo(rid)
                url?.takeIf { it.startsWith("http") }?.let { it to "酷我" }
            }
            BuiltinSource.isKuwoUrl(track.url) -> {
                val url = BuiltinSource.resolveKuwo(track.url)
                url?.takeIf { it.startsWith("http") }?.let { it to "酷我" }
            }
            BuiltinSource.isKugouUrl(track.url) -> {
                val url = BuiltinSource.resolveKugou(track.url)
                url?.takeIf { it.startsWith("http") }?.let { it to "酷狗" }
            }
            track.url.startsWith("http") -> track.url to track.sourceName
            else -> null
        }
    }

    /** 播放 / 下载：先试 lx 脚本源拿高音质直链，失败再回退内置源 */
    private fun playOnline(track: OnlineTrack) {
        if (!isAdded) return
        val hasLx = MusicSourceStore.enabled().any { it.isLx }
        if (hasLx && track.platform.isNotBlank()) Tip.show(activity, "正在通过音源获取播放地址…")
        viewLifecycleOwner.lifecycleScope.launch {
            val stream = resolveStream(track)
            if (!isAdded) return@launch
            if (stream == null) {
                Tip.show(
                    activity,
                    when (track.platform) {
                        "kg" -> "这首酷狗曲目拿不到直链（付费或无版权），登录酷狗账号或换一首"
                        "wy" -> "网易云这首拿不到音频，登录后重试或换一首"
                        else -> "这首歌暂时拿不到音频，换一首试试"
                    }
                )
                return@launch
            }
            if (stream.second != track.sourceName) {
                Tip.show(activity, "「${stream.second}」已取到地址，正在播放")
            }
            startPlayback(track, stream.first)
        }
    }

    private fun startPlayback(track: OnlineTrack, uri: String) {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifBlank { "未知歌手" })
                    .setAlbumTitle(track.album)
                    .build()
            )
            .build()
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
            controller.setMediaItem(item)
            PlaybackConnection.applyMode(controller)
            controller.prepare()
            controller.play()
            (activity as? MainActivity)?.openPlayer()
        }
    }

    private fun download(track: OnlineTrack) {
        onlineAdapter.setDownloading(track, true)
        viewLifecycleOwner.lifecycleScope.launch {
            val stream = resolveStream(track)
            if (!isAdded) return@launch
            if (stream == null) {
                onlineAdapter.setDownloading(track, false)
                Tip.show(activity, "这首歌暂时拿不到音频，换一首试试")
                return@launch
            }
            enqueueDownload(track, stream.first)
        }
    }

    private fun enqueueDownload(track: OnlineTrack, uri: String) {
        val fileName = buildFileName(track, uri)
        runCatching {
            val request = DownloadManager.Request(Uri.parse(uri))
                .setTitle(fileName)
                .setDescription("来自 ${track.sourceName}")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_MUSIC,
                    "简听/$fileName"
                )
            val manager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            onlineAdapter.setDownloading(track, true)
            watchingDownloads[id] = track
            startDownloadWatch()
            Tip.show(activity, "开始下载到 Music/简听")
        }.onFailure {
            onlineAdapter.setDownloading(track, false)
            Tip.show(activity, "下载失败：${it.message}")
        }
    }

    /** 轮询 DownloadManager，完成/失败后把圆形进度收回成下载图标 */
    private fun startDownloadWatch() {
        if (downloadWatch != null) return
        val manager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val runnable = object : Runnable {
            override fun run() {
                val done = ArrayList<Long>()
                for ((id, track) in watchingDownloads) {
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = runCatching { manager.query(query) }.getOrNull()
                    val status = cursor?.use {
                        if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        else -1
                    } ?: -1
                    if (status == DownloadManager.STATUS_SUCCESSFUL ||
                        status == DownloadManager.STATUS_FAILED ||
                        status == -1
                    ) {
                        done.add(id)
                        onlineAdapter.setDownloading(track, false)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Tip.show(activity, "已下载：${track.title}")
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            Tip.show(activity, "下载失败：${track.title}")
                        }
                    }
                }
                done.forEach { watchingDownloads.remove(it) }
                if (watchingDownloads.isEmpty()) {
                    downloadWatch = null
                } else {
                    handler.postDelayed(this, 1200)
                }
            }
        }
        downloadWatch = runnable
        handler.postDelayed(runnable, 800)
    }

    private fun buildFileName(track: OnlineTrack, uri: String): String {
        val base = listOf(track.title, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "在线音乐" }
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(80)
        val ext = when {
            uri.contains(".m4a", true) -> ".m4a"
            uri.contains(".flac", true) -> ".flac"
            uri.contains(".wav", true) -> ".wav"
            else -> ".mp3"
        }
        return base + ext
    }

    /** 同名同歌手只留一条；酷我排在前面，所以优先拿能播的 */
    private fun dedupeKey(track: OnlineTrack): String {
        fun norm(s: String) = s.lowercase()
            .replace("&nbsp;", "")
            .replace(Regex("[\\s\\u3000]"), "")
            .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
        return norm(track.title) + "|" + norm(track.artist)
    }

    // ---------------- 工具 ----------------

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: "新音源" }.getOrDefault("新音源")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
