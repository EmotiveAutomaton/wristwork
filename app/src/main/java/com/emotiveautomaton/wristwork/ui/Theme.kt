package com.emotiveautomaton.wristwork.ui

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography

/**
 * Shared look for every tap-frame: the watch face's own font (Google Sans on Pixel devices),
 * falling back silently to the system default where the device family doesn't exist.
 * Monospace tables opt out locally — alignment needs fixed advance.
 */
private val wristFontFamily: FontFamily by lazy {
    val candidate = Typeface.create("google-sans", Typeface.NORMAL)
    val probe = Typeface.create("zz-no-such-family-zz", Typeface.NORMAL)
    // Unknown families resolve to the same default Typeface object; a distinct result means
    // the device really has google-sans.
    if (candidate != null && candidate != probe) FontFamily(candidate) else FontFamily.Default
}

@Composable
fun WristTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = Typography(defaultFontFamily = wristFontFamily),
        content = content,
    )
}
