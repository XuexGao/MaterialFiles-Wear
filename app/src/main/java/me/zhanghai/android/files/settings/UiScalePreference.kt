/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.preference.Preference
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.defaultSharedPreferences
import me.zhanghai.android.files.util.getInteger
import kotlin.math.roundToInt

class UiScalePreference : Preference {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    /**
     * The UI scale in percent, persisted through the shared preferences backing this preference.
     */
    var scale: Int
        get() = getPersistedInt(defaultScale)
        set(value) {
            if (callChangeListener(value)) {
                persistInt(value)
            }
        }

    companion object {
        const val SCALE_MIN = 40
        const val SCALE_MAX = 100
        const val SCALE_STEP = 5

        /**
         * The width in dp of a typical phone screen; screens narrower than this, e.g. watch
         * displays, get a proportionally smaller default scale.
         */
        private const val REFERENCE_SMALLEST_WIDTH_DP = 360f

        val defaultScale: Int
            get() = resolveDefaultScale()

        /**
         * Computes a default scale from the physical screen size and pixel density, so that the
         * unscaled UI fits small square watch screens.
         */
        fun resolveDefaultScale(): Int {
            val metrics = Resources.getSystem().displayMetrics
            val smallestWidthDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
            val percent = smallestWidthDp / REFERENCE_SMALLEST_WIDTH_DP * 100
            return ((percent / SCALE_STEP).roundToInt() * SCALE_STEP)
                .coerceIn(SCALE_MIN, SCALE_MAX)
        }

        /**
         * Returns the scale saved by the user, or the screen-based default if the user hasn't
         * changed it, so that manual adjustments are preserved.
         */
        fun currentEffectiveScale(): Int {
            val key = application.getString(R.string.pref_key_ui_scale)
            val stored =
                defaultSharedPreferences.getInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            return stored ?: resolveDefaultScale()
        }
    }
}
