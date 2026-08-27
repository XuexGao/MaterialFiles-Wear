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
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DialogApkInfoBinding
import me.zhanghai.android.files.util.showToast
import java.io.File

class ApkInfoDialogFragment : AppCompatDialogFragment() {
    private var _binding: DialogApkInfoBinding? = null
    private val binding get() = _binding!!

    private lateinit var entry: AppEntry
    private var onExtract: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = requireArguments().getParcelable(ARG_ENTRY)!!
        onExtract = {
            dismiss()
            extractApk(entry)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogApkInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.icon.setImageDrawable(entry.icon)
        binding.appName.text = entry.label
        binding.version.text = entry.versionName

        val pm = requireContext().packageManager
        val pkgInfo = try {
            pm.getPackageInfo(entry.packageName, 0)
        } catch (e: Exception) {
            null
        }

        val signingStatus = pkgInfo?.let {
            val digests = it.signingCertificateDigests
            if (digests.isNotEmpty()) {
                "V1 + V2"
            } else {
                "未签名"
            }
        } ?: "未知"

        val debuggable = pkgInfo?.let {
            (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } ?: false

        val rows = listOf(
            "包名" to entry.packageName,
            "版本号" to (pkgInfo?.versionCode?.toString() ?: "未知"),
            "安装包大小" to formatSize(entry.apkSize),
            "签名状态" to signingStatus,
            "加固状态" to if (debuggable) "可调试" else "未加固",
            "数据目录1" to (pkgInfo?.applicationInfo?.dataDir ?: "未知"),
            "数据目录2" to requireContext().getExternalFilesDir(null)?.absolutePath.orEmpty(),
            "APK路径" to entry.appInfo.sourceDir,
            "UID" to (pkgInfo?.applicationInfo?.uid?.toString() ?: "未知")
        )

        binding.infoList.removeAllViews()
        rows.forEach { (label, value) ->
            val row = layoutInflater.inflate(R.layout.apk_info_row, binding.infoList, false)
            row.findViewById<TextView>(R.id.label).text = label
            row.findViewById<TextView>(R.id.value).text = value
            binding.infoList.addView(row)
        }

        binding.btnMore.setOnClickListener {
            showMoreMenu()
        }

        binding.btnExtract.setOnClickListener {
            onExtract?.invoke()
        }
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            getString(R.string.apk_extract_menu_launch),
            getString(R.string.apk_extract_menu_details),
            getString(R.string.apk_extract_menu_uninstall)
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(entry.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchApp(entry.packageName)
                    1 -> openAppDetails(entry.packageName)
                    2 -> uninstallApp(entry.packageName)
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
        lifecycleScope.launch {
            var targetFile: File? = null
            try {
                val apkFile = File(entry.appInfo.sourceDir)
                val downloadDir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
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
                    putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ENTRY = "entry"

        fun newInstance(entry: AppEntry, onExtract: () -> Unit): ApkInfoDialogFragment {
            return ApkInfoDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_ENTRY, entry)
                }
                this.onExtract = onExtract
            }
        }
    }
}
