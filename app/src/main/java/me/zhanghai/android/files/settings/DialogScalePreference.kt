/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.preference.DialogPreference

// Extends DialogPreference so that MaterialPreferenceDialogFragmentCompat can read the title and
// buttons from it; otherwise tapping the preference in the settings would crash.
class DialogScalePreference : DialogPreference {
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
     * The dialog scale in percent on top of the UI scale, persisted through the shared
     * preferences backing this preference.
     */
    var scale: Int
        get() = getPersistedInt(DEFAULT)
        set(value) {
            if (callChangeListener(value)) {
                persistInt(value)
            }
        }

    companion object {
        const val SCALE_MIN = 40
        const val SCALE_MAX = 100
        const val SCALE_STEP = 5
        const val DEFAULT = 100
    }
}
