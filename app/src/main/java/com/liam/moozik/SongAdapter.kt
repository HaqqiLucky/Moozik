package com.liam.moozik

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Kelas ini tugasnya mengatur tampilan setiap baris lagu
class SongAdapter(
    private val titles: List<String>,
    private val artists: List<String>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    var selectedIndex = -1 // Lagu mana yang lagi main

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(android.R.id.text1)
        val tvSubtitle: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Kita pakai layout bawaan Android biar enteng (simple_list_item_2)
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tvTitle.text = titles[position]
        holder.tvSubtitle.text = artists[position]

        // Kalau aktif: Biru Langit (#039BE5). Kalau biasa: Hitam (#212121)
        if (position == selectedIndex) {
            holder.tvTitle.setTextColor(Color.parseColor("#039BE5"))
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            holder.tvTitle.setTextColor(Color.parseColor("#212121"))
            holder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        // Subtitle abu-abu
        holder.tvSubtitle.setTextColor(Color.GRAY)

        holder.itemView.setOnClickListener {
            onClick(position)
        }
    }

    override fun getItemCount() = titles.size
}