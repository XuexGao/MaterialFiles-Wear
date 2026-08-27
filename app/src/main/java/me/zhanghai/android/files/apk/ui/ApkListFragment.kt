/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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

    private fun loadApps() {
        if (isLoading) {
            return
        }
        isLoading = true
        binding.progressBar.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                fetchApps()
            }
            adapter.setItems(apps)
            binding.progressBar.visibility = View.GONE
            binding.swipeRefreshLayout.isRefreshing = false
            isLoading = false
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
                        appInfo = appInfo,
                        label = label,
                        packageName = packageName,
                        versionName = versionName,
                        apkSize = apkSize,
                        icon = appInfo.loadIcon(pm) ?: context.getDrawable(R.drawable.file_apk_icon)!!
                    )
                )
            }
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    private fun showAppMenu(entry: AppEntry) {
        val items = arrayOf(
            getString(R.string.apk_extract_menu_launch),
            getString(R.string.apk_extract_menu_details),
            getString(R.string.apk_extract_menu_uninstall),
            getString(R.string.apk_extract_menu_extract)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(entry.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchApp(entry.packageName)
                    1 -> openAppDetails(entry.packageName)
                    2 -> uninstallApp(entry.packageName)
                    3 -> extractApk(entry)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            startActivitySafe(intent)
        } catch (e: Exception) {
            showToast(R.string.apk_extract_uninstall_failed)
        }
    }

    private fun extractApk(entry: AppEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apkFile = File(entry.appInfo.sourceDir)
                val targetDir = File(requireContext().getExternalFilesDir(null), "apks")
                targetDir.mkdirs()
                val targetFile = File(targetDir, "${entry.label}_${entry.versionName}.apk")
                withContext(Dispatchers.IO) {
                    apkFile.copyTo(targetFile, overwrite = true)
                }
                showToast(getString(R.string.apk_extract_saved, targetFile.absolutePath))
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, Uri.fromFile(targetFile))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivitySafe(Intent.createChooser(shareIntent, getString(R.string.apk_extract_share_title)))
            } catch (e: Exception) {
                showToast(R.string.apk_extract_failed)
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
    val appInfo: android.content.pm.ApplicationInfo,
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkSize: Long,
    val icon: android.graphics.drawable.Drawable
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
            binding.icon.setImageDrawable(entry.icon)
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
