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

import android.annotation.SuppressLint
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.widget.EditText
import androidx.core.content.ContextCompat
import org.linphone.R

/**
 * Adds a tap-to-reveal "eye" toggle to a password [EditText], rendered as a trailing icon at the
 * field's right edge. Tapping it flips the text between masked and visible without changing the
 * field's `inputType`, so it keeps its password semantics (no keyboard suggestions, secure-text).
 *
 * Reuses linphone's `@drawable/eye` / `@drawable/eye_slash` assets so the AgentPro-custom screens
 * (PBX settings, config export/import) match the login screens that already have this toggle. The
 * app is LTR-only, so the icon always sits on the right and the tap zone is the right edge.
 */
@SuppressLint("ClickableViewAccessibility")
fun EditText.enablePasswordToggle() {
    val sizePx = (20 * resources.displayMetrics.density).toInt()
    val eye = ContextCompat.getDrawable(context, R.drawable.eye)?.apply {
        setBounds(0, 0, sizePx, sizePx)
    }
    val eyeSlash = ContextCompat.getDrawable(context, R.drawable.eye_slash)?.apply {
        setBounds(0, 0, sizePx, sizePx)
    }

    // Start masked, regardless of how the field's inputType was declared.
    transformationMethod = PasswordTransformationMethod.getInstance()
    var visible = false

    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    setCompoundDrawablesRelative(null, null, eye, null)

    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_UP) {
            val icon = compoundDrawablesRelative[2] ?: return@setOnTouchListener false
            val tapZoneStart = width - paddingEnd - icon.bounds.width()
            if (event.x >= tapZoneStart) {
                visible = !visible
                transformationMethod = if (visible) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                setSelection(text?.length ?: 0)
                setCompoundDrawablesRelative(null, null, if (visible) eyeSlash else eye, null)
                performClick()
                return@setOnTouchListener true
            }
        }
        false
    }
}
