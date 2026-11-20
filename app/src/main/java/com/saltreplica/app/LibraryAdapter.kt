package com.saltreplica.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView

/** 列表行类型 */
sealed class Row {
    data class Song(val track: Track) : Row()

    /** 专辑 / 歌手 / 文件夹 分组，代表曲目用来显示封面；tracks 用于整组隐藏 */
    data class Group(
        val name: String,
        val sub: String,
        val sample: Track? = null,
        val tracks: List<Track> = emptyList()
    ) : Row()

    data class PlaylistRow(
        val id: Long,
        val name: String,
        val count: Int,
        val sample: Track? = null
    ) : Row()
}

class LibraryAdapter(private val listener: Listener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onSongClick(position: Int)
        fun onSongLongClick(position: Int)
        fun onGroupClick(position: Int)
        fun onGroupLongClick(position: Int)
        fun onPlaylistClick(position: Int)
        fun onPlaylistLongClick(position: Int)
    }

    private var rows: List<Row> = emptyList()

    fun submit(items: List<Row>) {
        rows = items
        notifyDataSetChanged()
    }

    fun currentSongs(): List<Track> = rows.filterIsInstance<Row.Song>().map { it.track }

    fun rowAt(position: Int): Row? = rows.getOrNull(position)

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Song -> TYPE_SONG
        is Row.Group -> TYPE_GROUP
        is Row.PlaylistRow -> TYPE_PLAYLIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SONG -> SongVH(inflater.inflate(R.layout.item_song, parent, false))
            TYPE_GROUP -> GroupVH(inflater.inflate(R.layout.item_group, parent, false), isPlaylist = false)
            else -> GroupVH(inflater.inflate(R.layout.item_group, parent, false), isPlaylist = true)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Song -> {
                (holder as SongVH).bind(row.track)
                holder.itemView.setOnClickListener {
                    listener.onSongClick(positionOf(holder, position))
                }
                holder.itemView.setOnLongClickListener {
                    listener.onSongLongClick(positionOf(holder, position))
                    true
                }
            }
            is Row.Group -> {
                (holder as GroupVH).bind(row.name, row.sub, row.sample)
                holder.itemView.setOnClickListener {
                    listener.onGroupClick(positionOf(holder, position))
                }
                holder.itemView.setOnLongClickListener {
                    listener.onGroupLongClick(positionOf(holder, position))
                    true
                }
            }
            is Row.PlaylistRow -> {
                (holder as GroupVH).bind(row.name, "${row.count} 首", row.sample)
                holder.itemView.setOnClickListener {
                    listener.onPlaylistClick(positionOf(holder, position))
                }
                holder.itemView.setOnLongClickListener {
                    listener.onPlaylistLongClick(positionOf(holder, position))
                    true
                }
            }
        }
    }

    /** 优先用实时位置；未 attach 到 RecyclerView 时退回绑定时的位置，保证点击永远有反应 */
    private fun positionOf(holder: RecyclerView.ViewHolder, fallback: Int): Int {
        val live = holder.bindingAdapterPosition
        return if (live != RecyclerView.NO_POSITION) live else fallback
    }

    class SongVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ShapeableImageView = view.findViewById(R.id.cover)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val sub: TextView = view.findViewById(R.id.sub)
        private val duration: TextView = view.findViewById(R.id.duration)

        fun bind(track: Track) {
            itemView.tag = track.id
            title.text = track.titleSafe
            sub.text = if (track.artistSafe == "未知歌手") track.albumSafe
            else "${track.artistSafe} · ${track.albumSafe}"
            duration.text = formatDuration(track.durationMs)
            bindCover(track)
        }

        private fun bindCover(track: Track) {
            val existing = CoverStore.peek(track)
            if (existing != null) {
                applyCover(existing)
            } else {
                showPlaceholder()
                CoverStore.request(track) { uri ->
                    if (itemView.tag == track.id) applyCover(uri)
                }
            }
        }

        private fun applyCover(uri: Uri) {
            cover.visibility = View.VISIBLE
            icon.visibility = View.GONE
            cover.load(uri) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        }

        private fun showPlaceholder() {
            cover.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }

    class GroupVH(view: View, private val isPlaylist: Boolean) : RecyclerView.ViewHolder(view) {
        private val cover: ShapeableImageView = view.findViewById(R.id.cover)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val sub: TextView = view.findViewById(R.id.sub)

        fun bind(name: String, subText: String, sample: Track?) {
            title.text = name
            sub.text = subText
            icon.setImageResource(if (isPlaylist) R.drawable.ic_queue else R.drawable.ic_music)
            itemView.tag = sample?.id ?: -1L

            val existing = sample?.let { CoverStore.peek(it) }
            if (existing != null) {
                applyCover(existing)
            } else {
                showPlaceholder()
                if (sample != null) {
                    CoverStore.request(sample) { uri ->
                        if (itemView.tag == sample.id) applyCover(uri)
                    }
                }
            }
        }

        private fun applyCover(uri: Uri) {
            cover.visibility = View.VISIBLE
            icon.visibility = View.GONE
            cover.load(uri) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        }

        private fun showPlaceholder() {
            cover.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_GROUP = 1
        private const val TYPE_PLAYLIST = 2
    }
}
