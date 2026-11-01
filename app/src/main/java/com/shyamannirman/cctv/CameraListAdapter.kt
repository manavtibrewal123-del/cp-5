package com.shyamannirman.cctv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class Row {
    data class Header(val title: String) : Row()
    data class Item(val camera: Camera) : Row()
}

class CameraListAdapter(
    private var rows: List<Row>,
    private val onClick: (Camera) -> Unit,
    private val onLongClick: (Camera) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val text = view as TextView
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val detail: TextView = view.findViewById(R.id.tvDetail)
        val star: ImageView = view.findViewById(R.id.ivStar)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            ItemVH(inflater.inflate(R.layout.item_camera, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).text.text = row.title
            is Row.Item -> {
                val cam = row.camera
                val h = holder as ItemVH
                h.name.text = cam.name
                h.detail.text = if (cam.customUrl.isNotBlank()) "Custom stream" else "${cam.host} · ch ${cam.channel}"
                h.star.visibility = if (cam.favorite) View.VISIBLE else View.GONE
                h.itemView.setOnClickListener { onClick(cam) }
                h.itemView.setOnLongClickListener { onLongClick(cam); true }
            }
        }
    }

    override fun getItemCount() = rows.size
}
