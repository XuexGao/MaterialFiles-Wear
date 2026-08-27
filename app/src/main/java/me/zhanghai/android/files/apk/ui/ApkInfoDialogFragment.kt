/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.coil.ignoreError
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.compat.longVersionCodeCompat
import me.zhanghai.android.files.databinding.DialogApkInfoBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser

class ApkInfoDialogFragment : AppCompatDialogFragment() {
    private lateinit var entry: AppEntry

    private lateinit var binding: DialogApkInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        entry = AppEntry(
            label = args.getString(ARG_LABEL)!!,
            packageName = args.getString(ARG_PACKAGE_NAME)!!,
            versionName = args.getString(ARG_VERSION_NAME)!!,
            apkSize = args.getLong(ARG_APK_SIZE),
            sourceDir = args.getString(ARG_SOURCE_DIR)!!
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogApkInfoBinding.inflate(layoutInflater)
        populateViews()
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(entry.label)
            .setView(binding.root)
            .setNegativeButton(R.string.apk_extract_more) { _, _ -> showMoreMenu() }
            .setPositiveButton(R.string.apk_extract_action_extract) { _, _ -> extractApk(entry) }
            .create()
    }

    private fun populateViews() {
        val context = requireContext()
        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(entry.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val pkgInfo = try {
            pm.getPackageInfo(entry.packageName, 0)
        } catch (e: Exception) {
            null
        }

        val placeholder = context.getDrawableCompat(R.drawable.file_apk_icon)
        binding.icon.load(AppIconPackageName(entry.packageName)) {
            placeholder(placeholder)
            ignoreError()
        }
        binding.version.text = entry.versionName

        val signed = pkgInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.signingInfo?.hasMultipleSigners() == true ||
                        it.signingInfo?.apkContentsSigners?.isNotEmpty() == true ||
                        it.signingInfo?.signingCertificateHistory?.isNotEmpty() == true
            } else {
                @Suppress("DEPRECATION")
                it.signatures?.isNotEmpty() == true
            }
        }
        val rows = listOf(
            getString(R.string.apk_extract_info_package_name) to entry.packageName,
            getString(R.string.apk_extract_info_version_code) to
                    (pkgInfo?.longVersionCodeCompat?.toString() ?: getString(R.string.unknown)),
            getString(R.string.apk_extract_info_apk_size) to
                    entry.apkSize.asFileSize().formatHumanReadable(context),
            getString(R.string.apk_extract_info_signed) to getString(
                if (signed == true) {
                    R.string.apk_extract_info_signed_yes
                } else {
                    R.string.apk_extract_info_signed_no
                }
            ),
            getString(R.string.apk_extract_info_data_dir) to
                    (appInfo?.dataDir ?: getString(R.string.unknown)),
            getString(R.string.apk_extract_info_apk_path) to entry.sourceDir,
            getString(R.string.apk_extract_info_uid) to
                    (appInfo?.uid?.toString() ?: getString(R.string.unknown))
        )
        val infoList = binding.infoList
        for ((label, value) in rows) {
            val row = layoutInflater.inflate(R.layout.apk_info_row, infoList, false)
            row.findViewById<TextView>(R.id.label).text = label
            row.findViewById<TextView>(R.id.value).text = value
            infoList.addView(row)
        }
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            getString(R.string.apk_extract_menu_launch),
            getString(R.string.apk_extract_menu_details),
            getString(R.string.apk_extract_menu_uninstall)
        )
        MaterialAlertDialogBuilder(requireContext())
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
        val intent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
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
            showToast(R.string.apk_extract_uninstall_failed)
        }
    }

    private fun extractApk(entry: AppEntry) {
        // The dialog is dismissed when this runs, so capture the application context and use the
        // process lifecycle to keep the copy alive after this fragment is destroyed.
        val context = requireContext().applicationContext
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            val targetFile = try {
                withContext(Dispatchers.IO) {
                    val apkFile = File(entry.sourceDir)
                    val downloadDir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        "apks"
                    )
                    downloadDir.mkdirs()
                    val fileName = "${entry.label}_${entry.versionName}"
                        .replace(UNSAFE_FILE_NAME_CHARS, "_") + ".apk"
                    val targetFile = File(downloadDir, fileName)
                    apkFile.copyTo(targetFile, overwrite = true)
                    targetFile
                }
            } catch (e: Exception) {
                e.printStackTrace()
                context.showToast(R.string.apk_extract_failed)
                return@launch
            }

            context.showToast(context.getString(R.string.apk_extract_saved, targetFile.absolutePath))
            val intent = Paths.get(targetFile.absolutePath).fileProviderUri
                .createSendStreamIntent(MimeType.APK)
                .withChooser(context.getString(R.string.apk_extract_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivitySafe(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val ARG_LABEL = "label"
        private const val ARG_PACKAGE_NAME = "package_name"
        private const val ARG_VERSION_NAME = "version_name"
        private const val ARG_APK_SIZE = "apk_size"
        private const val ARG_SOURCE_DIR = "source_dir"
        private val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000]")

        fun newInstance(entry: AppEntry): ApkInfoDialogFragment {
            return ApkInfoDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_LABEL, entry.label)
                    putString(ARG_PACKAGE_NAME, entry.packageName)
                    putString(ARG_VERSION_NAME, entry.versionName)
                    putLong(ARG_APK_SIZE, entry.apkSize)
                    putString(ARG_SOURCE_DIR, entry.sourceDir)
                }
            }
        }
    }
}
