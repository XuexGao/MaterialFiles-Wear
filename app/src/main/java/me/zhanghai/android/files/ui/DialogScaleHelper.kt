/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.defaultSharedPreferences

/**
 * Scales dialogs independently of the activity UI scale, so that dialogs can be shrunk further
 * on small watch screens. Applied on top of [UiScaleHelper]'s density: wrapping an activity
 * context multiplies the already scaled density with the dialog scale, while dialogs created
 * without a wrapped context simply follow the UI scale.
 */
object DialogScaleHelper {
    fun wrapContext(context: Context): Context {
        val scale = currentScale
        if (scale == 1f) {
            return context
        }
        return try {
            val configuration = Configuration(context.resources.configuration)
            val densityDpi = context.resources.displayMetrics.densityDpi
            configuration.densityDpi = (densityDpi * scale).toInt().coerceIn(1, densityDpi)
            context.createConfigurationContext(configuration)
        } catch (e: Exception) {
            // Never let the dialog scale crash a dialog; log the cause and fall back to the
            // unscaled context.
            Log.e(TAG, "Failed to apply dialog scale $scale", e)
            context
        }
    }

    private val currentScale: Float
        get() {
            // Read the shared preferences directly, because dialogs are created lazily and the
            // settings live data may not be loaded yet.
            val key = application.getString(R.string.pref_key_dialog_scale)
            val raw = defaultSharedPreferences.all[key]
            val value = when (raw) {
                is Int -> raw
                is String -> raw.toIntOrNull()
                else -> null
            } ?: me.zhanghai.android.files.settings.DialogScalePreference.DEFAULT
            return value.coerceIn(
                me.zhanghai.android.files.settings.DialogScalePreference.SCALE_MIN,
                me.zhanghai.android.files.settings.DialogScalePreference.SCALE_MAX
            ) / 100f
        }
}
