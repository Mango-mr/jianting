package com.saltreplica.app

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** 歌词列表，当前行高亮 */
class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private var lines: List<LrcLine> = emptyList()
    private var hint: String? = null
    private var highlighted = -1

    fun submitLines(list: List<LrcLine>) {
        lines = list
        hint = null
        highlighted = -1
        notifyDataSetChanged()
    }

    fun submitHint(text: String) {
        lines = emptyList()
        hint = text
        highlighted = -1
        notifyDataSetChanged()
    }

    fun highlight(index: Int) {
        val old = highlighted
        if (old == index) return
        highlighted = index
        if (old in lines.indices) notifyItemChanged(old)
        if (index in lines.indices) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = if (hint != null) 1 else lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val context = holder.tv.context
        val text = if (hint != null) hint!! else lines[position].text
        holder.tv.text = text
        val focus = hint == null && position == highlighted
        val color = LyricsStyle.color(context)
        holder.tv.setTextColor(color)
        if (focus) {
            holder.tv.alpha = 1f
            holder.tv.textSize = LyricsStyle.focusSize()
            holder.tv.setTypeface(null, Typeface.BOLD)
        } else {
            holder.tv.alpha = if (hint != null) 0.72f else 0.5f
            holder.tv.textSize = LyricsStyle.normalSize()
            holder.tv.setTypeface(null, Typeface.NORMAL)
        }
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
