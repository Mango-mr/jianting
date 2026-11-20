package com.saltreplica.app

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

/**
 * 设置页：全部设置项都是「一行标题 + 下拉面板」，点开即改，不再跳二级页面。
 * 面板里优先用滑块，拖动即生效。
 */
class SettingsFragment : Fragment() {

    private lateinit var boxSettings: LinearLayout
    private lateinit var cardTheme: SettingsUi.Card
    private lateinit var cardPlay: SettingsUi.Card
    private lateinit var cardLyrics: SettingsUi.Card
    private lateinit var cardScan: SettingsUi.Card
    private lateinit var cardSleep: SettingsUi.Card
    private lateinit var cardEq: SettingsUi.Card

    private data class ThemeSpec(val name: String, val preview: Int, val accent: Int)

    private val themes = listOf(
        ThemeSpec("奶白", 0xFFF6F5F3.toInt(), 0xFF1C1C1E.toInt()),
        ThemeSpec("深灰", 0xFF131312.toInt(), 0xFFC0AEDB.toInt()),
        ThemeSpec("霓虹", 0xFF140B1E.toInt(), 0xFFB478FF.toInt()),
        ThemeSpec("暗橙", 0xFF15100C.toInt(), 0xFFFF8A5C.toInt()),
        ThemeSpec("荧光", 0xFF13140A.toInt(), 0xFFDCEF5A.toInt())
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        boxSettings = view.findViewById(R.id.box_settings)

        view.findViewById<View>(R.id.btn_back).setOnClickListener { close() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = close()
            }
        )

        buildThemeCard()
        buildPlayCard()
        buildLyricsCard()
        buildScanCard()
        buildSleepCard()
        buildEqCard()
        buildSourceCard()
    }

    private fun close() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    // ---------------- 主题 ----------------

    private fun buildThemeCard() {
        val ctx = requireContext()
        cardTheme = SettingsUi.card(ctx, R.drawable.ic_set_theme, "主题")
        cardTheme.value.text = themes[Prefs.themeMode.coerceIn(0, themes.lastIndex)].name
        boxSettings.addView(cardTheme.root)
        fillThemePanel()
    }

    private fun fillThemePanel() {
        val ctx = requireContext()
        val panel = cardTheme.panel
        panel.removeAllViews()

        val swatchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SettingsUi.dp(ctx, 8) }
        }
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SettingsUi.dp(ctx, 8) }
        }

        themes.forEachIndexed { index, spec ->
            val selected = Prefs.themeMode == index
            swatchRow.addView(SettingsUi.swatch(ctx, spec.preview, spec.accent, selected))
            val name = TextView(ctx).apply {
                text = spec.name
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (selected) R.color.on_bg else R.color.on_bg_sub
                    )
                )
                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = SettingsUi.dp(ctx, 8) }
                setOnClickListener { applyTheme(index) }
            }
            nameRow.addView(name)
            swatchRow.getChildAt(swatchRow.childCount - 1).setOnClickListener { applyTheme(index) }
        }

        panel.addView(swatchRow)
        panel.addView(nameRow)
        panel.addView(SettingsUi.label(ctx, "深色主题为深底 + 霓虹强调色，切换后界面整体跟随", 10))
    }

    private fun applyTheme(index: Int) {
        Prefs.themeMode = index
        AppCompatDelegate.setDefaultNightMode(
            if (index == 0) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        requireActivity().recreate()
    }

    // ---------------- 播放页 ----------------

    private fun buildPlayCard() {
        val ctx = requireContext()
        cardPlay = SettingsUi.card(ctx, R.drawable.ic_set_player, "播放页")
        boxSettings.addView(cardPlay.root)

        cardPlay.panel.addView(
            SettingsUi.switchRow(ctx, "用当前歌曲封面作为背景", Prefs.coverBackground) {
                Prefs.coverBackground = it
                refreshPlayValue()
            }
        )
        cardPlay.panel.addView(
            SettingsUi.sliderRow(
                ctx, "背景模糊程度", 0, 25, 1, Prefs.coverBlur,
                { if (it == 0) "清晰" else "模糊 $it" }
            ) {
                Prefs.coverBlur = it
                refreshPlayValue()
            }
        )
        cardPlay.panel.addView(SettingsUi.label(ctx, "Android 12 以下设备只叠加封面，不做实时模糊", 8))
        refreshPlayValue()
    }

    private fun refreshPlayValue() {
        cardPlay.value.text =
            if (Prefs.coverBackground) "封面 · 模糊 ${Prefs.coverBlur}" else "纯色背景"
    }

    // ---------------- 歌词 ----------------

    private fun buildLyricsCard() {
        val ctx = requireContext()
        cardLyrics = SettingsUi.card(ctx, R.drawable.ic_set_lyrics, "歌词")
        boxSettings.addView(cardLyrics.root)
        fillLyricsPanel()
    }

    private fun fillLyricsPanel() {
        val ctx = requireContext()
        val panel = cardLyrics.panel
        panel.removeAllViews()

        panel.addView(SettingsUi.label(ctx, "颜色", 8))
        val dotRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SettingsUi.dp(ctx, 8) }
        }
        LyricsStyle.colors.forEachIndexed { index, _ ->
            val dot = SettingsUi.dot(ctx, LyricsStyle.color(ctx, index), Prefs.lyricsColor == index, 28)
            dot.setOnClickListener {
                Prefs.lyricsColor = index
                fillLyricsPanel()
                refreshLyricsValue()
            }
            dotRow.addView(dot)
        }
        panel.addView(dotRow)

        panel.addView(
            SettingsUi.sliderRow(
                ctx, "字号", 12, 26, 1, Prefs.lyricsSize,
                { "${it}sp" }
            ) {
                Prefs.lyricsSize = it
                refreshLyricsValue()
                preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (it + 4).toFloat())
            }
        )

        preview = TextView(ctx).apply {
            text = "山有木兮木有枝，心悦君兮君不知"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (Prefs.lyricsSize + 4).toFloat())
            setTextColor(LyricsStyle.color(ctx))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SettingsUi.dp(ctx, 10) }
        }
        panel.addView(preview)
        refreshLyricsValue()
    }

    private lateinit var preview: TextView

    private fun refreshLyricsValue() {
        cardLyrics.value.text = "${Prefs.lyricsSize}sp"
    }

    // ---------------- 自动扫描 ----------------

    private fun buildScanCard() {
        val ctx = requireContext()
        cardScan = SettingsUi.card(ctx, R.drawable.ic_set_scan, "自动扫描")
        boxSettings.addView(cardScan.root)

        cardScan.panel.addView(
            SettingsUi.switchRow(ctx, "启动时自动扫描新增歌曲", Prefs.autoScan) {
                Prefs.autoScan = it
                refreshScanCount()
            }
        )

        cardScan.panel.addView(
            SettingsUi.sliderRow(
                ctx, "最短时长", 0, 600, 10, Prefs.scanMinSec,
                { if (it == 0) "不限" else "≥ ${it}s" }
            ) { Prefs.scanMinSec = it; refreshScanCount() }
        )
        cardScan.panel.addView(
            SettingsUi.sliderRow(
                ctx, "最长时长", 0, 3600, 60, Prefs.scanMaxSec,
                { if (it == 0) "不限" else "≤ ${it}s" }
            ) { Prefs.scanMaxSec = it; refreshScanCount() }
        )
        cardScan.panel.addView(
            SettingsUi.sliderRow(
                ctx, "最小文件体积", 0, 100, 1, Prefs.scanMinMb,
                { if (it == 0) "不限" else "≥ ${it}MB" }
            ) { Prefs.scanMinMb = it; refreshScanCount() }
        )
        cardScan.panel.addView(
            SettingsUi.sliderRow(
                ctx, "最大文件体积", 0, 2000, 50, Prefs.scanMaxMb,
                { if (it == 0) "不限" else "≤ ${it}MB" }
            ) { Prefs.scanMaxMb = it; refreshScanCount() }
        )
        cardScan.panel.addView(
            SettingsUi.switchRow(ctx, "只保留标题和歌手都完整的歌曲", Prefs.scanRequireTag) {
                Prefs.scanRequireTag = it
                refreshScanCount()
            }
        )

        val rescanButton = TextView(ctx).apply {
            text = "立即扫描"
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(SettingsUi.onPrimary(ctx))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_chip_on)
            setPadding(0, SettingsUi.dp(ctx, 10), 0, SettingsUi.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SettingsUi.dp(ctx, 12) }
            setOnClickListener { rescan() }
        }
        cardScan.panel.addView(rescanButton)

        tvScanCount = TextView(ctx).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_bg_sub))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SettingsUi.dp(ctx, 8) }
        }
        cardScan.panel.addView(tvScanCount)
        refreshScanCount()
    }

    private lateinit var tvScanCount: TextView

    private fun refreshScanCount() {
        val cached = TrackCache.songs.size
        cardScan.value.text = (if (Prefs.autoScan) "自动" else "手动") + " · $cached 首"
        if (::tvScanCount.isInitialized) {
            tvScanCount.text = if (cached > 0) {
                "当前曲库 $cached 首，改变条件后点「立即扫描」重新筛选"
            } else {
                "曲库为空，点「立即扫描」按当前条件收歌"
            }
        }
    }

    private fun rescan() {
        if (!::tvScanCount.isInitialized) return
        tvScanCount.text = "扫描中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = MusicRepository.queryAsync(requireContext(), MusicRepository.ScanFilter.fromPrefs())
            TrackCache.songs = songs.filterNot { Prefs.isHidden(it.id) }
            runCatching { TrackStore.save(requireContext(), songs) }
            if (!isAdded) return@launch
            refreshScanCount()
            Tip.show(activity, "找到 ${TrackCache.songs.size} 首")
        }
    }

    // ---------------- 睡眠定时 ----------------

    private fun buildSleepCard() {
        val ctx = requireContext()
        cardSleep = SettingsUi.card(ctx, R.drawable.ic_set_sleep, "睡眠定时")
        boxSettings.addView(cardSleep.root)

        cardSleep.panel.addView(
            SettingsUi.sliderRow(
                ctx, "倒计时", 0, 120, 5, normalizeMinutes(SleepTimer.remainMinutes()),
                { if (it == 0) "关闭" else "$it 分钟后暂停" }
            ) { minutes ->
                SleepTimer.schedule(minutes)
                refreshSleepValue()
                if (minutes > 0) Tip.show(activity, "${minutes} 分钟后暂停播放")
            }
        )
        cardSleep.panel.addView(SettingsUi.label(ctx, "到点自动暂停，滑块拖到 0 即取消", 8))
        refreshSleepValue()
    }

    private fun normalizeMinutes(minutes: Int): Int {
        if (minutes <= 0) return 0
        return ((minutes + 2) / 5) * 5
    }

    private fun refreshSleepValue() {
        val left = SleepTimer.remainMinutes()
        cardSleep.value.text = if (left <= 0) "关闭" else "剩 $left 分钟"
    }

    // ---------------- 均衡器 ----------------

    private fun buildEqCard() {
        val ctx = requireContext()
        cardEq = SettingsUi.card(ctx, R.drawable.ic_set_eq, "均衡器")
        boxSettings.addView(cardEq.root)
        fillEqPanel()
    }

    private fun fillEqPanel() {
        val ctx = requireContext()
        val panel = cardEq.panel
        panel.removeAllViews()

        panel.addView(
            SettingsUi.switchRow(ctx, "启用均衡器", Prefs.eqEnabled) {
                EqController.setEnabled(it)
                refreshEqValue()
            }
        )

        panel.addView(SettingsUi.label(ctx, "预设", 10))
        panel.addView(
            SettingsUi.chips(
                ctx, EqController.presetNames.toList(), Prefs.eqPreset
            ) { index ->
                EqController.applyPreset(index)
                fillEqPanel()
            }
        )

        panel.addView(SettingsUi.label(ctx, "自定义（低频 → 高频，单位 dB）", 12))
        val curve = EqController.currentCurve()
        val bandNames = arrayOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
        for (i in 0..4) {
            panel.addView(
                SettingsUi.sliderRow(
                    ctx, bandNames[i], -12, 12, 1, curve[i].toInt(),
                    { if (it > 0) "+${it}dB" else "${it}dB" }
                ) { db ->
                    EqController.setCustomBand(i, db.toFloat())
                    refreshEqValue()
                }
            )
        }
        panel.addView(SettingsUi.label(ctx, "拖动任一频段即进入自定义曲线", 8))
        refreshEqValue()
    }

    private fun refreshEqValue() {
        val preset = Prefs.eqPreset
        cardEq.value.text = when {
            !Prefs.eqEnabled -> "已关闭"
            preset in EqController.presetNames.indices -> EqController.presetNames[preset]
            else -> "自定义"
        }
    }

    // ---------------- 音乐源 ----------------

    private fun buildSourceCard() {
        val ctx = requireContext()
        val card = SettingsUi.card(ctx, R.drawable.ic_set_source, "音乐源", expandable = false)
        card.value.text = "在线搜索 / 下载"
        card.header.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, OnlineFragment(), "online")
                .addToBackStack("online")
                .commit()
        }
        boxSettings.addView(card.root)
    }
}
