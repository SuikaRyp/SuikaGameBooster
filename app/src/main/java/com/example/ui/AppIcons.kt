package com.example.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Set ikon monoline GameBoost (grid 24, garis 1.9, ujung bulat).
 * Sumber SVG-nya ada di folder `design/icons/*.svg`; path datanya sama persis.
 * Dipakai sebagai pengganti emoji dan ikon Material supaya tampilan konsisten.
 */
object AppIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        )
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    val Camera: ImageVector by lazy { icon("camera", "M4 8h3l1.6-2h6.8L17 8h3v11H4z", "M12 16.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4z") }
    val Record: ImageVector by lazy { icon("record", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z") }
    val Sun: ImageVector by lazy { icon("sun", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M12 2.5v2.2", "M12 19.3v2.2", "M2.5 12h2.2", "M19.3 12h2.2", "M5.3 5.3l1.6 1.6", "M17.1 17.1l1.6 1.6", "M5.3 18.7l1.6-1.6", "M17.1 6.9l1.6-1.6") }
    val Volume: ImageVector by lazy { icon("volume", "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4.2 4.2 0 0 1 0 6", "M18 6.5a8 8 0 0 1 0 11") }
    val VolumeOff: ImageVector by lazy { icon("volume_off", "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M16 9.5l5 5", "M21 9.5l-5 5") }
    val Lock: ImageVector by lazy { icon("lock", "M5.5 11h13v9h-13z", "M8 11V8a4 4 0 0 1 8 0v3") }
    val Unlock: ImageVector by lazy { icon("unlock", "M5.5 11h13v9h-13z", "M8 11V8a4 4 0 0 1 7.5-1.9") }
    val RotateLock: ImageVector by lazy { icon("rotate_lock", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v3.6h-3.6", "M10 12h4v3.5h-4z", "M10.8 12v-1a1.2 1.2 0 0 1 2.4 0v1") }
    val Compass: ImageVector by lazy { icon("compass", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M15.8 8.2l-2 5.6-5.6 2 2-5.6z") }
    val Moon: ImageVector by lazy { icon("moon", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z") }
    val BellOff: ImageVector by lazy { icon("bell_off", "M6 16.5V11a6 6 0 0 1 9.3-5", "M18 11v5.5l1.5 1.5h-14", "M10.2 20.5a2 2 0 0 0 3.6 0", "M3.5 3.5l17 17") }
    val PhoneOff: ImageVector by lazy { icon("phone_off", "M21 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 1.1 4.2 2 2 0 0 1 3.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L7.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z", "M3 3l18 18") }
    val Target: ImageVector by lazy { icon("target", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9z", "M12 12.01v.01") }
    val Crosshair: ImageVector by lazy { icon("crosshair", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 2v5", "M12 17v5", "M2 12h5", "M17 12h5") }
    val Wifi: ImageVector by lazy { icon("wifi", "M2.8 9.2a13.5 13.5 0 0 1 18.4 0", "M5.8 12.6a9.3 9.3 0 0 1 12.4 0", "M8.9 16a5 5 0 0 1 6.2 0", "M12 19.4v.01") }
    val Bluetooth: ImageVector by lazy { icon("bluetooth", "M6.5 7.5l11 9-5.5 4.5V3l5.5 4.5-11 9") }
    val ShieldTouch: ImageVector by lazy { icon("shield_touch", "M12 3l7.5 3v5.5c0 4.6-3.1 8.1-7.5 9.5-4.4-1.4-7.5-4.9-7.5-9.5V6z", "M9.5 12l1.9 1.9 3.4-3.6") }
    val Pointer: ImageVector by lazy { icon("pointer", "M6 3.5l12 7.2-5.2 1.4 3 5.4-2.3 1.3-3-5.4L6.6 17z") }
    val Zap: ImageVector by lazy { icon("zap", "M13.5 2.5L5 13.5h6l-1 8 8.5-11h-6z") }
    val Battery: ImageVector by lazy { icon("battery", "M3.5 8h14v8h-14z", "M20.5 11v2", "M6.5 11v2") }
    val Globe: ImageVector by lazy { icon("globe", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M3 12h18", "M12 3c2.6 2.4 3.9 5.4 3.9 9s-1.3 6.6-3.9 9c-2.6-2.4-3.9-5.4-3.9-9S9.4 5.4 12 3z") }
    val Film: ImageVector by lazy { icon("film", "M4 5h16v14H4z", "M8 5v14", "M16 5v14", "M4 9.5h4", "M4 14.5h4", "M16 9.5h4", "M16 14.5h4") }
    val Memory: ImageVector by lazy { icon("memory", "M4 7h16v10H4z", "M8 7v-2", "M12 7v-2", "M16 7v-2", "M8 19v-2", "M12 19v-2", "M16 19v-2", "M7.5 11h9") }
    val Cpu: ImageVector by lazy { icon("cpu", "M7 7h10v10H7z", "M10 10h4v4h-4z", "M10 3v4", "M14 3v4", "M10 17v4", "M14 17v4", "M3 10h4", "M3 14h4", "M17 10h4", "M17 14h4") }
    val Thermo: ImageVector by lazy { icon("thermo", "M10 14.2V5a2 2 0 1 1 4 0v9.2a4 4 0 1 1-4 0z", "M12 9v7") }
    val Chart: ImageVector by lazy { icon("chart", "M4 20V4", "M4 20h16", "M8 16v-4", "M12 16V8", "M16 16v-6") }
    val Note: ImageVector by lazy { icon("note", "M6 3.5h8.5L19 8v12.5H6z", "M14 3.5V8h5", "M9 12.5h7", "M9 16h7") }
    val Timer: ImageVector by lazy { icon("timer", "M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 9v4l2.5 1.5", "M9.5 2.5h5") }
    val Stopwatch: ImageVector by lazy { icon("stopwatch", "M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 5V2.5", "M9.5 2.5h5", "M12 13l3.2-3.2") }
    val Calculator: ImageVector by lazy { icon("calculator", "M6 3.5h12v17H6z", "M8.5 7h7v3h-7z", "M9 14v.01", "M12 14v.01", "M15 14v.01", "M9 17.2v.01", "M12 17.2v.01", "M15 17.2v.01") }
    val Bubble: ImageVector by lazy { icon("bubble", "M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17z", "M12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z") }
    val Gamepad: ImageVector by lazy { icon("gamepad", "M7 8h10a4.5 4.5 0 0 1 4.4 5.4l-.7 3.2a2.4 2.4 0 0 1-4 1.2L14.5 16h-5l-2.2 1.8a2.4 2.4 0 0 1-4-1.2l-.7-3.2A4.5 4.5 0 0 1 7 8z", "M8 10.8v3", "M6.5 12.3h3", "M15.8 11.5v.01", "M17.8 13.5v.01") }
    val Trend: ImageVector by lazy { icon("trend", "M3 17l6-6 4 4 8-8", "M15 7h6v6") }
    val Touch: ImageVector by lazy { icon("touch", "M9 11V5.5a1.5 1.5 0 0 1 3 0V11", "M12 10.5v-1a1.5 1.5 0 0 1 3 0v1.5", "M15 11a1.5 1.5 0 0 1 3 0v4.5a5.5 5.5 0 0 1-5.5 5.5h-1.2a5 5 0 0 1-4-2L5 15.5a1.4 1.4 0 0 1 2.2-1.7L9 15.5") }
    val Grid: ImageVector by lazy { icon("grid", "M4 4h6.5v6.5H4z", "M13.5 4H20v6.5h-6.5z", "M4 13.5h6.5V20H4z", "M13.5 13.5H20V20h-6.5z") }
    val Gear: ImageVector by lazy { icon("gear", "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3.9a7 7 0 0 0-2-1.2L14.3 3h-4l-.4 2.6a7 7 0 0 0-2 1.2l-2.3-.9-2 3.4 2 1.5a7 7 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-.9a7 7 0 0 0 2 1.2l.4 2.6h4l.4-2.6a7 7 0 0 0 2-1.2l2.3.9 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z") }
    val Close: ImageVector by lazy { icon("close", "M6 6l12 12", "M18 6L6 18") }
    val Minus: ImageVector by lazy { icon("minus", "M6 12h12") }
    val Plus: ImageVector by lazy { icon("plus", "M12 5v14", "M5 12h14") }
    val Check: ImageVector by lazy { icon("check", "M5 12.5l4.5 4.5L19 7.5") }
    val CheckCircle: ImageVector by lazy { icon("check_circle", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M8 12.3l2.8 2.8L16.3 9.5") }
    val Alert: ImageVector by lazy { icon("alert", "M12 3.5l9.5 16.5h-19z", "M12 10v4.5", "M12 17.2v.01") }
    val XCircle: ImageVector by lazy { icon("x_circle", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M9 9l6 6", "M15 9l-6 6") }
    val Backspace: ImageVector by lazy { icon("backspace", "M9 5.5h11v13H9l-6-6.5z", "M12.5 9.5l5 5", "M17.5 9.5l-5 5") }
    val Refresh: ImageVector by lazy { icon("refresh", "M20 12a8 8 0 1 1-2.4-5.7", "M20 4.5v4h-4") }
    val Flame: ImageVector by lazy { icon("flame", "M12 21c3.9 0 6.5-2.6 6.5-6.2 0-2.5-1.3-4.1-2.7-5.6-.4 1.4-1.2 2.4-2.3 2.8.4-3-.4-6.1-3.2-8.5.2 3.2-1.3 4.8-2.8 6.5C6.6 11.5 5.5 12.9 5.5 14.8 5.5 18.4 8.1 21 12 21z") }
    val Mouse: ImageVector by lazy { icon("mouse", "M12 21a6 6 0 0 0 6-6V9a6 6 0 0 0-12 0v6a6 6 0 0 0 6 6z", "M12 3v6.5", "M6 9.5h12") }
    val Sliders: ImageVector by lazy { icon("sliders", "M4 7h9", "M17 7h3", "M15 4.5v5", "M4 17h3", "M11 17h9", "M9 14.5v5", "M4 12h16") }
    val Leaf: ImageVector by lazy { icon("leaf", "M5 19c0-9 5-14 15-14 0 10-5 15-14 15", "M5 19c2-4 5-7 9-9") }
    val Shield: ImageVector by lazy { icon("shield", "M12 3l7.5 3v5.5c0 4.6-3.1 8.1-7.5 9.5-4.4-1.4-7.5-4.9-7.5-9.5V6z") }
    val Usb: ImageVector by lazy { icon("usb", "M9 3v5", "M15 3v5", "M6.5 8h11v3.5a5.5 5.5 0 0 1-11 0z", "M12 17v4") }
    val Accessibility: ImageVector by lazy { icon("accessibility", "M12 6.5a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z", "M5 8.5l7 1.2 7-1.2", "M12 9.7V14", "M12 14l-3 6.5", "M12 14l3 6.5") }
    val Smartphone: ImageVector by lazy { icon("smartphone", "M7.5 3h9a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 19.5v-15A1.5 1.5 0 0 1 7.5 3z", "M11 18h2") }
    val Monitor: ImageVector by lazy { icon("monitor", "M3.5 4.5h17v11h-17z", "M9 20h6", "M12 15.5V20") }
    val Dashboard: ImageVector by lazy { icon("dashboard", "M4 4h7v8H4z", "M13 4h7v5h-7z", "M13 11h7v9h-7z", "M4 14h7v6H4z") }
    val Rocket: ImageVector by lazy { icon("rocket", "M12 3c3.5 2 5 5.5 4.5 10l-2.2 2.2h-4.6L7.5 13C7 8.5 8.5 5 12 3z", "M12 10.5a1.3 1.3 0 1 0 0-2.6 1.3 1.3 0 0 0 0 2.6z", "M9.7 15.2L8 19l3-1", "M14.3 15.2L16 19l-3-1") }
    val History: ImageVector by lazy { icon("history", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3", "M4.5 4.5v3.6h3.6", "M12 8v4.5l3 1.5") }
    val Analytics: ImageVector by lazy { icon("analytics", "M4 20V4", "M4 20h16", "M8 15l3.5-4 3 2.5L19 8") }
    val Play: ImageVector by lazy { icon("play", "M8 5l11 7-11 7z") }
    val ArrowRight: ImageVector by lazy { icon("arrow_right", "M5 12h14", "M13 6l6 6-6 6") }
    val ArrowDown: ImageVector by lazy { icon("arrow_down", "M6 9l6 6 6-6") }
    val Broom: ImageVector by lazy { icon("broom", "M13.5 3.5L11 11", "M6 11h10l1.5 9.5H4.5z", "M9 15v2.5", "M12.5 15v2.5") }
    val ClipboardCheck: ImageVector by lazy { icon("clipboard_check", "M8.5 4.5h7v3h-7z", "M7 5.5H5.5v15h13v-15H17", "M9 13.5l2.2 2.2 4-4.2") }
    val Restart: ImageVector by lazy { icon("restart", "M5 12a7 7 0 1 0 2.1-5", "M5 4.5v3.8h3.8", "M12 8.5V12") }
    val Circle: ImageVector by lazy { icon("circle", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z") }
    val Puzzle: ImageVector by lazy { icon("puzzle", "M9 4.5h3a1.8 1.8 0 1 1 3.4 0H19v4.2a1.8 1.8 0 1 0 0 3.4V19.5H4.5V12h2a1.8 1.8 0 1 0 0-3.5V4.5z") }
    val BoltPlug: ImageVector by lazy { icon("bolt_plug", "M13.5 2.5L5 13.5h6l-1 8 8.5-11h-6z") }
    val BatteryCharge: ImageVector by lazy { icon("battery_charge", "M3.5 8h14v8h-14z", "M20.5 11v2", "M10.5 9.5l-2 3h3l-1.5 3") }
    val Speed: ImageVector by lazy { icon("speed", "M12 20.5a8.5 8.5 0 1 1 8.5-8.5", "M12 12l4-4", "M12 12.01v.01", "M4.2 15.5h.01", "M20 15.5h.01") }
    val Tuner: ImageVector by lazy { icon("tuner", "M4 7h9", "M17 7h3", "M15 4.5v5", "M4 17h3", "M11 17h9", "M9 14.5v5", "M4 12h16") }
    val SettingsPc: ImageVector by lazy { icon("settings_pc", "M4 5h16v11H4z", "M9 20h6", "M12 16v4", "M8 10.5h8") }
    val Screenshot: ImageVector by lazy { icon("screenshot", "M7 3.5h10v17H7z", "M10 8h4v4h-4z") }
}
