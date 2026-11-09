package com.cafshare.app.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cafshare.app.R
import com.cafshare.app.databinding.ItemAppBinding
import com.cafshare.app.model.AppEntry

class AppAdapter(
    private val onShare: (AppEntry) -> Unit,
    private val onSave: (AppEntry) -> Unit,
    private val onClick: (AppEntry) -> Unit
) : ListAdapter<AppEntry, AppAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: AppEntry) {
            val ctx = b.root.context
            b.icon.setImageDrawable(item.icon)
            b.name.text = item.label
            b.packageName.text = item.packageName
            var meta = ctx.getString(
                R.string.meta_fmt,
                item.versionName,
                Formatter.formatShortFileSize(ctx, item.sizeBytes)
            )
            if (item.isSystem) meta += ctx.getString(R.string.system_badge)
            b.meta.text = meta
            b.btnShare.setOnClickListener { onShare(item) }
            b.btnSave.setOnClickListener { onSave(item) }
            b.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppEntry>() {
            override fun areItemsTheSame(a: AppEntry, b: AppEntry) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppEntry, b: AppEntry) = a == b
        }
    }
}
