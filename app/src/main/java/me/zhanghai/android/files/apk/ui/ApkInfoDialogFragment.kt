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
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.LargeAppIconPackageName
import me.zhanghai.android.files.coil.ignoreError
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.compat.longVersionCodeCompat
import me.zhanghai.android.files.databinding.DialogApkInfoBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.startActivitySafe

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
            .setView(binding.root)
            // The neutral button lands on the left of the dialog's button bar, like the overflow
            // menu in the main screen, and shares the exact style of the extract button.
            .setNeutralButton(R.string.apk_extract_more, null)
            .setPositiveButton(R.string.apk_extract_action_extract) { _, _ -> extractApk(entry) }
            .create()
    }

    override fun onStart() {
        super.onStart()

        // Display the dialog fullscreen when the watch setting asks for it; the default window
        // size is too small for dialogs with a lot of content on small screens.
        if (me.zhanghai.android.files.settings.Settings.DIALOG_FULLSCREEN.valueCompat) {
            dialog?.window?.apply {
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                // The Material dialog background is an inset drawable that keeps margins around
                // the window even at MATCH_PARENT; replace it with an opaque surface color so
                // the dialog really covers the whole screen.
                val typedArray = context.obtainStyledAttributes(
                    intArrayOf(android.R.attr.colorBackground)
                )
                setBackgroundDrawable(typedArray.getDrawable(0))
                typedArray.recycle()
            }
        }

        // Replace the neutral button's click listener so that showing the more menu doesn't
        // dismiss the dialog.
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { anchor ->
            showMoreMenu(anchor)
        }
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

        binding.appName.text = entry.label
        val placeholder = context.getDrawableCompat(R.drawable.file_apk_icon)
        binding.icon.load(LargeAppIconPackageName(entry.packageName)) {
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

    /**
     * Shows an anchored popup menu like the one in the top-right corner of the main screen, with
     * launch, details and uninstall actions.
     */
    private fun showMoreMenu(anchor: android.view.View) {
        val context = anchor.context
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, MENU_LAUNCH, 0, R.string.apk_extract_menu_launch)
        popup.menu.add(0, MENU_DETAILS, 1, R.string.apk_extract_menu_details)
        popup.menu.add(0, MENU_UNINSTALL, 2, R.string.apk_extract_menu_uninstall)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_LAUNCH -> launchApp()
                MENU_DETAILS -> openAppDetails()
                MENU_UNINSTALL -> uninstallApp()
            }
            true
        }
        popup.show()
    }

    private fun launchApp() {
        val context = requireContext().applicationContext
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            if (intent != null) {
                startActivitySafe(intent)
            } else {
                showToast(R.string.apk_extract_launch_failed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.apk_extract_launch_failed)
        }
    }

    private fun openAppDetails() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", entry.packageName, null)
            )
            startActivitySafe(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.apk_extract_open_details_failed)
        }
    }

    private fun uninstallApp() {
        try {
            val intent = Intent(
                Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null)
            )
            startActivitySafe(intent)
        } catch (e: Exception) {
            e.printStackTrace()
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
        }
    }

    companion object {
        private const val ARG_LABEL = "label"
        private const val ARG_PACKAGE_NAME = "package_name"
        private const val ARG_VERSION_NAME = "version_name"
        private const val ARG_APK_SIZE = "apk_size"
        private const val ARG_SOURCE_DIR = "source_dir"
        private val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000]")

        private const val MENU_LAUNCH = 1
        private const val MENU_DETAILS = 2
        private const val MENU_UNINSTALL = 3

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
