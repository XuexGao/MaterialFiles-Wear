/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.LargeAppIconPackageName
import me.zhanghai.android.files.coil.ignoreError
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.databinding.ApkAppItemBinding
import me.zhanghai.android.files.databinding.ApkAppListBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.showToast

class ApkListFragment : Fragment(R.layout.apk_app_list) {
    private var _binding: ApkAppListBinding? = null
    private val binding get() = _binding!!

    private var isSystemApps: Boolean = false
    private lateinit var adapter: ApkAppAdapter
    private var isLoading = false

    private var currentQuery: String = ""
    private var allApps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isSystemApps = requireArguments().getBoolean(ARG_IS_SYSTEM_APPS)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = ApkAppListBinding.bind(view)
        adapter = ApkAppAdapter { appEntry ->
            showAppMenu(appEntry)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefreshLayout.setOnRefreshListener {
            loadApps()
        }
        loadApps()
    }

    fun filter(query: String) {
        currentQuery = query
        // The view (and adapter) may not be created yet when this is called from the activity's
        // search; loadApps() will apply the query again once apps are loaded.
        if (_binding == null) {
            return
        }
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.replace(filtered)
    }

    private fun loadApps() {
        if (isLoading || _binding == null || !isAdded) {
            return
        }
        isLoading = true
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    fetchApps(context)
                }
                if (!isAdded || _binding == null) {
                    return@launch
                }
                allApps = apps
                filter(currentQuery)
            } catch (e: Exception) {
                e.printStackTrace()
                if (isAdded) {
                    showToast(R.string.apk_extract_load_failed)
                }
            } finally {
                if (isAdded && _binding != null) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
                isLoading = false
            }
        }
    }

    private fun fetchApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val result = mutableListOf<AppEntry>()
        for (appInfo in packages) {
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApps == isSystem) {
                val label = appInfo.loadLabel(pm).toString()
                val packageName = appInfo.packageName
                var versionName = ""
                var apkSize = 0L
                var installTime = 0L
                try {
                    val pkgInfo = pm.getPackageInfo(packageName, 0)
                    versionName = pkgInfo.versionName ?: ""
                    apkSize = File(appInfo.sourceDir).length()
                    installTime = pkgInfo.firstInstallTime
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                result.add(
                    AppEntry(
                        label = label,
                        packageName = packageName,
                        versionName = versionName,
                        apkSize = apkSize,
                        sourceDir = appInfo.sourceDir,
                        installTime = installTime
                    )
                )
            }
        }
        // Newest installs first.
        result.sortByDescending { it.installTime }
        return result
    }

    private fun showAppMenu(entry: AppEntry) {
        ApkInfoDialogFragment.newInstance(entry).show(parentFragmentManager, "apk_info")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_IS_SYSTEM_APPS = "is_system_apps"

        fun newInstance(isSystemApps: Boolean): ApkListFragment = ApkListFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_IS_SYSTEM_APPS, isSystemApps) }
        }
    }
}

private class ApkAppAdapter(
    private val onItemClick: (AppEntry) -> Unit
) : SimpleAdapter<AppEntry, ApkAppAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ApkAppItemBinding.inflate(parent.context.layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ApkAppItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(entry: AppEntry) {
            binding.title.text = entry.label
            binding.version.text = entry.versionName
            binding.size.text = entry.apkSize.asFileSize().formatHumanReadable(itemView.context)
            binding.packageName.text = entry.packageName
            val placeholder = itemView.context.getDrawableCompat(R.drawable.file_apk_icon)
            // Full-detail icon source so the large list icons stay sharp.
            binding.icon.load(LargeAppIconPackageName(entry.packageName)) {
                placeholder(placeholder)
                ignoreError()
            }
        }
    }
}
