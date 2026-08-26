/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.zhanghai.android.files.BuildConfig

/**
 * Writes uncaught exceptions into the external cache directory so that crashes can be diagnosed
 * without adb, which matters on devices like watches where capturing logcat is inconvenient.
 *
 * Only active in debug builds; at most [MAX_LOG_FILES] newest files are kept under
 * <external cache>/logs/.
 */
object CrashLogger {
    private const val MAX_LOG_FILES = 5

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "logs")
                dir.mkdirs()
                dir.listFiles { file -> file.name.startsWith("crash_") }
                    ?.sortedByDescending { it.name }
                    ?.drop(MAX_LOG_FILES - 1)
                    ?.forEach { it.delete() }
                val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                File(dir, "crash_${timeFormat.format(Date())}.txt").writeText(
                    buildString {
                        appendLine(
                            "App: " +
                                "${BuildConfig.APPLICATION_ID} " +
                                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                        )
                        appendLine(
                            "Device: ${Build.MANUFACTURER} ${Build.MODEL} / " +
                                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                        )
                        appendLine("Thread: ${thread.name}")
                        appendLine()
                        append(
                            StringWriter()
                                .also { throwable.printStackTrace(PrintWriter(it)) }
                                .toString()
                        )
                    }
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
