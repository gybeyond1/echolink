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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.notifysync.R
import com.notifysync.data.ApiClient
import com.notifysync.data.AuthManager
import com.notifysync.data.DiscoverTopic
import com.notifysync.data.MyTopic
import com.notifysync.data.TopicJoinRequest
import com.notifysync.data.TopicMessage
import com.notifysync.data.WebSocketClient
import com.notifysync.data.parseTopicMessage
import com.notifysync.databinding.FragmentTopicBinding
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class TopicFragment : Fragment() {
    private var _binding: FragmentTopicBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TopicAdapter
    private val myTopics = mutableListOf<MyTopic>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private var currentTopic: String? = null

    // 收到其他设备发来的话题消息（本机自己发的已被服务器过滤）
    private val topicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val topic = intent?.getStringExtra("topic") ?: return
            if (topic == currentTopic) {
                val msg = TopicMessage(
                    id = 0,
                    topic = topic,
                    title = intent.getStringExtra("title") ?: "",
                    text = intent.getStringExtra("text") ?: "",
                    senderName = intent.getStringExtra("sender_name") ?: "",
                    timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis()),
                    deviceId = intent.getLongExtra("device_id", -1)
                )
                adapter.appendItems(listOf(msg))
                scrollToBottom()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTopicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TopicAdapter(
            onItemLongClick = { msg -> enterSelection(msg) },
            onItemClick = { msg ->
                if (adapter.selectionMode) {
                    adapter.toggle(msg)
                    updateSelectionUI()
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnAddTopic.setOnClickListener { showCreateTopicDialog() }
        binding.btnRefresh.setOnClickListener { loadMessages() }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnDeleteTopic.setOnClickListener { confirmDeleteTopic() }

        // 多选操作栏
        binding.btnCancelSel.setOnClickListener { adapter.clearSelection(); updateSelectionUI() }
        binding.btnSelectAll.setOnClickListener { adapter.selectAll(); updateSelectionUI() }
        binding.btnDeleteSel.setOnClickListener { confirmDeleteSelected() }

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener { loadMessages() }

        // 发现/加入、待审批、退出
        binding.btnDiscover.setOnClickListener { showDiscoverDialog() }
        binding.btnPending.setOnClickListener { showPendingDialog() }
        binding.btnLeave.setOnClickListener { confirmLeave() }

        binding.spTopic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in myTopics.indices) switchTopic(myTopics[position].name)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadMyTopics()

        // 若从状态栏通知（话题消息）进入，定位到对应话题
        val argTopic = arguments?.getString("topic")
        if (!argTopic.isNullOrEmpty()) {
            if (myTopics.any { it.name == argTopic }) {
                val idx = myTopics.indexOfFirst { it.name == argTopic }
                binding.spTopic.setSelection(idx)
                if (idx == binding.spTopic.selectedItemPosition) switchTopic(argTopic)
            } else {
                // 尝试直接打开（可能是成员但列表尚未加载）
                currentTopic = argTopic
                loadMessages()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(
            topicReceiver,
            IntentFilter("com.notifysync.TOPIC_MESSAGE_RECEIVED"),
            Context.RECEIVER_NOT_EXPORTED
        )
        currentTopic?.let { WebSocketClient.sendSubscribe(it) }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(topicReceiver)
        } catch (e: Exception) {
            // ignored
        }
    }

    // 从服务器加载我参与的话题
    private fun loadMyTopics() {
        lifecycleScope.launch {
            try {
                val list = ApiClient.getMyTopics()
                myTopics.clear()
                myTopics.addAll(list.sortedByDescending { it.messageCount })
                // 同步本地订阅集合，供 SyncService 在重连时自动订阅
                AuthManager.subscribedTopics = myTopics.map { it.name }.toSet()
                val names = myTopics.map { it.name }
                spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spTopic.adapter = spinnerAdapter

                if (myTopics.isEmpty()) {
                    currentTopic = null
                    adapter.setItems(emptyList())
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                    binding.selectionBar.visibility = View.GONE
                    updateTopicInfo()
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.spTopic.setSelection(0)
                    switchTopic(myTopics[0].name)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "话题加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchTopic(topic: String) {
        if (currentTopic == topic && adapter.itemCount > 0) {
            updateTopicInfo()
            return
        }
        currentTopic?.let { WebSocketClient.sendUnsubscribe(it) }
        currentTopic = topic
        WebSocketClient.sendSubscribe(topic)
        updateTopicInfo()
        loadMessages()
    }

    // 顶部话题信息：角色 / 待审批数
    private fun updateTopicInfo() {
        val topic = currentTopic ?: run {
            binding.tvTopicInfo.text = ""
            binding.btnPending.visibility = View.GONE
            binding.btnLeave.visibility = View.GONE
            return
        }
        val mt = myTopics.find { it.name == topic }
        val info = when (mt?.myRole) {
            "owner" -> "创建者"
            "member" -> "成员"
            else -> ""
        }
        val pending = mt?.pendingRequests ?: 0
        binding.tvTopicInfo.text = buildString {
            if (info.isNotEmpty()) append(info)
            if (pending > 0) append(" · $pending 个待审批")
        }
        // 创建者才显示"待审批"入口
        binding.btnPending.visibility = if (mt?.myRole == "owner" && pending > 0) View.VISIBLE else View.GONE
        // 成员可退出；创建者需先删除话题
        binding.btnLeave.visibility = if (mt?.myRole == "member") View.VISIBLE else View.GONE
    }

    private fun loadMessages() {
        val topic = currentTopic ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val messages = ApiClient.getTopicMessages(topic, 50)
                adapter.setItems(messages)
                binding.tvEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
                scrollToBottom()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun sendMessage() {
        val topic = currentTopic
        if (topic == null) {
            Toast.makeText(requireContext(), "请先选择话题", Toast.LENGTH_SHORT).show()
            return
        }
        val text = binding.etInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return

        binding.etInput.setText("")
        lifecycleScope.launch {
            try {
                val json = ApiClient.publishTopicMessage(topic, "", text)
                val msg = parseTopicMessage(json.getJSONObject("topic_message"))
                adapter.appendItems(listOf(msg))
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                scrollToBottom()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 多选删除 =====

    private fun enterSelection(msg: TopicMessage) {
        adapter.enterSelection(msg)
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        if (adapter.selectionMode) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelectedCount.text = "已选 ${adapter.selectedCount}"
        } else {
            binding.selectionBar.visibility = View.GONE
        }
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择要删除的消息", Toast.LENGTH_SHORT).show()
            return
        }
        val topic = currentTopic ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("删除消息")
            .setMessage("确定删除选中的 ${ids.size} 条消息吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ids.forEach { ApiClient.deleteTopicMessage(topic, it) }
                        Toast.makeText(requireContext(), "已删除 ${ids.size} 条", Toast.LENGTH_SHORT).show()
                        adapter.clearSelection()
                        loadMessages()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 删除话题 =====

    private fun confirmDeleteTopic() {
        val topic = currentTopic
        if (topic == null) {
            Toast.makeText(requireContext(), "请先选择话题", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除话题")
            .setMessage("确定删除话题「$topic」吗？该话题下的全部消息都会被删除，且不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.deleteTopic(topic)
                        AuthManager.removeTopic(topic)
                        Toast.makeText(requireContext(), "话题已删除", Toast.LENGTH_SHORT).show()
                        loadMyTopics()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 创建话题 =====

    private fun showCreateTopicDialog() {
        val layout = requireActivity().layoutInflater.inflate(R.layout.dialog_create_topic, null)
        val etName = layout.findViewById<TextInputEditText>(R.id.etTopicName)
        val etTitle = layout.findViewById<TextInputEditText>(R.id.etTopicTitle)
        AlertDialog.Builder(requireContext())
            .setTitle("创建话题（群聊）")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text?.toString()?.trim()?.lowercase() ?: ""
                val title = etTitle.text?.toString()?.trim() ?: ""
                if (!Pattern.matches("^[a-z0-9_-]{1,64}$", name)) {
                    Toast.makeText(requireContext(), "话题名不合法（1-64位字母/数字/_/-）", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (myTopics.any { it.name == name }) {
                    Toast.makeText(requireContext(), "话题已存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        ApiClient.createTopic(name, title, "")
                        Toast.makeText(requireContext(), "话题已创建", Toast.LENGTH_SHORT).show()
                        loadMyTopics()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 发现 / 加入 =====

    private fun showDiscoverDialog() {
        val layout = requireActivity().layoutInflater.inflate(R.layout.dialog_discover_topic, null)
        val etName = layout.findViewById<TextInputEditText>(R.id.etJoinName)
        val listView = layout.findViewById<android.widget.ListView>(R.id.lvDiscover)
        val empty = layout.findViewById<android.widget.TextView>(R.id.tvDiscoverEmpty)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("发现 / 申请加入话题")
            .setView(layout)
            .setNegativeButton("关闭", null)
            .create()

        val items = mutableListOf<DiscoverTopic>()
        val adapterList = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapterList
        listView.setOnItemClickListener { _, _, pos, _ ->
            val t = items.getOrNull(pos) ?: return@setOnItemClickListener
            requestJoin(t.name, dialog)
        }

        fun refresh() {
            lifecycleScope.launch {
                try {
                    val list = ApiClient.getDiscoverTopics()
                    items.clear()
                    items.addAll(list)
                    adapterList.clear()
                    adapterList.addAll(list.map { "#${it.name}  (创建者 ${it.ownerName ?: "-"} · ${it.memberCount}人)" })
                    adapterList.notifyDataSetChanged()
                    empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        refresh()

        layout.findViewById<android.widget.Button>(R.id.btnJoinByName).setOnClickListener {
            val n = etName.text?.toString()?.trim()?.lowercase() ?: ""
            if (!Pattern.matches("^[a-z0-9_-]{1,64}$", n)) {
                Toast.makeText(requireContext(), "话题名不合法", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestJoin(n, dialog)
        }

        dialog.show()
    }

    private fun requestJoin(name: String, dialog: AlertDialog) {
        lifecycleScope.launch {
            try {
                ApiClient.requestJoinTopic(name, "")
                Toast.makeText(requireContext(), "已发送加入申请，等待创建者审批", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "申请失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 待审批申请（创建者） =====

    private fun showPendingDialog() {
        val topic = currentTopic ?: return
        lifecycleScope.launch {
            try {
                val list = ApiClient.getTopicRequests(topic).filter { it.status == "pending" }
                if (list.isEmpty()) {
                    Toast.makeText(requireContext(), "暂无待审批申请", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val names = list.map { "${it.username} 申请加入" }.toTypedArray()
                val checked = BooleanArray(list.size) { true }
                AlertDialog.Builder(requireContext())
                    .setTitle("待审批申请")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("通过选中") { _, _ ->
                        handleRequests(topic, list, checked, approve = true)
                    }
                    .setNeutralButton("拒绝选中") { _, _ ->
                        handleRequests(topic, list, checked, approve = false)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRequests(topic: String, list: List<TopicJoinRequest>, checked: BooleanArray, approve: Boolean) {
        lifecycleScope.launch {
            try {
                list.forEachIndexed { i, req ->
                    if (checked[i]) {
                        if (approve) ApiClient.approveTopicRequest(topic, req.id)
                        else ApiClient.rejectTopicRequest(topic, req.id)
                    }
                }
                Toast.makeText(requireContext(), if (approve) "已通过" else "已拒绝", Toast.LENGTH_SHORT).show()
                loadMyTopics()
                updateTopicInfo()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 退出话题 =====

    private fun confirmLeave() {
        val topic = currentTopic ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("退出话题")
            .setMessage("确定退出话题「$topic」吗？退出后将无法查看其消息，需重新申请加入。")
            .setPositiveButton("退出") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.leaveTopic(topic)
                        AuthManager.removeTopic(topic)
                        Toast.makeText(requireContext(), "已退出话题", Toast.LENGTH_SHORT).show()
                        loadMyTopics()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "退出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun scrollToBottom() {
        if (adapter.itemCount > 0) {
            binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
