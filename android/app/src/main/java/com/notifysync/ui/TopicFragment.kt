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
import com.notifysync.data.TopicMessage
import com.notifysync.data.WebSocketClient
import com.notifysync.data.parseTopicMessage
import com.notifysync.databinding.FragmentTopicBinding
import kotlinx.coroutines.launch

class TopicFragment : Fragment() {
    private var _binding: FragmentTopicBinding? = null
    private val binding get() = _binding!!

    private val adapter = TopicAdapter()
    private val topics = mutableListOf<String>()
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

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnAddTopic.setOnClickListener { showAddTopicDialog() }
        binding.btnRefresh.setOnClickListener { loadMessages() }
        binding.btnSend.setOnClickListener { sendMessage() }

        binding.spTopic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < topics.size) switchTopic(topics[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        setupTopics()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(
            topicReceiver,
            IntentFilter("com.notifysync.TOPIC_MESSAGE_RECEIVED"),
            Context.RECEIVER_NOT_EXPORTED
        )
        // 恢复连接后重新订阅当前话题
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

    private fun setupTopics() {
        topics.clear()
        topics.addAll(AuthManager.subscribedTopics.sorted())

        spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, topics)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spTopic.adapter = spinnerAdapter

        if (topics.isEmpty()) {
            currentTopic = null
            adapter.setItems(emptyList())
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.spTopic.setSelection(0)
        }
    }

    private fun switchTopic(topic: String) {
        if (currentTopic == topic) return
        // 退订旧话题，订阅新话题
        currentTopic?.let { WebSocketClient.sendUnsubscribe(it) }
        currentTopic = topic
        WebSocketClient.sendSubscribe(topic)
        loadMessages()
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
            }
        }
    }

    private fun sendMessage() {
        val topic = currentTopic
        if (topic == null) {
            Toast.makeText(requireContext(), "请先添加并选择话题", Toast.LENGTH_SHORT).show()
            return
        }
        val text = binding.etInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return

        binding.etInput.setText("")
        lifecycleScope.launch {
            try {
                // 服务器会推送给其他订阅设备，并过滤掉本机（不会重复收到自己发的消息）
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

    private fun showAddTopicDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "话题名：字母/数字/_/-（如 work、family）"
            isSingleLine = true
        }
        AlertDialog.Builder(requireContext())
            .setTitle("添加话题")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString()?.trim()?.lowercase() ?: ""
                if (!Regex("^[a-z0-9_-]{1,64}$").matches(name)) {
                    Toast.makeText(requireContext(), "话题名不合法（1-64位字母/数字/_/-）", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name in topics) {
                    Toast.makeText(requireContext(), "话题已存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addTopic(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addTopic(name: String) {
        AuthManager.addTopic(name)
        topics.clear()
        topics.addAll(AuthManager.subscribedTopics.sorted())
        spinnerAdapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        val idx = topics.indexOf(name)
        if (idx >= 0) {
            val currentPos = binding.spTopic.selectedItemPosition
            binding.spTopic.setSelection(idx)
            // 若选择位置未变化（不会触发 onItemSelected），手动切换
            if (idx == currentPos) switchTopic(name)
        }
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
