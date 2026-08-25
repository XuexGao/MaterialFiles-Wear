/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.preference.Preference
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.util.getInteger

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
        val defaultScale: Int
            get() = application.getInteger(R.integer.pref_default_value_ui_scale)
    }
}
