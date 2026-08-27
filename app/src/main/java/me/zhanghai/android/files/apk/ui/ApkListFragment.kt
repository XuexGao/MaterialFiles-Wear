/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ApkAppItemBinding
import me.zhanghai.android.files.util.getExtra
import java.io.File

class ApkListFragment : Fragment(R.layout.apk_app_list) {
    private var isSystemApps: Boolean = false
    private lateinit var adapter: ApkAppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isSystemApps = requireArguments().getBoolean(ARG_IS_SYSTEM_APPS)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ApkAppAdapter(requireContext(), isSystemApps)
        recycler.adapter = adapter
    }

    companion object {
        private const val ARG_IS_SYSTEM_APPS = "is_system_apps"

        fun newInstance(isSystemApps: Boolean): ApkListFragment = ApkListFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_IS_SYSTEM_APPS, isSystemApps) }
        }
    }
}

private class ApkAppAdapter(
    private val context: android.content.Context,
    private val isSystemApps: Boolean
) : RecyclerView.Adapter<ApkAppAdapter.ViewHolder>() {
    private val items = mutableListOf<AppEntry>()

    init {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in packages) {
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApps == isSystem) {
                items += AppEntry(
                    appInfo = appInfo,
                    label = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    versionName = try {
                        pm.getPackageInfo(appInfo.packageName, 0).versionName ?: ""
                    } catch (e: Exception) {
                        ""
                    },
                    apkSize = try {
                        File(appInfo.sourceDir).length()
                    } catch (e: Exception) {
                        0L
                    },
                    icon = appInfo.loadIcon(pm)
                )
            }
        }
        items.sortBy { it.label.lowercase() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ApkAppItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ApkAppItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
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

private data class AppEntry(
    val appInfo: ApplicationInfo,
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkSize: Long,
    val icon: android.graphics.drawable.Drawable
)
