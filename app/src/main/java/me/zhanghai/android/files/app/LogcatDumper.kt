/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LogcatDumper private constructor(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var process: Process? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        val logDir = File(context.externalCacheDir, "logs")
        logDir.mkdirs()
        val logFile = File(logDir, "app.log")
        thread = Thread {
            try {
                val builder = StringBuilder()
                builder.append("logcat")
                builder.append(" -v")
                builder.append(" threadtime")
                builder.append(" -T")
                builder.append(" 1")
                val cmd = builder.toString()
                process = Runtime.getRuntime().exec(cmd)
                process!!.inputStream.bufferedReader().use { reader ->
                    var line: String? = null
                    while (running.get() && reader.readLine().also { line = it } != null) {
                        try {
                            FileOutputStream(logFile, true).use { out ->
                                out.write((line + "\n").toByteArray())
                            }
                        } catch (e: IOException) {
                            Log.w(TAG, "Failed to write log line", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Logcat dumper stopped", e)
            }
        }
        thread?.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        try {
            process?.destroy()
        } catch (ignored: Exception) {
        }
        thread?.interrupt()
    }

    companion object {
        private const val TAG = "LogcatDumper"

        @Volatile
        private var instance: LogcatDumper? = null

        fun get(context: Context): LogcatDumper {
            return instance ?: synchronized(this) {
                instance ?: LogcatDumper(context.applicationContext).also { instance = it }
            }
        }
    }
}
