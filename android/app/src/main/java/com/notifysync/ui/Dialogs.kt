package com.notifysync.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.notifysync.R
import com.notifysync.data.ApiClient
import com.notifysync.data.ApiException
import com.notifysync.data.DiscoverTopic
import com.notifysync.data.SearchUser
import kotlinx.coroutines.launch
import java.util.regex.Pattern

private fun contextOf(owner: LifecycleOwner): Context =
    (owner as? Fragment)?.requireContext() ?: (owner as Context)

private fun inflaterOf(owner: LifecycleOwner): LayoutInflater =
    (owner as? Fragment)?.layoutInflater ?: (owner as AppCompatActivity).layoutInflater

/**
 * 强制把 AlertDialog 窗口背景设成聊天页背景色（不再依赖 theme colorSurface，
 * 因为系统/Material 对 colorSurface 的解析在不同 ROM 上不稳定，导致菜单反复白底）。
 */
private fun AlertDialog.forceChatBg() {
    window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
}

/**
 * 创建弹窗并强制设置 0.5 遮罩 + chat_bg 背景。
 */
private fun AlertDialog.Builder.buildDimmed(): AlertDialog =
    create().apply {
        forceChatBg()
        window?.setDimAmount(0.5f)
    }

/**
 * 一键创建 + chat_bg 背景 + 0.5 遮罩 + 显示。所有 AlertDialog 统一入口。
 */
fun AlertDialog.Builder.showDimmed(): AlertDialog {
    val dialog = create()
    dialog.forceChatBg()
    dialog.window?.setDimAmount(0.5f)
    dialog.show()
    return dialog
}

/**
 * 统一的「+」菜单：消息页与好友页共用同一组件，
 * 选项完全一致：创建话题 / 发现·加入话题 / 添加好友 / 设置。
 * 弹窗走 Theme.NotifySync.Dialog（白底 + 0.5 遮罩），与聊天页视觉一致。
 */
fun showGlobalFabMenu(
    owner: LifecycleOwner,
    onDiscover: () -> Unit,
    onCreateTopic: () -> Unit,
    onAddFriend: () -> Unit,
    onSettings: () -> Unit
) {
    val ctx = contextOf(owner)
    val options = arrayOf("创建话题", "发现 / 加入话题", "添加好友", "设置")
    AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog)
        .setTitle("菜单")
        .setItems(options) { _, which ->
            when (which) {
                0 -> onCreateTopic()
                1 -> onDiscover()
                2 -> onAddFriend()
                3 -> onSettings()
            }
        }
        .setNegativeButton("取消", null)
        .buildDimmed().show()
}

/** 创建话题（消息页 / 好友页共用） */
fun showCreateTopicDialog(owner: LifecycleOwner, openTopic: (String) -> Unit) {
    val ctx = contextOf(owner)
    val inflater = inflaterOf(owner)
    val layout = inflater.inflate(R.layout.dialog_create_topic, null)
    val etName = layout.findViewById<TextInputEditText>(R.id.etTopicName)
    val etTitle = layout.findViewById<TextInputEditText>(R.id.etTopicTitle)
    AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog)
        .setTitle("创建话题")
        .setView(layout)
        .setPositiveButton("创建") { _, _ ->
            val name = etName.text?.toString()?.trim()?.lowercase() ?: ""
            val title = etTitle.text?.toString()?.trim() ?: ""
            if (!Pattern.matches("^[a-z0-9_-]{1,64}$", name)) {
                Toast.makeText(ctx, "话题名不合法（字母/数字/_/-，≤64字符）", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            owner.lifecycleScope.launch {
                try {
                    ApiClient.createTopic(name, title, "")
                    Toast.makeText(ctx, "话题已创建", Toast.LENGTH_SHORT).show()
                    openTopic(name)
                } catch (e: Exception) {
                    if (e is ApiException && e.code == 409) {
                        Toast.makeText(ctx, "话题已存在，请到「发现」申请加入", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        .setNegativeButton("取消", null)
        .buildDimmed().show()
}

/** 发现 / 创建 / 加入话题（消息页 / 好友页共用，天然合并「发现」与「加入」） */
fun showDiscoverDialog(owner: LifecycleOwner, openTopic: (String) -> Unit) {
    val ctx = contextOf(owner)
    val inflater = inflaterOf(owner)
    val layout = inflater.inflate(R.layout.dialog_discover_topic, null)
    val etName = layout.findViewById<TextInputEditText>(R.id.etJoinName)
    val listView = layout.findViewById<android.widget.ListView>(R.id.lvDiscover)
    val empty = layout.findViewById<TextView>(R.id.tvDiscoverEmpty)
    val btnJoin = layout.findViewById<android.widget.Button>(R.id.btnJoinByName)
    btnJoin.text = "创建/加入"

    val dialog = AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog).setTitle("发现 / 创建话题").setView(layout).setNegativeButton("关闭", null).buildDimmed()

    val items = mutableListOf<DiscoverTopic>()
    val adapterList = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, mutableListOf<String>())
    listView.adapter = adapterList
    listView.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { discoverRequestJoin(owner, ctx, inflater, it.name, dialog) } }

    fun refresh() {
        owner.lifecycleScope.launch {
            try {
                val list = ApiClient.getDiscoverTopics()
                items.clear(); items.addAll(list)
                adapterList.clear()
                adapterList.addAll(list.map { "#${it.name}  (创建者 ${it.ownerName ?: "-"} · ${it.memberCount}人)" })
                adapterList.notifyDataSetChanged()
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) { Toast.makeText(ctx, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }
    refresh()

    btnJoin.setOnClickListener {
        val n = etName.text?.toString()?.trim()?.lowercase() ?: ""
        if (!Pattern.matches("^[a-z0-9_-]{1,64}$", n)) { Toast.makeText(ctx, "话题名不合法", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
        // 先尝试创建：已存在则返回 409 -> 转为申请加入
        owner.lifecycleScope.launch {
            try {
                ApiClient.createTopic(n, "", "")
                Toast.makeText(ctx, "话题已创建（你是创建者）", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                openTopic(n)
            } catch (e: Exception) {
                if (e is ApiException && e.code == 409) {
                    discoverRequestJoin(owner, ctx, inflater, n, dialog)
                } else {
                    Toast.makeText(ctx, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    dialog.show()
}

private fun discoverRequestJoin(owner: LifecycleOwner, ctx: Context, inflater: LayoutInflater, name: String, dlg: AlertDialog) {
    val input = inflater.inflate(R.layout.dialog_input_single, null)
    val et = input.findViewById<TextInputEditText>(R.id.etInput)
    et.hint = "验证消息（选填）"
    AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog)
        .setTitle("申请加入「$name」")
        .setMessage("填写验证消息发送加群申请")
        .setView(input)
        .setPositiveButton("发送申请") { _, _ ->
            val message = et.text?.toString()?.trim() ?: ""
            owner.lifecycleScope.launch {
                try {
                    ApiClient.requestJoinTopic(name, message)
                    Toast.makeText(ctx, "已发送加入申请，等待创建者审批", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                } catch (e: Exception) { Toast.makeText(ctx, "申请失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
        .setNegativeButton("取消", null)
        .buildDimmed().show()
}

/** 添加好友（消息页 / 好友页共用）：搜索用户名 -> 选人 -> 发申请 */
fun showAddFriendDialog(owner: LifecycleOwner) {
    val ctx = contextOf(owner)
    val inflater = inflaterOf(owner)
    val input = inflater.inflate(R.layout.dialog_input_single, null)
    val et = input.findViewById<TextInputEditText>(R.id.etInput)
    AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog)
        .setTitle("添加好友")
        .setMessage("输入对方用户名的一部分进行搜索")
        .setView(input)
        .setPositiveButton("搜索") { _, _ ->
            val q = et.text?.toString()?.trim() ?: ""
            if (q.isEmpty()) Toast.makeText(ctx, "请输入关键词", Toast.LENGTH_SHORT).show()
            else addFriendDoSearch(owner, ctx, q)
        }
        .setNegativeButton("取消", null)
        .buildDimmed().show()
}

private fun addFriendDoSearch(owner: LifecycleOwner, ctx: Context, q: String) {
    owner.lifecycleScope.launch {
        val users = try {
            ApiClient.searchUsers(q)
        } catch (e: Exception) {
            Toast.makeText(ctx, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return@launch
        }
        if (users.isEmpty()) {
            Toast.makeText(ctx, "没有找到相关用户", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val labels = users.map {
            val name = it.displayName ?: it.username
            when {
                it.isFriend -> "$name（已是好友）"
                it.requested -> "$name（已发申请，等待对方处理）"
                else -> name
            }
        }.toTypedArray()
        AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog)
            .setTitle("搜索结果")
            .setItems(labels) { _, which ->
                val u = users[which]
                when {
                    u.isFriend -> Toast.makeText(ctx, "你们已经是好友了", Toast.LENGTH_SHORT).show()
                    u.requested -> Toast.makeText(ctx, "申请已发送，等待对方处理", Toast.LENGTH_SHORT).show()
                    else -> addFriendSendRequest(owner, ctx, inflaterOf(owner), u)
                }
            }
            .setNegativeButton("关闭", null)
            .buildDimmed().show()
    }
}

private fun addFriendSendRequest(owner: LifecycleOwner, ctx: Context, inflater: LayoutInflater, user: SearchUser) {
    val input = inflater.inflate(R.layout.dialog_input_single, null)
    val et = input.findViewById<TextInputEditText>(R.id.etInput)
    et.hint = "验证消息（选填）"
    AlertDialog.Builder(ctx, R.style.Theme_NotifySync_Dialog)
        .setTitle("添加好友")
        .setMessage("向 ${user.displayName ?: user.username} 发送好友申请")
        .setView(input)
        .setPositiveButton("发送申请") { _, _ ->
            val message = et.text?.toString()?.trim() ?: ""
            owner.lifecycleScope.launch {
                try {
                    ApiClient.sendFriendRequest(user.username, message)
                    Toast.makeText(ctx, "已发送好友申请", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setNegativeButton("取消", null)
        .buildDimmed().show()
}
