package com.example.ui

import androidx.compose.ui.graphics.vector.ImageVector

private val EMOJI_RE = Regex("[\\u2600-\\u27BF\\u2300-\\u23FF\\u2B00-\\u2BFF\\uFE0F\\u200D]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]")
private val SPACES_RE = Regex("[ \\t]{2,}")

/** Buang emoji/dingbat dari teks (log lama, nama profil yang tersimpan dengan emoji, dsb). */
fun String.stripEmoji(): String = replace(EMOJI_RE, "").replace(SPACES_RE, " ").trim()

/**
 * Peta nilai ikon profil yang tersimpan di database (emoji versi lama ATAU kunci baru) ke ikon vektor.
 */
fun profileIcon(icon: String?): ImageVector {
    val k = icon.orEmpty()
    return when {
        k == "flame" || "\uD83D\uDD25" in k -> AppIcons.Flame
        k == "mouse" || "\u2328" in k || "\uD83D\uDDB1" in k -> AppIcons.Mouse
        k == "target" || "\uD83C\uDFAF" in k -> AppIcons.Target
        k == "sliders" || "\u2696" in k -> AppIcons.Sliders
        k == "leaf" || "\uD83D\uDD0B" in k -> AppIcons.Leaf
        k == "zap" || "\u26A1" in k -> AppIcons.Zap
        else -> AppIcons.Gamepad
    }
}
