package com.notifysync.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifysync.data.NotificationItem
import com.notifysync.databinding.ItemNotificationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationAdapter :
    ListAdapter<NotificationItem, NotificationAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NotificationItem>() {
            override fun areItemsTheSame(a: NotificationItem, b: NotificationItem) = a.id == b.id
            override fun areContentsTheSame(a: NotificationItem, b: NotificationItem) = a == b
        }
    }

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemNotificationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NotificationItem) {
            binding.tvAppName.text = item.appName
            binding.tvTitle.text = item.title.ifEmpty { "(无标题)" }
            binding.tvText.text = item.text.ifEmpty { "(无内容)" }
            binding.tvTime.text = dateFormat.format(Date(item.timestamp))
            binding.tvPackage.text = item.packageName
            binding.tvDevice.text = item.deviceName ?: ""
        }
    }
}
