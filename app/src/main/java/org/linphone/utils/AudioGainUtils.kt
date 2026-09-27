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
package org.linphone.utils

import androidx.annotation.AnyThread
import org.linphone.R

/**
 * Shared helpers for the microphone / received-volume gain sliders, used by both the
 * Settings sliders (SettingsViewModel) and the in-call quick sliders (CurrentCallViewModel).
 * Both drive the same global core.micGainDb / core.playbackGainDb, so the slider range and
 * the value label must stay identical in both places — keeping them here avoids drift.
 */
object AudioGainUtils {
    // 0 dB = unchanged, +MAX_GAIN_DB ~ x10 louder. Higher would risk clipping/distortion.
    // NOTE: the SeekBars hard-code this as android:max in settings_advanced_fragment.xml and
    // call_audio_gain_bottom_sheet.xml — keep those in sync if you change this value.
    const val MAX_GAIN_DB = 20

    @AnyThread
    fun clamp(gainDb: Int): Int = gainDb.coerceIn(0, MAX_GAIN_DB)

    @AnyThread
    fun format(gainDb: Int): String {
        return if (gainDb <= 0) {
            AppUtils.getString(R.string.settings_advanced_gain_normal)
        } else {
            AppUtils.getString(R.string.settings_advanced_gain_boost).format(gainDb)
        }
    }
}
