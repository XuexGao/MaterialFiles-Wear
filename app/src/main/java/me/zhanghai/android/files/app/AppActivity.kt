/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.FontScaleHelper
import me.zhanghai.android.files.ui.UiScaleHelper

abstract class AppActivity : AppCompatActivity() {
    private var isDelegateCreated = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScaleHelper.wrapContext(UiScaleHelper.wrapContext(newBase)))
    }

    override fun getDelegate(): AppCompatDelegate {
        val delegate = super.getDelegate()

        if (!isDelegateCreated) {
            isDelegateCreated = true
            NightModeHelper.apply(this)
        }
        return delegate
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CustomThemeHelper.apply(this)

        super.onCreate(savedInstanceState)

        // The window background was resolved with the system configuration, so it stays light
        // when night mode comes from our own setting instead of the system, flashing a light
        // screen before the dark content gets drawn. Re-resolve it now that night mode has been
        // applied, so that the background matches the effective mode.
        if (NightModeHelper.isInNightMode(this)) {
            val typedArray = obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val background = typedArray.getDrawable(0)
            typedArray.recycle()
            if (background != null && background.opacity != PixelFormat.TRANSPARENT) {
                window.setBackgroundDrawable(background)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
