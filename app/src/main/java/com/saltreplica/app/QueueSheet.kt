package com.saltreplica.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.min

/** 播放队列：点「列表」弹出，能看队列、能点着切歌、能长按拖动排序 */
class QueueSheet : BottomSheetDialogFragment() {

    private val adapter = QueueAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_queue, container, false)

    override fun onStart() {
        super.onStart()
        // 去掉 BottomSheet 默认的白色方角背景，露出布局自己的圆角卡片
        (dialog as? BottomSheetDialog)?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val controller = PlaybackConnection.controller
        val recycler = view.findViewById<RecyclerView>(R.id.queue_recycler)
        val empty = view.findViewById<TextView>(R.id.queue_empty)
        val header = view.findViewById<TextView>(R.id.queue_header)
        val hint = view.findViewById<TextView>(R.id.queue_hint)

        val density = resources.displayMetrics.density
        val maxHeight = min((420 * density).toInt(), (resources.displayMetrics.heightPixels * 0.55f).toInt())
        recycler.layoutParams = recycler.layoutParams.apply { height = maxHeight }

        val count = controller?.mediaItemCount ?: 0
        header.text = if (count > 0) "播放列表 · $count 首" else "播放列表"

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        adapter.items = if (controller == null) {
            mutableListOf()
        } else {
            (0 until count).map { QueueItem(it, controller.getMediaItemAt(it)) }.toMutableList()
        }
        adapter.currentIndex = controller?.currentMediaItemIndex ?: -1
        adapter.onClick = { index ->
            PlaybackConnection.controller?.let { c ->
                c.seekTo(index, 0)
                if (!c.isPlaying) c.play()
            }
            dismissAllowingStateLoss()
        }

        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.move(from, to)
                // 队列顺序同步到播放器，拖完就直接生效
                runCatching { PlaybackConnection.controller?.moveMediaItem(from, to) }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // 拖动中的行给一点视觉反馈
                viewHolder?.itemView?.alpha = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 0.85f else 1f
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        ItemTouchHelper(dragCallback).attachToRecyclerView(recycler)

        val hasItems = adapter.items.isNotEmpty()
        empty.visibility = if (hasItems) View.GONE else View.VISIBLE
        hint.visibility = if (hasItems) View.VISIBLE else View.GONE
        recycler.visibility = if (hasItems) View.VISIBLE else View.GONE
    }

    data class QueueItem(val index: Int, val item: MediaItem)

    private class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {

        var items: MutableList<QueueItem> = mutableListOf()
        var currentIndex: Int = -1
        var onClick: ((Int) -> Unit)? = null

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val row: View = view.findViewById(R.id.queue_row)
            val index: TextView = view.findViewById(R.id.queue_index)
            val title: TextView = view.findViewById(R.id.queue_title)
            val artist: TextView = view.findViewById(R.id.queue_artist)
            val now: TextView = view.findViewById(R.id.queue_now)
        }

        fun move(from: Int, to: Int) {
            if (from !in items.indices || to !in items.indices) return
            val item = items.removeAt(from)
            items.add(to, item)
            // 当前播放位置跟着一起挪，否则高亮会指错行
            currentIndex = when {
                currentIndex == from -> to
                from < currentIndex && to >= currentIndex -> currentIndex - 1
                from > currentIndex && to <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_queue, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val meta = items[position].item.mediaMetadata
            val playing = position == currentIndex
            holder.index.text = (position + 1).toString()
            holder.title.text = meta.title?.toString().orEmpty().ifBlank { "未知曲目" }
            holder.artist.text = meta.artist?.toString().orEmpty()
            holder.now.visibility = if (playing) View.VISIBLE else View.GONE
            holder.title.setTextColor(
                if (playing) SettingsUi.primary(holder.itemView.context)
                else ContextCompat.getColor(holder.itemView.context, R.color.on_bg)
            )
            holder.row.setBackgroundResource(if (playing) R.drawable.bg_queue_now else 0)
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick?.invoke(pos)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
