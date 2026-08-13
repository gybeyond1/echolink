package com.notifysync.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.notifysync.data.ApiClient
import com.notifysync.data.AppFilter
import com.notifysync.databinding.FragmentAppFilterBinding
import kotlinx.coroutines.launch

class AppFilterFragment : Fragment() {
    private var _binding: FragmentAppFilterBinding? = null
    private val binding get() = _binding!!
    private val adapter = AppFilterAdapter { filter, enabled ->
        updateFilter(filter, enabled)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnSave.setOnClickListener { saveAllFilters() }

        loadFilters()
    }

    private fun loadFilters() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // 获取服务器上已保存的过滤器
                val serverFilters = ApiClient.getFilters().associateBy { it.packageName }

                // 获取本机已安装的应用列表
                val pm = requireContext().packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // 只显示非系统应用
                    .map {
                        val packageName = it.packageName
                        val appName = pm.getApplicationLabel(it).toString()
                        val existing = serverFilters[packageName]
                        AppFilter(
                            packageName = packageName,
                            appName = appName,
                            enabled = existing?.enabled ?: false
                        )
                    }
                    .sortedBy { it.appName.lowercase() }

                adapter.submitList(apps)
                binding.tvEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.tvEmpty.text = "加载失败: ${e.message}"
                binding.tvEmpty.visibility = View.VISIBLE
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateFilter(filter: AppFilter, enabled: Boolean) {
        filter.enabled = enabled
        lifecycleScope.launch {
            try {
                ApiClient.saveFilter(filter.packageName, filter.appName, enabled)
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    private fun saveAllFilters() {
        val filters = adapter.currentList.filter { it.enabled }
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                ApiClient.batchUpdateFilters(filters)
                binding.progressBar.visibility = View.GONE
                android.widget.Toast.makeText(
                    requireContext(),
                    "已保存 ${filters.size} 个应用的同步设置",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                android.widget.Toast.makeText(
                    requireContext(),
                    "保存失败: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
