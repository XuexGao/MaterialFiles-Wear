/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.ThemedSwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ApkAppItemBinding
import me.zhanghai.android.files.databinding.ApkAppListBinding
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import java.io.File

class ApkListFragment : Fragment(R.layout.apk_app_list) {
    private var _binding: ApkAppListBinding? = null
    private val binding get() = _binding!!

    private var isSystemApps: Boolean = false
    private lateinit var adapter: ApkAppAdapter
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isSystemApps = requireArguments().getBoolean(ARG_IS_SYSTEM_APPS)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = ApkAppListBinding.bind(view)
        adapter = ApkAppAdapter(requireContext()) { appEntry ->
            showAppMenu(appEntry)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.swipeRefreshLayout.setOnRefreshListener {
            loadApps()
        }
        loadApps()
    }

    private var currentQuery: String = ""
    private var allApps: List<AppEntry> = emptyList()

    fun filter(query: String) {
        currentQuery = query
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.setItems(filtered)
    }

    private fun loadApps() {
        if (isLoading || _binding == null || !isAdded) {
            return
        }
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    fetchApps()
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

    private fun fetchApps(): List<AppEntry> {
        val pm = requireContext().packageManager
        val packages = pm.getInstalledApplications(0)
        val result = mutableListOf<AppEntry>()
        for (appInfo in packages) {
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApps == isSystem) {
                val label = appInfo.loadLabel(pm).toString()
                val packageName = appInfo.packageName
                var versionName = ""
                var apkSize = 0L
                try {
                    val pkgInfo = pm.getPackageInfo(packageName, 0)
                    versionName = pkgInfo.versionName ?: ""
                    apkSize = File(appInfo.sourceDir).length()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                result.add(
                    AppEntry(
                        label = label,
                        packageName = packageName,
                        versionName = versionName,
                        apkSize = apkSize,
                        sourceDir = appInfo.sourceDir
                    )
                )
            }
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    private fun showAppMenu(entry: AppEntry) {
        ApkInfoDialogFragment.newInstance(entry) {
            extractApk(entry)
        }.show(parentFragmentManager, "apk_info")
    }


    private fun launchApp(packageName: String) {
        val pm = requireContext().packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivitySafe(intent)
        } else {
            showToast(R.string.apk_extract_launch_failed)
        }
    }

    private fun openAppDetails(packageName: String) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivitySafe(intent)
        } catch (e: Exception) {
            showToast(R.string.apk_extract_open_details_failed)
        }
    }

    private fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            startActivitySafe(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.apk_extract_uninstall_failed)
        }
    }

    private fun extractApk(entry: AppEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            var targetFile: File? = null
            try {
                val apkFile = File(entry.sourceDir)
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "apks"
                )
                downloadDir.mkdirs()
                targetFile = File(downloadDir, "${entry.label}_${entry.versionName}.apk")
                withContext(Dispatchers.IO) {
                    apkFile.copyTo(targetFile, overwrite = true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast(R.string.apk_extract_failed)
                return@launch
            }

            targetFile?.let { file ->
                showToast(getString(R.string.apk_extract_saved, file.absolutePath))
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivitySafe(Intent.createChooser(shareIntent, getString(R.string.apk_extract_share_title)))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
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

private data class AppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkSize: Long,
    val sourceDir: String
)

private class ApkAppAdapter(
    private val context: android.content.Context,
    private val onItemClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<ApkAppAdapter.ViewHolder>() {
    private val items = mutableListOf<AppEntry>()
    private val layoutInflater = LayoutInflater.from(context)
    private var lastAnimatedPosition = -1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ApkAppItemBinding.inflate(layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
        if (position > lastAnimatedPosition) {
            holder.itemView.alpha = 0f
            holder.itemView.animate().alpha(1f).setDuration(120).start()
            lastAnimatedPosition = position
        }
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<AppEntry>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        notifyItemRangeInserted(0, newItems.size)
        lastAnimatedPosition = -1
        if (newItems.size < oldSize) {
            notifyItemRangeRemoved(newItems.size, oldSize - newItems.size)
        }
    }

    inner class ViewHolder(private val binding: ApkAppItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(items[position])
                }
            }
        }

        fun bind(entry: AppEntry) {
            val pm = context.packageManager
            try {
                val appInfo = pm.getApplicationInfo(entry.packageName, 0)
                binding.icon.setImageDrawable(appInfo.loadIcon(pm))
            } catch (e: Exception) {
                binding.icon.setImageResource(R.drawable.file_apk_icon)
            }
            binding.title.text = entry.label
            binding.version.text = entry.versionName
            binding.size.text = formatSize(entry.apkSize)
            binding.packageName.text = entry.packageName
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024 && i < units.lastIndex) {
            size /= 1024
            i++
        }
        return String.format("%.1f %s", size, units[i])
    }
}
