/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

/**
 * Font scale options, persisted as an ordinal string by the list preference in the settings and
 * read back through [FontScaleHelper]. P100 leaves the system font scale untouched.
 */
enum class FontScale(val percent: Int) {
    P80(80),
    P90(90),
    P100(100),
    P110(110),
    P120(120),
    P130(130)
}
