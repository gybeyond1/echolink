package com.echolink.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.echolink.data.AppFilter
import com.echolink.databinding.ItemAppFilterBinding

class AppFilterAdapter(
    private val onToggle: (AppFilter, Boolean) -> Unit
) : ListAdapter<AppFilter, AppFilterAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppFilter>() {
            override fun areItemsTheSame(a: AppFilter, b: AppFilter) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppFilter, b: AppFilter) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppFilterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAppFilterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppFilter) {
            binding.tvAppName.text = item.appName
            binding.tvPackageName.text = item.packageName
            binding.switchSync.setOnCheckedChangeListener(null)
            binding.switchSync.isChecked = item.enabled
            binding.switchSync.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }
        }
    }
}
