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
import me.zhanghai.android.files.compat.recreateCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.util.valueCompat
import kotlin.math.roundToInt

/**
 * Scales the entire UI to a percentage of its normal size, mainly so that phone layouts remain
 * usable on small square watch screens.
 *
 * The scaling works by lowering [Configuration.densityDpi] on every activity's base context, which
 * uniformly shrinks everything measured in dp/sp (views, drawables, text) while keeping physical
 * window sizes unchanged. Dialogs, menus and popups follow their host activity's resources, so they
 * are scaled as well.
 *
 * screenWidthDp/screenHeightDp/smallestScreenWidthDp are deliberately kept at their unscaled values
 * so that resource qualifier based layout selection behaves exactly like the unscaled app.
 */
object UiScaleHelper {
    /**
     * Maps each live activity to the scale (as a factor like 0.6f) its base context was wrapped
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

    fun wrapContext(base: Context): Context {
        val scale = currentScale
        if (scale == 1f) {
            return base
        }
        val configuration = Configuration(base.resources.configuration)
        val densityDpi = base.resources.displayMetrics.densityDpi
        configuration.densityDpi = (densityDpi * scale).roundToInt().coerceIn(1, densityDpi)
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
        get() = Settings.UI_SCALE.valueCompat / 100f
}
