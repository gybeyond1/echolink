package com.echolink.ui

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
import android.os.Environment
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
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.graphics.drawable.BitmapDrawable
import com.echolink.data.BingWallpaper
import com.google.android.material.textfield.TextInputEditText
import com.echolink.R
import com.echolink.data.ApiClient
import com.echolink.data.ApiException
import com.echolink.data.AuthManager
import com.echolink.data.DiscoverTopic
import com.echolink.data.FriendRequest
import com.echolink.data.MyTopic
import com.echolink.data.P2pManager
import com.echolink.data.TopicJoinRequest
import com.echolink.data.TopicMessage
import com.echolink.data.UnifiedRequests
import com.echolink.data.UnifiedTopicRequest
import com.echolink.data.WebSocketClient
import com.echolink.data.parseTopicMessage
import com.echolink.databinding.FragmentTopicBinding
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
    /** 聊天界面未读消息计数：用户向上翻看历史时，新消息不自动滚动，改用气泡提示 */
    private var unreadChatCount = 0
    // 仅聊天模式：由好友页在平板右侧以子 Fragment 方式承载，只显示聊天、不含左侧列表
    private var chatOnly = false
    private var unifiedRequests = UnifiedRequests(emptyList(), emptyList())

    private var mediaRecorder: MediaRecorder? = null
    private var voiceFile: File? = null

    private var voiceMode = false          // true=按住说话  false=键盘输入
    private var panelMode: String? = null  // null | "emoji" | "more"

    // ===== 微信风录音弹窗 =====
    private var recordDialog: android.app.Dialog? = null
    private var recordStartTime = 0L
    private var recordHandler: Handler? = null
    private var recordStartY = 0f
    private var isCancelSwipe = false
    private val waveViews = mutableListOf<View>()

    private val topicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // 新的申请到达（好友申请/加群申请）：刷新置顶入口红点
                "com.echolink.REQUESTS_CHANGED" -> {
                    if (binding.listLayout.visibility == View.VISIBLE) loadRequests()
                }
                // 好友关系变化：话题列表可能新增私聊会话
                "com.echolink.FRIENDS_CHANGED" -> {
                    if (binding.listLayout.visibility == View.VISIBLE) loadTopicList()
                }
                // 头像/昵称变更（含其他设备换头像的 WS 推送）：重绑可见头像
                "com.echolink.PROFILE_CHANGED" -> {
                    if (binding.chatLayout.visibility == View.VISIBLE) chatAdapter.notifyDataSetChanged()
                    if (binding.listLayout.visibility == View.VISIBLE) listAdapter.notifyDataSetChanged()
                }
                "com.echolink.MESSAGE_READ" -> {
                    // 对方读了你发出的私聊消息 → 把对应气泡翻成双勾
                    val t = intent?.getStringExtra("topic") ?: return
                    if (t == currentTopic) {
                        val ids = intent.getLongArrayExtra("ids")?.toSet() ?: emptySet()
                        chatAdapter.markRead(ids)
                    }
                }
                "com.echolink.MESSAGE_DELETED" -> {
                    // 本账号在另一台设备软删除了某条消息 → 本地同步移除
                    val t = intent?.getStringExtra("topic") ?: return
                    if (t == currentTopic) {
                        val mid = intent.getLongExtra("message_id", -1)
                        if (mid > 0) chatAdapter.removeMessage(mid)
                    }
                }
                else -> {
                    val topic = intent?.getStringExtra("topic") ?: return
                    if (topic == currentTopic) {
                        val msg = TopicMessage(
                            id = intent.getLongExtra("id", 0),
                            topic = topic,
                            title = intent.getStringExtra("title") ?: "",
                            text = intent.getStringExtra("text") ?: "",
                            senderName = intent.getStringExtra("sender_name") ?: "",
                            timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis()),
                            deviceId = intent.getLongExtra("device_id", -1),
                            deviceName = intent.getStringExtra("device_name"),
                            mediaType = intent.getStringExtra("media_type") ?: "text",
                            mediaUrl = intent.getStringExtra("media_url"),
                            mediaName = intent.getStringExtra("media_name"),
                            mediaSize = intent.getLongExtra("media_size", 0),
                            senderUserId = intent.getLongExtra("sender_user_id", 0),
                            senderAvatar = intent.getStringExtra("sender_avatar"),
                            senderDisplayName = intent.getStringExtra("sender_display_name")
                        )
                        // 用户是否已经在底部：在追加前判断，避免新插入项导致判断失真
                        val wasAtBottom = isAtBottom()
                        chatAdapter.appendItems(listOf(msg))
                        if (wasAtBottom) {
                            scrollToBottom()
                        } else {
                            // 用户已向上翻看历史 → 不强制滚动，弹未读气泡累计
                            unreadChatCount++
                            showUnreadPill()
                        }
                        // dm 私聊：聊天页正打开对方发来的消息 → 标记已读并通知对方（实时双勾）
                        if (chatTopic?.kind == "dm" && msg.id > 0) {
                            lifecycleScope.launch {
                                try { ApiClient.markMessagesRead(topic, listOf(msg.id)) } catch (_: Exception) {}
                            }
                        }
                    } else {
                        // 新消息来自其他会话 → 刷新列表未读数气泡
                        refreshTopicListBadges()
                    }
                }
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

    // 多选文件/图片（替代原单选 GetContent）
    private val getMultipleContent = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) handlePickedFiles(uris)
    }

    // 拍照
    private var cameraPhotoFile: File? = null
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val file = cameraPhotoFile
            val topic = currentTopic
            if (file != null && topic != null && file.exists()) {
                sendMediaSmart(topic, file, "image", "photo_${System.currentTimeMillis()}.jpg")
            }
        }
        cameraPhotoFile = null
    }

    // 相机权限请求
    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    private val requestRecordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Toast.makeText(requireContext(), "需要录音权限才能发送语音", Toast.LENGTH_SHORT).show()
    }

    // 保存图片到相册：仅 API ≤28 需要 WRITE_EXTERNAL_STORAGE 运行时权限（29+ 走 MediaStore 免权限）
    private val requestStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bmp = pendingSaveBitmap
        pendingSaveBitmap = null
        if (granted && bmp != null) saveImageToGallery(requireContext(), bmp)
        else if (!granted) Toast.makeText(requireContext(), "未授予存储权限，无法保存图片", Toast.LENGTH_SHORT).show()
    }

    private var pendingSaveBitmap: android.graphics.Bitmap? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTopicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 仅聊天模式（平板好友页右侧）：不加载左侧列表，直接进入聊天
        chatOnly = arguments?.getBoolean(EXTRA_CHAT_ONLY) ?: false

        listAdapter = TopicListAdapter(
            onItemClick = { showChatMode(it) },
            onItemLongClick = { showTopicMenu(it) }
        )
        binding.rvTopics.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopics.adapter = listAdapter

        chatAdapter = TopicAdapter(
            onItemLongClick = { msg ->
                // 已处于多选：长按 = 选中/取消该条；否则弹出消息操作菜单（复制 / 删除 / 多选）
                if (chatAdapter.selectionMode) { chatAdapter.toggle(msg); updateSelectionUI() }
                else { showMessageActionDialog(msg) }
            },
            onItemClick = { msg ->
                if (chatAdapter.selectionMode) { chatAdapter.toggle(msg); updateSelectionUI() }
            },
            onImageClick = { url -> showImageFullscreen(url) },
            onAvatarClick = { msg -> handleAvatarClick(msg) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = chatAdapter

        // 滚动到聊天底部时自动隐藏未读气泡并清零
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isAtBottom()) resetUnreadPill()
            }
        })

        // 未读气泡：点击跳到最新消息并清零
        binding.unreadPill.setOnClickListener {
            resetUnreadPill()
            scrollToBottom()
        }

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
                    chatOnly -> (parentFragment as? ChatPaneHost)?.onChatPaneClosed()
                        ?: (activity as? MainActivity)?.backToTopics()
                    binding.chatLayout.visibility == View.VISIBLE -> showListMode()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // 聊天态：待审批
        binding.btnPending.setOnClickListener { showPendingDialog() }

        // 设置入口已移入全局「+」悬浮菜单（MainActivity），顶栏齿轮不再使用

        // 列表态：「新的申请」入口（好友申请 + 加群申请）
        binding.rowNewRequests.setOnClickListener { showRequestsDialog() }

        // 置顶「通知」条目 → 通知详情页
        binding.rowNotifications.setOnClickListener { (activity as? MainActivity)?.openNotifications() }

        // 多选操作栏（右上角：全选 / 删除；退出多选用系统返回手势）
        binding.btnSelectAll.setOnClickListener { if (chatAdapter.isAllSelected) chatAdapter.clearSelection() else chatAdapter.selectAll(); updateSelectionUI() }
        binding.btnDeleteSel.setOnClickListener { confirmDeleteSelected() }

        // 下拉刷新（保留）：聊天消息
        binding.swipeRefresh.setOnRefreshListener { loadMessages() }
        // 列表页下拉刷新：重新拉取会话列表
        binding.swipeListRefresh.setOnRefreshListener { loadTopicList() }

        // ===== 微信式输入栏 =====
        binding.btnSend.setOnClickListener { sendText() }
        binding.btnVoiceToggle.setOnClickListener { toggleVoiceMode() }
        binding.btnEmoji.setOnClickListener { togglePanel() }
        binding.btnPlus.setOnClickListener { hidePanels(); showAttachMenu() }
        binding.fabAddTopic.setOnClickListener { showTopicFabMenu() }
        binding.btnHoldTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordStartY = event.rawY
                    isCancelSwipe = false
                    ensureRecordPermission()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - recordStartY
                    // 上滑超过 100dp → 取消
                    if (dy < -dp(100)) {
                        if (!isCancelSwipe) {
                            isCancelSwipe = true
                            updateRecordStatus("松开取消")
                        }
                    } else {
                        if (isCancelSwipe) {
                            isCancelSwipe = false
                            updateRecordStatus("松开发送")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isCancelSwipe) cancelRecording() else stopRecordingAndSend()
                    true
                }
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
        setupEmojiGrid()

        // 全面屏沉浸式：顶部栏避开状态栏
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(binding.topBar.paddingLeft, systemBars.top, binding.topBar.paddingRight, binding.topBar.paddingBottom)
            insets
        }

        showListMode()
        listRefreshHandler.postDelayed(listRefreshRunnable, 15000)

        // 平板双栏：左侧列表固定宽度（≈360dp），右侧聊天占剩余空间
        // 仅聊天模式（好友页右侧）：左列表隐藏，聊天占满右栏
        val dual = isWide && !chatOnly
        if (dual) {
            val lp = binding.listLayout.layoutParams as LinearLayout.LayoutParams
            lp.width = (360 * resources.displayMetrics.density).toInt()
            lp.weight = 0f
            binding.listLayout.layoutParams = lp
            binding.tvTitleList.text = "消息"
        }

        // 外部入口定位到指定会话（状态栏通知 / 好友私聊）：直接进入聊天态
        val argTopic = arguments?.getString("topic")
        if (!argTopic.isNullOrEmpty()) {
            val argTitle = arguments?.getString("title")
            lifecycleScope.launch {
                try {
                    val t = myTopics.find { it.name == argTopic }
                        ?: ApiClient.getMyTopics().find { it.name == argTopic }
                    // 优先用外部传入的展示名（好友页私聊回传的是对方昵称），覆盖服务端可能返回的纯用户名
                    val disp = argTitle ?: t?.displayName
                    if (t != null) showChatMode(t.copy(displayName = disp))
                    else if (disp != null) {
                        // 服务器暂无该会话（极少）：用展示名兜底构造
                        showChatMode(
                            MyTopic(argTopic, "member", 0, 0, null, null,
                                kind = if (argTopic.startsWith("dm-")) "dm" else "normal",
                                displayName = disp)
                        )
                    }
                } catch (_: Exception) {}
            }
        } else if (chatOnly) {
            // 仅聊天模式但未带话题参数：回退到列表（理论上不会发生）
            showListMode()
        }
    }

    override fun onResume() {
        super.onResume()
        // 聊天态隐藏 FAB，列表态显示
        val inChat = chatTopic != null || binding.chatLayout.visibility == View.VISIBLE
        binding.fabAddTopic.visibility = if (inChat) View.GONE else View.VISIBLE
        requireActivity().registerReceiver(
            topicReceiver,
            IntentFilter("com.echolink.TOPIC_MESSAGE_RECEIVED").apply {
                addAction("com.echolink.REQUESTS_CHANGED")
                addAction("com.echolink.FRIENDS_CHANGED")
                addAction("com.echolink.PROFILE_CHANGED")
                addAction("com.echolink.MESSAGE_READ")
            },
            Context.RECEIVER_NOT_EXPORTED
        )
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

    /** 平板判定：当前窗口宽度 ≥600dp（Multi-window 拖窗口时动态变化） */
    private val isWide: Boolean
        get() = resources.configuration.screenWidthDp >= 600

    /** 平板双栏：左列表标题栏宽度跟随列表列（360dp, weight=0），聊天标题才能在聊天面板内居中 */
    private fun setDualTitleListWidth(width: Int, weight: Float) {
        val lp = binding.tvTitleList.layoutParams as LinearLayout.LayoutParams
        lp.width = width
        lp.weight = weight
        binding.tvTitleList.layoutParams = lp
    }

    private fun showListMode() {
        currentTopic = null
        chatTopic = null
        binding.listLayout.visibility = View.VISIBLE
        binding.chatLayout.visibility = View.GONE
        binding.tvTitleList.text = "消息"
        binding.tvTitleList.visibility = View.VISIBLE
        // 列表态：标题占满整行（weight=1）
        setDualTitleListWidth(0, 1f)
        backCallback.isEnabled = false
        binding.tvChatTitle.visibility = View.GONE
        binding.btnPending.visibility = View.GONE
        // 列表态显示 FAB
        binding.fabAddTopic.visibility = View.VISIBLE
        loadTopicList()
    }

    private fun showChatMode(topic: MyTopic) {
        chatTopic = topic
        currentTopic = topic.name
        // 进入聊天详情：隐藏 FAB
        binding.fabAddTopic.visibility = View.GONE
        // 会话「对方」头像（私聊 dm 为好友头像），供历史消息 sender_avatar 缺失时回退，
        // 避免「没头像」；也用于自聊场景让两侧都用当前实时头像
        chatAdapter.peerAvatarUrl = topic.avatarUrl
        // DM 标记：私聊里「非自己」消息头像一律回退到对方实时头像，根治首条没头像
        chatAdapter.isDm = topic.kind == "dm"
        // 留言板标记：访客消息统一显示 📮 头像（与 WebUI 一致）
        chatAdapter.isMessageWall = topic.kind == "messagewall"
        // 已读回执：仅 dm 私聊开启（通知/我的设备/群组不显示单双勾）
        chatAdapter.showReadReceipts = topic.kind == "dm"
        // 聊天标题显示昵称（优先外部传入的展示名），特殊会话前端兜底中文名，并强制水平居中
        binding.tvChatTitle.text = when (topic.kind) {
            "devices" -> "我的设备"
            "messagewall" -> "留言板"
            else -> topic.displayName ?: topic.name
        }
        binding.tvChatTitle.gravity = Gravity.CENTER
        binding.btnPending.visibility = if (topic.myRole == "owner" && topic.pendingRequests > 0) View.VISIBLE else View.GONE
        // 留言板是单向接收（只看不发），隐藏底部输入栏和表情/附件面板
        val isMw = topic.kind == "messagewall"
        binding.inputBar.visibility = if (isMw) View.GONE else View.VISIBLE
        binding.panelContainer.visibility = View.GONE
        binding.tvEmptyChat.text = if (isMw) "暂无留言" else "暂无消息\n发送一条消息开始聊天"
        // 平板且非仅聊天模式 → 左列表 + 右聊天并排；其余（手机 / 仅聊天模式）→ 聊天占满
        val dual = isWide && !chatOnly
        if (dual) {
            // 平板双栏（平行视界）：左侧列表保持显示，聊天在右侧打开
            binding.listLayout.visibility = View.VISIBLE
            binding.chatLayout.visibility = View.VISIBLE
            binding.tvTitleList.visibility = View.VISIBLE
            binding.tvChatTitle.visibility = View.VISIBLE
            // #198 修复：左列表固定 360dp、weight=0，使聊天标题在「聊天面板」内而非整屏右半居中
            setDualTitleListWidth((360 * resources.displayMetrics.density).toInt(), 0f)
            binding.btnSettings.visibility = View.GONE
        } else {
            // 手机：列表/聊天全屏切换
            binding.listLayout.visibility = View.GONE
            binding.chatLayout.visibility = View.VISIBLE
            binding.tvTitleList.visibility = View.GONE
            binding.tvChatTitle.visibility = View.VISIBLE
            binding.btnSettings.visibility = View.GONE
        }
        backCallback.isEnabled = true
        WebSocketClient.sendSubscribe(topic.name)
        loadMessages()
        // 进入会话后标记已读，未读气泡清零
        markCurrentTopicRead(topic.name)
    }

    private fun markCurrentTopicRead(topicName: String) {
        lifecycleScope.launch {
            try {
                ApiClient.markTopicRead(topicName)
                // 本地把该话题未读数清零并刷新列表
                val idx = myTopics.indexOfFirst { it.name == topicName }
                if (idx >= 0 && myTopics[idx].unreadCount > 0) {
                    myTopics[idx] = myTopics[idx].copy(unreadCount = 0)
                    listAdapter.setItems(myTopics)
                }
            } catch (_: Exception) {}
        }
    }

    // ===== 悬浮加号菜单：新建 / 发现话题（与好友页统一，见 Dialogs.kt） =====

    private fun showTopicFabMenu() {
        showGlobalFabMenu(
            owner = this,
            onDiscover = { showDiscoverDialog(this) { t -> (activity as? MainActivity)?.openTopic(t) } },
            onCreateTopic = { showCreateTopicDialog(this) { t -> (activity as? MainActivity)?.openTopic(t) } },
            onAddFriend = { showAddFriendDialog(this) },
            onSettings = { (activity as? MainActivity)?.openSettings() }
        )
    }

    // ===== 话题列表 =====

    private fun loadTopicList() {
        // 先展示本地内存缓存，避免切换 tab 时空列表闪烁/消失
        ApiClient.cachedTopics?.let { cached ->
            if (_binding != null) {
                myTopics.clear(); myTopics.addAll(cached)
                listAdapter.setItems(myTopics)
                binding.tvEmptyTopics.visibility = if (myTopics.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            try {
                val list = ApiClient.getMyTopics()
                if (_binding == null) return@launch
                myTopics.clear()
                myTopics.addAll(list)
                AuthManager.subscribedTopics = myTopics.map { it.name }.toSet()
                listAdapter.setItems(myTopics)
                binding.tvEmptyTopics.visibility = if (myTopics.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                if (ApiClient.cachedTopics == null) {
                    Toast.makeText(requireContext(), "话题列表加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            if (_binding != null) binding.swipeListRefresh.isRefreshing = false
            loadRequests()
        }
    }

    // ===== 新的申请（好友申请 + 加群申请） =====

    // 拉取统一申请汇总并更新置顶入口
    private fun loadRequests() {
        lifecycleScope.launch {
            try {
                unifiedRequests = ApiClient.getAllRequests()
                val total = unifiedRequests.friendRequests.size + unifiedRequests.topicRequests.size
                if (total > 0) {
                    binding.rowNewRequests.visibility = View.VISIBLE
                    binding.tvReqBadge.text = if (total > 99) "99+" else total.toString()
                    binding.tvReqPreview.text =
                        unifiedRequests.friendRequests.firstOrNull()?.let { "${it.username} 请求加你为好友" }
                        ?: unifiedRequests.topicRequests.firstOrNull()?.let { "${it.username} 申请加入「${it.topic}」" }
                        ?: ""
                } else {
                    binding.rowNewRequests.visibility = View.GONE
                }
            } catch (_: Exception) {}
        }
    }

    // 申请列表：好友申请在前，加群申请在后；点开单项可同意/拒绝/忽略
    private fun showRequestsDialog() {
        lifecycleScope.launch {
            try {
                unifiedRequests = ApiClient.getAllRequests()
            } catch (_: Exception) {}
            val fr = unifiedRequests.friendRequests
            val tr = unifiedRequests.topicRequests
            if (fr.isEmpty() && tr.isEmpty()) {
                binding.rowNewRequests.visibility = View.GONE
                Toast.makeText(requireContext(), "暂无新的申请", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = fr.map { "【好友】${it.username}${if (!it.message.isNullOrEmpty()) "：${it.message}" else ""}" } +
                tr.map { "【加群】${it.username} → ${it.topic}${if (!it.message.isNullOrEmpty()) "：${it.message}" else ""}" }
            AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
                .setTitle("新的申请")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < fr.size) showHandleFriendRequest(fr[which])
                    else showHandleTopicRequest(tr[which - fr.size])
                }
                .setNegativeButton("关闭", null)
                .showDimmed()
        }
    }

    private fun showHandleFriendRequest(req: FriendRequest) {
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle("${req.username} 请求加你为好友")
            .setMessage(if (req.message.isNullOrEmpty()) "验证消息：（无）" else "验证消息：${req.message}")
            .setPositiveButton("同意") { _, _ -> handleFriendRequest(req, "accept") }
            .setNegativeButton("拒绝") { _, _ -> handleFriendRequest(req, "reject") }
            .setNeutralButton("忽略") { _, _ -> handleFriendRequest(req, "ignore") }
            .showDimmed()
    }

    private fun handleFriendRequest(req: FriendRequest, action: String) {
        lifecycleScope.launch {
            try {
                when (action) {
                    "accept" -> { ApiClient.acceptFriendRequest(req.id); Toast.makeText(requireContext(), "已同意", Toast.LENGTH_SHORT).show() }
                    "reject" -> { ApiClient.rejectFriendRequest(req.id); Toast.makeText(requireContext(), "已拒绝", Toast.LENGTH_SHORT).show() }
                    else -> { ApiClient.ignoreFriendRequest(req.id); Toast.makeText(requireContext(), "已忽略", Toast.LENGTH_SHORT).show() }
                }
                loadRequests()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showHandleTopicRequest(req: UnifiedTopicRequest) {
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle("${req.username} 申请加入「${req.topic}」")
            .setMessage(if (req.message.isNullOrEmpty()) "验证消息：（无）" else "验证消息：${req.message}")
            .setPositiveButton("同意") { _, _ -> handleTopicRequest(req, true) }
            .setNegativeButton("拒绝") { _, _ -> handleTopicRequest(req, false) }
            .setNeutralButton("忽略") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.ignoreTopicRequest(req.topic, req.id)
                        Toast.makeText(requireContext(), "已忽略", Toast.LENGTH_SHORT).show()
                        loadRequests(); loadTopicList()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .showDimmed()
    }

    private fun handleTopicRequest(req: UnifiedTopicRequest, approve: Boolean) {
        lifecycleScope.launch {
            try {
                if (approve) {
                    ApiClient.approveTopicRequest(req.topic, req.id)
                    Toast.makeText(requireContext(), "已同意加入", Toast.LENGTH_SHORT).show()
                } else {
                    ApiClient.rejectTopicRequest(req.topic, req.id)
                    Toast.makeText(requireContext(), "已拒绝", Toast.LENGTH_SHORT).show()
                }
                loadRequests(); loadTopicList()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            loadRequests()
        }
    }

    // 长按菜单：打开 / 删除（从列表移除）/ 关闭（彻底关闭，仅创建者）
    private fun showTopicMenu(topic: MyTopic) {
        if (topic.kind == "devices" || topic.kind == "messagewall") {
            Toast.makeText(requireContext(), "「我的设备」是默认会话，不可删除", Toast.LENGTH_SHORT).show()
            return
        }
        val owner = topic.kind == "normal" && topic.myRole == "owner"
        val options = mutableListOf("打开")
        if (owner) options.add("关闭话题（彻底关闭）") else options.add("删除（从我的列表移除）")
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle(topic.displayName ?: topic.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showChatMode(topic)
                    1 -> if (owner) confirmCloseTopic(topic) else confirmLeaveTopic(topic)
                }
            }.showDimmed()
    }

    private fun swipeDelete(topic: MyTopic) {
        if (topic.kind == "devices" || topic.kind == "messagewall") {
            Toast.makeText(requireContext(), "该会话不可删除", Toast.LENGTH_SHORT).show()
            loadTopicList() // 还原被滑走的列表项
            return
        }
        val owner = topic.kind == "normal" && topic.myRole == "owner"
        if (owner) confirmCloseTopic(topic) else confirmLeaveTopic(topic)
    }

    private fun confirmLeaveTopic(topic: MyTopic) {
        val display = topic.displayName ?: topic.name
        val message = if (topic.kind == "dm")
            "确定把私聊会话「$display」从你的列表移除吗？（好友关系保留，再次发消息时会自动恢复）"
        else
            "确定把话题「$display」从你的列表移除吗？（仍可在「发现/加入」重新申请）"
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle("从列表移除")
            .setMessage(message)
            .setPositiveButton("移除") { _, _ ->
                lifecycleScope.launch {
                    try { ApiClient.leaveTopic(topic.name); AuthManager.removeTopic(topic.name); Toast.makeText(requireContext(), "已移除", Toast.LENGTH_SHORT).show(); showListMode() }
                    catch (e: Exception) { Toast.makeText(requireContext(), "失败: ${e.message}", Toast.LENGTH_SHORT).show(); loadTopicList() }
                }
            }.setNegativeButton("取消") { _, _ -> loadTopicList() }.showDimmed()
    }

    private fun confirmCloseTopic(topic: MyTopic) {
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle("关闭话题")
            .setMessage("确定彻底关闭话题「${topic.name}」吗？所有成员将无法加入，全部消息会被清空，且不可恢复。")
            .setPositiveButton("彻底关闭") { _, _ ->
                lifecycleScope.launch {
                    try { ApiClient.deleteTopic(topic.name); AuthManager.removeTopic(topic.name); Toast.makeText(requireContext(), "话题已关闭", Toast.LENGTH_SHORT).show(); showListMode() }
                    catch (e: Exception) { Toast.makeText(requireContext(), "失败: ${e.message}", Toast.LENGTH_SHORT).show(); loadTopicList() }
                }
            }.setNegativeButton("取消") { _, _ -> loadTopicList() }.showDimmed()
    }

    // ===== 聊天 =====

    private fun loadMessages() {
        val topic = currentTopic ?: return
        resetUnreadPill()
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

    private fun publish(topic: String, title: String, text: String, mediaType: String, mediaUrl: String?, mediaName: String?, mediaSize: Long, duration: Int = 0) {
        // 先插入发送中的临时消息，让用户立即看到
        val tempId = -System.currentTimeMillis()
        val tempMsg = TopicMessage(
            id = tempId,
            topic = topic,
            title = title,
            text = text,
            senderName = AuthManager.username ?: "me",
            timestamp = System.currentTimeMillis(),
            deviceId = AuthManager.deviceId,
            deviceName = AuthManager.deviceName,
            mediaType = mediaType,
            mediaUrl = mediaUrl,
            mediaName = mediaName,
            mediaSize = mediaSize,
            duration = duration,
            senderUserId = AuthManager.userId,
            sending = true
        )
        chatAdapter.appendItems(listOf(tempMsg))
        binding.tvEmptyChat.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        scrollToBottom()

        lifecycleScope.launch {
            try {
                val json = ApiClient.publishTopicMessage(topic, title, text, mediaType, mediaUrl, mediaName, mediaSize, duration)
                val msg = parseTopicMessage(json.getJSONObject("topic_message"))
                // 用真实消息替换临时消息
                chatAdapter.replaceMessage(tempId, msg)
            } catch (e: Exception) {
                // 发送失败：移除临时消息
                chatAdapter.removeMessage(tempId)
                Toast.makeText(requireContext(), "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) binding.recyclerView.scrollToPosition(chatAdapter.itemCount - 1)
    }

    /** 是否在聊天底部（最后一条消息基本可见）：用于决定是否自动滚动 / 显示未读气泡 */
    private fun isAtBottom(): Boolean {
        val rv = binding.recyclerView
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val count = chatAdapter.itemCount
        if (count == 0) return true
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible < count - 1) return false
        val lastChild = rv.getChildAt(rv.childCount - 1)
        return lastChild != null && lastChild.bottom <= rv.height + 40
    }

    private fun showUnreadPill() {
        binding.tvUnreadCount.text = if (unreadChatCount > 99) "99+" else unreadChatCount.toString()
        binding.unreadPill.visibility = View.VISIBLE
    }

    private fun resetUnreadPill() {
        unreadChatCount = 0
        binding.unreadPill.visibility = View.GONE
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

    private fun togglePanel() {
        if (panelMode == "emoji") { hidePanels(); return }
        panelMode = "emoji"
        binding.panelContainer.visibility = View.VISIBLE
        binding.emojiGrid.visibility = View.VISIBLE
        hideKeyboard()
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

    // ===== 附件（拍照/相册/文件） =====

    /** 附件菜单：拍照 / 相册（多选）/ 文件（多选） */
    private fun showAttachMenu() {
        val items = arrayOf("📷  拍照", "🖼️  从相册选择", "📁  选择文件")
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ensureCameraPermission()
                    1 -> getMultipleContent.launch("image/*")
                    2 -> getMultipleContent.launch("*/*")
                }
            }
            .setNegativeButton("取消", null)
            .showDimmed()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val photoFile = File(requireContext().cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            cameraPhotoFile = photoFile
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photoFile
            )
            takePicture.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法启动相机: ${e.message}", Toast.LENGTH_SHORT).show()
            cameraPhotoFile = null
        }
    }

    /** 多文件处理：单个走 P2P 智能发送；多个顺序 HTTP 上传 */
    private fun handlePickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val topic = currentTopic ?: return

        if (uris.size == 1) {
            // 单文件：保留 P2P 智能路径
            handlePickedFile(uris[0])
            return
        }

        // 多文件：顺序上传
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            var success = 0
            for (uri in uris) {
                val mime = try { requireContext().contentResolver.getType(uri) } catch (_: Exception) { null }
                val kind = if (mime?.startsWith("image/") == true) "image" else "file"
                val file = withContext(Dispatchers.IO) { copyUriToFile(uri) } ?: continue
                val displayName = withContext(Dispatchers.IO) { queryDisplayName(uri) } ?: file.name
                try {
                    val json = ApiClient.uploadTopicMedia(topic, file, kind)
                    val url = json.getString("url")
                    val size = json.optLong("size", file.length())
                    val pubJson = ApiClient.publishTopicMessage(topic, "", "", kind, url, displayName, size)
                    val msg = parseTopicMessage(pubJson.getJSONObject("topic_message"))
                    withContext(Dispatchers.Main) {
                        chatAdapter.appendItems(listOf(msg))
                        binding.tvEmptyChat.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        scrollToBottom()
                    }
                    success++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "发送「$displayName」失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                try { file.delete() } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (success > 0) {
                    Toast.makeText(requireContext(), "已发送 $success 个文件", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== 头像点击（群聊中加好友/发起私聊） =====

    private fun handleAvatarClick(msg: TopicMessage) {
        val topic = chatTopic ?: return
        // 仅群聊（normal）支持头像交互；设备会话/私聊不处理
        if (topic.kind != "normal") return

        val senderUserId = msg.senderUserId
        val senderUsername = msg.senderName

        // 自己的头像 → 不处理
        if (senderUserId > 0 && senderUserId == AuthManager.userId) return
        if (senderUsername == AuthManager.username) return

        val displayName = msg.senderDisplayName?.takeIf { it.isNotBlank() } ?: senderUsername

        lifecycleScope.launch {
            try {
                // 搜索用户判断好友关系
                val users = ApiClient.searchUsers(senderUsername)
                val target = users.find { it.username == senderUsername }
                if (target == null) {
                    Toast.makeText(requireContext(), "未找到用户", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                when {
                    target.isFriend -> {
                        // 已是好友 → 发起私聊
                        val (dmTopic, title) = ApiClient.openFriendChat(target.username)
                        val displayTitle = target.displayName ?: title
                        (activity as? MainActivity)?.openTopic(dmTopic, displayTitle)
                    }
                    target.requested -> {
                        Toast.makeText(requireContext(), "已发送好友申请，等待对方处理", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // 不是好友 → 填写验证消息后发送申请
                        val input = requireActivity().layoutInflater.inflate(R.layout.dialog_input_single, null)
                        val etMsg = input.findViewById<TextInputEditText>(R.id.etInput)
                        etMsg.hint = "验证消息（选填）"
                        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
                            .setTitle("添加好友")
                            .setMessage("向 $displayName 发送好友申请")
                            .setView(input)
                            .setPositiveButton("发送申请") { _, _ ->
                                val message = etMsg.text?.toString()?.trim() ?: ""
                                lifecycleScope.launch {
                                    try {
                                        ApiClient.sendFriendRequest(target.username, message)
                                        Toast.makeText(requireContext(), "已发送好友申请", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(requireContext(), "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .showDimmed()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 多选删除消息 =====

    private fun enterSelection(msg: TopicMessage) { chatAdapter.enterSelection(msg); updateSelectionUI() }
    private fun updateSelectionUI() {
        binding.selectionBar.visibility = if (chatAdapter.selectionMode) View.VISIBLE else View.GONE
        binding.btnSelectAll.setImageResource(if (chatAdapter.isAllSelected) R.drawable.ic_select_all_filled else R.drawable.ic_select_all)
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
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog).setTitle("删除消息").setMessage("确定删除选中的 ${ids.size} 条消息吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ids.forEach { ApiClient.deleteTopicMessage(topic, it) }
                        Toast.makeText(requireContext(), "已删除 ${ids.size} 条", Toast.LENGTH_SHORT).show()
                        chatAdapter.clearSelection()
                        updateSelectionUI()   // 关键：删除后收起右上角全选/删除栏，彻底退出多选态
                        loadMessages()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        chatAdapter.clearSelection()
                        updateSelectionUI()
                    }
                }
            }.setNegativeButton("取消", null).showDimmed()
    }

    /** 长按单条消息：复制 / 删除 / 多选 */
    private fun showMessageActionDialog(msg: TopicMessage) {
        val topic = currentTopic ?: return
        val options = arrayOf("复制", "删除", "多选")
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle("消息操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> chatAdapter.copyMessage(requireContext(), msg)
                    1 -> confirmDeleteMessage(topic, msg)
                    2 -> { enterSelection(msg); vibrate() }
                }
            }
            .setNegativeButton("取消", null)
            .showDimmed()
    }

    private fun confirmDeleteMessage(topic: String, msg: TopicMessage) {
        AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog)
            .setTitle("删除消息")
            .setMessage("确定删除这条消息吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        ApiClient.deleteTopicMessage(topic, msg.id)
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        loadMessages()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .showDimmed()
    }

    // ===== 待审批（创建者） =====

    private fun showPendingDialog() {
        val topic = currentTopic ?: return
        lifecycleScope.launch {
            try {
                val list = ApiClient.getTopicRequests(topic).filter { it.status == "pending" }
                if (list.isEmpty()) { Toast.makeText(requireContext(), "暂无待审批申请", Toast.LENGTH_SHORT).show(); return@launch }
                val names = list.map { r -> if (r.message.isNullOrEmpty()) "${r.username} 申请加入" else "${r.username} 申请加入：${r.message}" }.toTypedArray()
                val checked = BooleanArray(list.size) { true }
                AlertDialog.Builder(requireContext(), R.style.Theme_EchoLink_Dialog).setTitle("待审批申请")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("通过选中") { _, _ -> handleRequests(topic, list, checked, true) }
                    .setNeutralButton("拒绝选中") { _, _ -> handleRequests(topic, list, checked, false) }
                    .setNegativeButton("取消", null).showDimmed()
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

    private fun handlePickedFile(uri: Uri) {
        val topic = currentTopic ?: return
        val mime = try { requireContext().contentResolver.getType(uri) } catch (_: Exception) { null }
        val kind = if (mime?.startsWith("image/") == true) "image" else "file"
        val file = copyUriToFile(uri) ?: return
        val displayName = queryDisplayName(uri) ?: file.name
        sendMediaSmart(topic, file, kind, displayName)
    }

    // 媒体发送统一入口：好友私聊（dm）优先 P2P 直连（不占服务器带宽），
    // 30 秒打洞不成功自动回退 HTTP 上传（服务器中转兜底）；其他会话直接走 HTTP
    private fun sendMediaSmart(topic: String, file: File, kind: String, name: String, duration: Int = 0) {
        if (chatTopic?.kind == "dm" && P2pManager.isReady) {
            P2pManager.sendFileWithFallback(
                requireContext(), topic, file, kind, name,
                onFallback = {
                    Toast.makeText(requireContext(), "P2P 直连未成功，改走服务器中转", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch { httpUploadAndPublish(topic, file, kind, name, duration) }
                },
                onSuccess = { p2pUrl ->
                    publish(topic, "", "", kind, p2pUrl, name, file.length(), duration)
                    try { file.delete() } catch (_: Exception) {}
                },
                onError = { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    try { file.delete() } catch (_: Exception) {}
                }
            )
        } else {
            lifecycleScope.launch { httpUploadAndPublish(topic, file, kind, name, duration) }
        }
    }

    // 服务器上传 + 发消息（原路径，P2P 的兜底）
    private suspend fun httpUploadAndPublish(topic: String, file: File, kind: String, name: String, duration: Int = 0) {
        try {
            val json = ApiClient.uploadTopicMedia(topic, file, kind)
            val url = json.getString("url")
            val size = json.optLong("size", file.length())
            publish(topic, "", "", kind, url, name, size, duration)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    // 取附件原始文件名（显示用）
    private fun queryDisplayName(uri: Uri): String? = try {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) { null }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            // 优先从原始文件名提取扩展名
            val displayName = queryDisplayName(uri)
            val ext = displayName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
                ?.takeIf { it.length in 1..5 && it.matches(Regex("[a-z0-9]+")) }
                ?: when (requireContext().contentResolver.getType(uri)) {
                    "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"
                    "image/webp" -> "webp"; "image/bmp" -> "bmp"
                    "audio/mpeg" -> "mp3"; "audio/amr" -> "amr"; "audio/mp4" -> "m4a"
                    "audio/ogg" -> "ogg"; "audio/aac" -> "aac"
                    "video/mp4" -> "mp4"; "video/3gpp" -> "3gp"
                    "application/pdf" -> "pdf"
                    "application/zip" -> "zip"; "application/x-rar-compressed" -> "rar"
                    "application/msword" -> "doc"
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    "application/vnd.ms-excel" -> "xls"
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                    "application/vnd.ms-powerpoint" -> "ppt"
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                    "text/plain" -> "txt"; "text/csv" -> "csv"
                    else -> "bin"
                }
            val f = File(requireContext().cacheDir, "attach_${System.currentTimeMillis()}.$ext")
            requireContext().contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
            f
        } catch (e: Exception) { null }
    }

    // ===== 语音（微信风格） =====

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
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(voiceFile!!.absolutePath)
                prepare(); start()
            }
            recordStartTime = System.currentTimeMillis()
            showRecordDialog()
            startRecordUpdates()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            releaseRecorder()
        }
    }

    private fun stopRecordingAndSend() {
        val rec = mediaRecorder ?: return
        val file = voiceFile ?: return
        val duration = ((System.currentTimeMillis() - recordStartTime) / 1000).toInt()
        try { rec.stop() } catch (_: Exception) {}
        releaseRecorder()
        stopRecordUpdates()
        dismissRecordDialog()
        val topic = currentTopic ?: return
        if (file.length() < 500 || duration < 1) {
            file.delete()
            Toast.makeText(requireContext(), "录音太短", Toast.LENGTH_SHORT).show()
            return
        }
        sendMediaSmart(topic, file, "voice", "语音消息", duration)
    }

    private fun cancelRecording() {
        val rec = mediaRecorder ?: return
        val file = voiceFile
        try { rec.stop() } catch (_: Exception) {}
        releaseRecorder()
        stopRecordUpdates()
        dismissRecordDialog()
        file?.delete()
        Toast.makeText(requireContext(), "已取消", Toast.LENGTH_SHORT).show()
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    // ===== 录音弹窗 =====

    private fun showRecordDialog() {
        val ctx = requireContext()
        val dialog = android.app.Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        val view = layoutInflater.inflate(R.layout.dialog_voice_record, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)
        // 收集声波 View（9 个柱）
        waveViews.clear()
        for (i in 0..8) {
            val id = ctx.resources.getIdentifier("wave$i", "id", ctx.packageName)
            view.findViewById<View>(id)?.let { waveViews.add(it) }
        }
        recordDialog = dialog
        dialog.show()
    }

    private fun dismissRecordDialog() {
        recordDialog?.dismiss()
        recordDialog = null
        waveViews.clear()
    }

    private fun updateRecordStatus(text: String) {
        recordDialog?.findViewById<TextView>(R.id.tvRecordStatus)?.text = text
    }

    private fun startRecordUpdates() {
        recordHandler = Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (mediaRecorder == null) return
                // 更新计时
                val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
                val mm = String.format("%02d", elapsed / 60)
                val ss = String.format("%02d", elapsed % 60)
                recordDialog?.findViewById<TextView>(R.id.tvRecordTime)?.text = "$mm:$ss"
                // 更新声波（基于 maxAmplitude）
                try {
                    val amp = mediaRecorder?.maxAmplitude ?: 0
                    val level = (amp / 32767f).coerceIn(0f, 1f)
                    updateWaveform(level)
                } catch (_: Exception) {}
                recordHandler?.postDelayed(this, 100)
            }
        }
        recordHandler?.post(runnable)
    }

    private fun stopRecordUpdates() {
        recordHandler?.removeCallbacksAndMessages(null)
        recordHandler = null
    }

    private fun updateWaveform(level: Float) {
        if (waveViews.isEmpty()) return
        val count = waveViews.size
        val center = count / 2
        for (i in waveViews.indices) {
            // 中间高两边低，乘以音量
            val dist = Math.abs(i - center).toFloat() / center
            val baseH = (1 - dist * 0.7f) * level * 40 + 4
            val lp = waveViews[i].layoutParams
            lp.height = baseH.toInt().coerceIn(4, 42)
            waveViews[i].layoutParams = lp
        }
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    // ===== 图片全屏查看 + 长按保存到相册 =====

    private fun showImageFullscreen(url: String) {
        val ctx = requireContext()
        val dialog = AlertDialog.Builder(ctx, R.style.Theme_EchoLink_Dialog).create()
        val root = android.widget.FrameLayout(ctx)
        root.setBackgroundColor(0xFF000000.toInt())

        val iv = android.widget.ImageView(ctx)
        iv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        iv.setOnClickListener { dialog.dismiss() }

        // 长按保存图片到相册
        iv.setOnLongClickListener {
            val bmp = pendingSaveBitmap
            if (bmp == null) {
                Toast.makeText(ctx, "图片尚未加载完成，请稍候", Toast.LENGTH_SHORT).show()
            } else {
                vibrate()
                trySaveImage(bmp)
            }
            true
        }

        val progress = android.widget.ProgressBar(ctx)
        progress.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER }
        root.addView(iv)
        root.addView(progress)

        dialog.setView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        lifecycleScope.launch {
            try {
                val bmp = if (url.startsWith("p2p:")) {
                    val local = P2pManager.localP2pFile(ctx, url)
                    if (local == null) {
                        Toast.makeText(ctx, "该图片经 P2P 直连传输，未落地本设备", Toast.LENGTH_SHORT).show()
                        null
                    } else withContext(Dispatchers.IO) { decodeSampledBitmap(local.absolutePath) }
                } else downloadSampledBitmap(url)
                progress.visibility = View.GONE
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                    pendingSaveBitmap = bmp
                } else if (dialog.isShowing) {
                    Toast.makeText(ctx, "图片加载失败", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                progress.visibility = View.GONE
                if (dialog.isShowing) Toast.makeText(ctx, "图片加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 长按保存入口：API 29+ 免权限直存 MediaStore；API ≤28 先查/申请写存储权限 */
    private fun trySaveImage(bmp: android.graphics.Bitmap) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageToGallery(ctx, bmp)
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) {
            saveImageToGallery(ctx, bmp)
        } else {
            pendingSaveBitmap = bmp
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** 保存到相册（IO 线程执行，主线程 Toast 结果） */
    private fun saveImageToGallery(ctx: Context, bmp: android.graphics.Bitmap) {
        val name = "echolink_${System.currentTimeMillis()}.jpg"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // API 29+：MediaStore + RELATIVE_PATH，无需权限
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EchoLink")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = ctx.contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) return@withContext false
                        resolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        resolver.update(uri, android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }, null, null)
                        true
                    } else {
                        // API ≤28：写入公共 Pictures/EchoLink 并广播扫描，让相册立即可见
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val file = java.io.File(dir, "EchoLink/$name")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                        true
                    }
                } catch (_: Exception) { false }
            }
            Toast.makeText(ctx, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 大图采样解码：先读尺寸，按目标上限(2048px)算 inSampleSize，防全屏大图 OOM */
    private fun decodeSampledBitmap(path: String, maxSize: Int = 2048): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxSize || opts.outHeight / sample > maxSize) sample *= 2
        val real = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeFile(path, real)
    }

    private suspend fun downloadSampledBitmap(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                doInput = true
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext null
            var sample = 1
            while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
            val real = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
        } catch (_: Exception) { null }
    }

    /** 仅聊天模式宿主回调：好友页右侧聊天容器关闭时通知宿主收起右栏 */
    interface ChatPaneHost {
        fun onChatPaneClosed()
    }

    companion object {
        const val EXTRA_CHAT_ONLY = "chat_only"
        /** 构造一个「仅聊天」的 TopicFragment，用于平板好友页右侧承载私聊 */
        fun chatOnly(topic: String, title: String?): TopicFragment {
            val f = TopicFragment()
            f.arguments = Bundle().apply {
                putBoolean(EXTRA_CHAT_ONLY, true)
                putString("topic", topic)
                putString("title", title)
            }
            return f
        }
    }
}
