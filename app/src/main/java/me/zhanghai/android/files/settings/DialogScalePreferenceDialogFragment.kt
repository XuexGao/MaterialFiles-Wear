/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.putState

class DialogScalePreferenceDialogFragment : MaterialPreferenceDialogFragmentCompat() {
    override val preference: DialogScalePreference
        get() = super.preference as DialogScalePreference

    private var scale = 0

    private lateinit var seekBar: SeekBar
    private lateinit var valueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scale =
            if (savedInstanceState == null) {
                preference.scale
            } else {
                savedInstanceState.getState<State>().scale
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putState(State(scale))
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        seekBar = view.findViewById(R.id.dialog_scale_seek_bar)
        valueText = view.findViewById(R.id.dialog_scale_value_text)
        seekBar.max =
            (DialogScalePreference.SCALE_MAX - DialogScalePreference.SCALE_MIN) /
                    DialogScalePreference.SCALE_STEP
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    scale = (DialogScalePreference.SCALE_MIN +
                            progress * DialogScalePreference.SCALE_STEP)
                        .coerceIn(DialogScalePreference.SCALE_MIN, DialogScalePreference.SCALE_MAX)
                    updateViews()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        updateViews()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult && scale != preference.scale) {
            preference.scale = scale
        }
    }

    private fun updateViews() {
        seekBar.progress =
            (scale - DialogScalePreference.SCALE_MIN) / DialogScalePreference.SCALE_STEP
        valueText.text =
            requireContext().getString(R.string.settings_dialog_scale_value_format, scale)
    }

    @Parcelize
    private class State(val scale: Int) : ParcelableState
}
