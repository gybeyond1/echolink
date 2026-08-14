package com.notifysync.ui

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class TopicFragment : Fragment() {
    private var _binding: FragmentTopicBinding? = null
    private val binding get() = _binding!!

    private lateinit var listAdapter: TopicListAdapter
    private lateinit var chatAdapter: TopicAdapter
    private val myTopics = mutableListOf<MyTopic>()
    private var currentTopic: String? = null
    private var chatTopic: MyTopic? = null

    private var mediaRecorder: MediaRecorder? = null
    private var voiceFile: File? = null

    private var voiceMode = false          // true=按住说话  false=键盘输入
    private var panelMode: String? = null  // null | "emoji" | "more"

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
                chatAdapter.appendItems(listOf(msg))
                scrollToBottom()
            }
        }
    }

    private lateinit var backCallback: OnBackPressedCallback

    private val listRefreshHandler = Handler(Looper.getMainLooper())
    private val listRefreshRunnable = object : Runnable {
        override fun run() {
            if (_binding != null && binding.listLayout.visibility == View.VISIBLE) {
                refreshTopicListBadges()
            }
            listRefreshHandler.postDelayed(this, 15000)
        }
    }

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePickedFile(uri)
    }

    private val requestRecordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Toast.makeText(requireContext(), "需要录音权限才能发送语音", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTopicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listAdapter = TopicListAdapter(
            onItemClick = { showChatMode(it) },
            onItemLongClick = { showTopicMenu(it) }
        )
        binding.rvTopics.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopics.adapter = listAdapter

        chatAdapter = TopicAdapter(
            onItemLongClick = { msg ->
                // 已处于多选：长按 = 选中/取消该条（不震动）；否则长按进入多选并选中（震动反馈）
                if (chatAdapter.selectionMode) { chatAdapter.toggle(msg); updateSelectionUI() }
                else { enterSelection(msg); vibrate() }
            },
            onItemClick = { msg ->
                if (chatAdapter.selectionMode) { chatAdapter.toggle(msg); updateSelectionUI() }
            },
            onImageClick = { url -> showImageFullscreen(url) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = chatAdapter

        // 左滑删除（成员=从列表移除；创建者=彻底关闭）
        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, vh2: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val t = listAdapter.getItem(vh.bindingAdapterPosition)
                swipeDelete(t)
            }
        })
        touch.attachToRecyclerView(binding.rvTopics)

        // 系统返回手势/按键：聊天态 → 返回话题列表；多选态 → 先退出多选；列表态 → 交给系统（默认行为）
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    chatAdapter.selectionMode -> { chatAdapter.clearSelection(); updateSelectionUI() }
                    binding.chatLayout.visibility == View.VISIBLE -> showListMode()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // 聊天态：待审批
        binding.btnPending.setOnClickListener { showPendingDialog() }

        // 列表态：底部「+」= 发现 / 加入 / 创建（含新建）
        binding.fabAddTopic.setOnClickListener { showDiscoverDialog() }

        // 多选操作栏（右上角：全选 / 删除；退出多选用系统返回手势）
        binding.btnSelectAll.setOnClickListener { chatAdapter.selectAll(); updateSelectionUI() }
        binding.btnDeleteSel.setOnClickListener { confirmDeleteSelected() }

        // 下拉刷新（保留）
        binding.swipeRefresh.setOnRefreshListener { loadMessages() }

        // ===== 微信式输入栏 =====
        binding.btnSend.setOnClickListener { sendText() }
        binding.btnVoiceToggle.setOnClickListener { toggleVoiceMode() }
        binding.btnEmoji.setOnClickListener { togglePanel("emoji") }
        binding.btnPlus.setOnClickListener { togglePanel("more") }
        binding.btnHoldTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { ensureRecordPermission(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopRecordingAndSend(); true }
                else -> false
            }
        }
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val has = !s.isNullOrEmpty()
                binding.btnSend.visibility = if (has) View.VISIBLE else View.GONE
                binding.btnPlus.visibility = if (has) View.GONE else View.VISIBLE
                if (has) hidePanels()
            }
        })
        binding.etInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) hidePanels() }
        binding.moreImage.setOnClickListener { hidePanels(); pickImage() }
        binding.moreFile.setOnClickListener { hidePanels(); pickFile() }
        binding.moreJoin.setOnClickListener { hidePanels(); showDiscoverDialog() }
        binding.moreSearch.setOnClickListener { hidePanels(); showDiscoverDialog() }
        binding.moreCreate.setOnClickListener { hidePanels(); showCreateTopicDialog() }
        setupEmojiGrid()

        showListMode()
        listRefreshHandler.postDelayed(listRefreshRunnable, 15000)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(topicReceiver, IntentFilter("com.notifysync.TOPIC_MESSAGE_RECEIVED"), Context.RECEIVER_NOT_EXPORTED)
        currentTopic?.let { WebSocketClient.sendSubscribe(it) }
    }

    override fun onPause() {
        super.onPause()
        try { requireActivity().unregisterReceiver(topicReceiver) } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listRefreshHandler.removeCallbacks(listRefreshRunnable)
        releaseRecorder()
        _binding = null
    }

    // ===== 模式切换 =====

    private fun showListMode() {
        currentTopic = null
        chatTopic = null
        binding.listLayout.visibility = View.VISIBLE
        binding.chatLayout.visibility = View.GONE
        binding.tvTitleList.visibility = View.VISIBLE
        backCallback.isEnabled = false
        binding.tvChatTitle.visibility = View.GONE
        binding.btnPending.visibility = View.GONE
        binding.fabAddTopic.visibility = View.VISIBLE
        loadTopicList()
    }

    private fun showChatMode(topic: MyTopic) {
        chatTopic = topic
        currentTopic = topic.name
        binding.listLayout.visibility = View.GONE
        binding.chatLayout.visibility = View.VISIBLE
        binding.tvTitleList.visibility = View.GONE
        backCallback.isEnabled = true
        binding.tvChatTitle.visibility = View.VISIBLE
        binding.tvChatTitle.text = topic.name
        binding.fabAddTopic.visibility = View.GONE
        binding.btnPending.visibility = if (topic.myRole == "owner" && topic.pendingRequests > 0) View.VISIBLE else View.GONE
        WebSocketClient.sendSubscribe(topic.name)
        loadMessages()
    }

    // ===== 话题列表 =====

    private fun loadTopicList() {
        lifecycleScope.launch {
            try {
                val list = ApiClient.getMyTopics()
                myTopics.clear()
                myTopics.addAll(list)
                AuthManager.subscribedTopics = myTopics.map { it.name }.toSet()
                listAdapter.setItems(myTopics)
                binding.tvEmptyTopics.visibility = if (myTopics.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "话题列表加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 只刷新徽标（待审批数），不打断滚动
    private fun refreshTopicListBadges() {
        lifecycleScope.launch {
            try {
                val list = ApiClient.getMyTopics()
                myTopics.clear(); myTopics.addAll(list)
                AuthManager.subscribedTopics = myTopics.map { it.name }.toSet()
                listAdapter.setItems(myTopics)
            } catch (_: Exception) {}
        }
    }

    // 长按菜单：打开 / 删除（从列表移除）/ 关闭（彻底关闭，仅创建者）
    private fun showTopicMenu(topic: MyTopic) {
        val owner = topic.myRole == "owner"
        val options = mutableListOf("打开")
        if (owner) options.add("关闭话题（彻底关闭）") else options.add("删除（从我的列表移除）")
        AlertDialog.Builder(requireContext())
            .setTitle(topic.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showChatMode(topic)
                    1 -> if (owner) confirmCloseTopic(topic) else confirmLeaveTopic(topic)
                }
            }.show()
    }

    private fun swipeDelete(topic: MyTopic) {
        if (topic.myRole == "owner") confirmCloseTopic(topic) else confirmLeaveTopic(topic)
    }

    private fun confirmLeaveTopic(topic: MyTopic) {
        AlertDialog.Builder(requireContext())
            .setTitle("从列表移除")
            .setMessage("确定把话题「${topic.name}」从你的列表移除吗？（仍可在「发现/加入」重新申请）")
            .setPositiveButton("移除") { _, _ ->
                lifecycleScope.launch {
                    try { ApiClient.leaveTopic(topic.name); AuthManager.removeTopic(topic.name); Toast.makeText(requireContext(), "已移除", Toast.LENGTH_SHORT).show(); showListMode() }
                    catch (e: Exception) { Toast.makeText(requireContext(), "失败: ${e.message}", Toast.LENGTH_SHORT).show(); loadTopicList() }
                }
            }.setNegativeButton("取消") { _, _ -> loadTopicList() }.show()
    }

    private fun confirmCloseTopic(topic: MyTopic) {
        AlertDialog.Builder(requireContext())
            .setTitle("关闭话题")
            .setMessage("确定彻底关闭话题「${topic.name}」吗？所有成员将无法加入，全部消息会被清空，且不可恢复。")
            .setPositiveButton("彻底关闭") { _, _ ->
                lifecycleScope.launch {
                    try { ApiClient.deleteTopic(topic.name); AuthManager.removeTopic(topic.name); Toast.makeText(requireContext(), "话题已关闭", Toast.LENGTH_SHORT).show(); showListMode() }
                    catch (e: Exception) { Toast.makeText(requireContext(), "失败: ${e.message}", Toast.LENGTH_SHORT).show(); loadTopicList() }
                }
            }.setNegativeButton("取消") { _, _ -> loadTopicList() }.show()
    }

    // ===== 聊天 =====

    private fun loadMessages() {
        val topic = currentTopic ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val messages = ApiClient.getTopicMessages(topic, 50)
                chatAdapter.setItems(messages)
                binding.tvEmptyChat.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
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

    private fun sendText() {
        val topic = currentTopic ?: return
        val text = binding.etInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return
        binding.etInput.setText("")
        hidePanels()
        publish(topic, "", text, "text", null, null, 0)
    }

    private fun publish(topic: String, title: String, text: String, mediaType: String, mediaUrl: String?, mediaName: String?, mediaSize: Long) {
        lifecycleScope.launch {
            try {
                val json = ApiClient.publishTopicMessage(topic, title, text, mediaType, mediaUrl, mediaName, mediaSize)
                val msg = parseTopicMessage(json.getJSONObject("topic_message"))
                chatAdapter.appendItems(listOf(msg))
                binding.tvEmptyChat.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                scrollToBottom()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) binding.recyclerView.scrollToPosition(chatAdapter.itemCount - 1)
    }

    // ===== 微信式输入栏交互 =====

    private fun toggleVoiceMode() {
        voiceMode = !voiceMode
        if (voiceMode) {
            binding.etInput.visibility = View.GONE
            binding.btnHoldTalk.visibility = View.VISIBLE
            binding.btnVoiceToggle.setImageResource(R.drawable.ic_keyboard)
            hideKeyboard()
            hidePanels()
        } else {
            binding.etInput.visibility = View.VISIBLE
            binding.btnHoldTalk.visibility = View.GONE
            binding.btnVoiceToggle.setImageResource(R.drawable.ic_mic)
            binding.etInput.requestFocus()
        }
    }

    private fun togglePanel(which: String) {
        if (panelMode == which) { hidePanels(); return }
        panelMode = which
        binding.panelContainer.visibility = View.VISIBLE
        binding.emojiGrid.visibility = if (which == "emoji") View.VISIBLE else View.GONE
        binding.moreLayout.visibility = if (which == "more") View.VISIBLE else View.GONE
        if (which == "emoji") hideKeyboard()
    }

    private fun hidePanels() {
        panelMode = null
        binding.panelContainer.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    private val EMOJIS = listOf(
        "😀","😁","😂","🤣","😊","😍","😘","😎","🤔","😜","😭","😡",
        "👍","👎","👌","🙏","💪","🤝","❤️","💔","🔥","⭐","🎉","✨",
        "🌹","🌟","😅","😏","😴","🤗","😇","🥺","😋","🤩","😱","😤",
        "🤯","🥳","😬","🙄","😳","💯","✅","❌","⚡","🌈","☀️","🌙",
        "💡","📌","🎯","🍻","🍺","☕","🍎","🎁","💰","🚀","⏰","📱",
        "💬","👀","🔔","🐶","🐱","🌻","⚽","🎮","🎵","⌫"
    )

    private fun setupEmojiGrid() {
        val adapter = ArrayAdapter(requireContext(), R.layout.item_emoji, EMOJIS)
        binding.emojiGrid.adapter = adapter
        binding.emojiGrid.setOnItemClickListener { _, _, pos, _ ->
            val e = EMOJIS[pos]
            val et = binding.etInput
            if (e == "⌫") {
                val t = et.text
                if (!t.isNullOrEmpty()) {
                    val start = et.selectionStart
                    val end = et.selectionEnd
                    if (start == end && start > 0) et.text?.delete(start - 1, start)
                    else if (start != end) et.text?.delete(start, end)
                }
            } else {
                val start = et.selectionStart
                val end = et.selectionEnd
                et.text?.replace(start, end, e, 0, e.length)
                et.setSelection(start + e.length)
            }
        }
    }

    private fun pickImage() { getContent.launch("image/*") }

    // ===== 多选删除消息 =====

    private fun enterSelection(msg: TopicMessage) { chatAdapter.enterSelection(msg); updateSelectionUI() }
    private fun updateSelectionUI() {
        binding.selectionBar.visibility = if (chatAdapter.selectionMode) View.VISIBLE else View.GONE
    }

    // 触觉反馈：长按进入多选时短震动一次
    private fun vibrate() {
        try {
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(30)
            }
        } catch (_: Exception) {}
    }
    private fun confirmDeleteSelected() {
        val ids = chatAdapter.getSelectedIds()
        val topic = currentTopic ?: return
        if (ids.isEmpty()) return
        AlertDialog.Builder(requireContext()).setTitle("删除消息").setMessage("确定删除选中的 ${ids.size} 条消息吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try { ids.forEach { ApiClient.deleteTopicMessage(topic, it) }; Toast.makeText(requireContext(), "已删除 ${ids.size} 条", Toast.LENGTH_SHORT).show(); chatAdapter.clearSelection(); loadMessages() }
                    catch (e: Exception) { Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.setNegativeButton("取消", null).show()
    }

    // ===== 新建话题 =====

    private fun showCreateTopicDialog() {
        val layout = requireActivity().layoutInflater.inflate(R.layout.dialog_create_topic, null)
        val etName = layout.findViewById<TextInputEditText>(R.id.etTopicName)
        val etTitle = layout.findViewById<TextInputEditText>(R.id.etTopicTitle)
        AlertDialog.Builder(requireContext()).setTitle("新建话题（群聊）")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text?.toString()?.trim()?.lowercase() ?: ""
                val title = etTitle.text?.toString()?.trim() ?: ""
                if (!Pattern.matches("^[a-z0-9_-]{1,64}$", name)) { Toast.makeText(requireContext(), "话题名不合法（1-64位字母/数字/_/-）", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                lifecycleScope.launch {
                    try { ApiClient.createTopic(name, title, ""); Toast.makeText(requireContext(), "话题已创建", Toast.LENGTH_SHORT).show(); val t = ApiClient.getMyTopics().find { it.name == name }; if (t != null) showChatMode(t) else showListMode() }
                    catch (e: Exception) { Toast.makeText(requireContext(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.setNegativeButton("取消", null).show()
    }

    // ===== 发现 / 加入 / 创建 =====

    private fun showDiscoverDialog() {
        val layout = requireActivity().layoutInflater.inflate(R.layout.dialog_discover_topic, null)
        val etName = layout.findViewById<TextInputEditText>(R.id.etJoinName)
        val listView = layout.findViewById<android.widget.ListView>(R.id.lvDiscover)
        val empty = layout.findViewById<android.widget.TextView>(R.id.tvDiscoverEmpty)
        val btnJoin = layout.findViewById<android.widget.Button>(R.id.btnJoinByName)
        btnJoin.text = "创建/加入"

        val dialog = AlertDialog.Builder(requireContext()).setTitle("发现 / 创建话题").setView(layout).setNegativeButton("关闭", null).create()

        val items = mutableListOf<DiscoverTopic>()
        val adapterList = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapterList
        listView.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { requestJoin(it.name, dialog) } }

        fun refresh() {
            lifecycleScope.launch {
                try {
                    val list = ApiClient.getDiscoverTopics()
                    items.clear(); items.addAll(list)
                    adapterList.clear(); adapterList.addAll(list.map { "#${it.name}  (创建者 ${it.ownerName ?: "-"} · ${it.memberCount}人)" }); adapterList.notifyDataSetChanged()
                    empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                } catch (e: Exception) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
        refresh()

        btnJoin.setOnClickListener {
            val n = etName.text?.toString()?.trim()?.lowercase() ?: ""
            if (!Pattern.matches("^[a-z0-9_-]{1,64}$", n)) { Toast.makeText(requireContext(), "话题名不合法", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            // 先尝试创建：已存在则返回 409 -> 转为申请加入
            lifecycleScope.launch {
                try {
                    ApiClient.createTopic(n, "", "")
                    Toast.makeText(requireContext(), "话题已创建（你是创建者）", Toast.LENGTH_SHORT).show()
                    dialog.dismiss(); val t = ApiClient.getMyTopics().find { it.name == n }; if (t != null) showChatMode(t) else showListMode()
                } catch (e: Exception) {
                    if (e is com.notifysync.data.ApiException && e.code == 409) {
                        requestJoin(n, dialog)
                    } else {
                        Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun requestJoin(name: String, dialog: AlertDialog) {
        lifecycleScope.launch {
            try { ApiClient.requestJoinTopic(name, ""); Toast.makeText(requireContext(), "已发送加入申请，等待创建者审批", Toast.LENGTH_SHORT).show(); dialog.dismiss() }
            catch (e: Exception) { Toast.makeText(requireContext(), "申请失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    // ===== 待审批（创建者） =====

    private fun showPendingDialog() {
        val topic = currentTopic ?: return
        lifecycleScope.launch {
            try {
                val list = ApiClient.getTopicRequests(topic).filter { it.status == "pending" }
                if (list.isEmpty()) { Toast.makeText(requireContext(), "暂无待审批申请", Toast.LENGTH_SHORT).show(); return@launch }
                val names = list.map { "${it.username} 申请加入" }.toTypedArray()
                val checked = BooleanArray(list.size) { true }
                AlertDialog.Builder(requireContext()).setTitle("待审批申请")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("通过选中") { _, _ -> handleRequests(topic, list, checked, true) }
                    .setNeutralButton("拒绝选中") { _, _ -> handleRequests(topic, list, checked, false) }
                    .setNegativeButton("取消", null).show()
            } catch (e: Exception) { Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun handleRequests(topic: String, list: List<TopicJoinRequest>, checked: BooleanArray, approve: Boolean) {
        lifecycleScope.launch {
            try {
                list.forEachIndexed { i, req -> if (checked[i]) { if (approve) ApiClient.approveTopicRequest(topic, req.id) else ApiClient.rejectTopicRequest(topic, req.id) } }
                Toast.makeText(requireContext(), if (approve) "已通过" else "已拒绝", Toast.LENGTH_SHORT).show()
                binding.btnPending.visibility = View.GONE
                loadMessages()
            } catch (e: Exception) { Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    // ===== 附件（图片/文件） =====

    private fun pickFile() {
        getContent.launch("*/*")
    }

    private fun handlePickedFile(uri: Uri) {
        val topic = currentTopic ?: return
        val mime = try { requireContext().contentResolver.getType(uri) } catch (_: Exception) { null }
        val kind = if (mime?.startsWith("image/") == true) "image" else "file"
        val file = copyUriToFile(uri) ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val json = ApiClient.uploadTopicMedia(topic, file, kind)
                val url = json.getString("url")
                val name = json.optString("name", file.name)
                val size = json.optLong("size", file.length())
                publish(topic, "", "", kind, url, name, size)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val ext = when (requireContext().contentResolver.getType(uri)) {
                "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/gif" -> ".gif"; "audio/mpeg" -> ".mp3"; "audio/amr" -> ".amr"; "application/pdf" -> ".pdf"; else -> ".bin"
            }
            val f = File(requireContext().cacheDir, "attach_${System.currentTimeMillis()}$ext")
            requireContext().contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
            f
        } catch (e: Exception) { null }
    }

    // ===== 语音 =====

    private fun ensureRecordPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        try {
            releaseRecorder()
            voiceFile = File(requireContext().cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(voiceFile!!.absolutePath)
                prepare(); start()
            }
            Toast.makeText(requireContext(), "正在录音…松开发送", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseRecorder()
        }
    }

    private fun stopRecordingAndSend() {
        val rec = mediaRecorder ?: return
        val file = voiceFile ?: return
        try { rec.stop() } catch (_: Exception) {}
        releaseRecorder()
        val topic = currentTopic ?: return
        if (file.length() < 500) { file.delete(); Toast.makeText(requireContext(), "录音太短", Toast.LENGTH_SHORT).show(); return }
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val json = ApiClient.uploadTopicMedia(topic, file, "voice")
                val url = json.getString("url")
                val size = json.optLong("size", file.length())
                publish(topic, "", "", "voice", url, "语音消息", size)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "语音发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    // ===== 图片全屏查看 =====

    private fun showImageFullscreen(url: String) {
        val dialog = AlertDialog.Builder(requireContext()).create()
        val iv = android.widget.ImageView(requireContext())
        iv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(0xFF000000.toInt())
        iv.setOnClickListener { dialog.dismiss() }
        dialog.setView(iv)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        lifecycleScope.launch {
            try {
                val bmp = downloadBitmap(url)
                if (bmp != null) iv.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }
    }

    private suspend fun downloadBitmap(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                doInput = true
            }
            conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
