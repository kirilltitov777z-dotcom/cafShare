package com.cafshare.app.adapter

import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cafshare.app.R
import com.cafshare.app.databinding.ItemApkBinding
import com.cafshare.app.model.ApkEntry
import java.util.Date

class ApkAdapter(
    private val onInstall: (ApkEntry) -> Unit,
    private val onShare: (ApkEntry) -> Unit
) : ListAdapter<ApkEntry, ApkAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemApkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemApkBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ApkEntry) {
            val ctx = b.root.context
            b.name.text = item.label
            b.meta.text = ctx.getString(
                R.string.apk_meta_fmt,
                Formatter.formatShortFileSize(ctx, item.size),
                DateFormat.getDateFormat(ctx).format(Date(item.modified))
            )
            b.btnInstall.setOnClickListener { onInstall(item) }
            b.btnShare.setOnClickListener { onShare(item) }
            b.root.setOnClickListener { onInstall(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ApkEntry>() {
            override fun areItemsTheSame(a: ApkEntry, b: ApkEntry) = a.file.absolutePath == b.file.absolutePath
            override fun areContentsTheSame(a: ApkEntry, b: ApkEntry) = a == b
        }
    }
}
