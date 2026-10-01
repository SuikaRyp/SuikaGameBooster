package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanTest {

    @Test fun stripsLeadingEmojiAndTrims() {
        assertEquals("EXTREME", "\uD83D\uDD25 EXTREME".stripEmoji())
        assertEquals("FF MOUSE", "\u2328\uFE0F FF MOUSE".stripEmoji())
    }

    @Test fun keepsNormalPunctuation() {
        assertEquals("Mode: Boost · 120 Hz — aktif", "Mode: Boost · 120 Hz — aktif".stripEmoji())
        assertEquals("Suhu 38°C", "Suhu 38°C".stripEmoji())
    }

    @Test fun collapsesSpacesLeftByEmoji() {
        assertEquals("Selesai dan terverifikasi", "Selesai \u2705 dan terverifikasi".stripEmoji().replace("  ", " "))
    }
}
