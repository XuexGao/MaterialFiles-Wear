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
        if (isLoading || _binding == null) {
            return
        }
        isLoading = true
        binding.swipeRefreshLayout.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    fetchApps()
                }
                allApps = apps
                if (_binding != null) {
                    filter(currentQuery)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast(R.string.apk_extract_load_failed)
            } finally {
                if (_binding != null) {
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
                        appInfo = appInfo,
                        label = label,
                        packageName = packageName,
                        versionName = versionName,
                        apkSize = apkSize,
                        icon = appInfo.loadIcon(pm) ?: requireContext().getDrawable(R.drawable.file_apk_icon)!!
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
        MaterialAlertDialogBuilder(requireContext())
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
            val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            startActivitySafe(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.apk_extract_uninstall_failed)
        }
    }

    private fun extractApk(entry: AppEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apkFile = File(entry.appInfo.sourceDir)
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "apks"
                )
                downloadDir.mkdirs()
                val targetFile = File(downloadDir, "${entry.label}_${entry.versionName}.apk")
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
                e.printStackTrace()
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
