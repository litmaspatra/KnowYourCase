package com.knowyourcase.app.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.knowyourcase.app.data.local.HistoryEntity
import com.knowyourcase.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val onClick: (String) -> Unit
) : ListAdapter<HistoryEntity, HistoryAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HistoryEntity) {
            b.tvCaseTitle.text = item.caseTitle
            b.tvCnr.text       = item.cnr
            b.tvDate.text      = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                .format(Date(item.searchedAt))
            b.root.setOnClickListener { onClick(item.cnr) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<HistoryEntity>() {
            override fun areItemsTheSame(a: HistoryEntity, b: HistoryEntity) = a.cnr == b.cnr
            override fun areContentsTheSame(a: HistoryEntity, b: HistoryEntity) = a == b
        }
    }
}
