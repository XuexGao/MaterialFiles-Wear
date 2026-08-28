/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk.ui

data class AppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkSize: Long,
    val sourceDir: String,
    val installTime: Long = 0
)
