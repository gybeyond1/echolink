package com.notifysync.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.notifysync.R
import com.notifysync.data.MyTopic
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
        items.addAll(list.sortedByDescending { it.lastMessageAtOrCount() })
        notifyDataSetChanged()
    }

    // 排序：有最新消息时间的按时间，否则按消息数
    private fun MyTopic.lastMessageAtOrCount(): Long = (System.currentTimeMillis())

    fun getItem(position: Int): MyTopic = items[position]
    fun getItems(): List<MyTopic> = items

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvRole: TextView = view.findViewById(R.id.tvRole)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvPending: TextView = view.findViewById(R.id.tvPending)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAvatar.text = item.name.firstOrNull()?.uppercase()?.toString() ?: "#"
        holder.tvName.text = item.name

        // 角色标签
        if (item.myRole == "owner") {
            holder.tvRole.visibility = View.VISIBLE
            holder.tvRole.text = "创建者"
        } else {
            holder.tvRole.visibility = View.GONE
        }

        // 预览
        holder.tvPreview.text = if (!item.lastMessage.isNullOrEmpty()) item.lastMessage else "暂无消息"

        // 待审批红点（仅创建者）
        if (item.myRole == "owner" && item.pendingRequests > 0) {
            holder.tvPending.visibility = View.VISIBLE
            holder.tvPending.text = item.pendingRequests.toString()
        } else {
            holder.tvPending.visibility = View.GONE
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
