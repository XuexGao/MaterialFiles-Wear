/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import java.util.WeakHashMap
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.defaultSharedPreferences
import me.zhanghai.android.files.compat.recreateCompat
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks

/**
 * Applies a user-configurable font scale on top of the system one, so that text in this app can be
 * enlarged or shrunk independently of the global system setting.
 *
 * The scale works by multiplying [Configuration.fontScale] on every activity's base context, so
 * everything measured in sp follows. Dialogs, menus and popups follow their host activity's
 * resources, so they are scaled as well.
 *
 * Values below 100 make text smaller than the system setting, values above 100 make it larger;
 * 100 leaves the system font scale untouched.
 */
object FontScaleHelper {
    /**
     * Maps each live activity to the font scale factor (like 1.1f) its base context was wrapped
     * with. Activities are recreated by [sync] whenever this differs from [currentScale].
     */
    private val activityScales = WeakHashMap<Activity, Float>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // The scale has already been applied in AppActivity.attachBaseContext(), record it
                // so that we know which activities have to be recreated upon a change.
                activityScales[activity] = currentScale
            }

            override fun onActivityDestroyed(activity: Activity) {
                activityScales.remove(activity)
            }
        })
    }

    /**
     * Wraps [base] with the current font scale. Idempotent: the target font scale is always
     * computed from the passed context's own configuration, so the system font scale and any
     * previous wrapping are both preserved as the base.
     */
    fun wrapContext(base: Context): Context {
        val scale = currentScale
        if (scale == 1f) {
            return base
        }
        val configuration = Configuration(base.resources.configuration)
        configuration.fontScale *= scale
        return base.createConfigurationContext(configuration)
    }

    fun sync() {
        val scale = currentScale
        // Iterate over a copy because recreation doesn't immediately remove the old activities.
        for ((activity, appliedScale) in activityScales.entries.toList()) {
            if (appliedScale != scale && !activity.isFinishing) {
                activity.recreateCompat()
            }
        }
    }

    private val currentScale: Float
        get() {
            // Read the shared preferences directly because this is called from
            // attachBaseContext(), where the settings live data may not be loaded yet.
            // The preference stores an ordinal string (see FontScale); fall back to 100% for a
            // missing or out-of-range value. Reading through the map also tolerates an integer
            // left behind by an older build, which would crash SharedPreferences.getString().
            val key = application.getString(R.string.pref_key_font_scale)
            val raw = defaultSharedPreferences.all[key]
            val ordinal = when (raw) {
                is String -> raw.toIntOrNull()
                is Int -> raw
                else -> null
            }
            val scale = FontScale.entries.getOrNull(ordinal ?: -1) ?: FontScale.P100
            return scale.percent / 100f
        }
}
