/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.MaterialPreferenceDialogFragmentCompat
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.putState

class UiScalePreferenceDialogFragment : MaterialPreferenceDialogFragmentCompat() {
    private val uiScalePreference: UiScalePreference
        get() = preference as UiScalePreference

    private var scale = 0

    private lateinit var seekBar: SeekBar
    private lateinit var valueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scale =
            if (savedInstanceState == null) {
                uiScalePreference.scale
            } else {
                savedInstanceState.getState<State>().scale
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putState(State(scale))
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        // The click listener is replaced in onStart() so that the dialog isn't dismissed.
        builder.setNeutralButton(R.string.reset, null)
    }

    override fun onStart() {
        super.onStart()

        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            scale = UiScalePreference.defaultScale
            updateViews()
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        seekBar = view.findViewById(R.id.ui_scale_seek_bar)
        valueText = view.findViewById(R.id.ui_scale_value_text)
        seekBar.max = (SCALE_MAX - SCALE_MIN) / SCALE_STEP
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    scale = (SCALE_MIN + progress * SCALE_STEP).coerceIn(SCALE_MIN, SCALE_MAX)
                    updateViews()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        updateViews()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult && scale != uiScalePreference.scale) {
            uiScalePreference.scale = scale
        }
    }

    private fun updateViews() {
        seekBar.progress = (scale - SCALE_MIN) / SCALE_STEP
        valueText.text = requireContext().getString(R.string.settings_ui_scale_value_format, scale)
    }

    companion object {
        private const val SCALE_MIN = 40
        private const val SCALE_MAX = 100
        private const val SCALE_STEP = 5
    }

    @Parcelize
    private class State(val scale: Int) : ParcelableState
}
