package com.saltreplica.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** 音源管理列表：首行固定为「添加音源」，末行是「下载更多音乐」外链 */
class SourceAdapter(private val listener: Listener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onAdd()
        fun onToggle(position: Int)
        fun onRow(position: Int)
        fun onDelete(position: Int)
        fun onMore()
    }

    private var items: List<MusicSource> = emptyList()

    fun submit(list: List<MusicSource>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size + 2

    override fun getItemViewType(position: Int): Int =
        if (position == itemCount - 1) TYPE_LINK else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LINK) {
            LinkVH(inflater.inflate(R.layout.item_source_link, parent, false))
        } else {
            VH(inflater.inflate(R.layout.item_source, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is LinkVH) {
            holder.link.paintFlags = holder.link.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            holder.link.setOnClickListener { listener.onMore() }
            return
        }
        holder as VH
        if (position == 0) {
            holder.title.text = holder.itemView.context.getString(R.string.source_add)
            holder.sub.text = "内置搜索已可用；可填 JSON 接口，或粘贴 lx 脚本 .js 链接"
            holder.state.visibility = View.GONE
            holder.switch.visibility = View.GONE
            holder.delete.visibility = View.GONE
            holder.itemView.setOnClickListener { listener.onAdd() }
            holder.itemView.setOnLongClickListener(null)
            return
        }
        val index = position - 1
        val source = items.getOrNull(index) ?: return
        val builtin = source.id <= BuiltinSource.BUILTIN_ID
        val header = source.id == BuiltinSource.BUILTIN_ID
        holder.title.text = source.name
        holder.sub.text = when {
            source.isLx -> source.summary.ifBlank { "脚本源" } + " · " + source.url
            else -> source.url
        }
        holder.state.visibility = View.GONE
        holder.switch.visibility = if (header) View.GONE else View.VISIBLE
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = source.enabled
        holder.switch.setOnCheckedChangeListener { _, checked ->
            if (checked != source.enabled) listener.onToggle(index)
        }
        holder.delete.visibility = if (builtin) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { if (!header) listener.onRow(index) }
        holder.itemView.setOnLongClickListener(null)
        holder.delete.setOnClickListener { listener.onDelete(index) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val sub: TextView = view.findViewById(R.id.sub)
        val state: TextView = view.findViewById(R.id.state)
        val switch: com.google.android.material.materialswitch.MaterialSwitch =
            view.findViewById(R.id.switch_on)
        val delete: ImageView = view.findViewById(R.id.btn_delete)
    }

    class LinkVH(view: View) : RecyclerView.ViewHolder(view) {
        val link: TextView = view.findViewById(R.id.link)
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_LINK = 1
    }
}

/** 在线搜索结果列表 */
class OnlineAdapter(
    private val onPlay: (OnlineTrack) -> Unit,
    private val onDownload: (OnlineTrack) -> Unit
) : RecyclerView.Adapter<OnlineAdapter.VH>() {

    private var items: List<OnlineTrack> = emptyList()
    /** 正在下载的曲目 key（用 url 区分），下载中把按钮换成圆圈进度 */
    private val downloading = HashSet<String>()

    fun submit(list: List<OnlineTrack>) {
        items = list
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): OnlineTrack? = items.getOrNull(position)

    fun setDownloading(track: OnlineTrack, value: Boolean) {
        val key = track.url
        val changed = if (value) downloading.add(key) else downloading.remove(key)
        if (!changed) return
        val index = items.indexOfFirst { it.url == key }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_online, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = items.getOrNull(position) ?: return
        holder.title.text = track.title
        val sub = listOfNotNull(
            track.artist.takeIf { it.isNotBlank() },
            track.album.takeIf { it.isNotBlank() },
            SourceClient.formatDuration(track.duration).takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        holder.sub.text = sub.ifBlank { "在线音乐" }
        holder.source.text = track.sourceName
        holder.itemView.setOnClickListener { onPlay(track) }
        holder.itemView.setOnLongClickListener {
            onDownload(track)
            true
        }

        val busy = downloading.contains(track.url)
        holder.download.visibility = if (busy) View.GONE else View.VISIBLE
        holder.progress.visibility = if (busy) View.VISIBLE else View.GONE
        holder.download.setOnClickListener { if (!busy) onDownload(track) }

        if (track.coverUrl.isBlank()) {
            holder.cover.setPadding(0, 0, 0, 0)
            holder.cover.imageTintList = null
            // 用 Coil 加载本地占位图：顺带取消这行之前可能还在路上的封面请求
            holder.cover.load(R.drawable.bg_cover_note)
        } else {
            holder.cover.setPadding(0, 0, 0, 0)
            holder.cover.imageTintList = null
            holder.cover.load(track.coverUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val sub: TextView = view.findViewById(R.id.sub)
        val source: TextView = view.findViewById(R.id.source)
        val download: ImageView = view.findViewById(R.id.btn_download)
        val progress: android.widget.ProgressBar = view.findViewById(R.id.download_progress)
        val cover: com.google.android.material.imageview.ShapeableImageView =
            view.findViewById(R.id.cover)
    }
}
