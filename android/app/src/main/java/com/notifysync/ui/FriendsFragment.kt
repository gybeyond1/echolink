package com.notifysync.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.notifysync.R
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.drawable.BitmapDrawable
import com.notifysync.data.ApiClient
import com.notifysync.data.BingWallpaper
import com.notifysync.data.ApiException
import com.notifysync.data.AvatarLoader
import com.notifysync.data.DiscoverTopic
import java.util.regex.Pattern
import com.notifysync.data.Friend
import com.notifysync.data.FriendRequest
import com.notifysync.data.SearchUser
import com.notifysync.databinding.FragmentFriendsBinding
import kotlinx.coroutines.launch

/**
 * 好友（通讯录）页：微信风格
 * - 列表展示好友，点击进入私聊（dm 话题，复用话题聊天全套能力）
 * - 长按好友删除
 * - 「新的朋友」：收到的好友申请，可同意/拒绝/忽略
 * - 底部大「+」：添加好友 / 发现·创建话题
 */
class FriendsFragment : Fragment(), TopicFragment.ChatPaneHost {
    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var friendAdapter: FriendAdapter

    /** 平板判定：最小宽度 ≥600dp */
    private val isWide: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600

    // WS 推送（好友申请/通过验证）到达时刷新
    private val friendsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (_binding != null) load()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        friendAdapter = FriendAdapter(
            onItemClick = { openChat(it) },
            onItemLongClick = { confirmDeleteFriend(it) }
        )
        binding.rvFriends.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFriends.adapter = friendAdapter

        binding.fabAdd.setOnClickListener { showFabMenu() }
        binding.rowNewFriends.setOnClickListener { showNewFriendsDialog() }

        // 好友列表下拉刷新
        binding.swipeFriendsRefresh.setOnRefreshListener { load() }

        // 全面屏沉浸式：顶部栏避开状态栏
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(binding.topBar.paddingLeft, systemBars.top, binding.topBar.paddingRight, binding.topBar.paddingBottom)
            insets
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(
            friendsReceiver,
            IntentFilter("com.notifysync.FRIENDS_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED
        )
        load()
    }

    override fun onPause() {
        super.onPause()
        try { requireActivity().unregisterReceiver(friendsReceiver) } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        // 先展示本地内存缓存，避免切换 tab 时列表空掉/过会儿才出
        ApiClient.cachedFriends?.let { cached ->
            if (_binding != null) {
                friendAdapter.setItems(cached)
                binding.tvEmptyFriends.visibility = if (cached.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            try {
                val friends = ApiClient.getFriends()
                if (_binding == null) return@launch
                friendAdapter.setItems(friends)
                binding.tvEmptyFriends.visibility = if (friends.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                if (ApiClient.cachedFriends == null) {
                    Toast.makeText(requireContext(), "好友列表加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            if (_binding != null) binding.swipeFriendsRefresh.isRefreshing = false
            refreshNewBadge()
        }
    }

    // 「新的朋友」红点 = 待处理好友申请数
    private fun refreshNewBadge() {
        lifecycleScope.launch {
            try {
                val pending = ApiClient.getFriendRequests().filter { it.status == "pending" }
                if (pending.isNotEmpty()) {
                    binding.tvNewBadge.visibility = View.VISIBLE
                    binding.tvNewBadge.text = if (pending.size > 99) "99+" else pending.size.toString()
                } else {
                    binding.tvNewBadge.visibility = View.GONE
                }
            } catch (_: Exception) {}
        }
    }

    // ===== 好友私聊 =====

    private fun openChat(friend: Friend) {
        if (isWide) {
            // 平板：右侧聊天容器打开，左栏保留好友列表（镜像消息页的双栏体验）
            showChatOnRight(friend)
        } else {
            // 手机：整页切到聊天
            lifecycleScope.launch {
                try {
                    val (topic, title) = ApiClient.openFriendChat(friend.username)
                    val displayTitle = friend.displayName ?: title
                    (activity as? MainActivity)?.openTopic(topic, displayTitle)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "打开私聊失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 平板：在右侧 chatContainer 以「仅聊天」模式承载私聊，左栏好友列表保持可见 */
    private fun showChatOnRight(friend: Friend) {
        lifecycleScope.launch {
            try {
                val (topic, title) = ApiClient.openFriendChat(friend.username)
                val displayTitle = friend.displayName ?: title
                val frag = TopicFragment.chatOnly(topic, displayTitle)
                childFragmentManager.beginTransaction()
                    .replace(binding.chatContainer.id, frag)
                    .commit()
                binding.chatContainer.visibility = View.VISIBLE
                // 左栏固定宽度（≈360dp），右栏聊天占剩余空间
                val dm = resources.displayMetrics.density
                val lp = binding.leftPane.layoutParams as LinearLayout.LayoutParams
                lp.width = (360 * dm).toInt()
                lp.weight = 0f
                binding.leftPane.layoutParams = lp
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "打开私聊失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 右侧聊天关闭（返回手势）：收起右栏，左栏恢复整宽 */
    override fun onChatPaneClosed() {
        binding.chatContainer.visibility = View.GONE
        val lp = binding.leftPane.layoutParams as LinearLayout.LayoutParams
        lp.width = 0
        lp.weight = 1f
        binding.leftPane.layoutParams = lp
        // 清理右侧子 Fragment，避免残留
        childFragmentManager.fragments.firstOrNull()?.let {
            childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
    }

    private fun confirmDeleteFriend(friend: Friend) {
        val showName = friend.displayName ?: friend.username
        AlertDialog.Builder(requireContext())
            .setTitle("删除好友")
            .setMessage("确定删除好友「$showName」吗？聊天记录将保留，但需重新添加好友才能继续私聊。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.deleteFriend(friend.username)
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        load()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ===== 新的朋友（好友申请） =====

    private fun showNewFriendsDialog() {
        lifecycleScope.launch {
            val requests = try {
                ApiClient.getFriendRequests()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val pending = requests.filter { it.status == "pending" }
            val handled = requests.filter { it.status != "pending" }
            if (requests.isEmpty()) {
                Toast.makeText(requireContext(), "暂无好友申请", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 待处理在前；已处理的显示状态
            val labels = (pending.map { "【待处理】${it.username}${if (!it.message.isNullOrEmpty()) "：${it.message}" else ""}" } +
                handled.map { "【${statusLabel(it.status)}】${it.username}" }).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("新的朋友")
                .setItems(labels) { _, which ->
                    if (which < pending.size) showHandleRequestDialog(pending[which])
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun statusLabel(status: String): String = when (status) {
        "accepted" -> "已同意"
        "rejected" -> "已拒绝"
        "ignored" -> "已忽略"
        else -> "待处理"
    }

    private fun showHandleRequestDialog(req: FriendRequest) {
        AlertDialog.Builder(requireContext())
            .setTitle("${req.username} 请求加你为好友")
            .setMessage(if (req.message.isNullOrEmpty()) "验证消息：（无）" else "验证消息：${req.message}")
            .setPositiveButton("同意") { _, _ -> handleFriendRequest(req, "accept") }
            .setNegativeButton("拒绝") { _, _ -> handleFriendRequest(req, "reject") }
            .setNeutralButton("忽略") { _, _ -> handleFriendRequest(req, "ignore") }
            .show()
    }

    private fun handleFriendRequest(req: FriendRequest, action: String) {
        lifecycleScope.launch {
            try {
                when (action) {
                    "accept" -> { ApiClient.acceptFriendRequest(req.id); Toast.makeText(requireContext(), "已同意，你们现在是好友了", Toast.LENGTH_SHORT).show() }
                    "reject" -> { ApiClient.rejectFriendRequest(req.id); Toast.makeText(requireContext(), "已拒绝", Toast.LENGTH_SHORT).show() }
                    else -> { ApiClient.ignoreFriendRequest(req.id); Toast.makeText(requireContext(), "已忽略", Toast.LENGTH_SHORT).show() }
                }
                load()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 添加好友（搜索用户名，顶部搜索框复用） =====

    private fun doSearch(q: String) {
        lifecycleScope.launch {
            val users = try {
                ApiClient.searchUsers(q)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (users.isEmpty()) {
                Toast.makeText(requireContext(), "没有找到相关用户", Toast.LENGTH_SHORT).show()
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
            AlertDialog.Builder(requireContext())
                .setTitle("搜索结果")
                .setItems(labels) { _, which ->
                    val u = users[which]
                    if (u.isFriend) {
                        Toast.makeText(requireContext(), "你们已经是好友了", Toast.LENGTH_SHORT).show()
                    } else if (u.requested) {
                        Toast.makeText(requireContext(), "申请已发送，等待对方处理", Toast.LENGTH_SHORT).show()
                    } else {
                        sendRequest(u)
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun sendRequest(user: SearchUser) {
        val input = layoutInflater.inflate(R.layout.dialog_input_single, null)
        val et = input.findViewById<TextInputEditText>(R.id.etInput)
        et.hint = "验证消息（选填）"
        AlertDialog.Builder(requireContext())
            .setTitle("添加好友")
            .setMessage("向 ${user.displayName ?: user.username} 发送好友申请")
            .setView(input)
            .setPositiveButton("发送申请") { _, _ ->
                val message = et.text?.toString()?.trim() ?: ""
                lifecycleScope.launch {
                    try {
                        ApiClient.sendFriendRequest(user.username, message)
                        Toast.makeText(requireContext(), "已发送好友申请", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 大「+」菜单：与消息页统一（创建话题 / 发现·加入话题 / 添加好友 / 设置） =====

    private fun showFabMenu() {
        showGlobalFabMenu(
            owner = this,
            onDiscover = { showDiscoverDialog(this) { t -> (activity as? MainActivity)?.openTopic(t) } },
            onCreateTopic = { showCreateTopicDialog(this) { t -> (activity as? MainActivity)?.openTopic(t) } },
            onAddFriend = { showAddFriendDialog(this) },
            onSettings = { (requireActivity() as? MainActivity)?.openSettings() }
        )
    }

    // ===== 好友列表 Adapter =====

    private inner class FriendAdapter(
        private val onItemClick: (Friend) -> Unit,
        private val onItemLongClick: (Friend) -> Unit
    ) : RecyclerView.Adapter<FriendAdapter.ViewHolder>() {

        private val items = mutableListOf<Friend>()

        fun setItems(list: List<Friend>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
            val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
            val tvName: TextView = view.findViewById(R.id.tvFriendName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_friend, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val showName = item.displayName ?: item.username
            holder.tvName.text = showName
            if (!item.avatarUrl.isNullOrBlank()) {
                holder.tvAvatar.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                AvatarLoader.load(ApiClient.fullAvatarUrl(item.avatarUrl), holder.ivAvatar)
            } else {
                holder.ivAvatar.visibility = View.GONE
                holder.tvAvatar.visibility = View.VISIBLE
                holder.tvAvatar.text = showName.firstOrNull()?.uppercase()?.toString() ?: "?"
            }
            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
