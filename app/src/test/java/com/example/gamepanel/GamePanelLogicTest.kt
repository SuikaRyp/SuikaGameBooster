package com.example.gamepanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePanelLogicTest {

    @Test
    fun history_ring_buffer_menyimpan_urutan_dan_membuang_yang_lama() {
        val h = History(3)
        h.add(1f); h.add(2f); h.add(3f); h.add(4f)
        assertEquals(listOf(2f, 3f, 4f), h.snapshot().toList())
    }

    @Test
    fun history_menyimpan_NaN_untuk_data_tidak_tersedia() {
        val h = History(4)
        h.add(null); h.add(10f)
        val s = h.snapshot()
        assertEquals(2, s.size)
        assertTrue(s[0].isNaN())
        assertEquals(10f, s[1], 0.001f)
    }

    @Test
    fun history_clear_mengosongkan() {
        val h = History(5)
        h.add(1f); h.clear()
        assertEquals(0, h.snapshot().size)
    }

    @Test
    fun gameProfile_roundtrip_json() {
        val p = GameProfile(
            packageName = "com.contoh.game", gameName = "Contoh",
            perfMode = PerfMode.BOOST, touchProtection = Level.HIGH, edgeProtection = Level.LOW,
            notifBlock = true, brightness = 60, orientation = "LANDSCAPE",
            networkMonitor = false, fpsMonitor = true, autoOpenPanel = true
        )
        assertEquals(p, GameProfile.fromJson(p.toJson()))
    }

    @Test
    fun level_dan_mode_fallback_aman_untuk_nilai_tidak_dikenal() {
        assertEquals(Level.OFF, Level.from("ngawur"))
        assertEquals(Level.OFF, Level.from(null))
        assertEquals(PerfMode.SEIMBANG, PerfMode.from("ngawur"))
    }

    @Test
    fun interval_sampling_makin_cepat_di_mode_boost() {
        assertTrue(PerfMode.HEMAT.sampleMs > PerfMode.SEIMBANG.sampleMs)
        assertTrue(PerfMode.SEIMBANG.sampleMs > PerfMode.BOOST.sampleMs)
        assertTrue("tidak boleh polling per-milidetik", PerfMode.BOOST.sampleMs >= 1000L)
    }
}
