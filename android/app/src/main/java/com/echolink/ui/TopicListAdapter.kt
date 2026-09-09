package com.echolink.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.echolink.R
import com.echolink.data.ApiClient
import com.echolink.data.AvatarLoader
import com.echolink.data.MyTopic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TopicListAdapter(
    private val onItemClick: (MyTopic) -> Unit,
    private val onItemLongClick: (MyTopic) -> Unit
) : RecyclerView.Adapter<TopicListAdapter.ViewHolder>() {

    private val items = mutableListOf<MyTopic>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun setItems(list: List<MyTopic>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): MyTopic = items[position]
    fun getItems(): List<MyTopic> = items

    // 头像：设备/留言板/MP/通知→新拟态PNG图标；私聊→好友头像；群聊→首字母
    private fun bindAvatar(holder: ViewHolder, item: MyTopic, display: String) {
        when {
            item.kind == "devices" -> {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                holder.ivAvatar.setImageResource(R.drawable.ic_my_device_neo)
                holder.ivAvatar.setBackgroundResource(0)
            }
            item.kind == "messagewall" -> {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                holder.ivAvatar.setImageResource(R.drawable.ic_messagewall_neo)
                holder.ivAvatar.setBackgroundResource(0)
            }
            item.kind == "moviepilot" -> {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                holder.ivAvatar.setImageResource(R.drawable.ic_moviepilot_neo)
                holder.ivAvatar.setBackgroundResource(0)
            }
            item.kind == "notification" -> {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                holder.ivAvatar.setImageResource(R.drawable.ic_notification_neo)
                holder.ivAvatar.setBackgroundResource(0)
            }
            !item.avatarUrl.isNullOrBlank() -> {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                AvatarLoader.load(ApiClient.fullAvatarUrl(item.avatarUrl), holder.ivAvatar)
            }
            else -> {
                holder.ivAvatar.visibility = View.GONE
                holder.tvAvatar.visibility = View.VISIBLE
                holder.tvAvatar.text = display.firstOrNull()?.uppercase()?.toString() ?: "#"
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvRole: TextView = view.findViewById(R.id.tvRole)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvPending: TextView = view.findViewById(R.id.tvPending)
        val tvUnread: TextView = view.findViewById(R.id.tvUnread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val display = when (item.kind) {
            "devices" -> "我的设备"
            "messagewall" -> "留言板"
            "moviepilot" -> "MoviePilot"
            "notification" -> "通知"
            else -> item.displayName ?: item.name
        }
        holder.tvName.text = display
        bindAvatar(holder, item, display)

        if (item.myRole == "owner" && item.kind == "normal") {
            holder.tvRole.visibility = View.VISIBLE
            holder.tvRole.text = "创建者"
        } else {
            holder.tvRole.visibility = View.GONE
        }

        holder.tvPreview.text = if (!item.lastMessage.isNullOrEmpty()) item.lastMessage else "暂无消息"

        if (item.myRole == "owner" && item.pendingRequests > 0) {
            holder.tvPending.visibility = View.VISIBLE
            holder.tvPending.text = item.pendingRequests.toString()
        } else {
            holder.tvPending.visibility = View.GONE
        }

        if (item.unreadCount > 0) {
            holder.tvUnread.visibility = View.VISIBLE
            holder.tvUnread.text = item.unreadCount.toString()
        } else {
            holder.tvUnread.visibility = View.GONE
        }

        holder.tvTime.text = if (item.messageCount > 0) "${item.messageCount}条" else ""

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
