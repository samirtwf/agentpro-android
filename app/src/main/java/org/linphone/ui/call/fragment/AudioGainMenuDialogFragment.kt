/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.call.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.linphone.databinding.CallAudioGainBottomSheetBinding
import org.linphone.ui.call.viewmodel.CurrentCallViewModel

@UiThread
class AudioGainMenuDialogFragment : BottomSheetDialogFragment() {
    companion object {
        const val TAG = "AudioGainMenuDialogFragment"
    }

    // Shares the activity-scoped CurrentCallViewModel, so the sliders read/write the same
    // global gain (core.micGainDb / core.playbackGainDb) and apply live to the ongoing call.
    private lateinit var viewModel: CurrentCallViewModel

    private val microphoneGainSeekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) viewModel.onMicrophoneGainChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            viewModel.setMicrophoneGain(seekBar.progress)
        }
    }

    private val receivedVolumeSeekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) viewModel.onReceivedVolumeChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            viewModel.setReceivedVolume(seekBar.progress)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(requireActivity())[CurrentCallViewModel::class.java]

        val binding = CallAudioGainBottomSheetBinding.inflate(layoutInflater)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        binding.microphoneGainSeekbar.setOnSeekBarChangeListener(microphoneGainSeekBarListener)
        binding.receivedVolumeSeekbar.setOnSeekBarChangeListener(receivedVolumeSeekBarListener)
        return binding.root
    }
}
