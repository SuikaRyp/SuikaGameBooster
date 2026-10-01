package com.example.gamepanel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class PerfMode(val label: String, val sampleMs: Long) {
    HEMAT("Penghemat Baterai", 5000L),
    SEIMBANG("Seimbang", 2000L),
    BOOST("BOOST", 1000L);

    companion object {
        fun from(name: String?): PerfMode = values().firstOrNull { it.name == name } ?: SEIMBANG
    }
}

enum class Level(val label: String, val dp: Int) {
    OFF("OFF", 0), LOW("LOW", 12), MEDIUM("MEDIUM", 24), HIGH("HIGH", 40);

    companion object {
        fun from(name: String?): Level = values().firstOrNull { it.name == name } ?: OFF
    }
}

/** Pengaturan global panel (SharedPreferences). */
object PanelSettings {
    private const val PREFS = "game_panel_settings"

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Saklar utama Panel Manager ──
    /** false = Game Panel mati total (tanpa edge swipe, panel, HUD, bubble). Default ON agar perilaku lama tidak berubah. */
    fun panelEnabled(c: Context) = p(c).getBoolean("panel_enabled", true)
    fun setPanelEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("panel_enabled", v).apply()

    // ── Edge swipe ──
    fun edgeEnabled(c: Context) = p(c).getBoolean("edge_enabled", true)
    fun setEdgeEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("edge_enabled", v).apply()

    /** Lebar area sentuh di tepi kanan (dp). */
    fun edgeWidthDp(c: Context) = p(c).getInt("edge_width_dp", 20)
    fun setEdgeWidthDp(c: Context, v: Int) = p(c).edit().putInt("edge_width_dp", v.coerceIn(8, 48)).apply()

    /** Jarak geser minimum (dp) agar gesture dianggap sah. */
    fun swipeDistanceDp(c: Context) = p(c).getInt("swipe_distance_dp", 64)
    fun setSwipeDistanceDp(c: Context, v: Int) = p(c).edit().putInt("swipe_distance_dp", v.coerceIn(24, 160)).apply()

    /** 1..5, makin tinggi makin gampang terpicu (mengurangi jarak yang dibutuhkan). */
    fun sensitivity(c: Context) = p(c).getInt("sensitivity", 3)
    fun setSensitivity(c: Context, v: Int) = p(c).edit().putInt("sensitivity", v.coerceIn(1, 5)).apply()

    fun cooldownMs(c: Context) = p(c).getLong("cooldown_ms", 700L)

    /** Persentase tinggi layar (bagian tengah) yang dipakai strip gesture, menghindari area gestur back/home. */
    fun edgeHeightPercent(c: Context) = p(c).getInt("edge_height_pct", 60)
    fun setEdgeHeightPercent(c: Context, v: Int) = p(c).edit().putInt("edge_height_pct", v.coerceIn(30, 100)).apply()

    // ── Panel ──
    fun panelOpacity(c: Context) = p(c).getInt("panel_opacity", 88)
    fun setPanelOpacity(c: Context, v: Int) = p(c).edit().putInt("panel_opacity", v.coerceIn(40, 100)).apply()

    fun panelSizePercent(c: Context) = p(c).getInt("panel_size_pct", 60)
    fun setPanelSizePercent(c: Context, v: Int) = p(c).edit().putInt("panel_size_pct", v.coerceIn(40, 90)).apply()

    /** Kecepatan animasi: 1 lambat .. 3 cepat. */
    fun animSpeed(c: Context) = p(c).getInt("anim_speed", 2)
    fun setAnimSpeed(c: Context, v: Int) = p(c).edit().putInt("anim_speed", v.coerceIn(1, 3)).apply()
    fun animDurationMs(c: Context): Long = when (animSpeed(c)) { 1 -> 380L; 3 -> 140L; else -> 240L }

    fun theme(c: Context) = p(c).getString("theme", "cyan") ?: "cyan"
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()

    // ── HUD ──
    fun hudEnabled(c: Context) = p(c).getBoolean("hud_enabled", false)
    fun setHudEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("hud_enabled", v).apply()
    fun hudOpacity(c: Context) = p(c).getInt("hud_opacity", 80)
    fun setHudOpacity(c: Context, v: Int) = p(c).edit().putInt("hud_opacity", v.coerceIn(30, 100)).apply()
    fun hudSizeSp(c: Context) = p(c).getInt("hud_size_sp", 11)
    fun setHudSizeSp(c: Context, v: Int) = p(c).edit().putInt("hud_size_sp", v.coerceIn(9, 18)).apply()
    /** 0=kiri-atas 1=kanan-atas 2=kiri-bawah 3=kanan-bawah 4=kustom (hasil drag) */
    fun hudCorner(c: Context) = p(c).getInt("hud_corner", 0)
    fun setHudCorner(c: Context, v: Int) = p(c).edit().putInt("hud_corner", v).apply()
    fun hudX(c: Context) = p(c).getInt("hud_x", 16)
    fun hudY(c: Context) = p(c).getInt("hud_y", 16)
    fun setHudXY(c: Context, x: Int, y: Int) = p(c).edit().putInt("hud_x", x).putInt("hud_y", y).apply()
    fun hudMetric(c: Context, key: String, def: Boolean = key != "ping") = p(c).getBoolean("hud_m_$key", def)
    fun setHudMetric(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean("hud_m_$key", v).apply()

    // ── Bubble ──
    fun bubbleX(c: Context) = p(c).getInt("bubble_x", -1)
    fun bubbleY(c: Context) = p(c).getInt("bubble_y", 200)
    fun setBubbleXY(c: Context, x: Int, y: Int) = p(c).edit().putInt("bubble_x", x).putInt("bubble_y", y).apply()

    // ── Umum ──
    fun autoStart(c: Context) = p(c).getBoolean("auto_start", false)
    fun setAutoStart(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_start", v).apply()
    fun defaultMode(c: Context) = PerfMode.from(p(c).getString("default_mode", PerfMode.SEIMBANG.name))
    fun setDefaultMode(c: Context, m: PerfMode) = p(c).edit().putString("default_mode", m.name).apply()

    // ── Proteksi sentuh global ──
    fun touchProtection(c: Context) = Level.from(p(c).getString("touch_prot", Level.OFF.name))
    fun setTouchProtection(c: Context, l: Level) = p(c).edit().putString("touch_prot", l.name).apply()
    fun edgeProtection(c: Context) = Level.from(p(c).getString("edge_prot", Level.OFF.name))
    fun setEdgeProtection(c: Context, l: Level) = p(c).edit().putString("edge_prot", l.name).apply()

    // ── Toggle alat ──
    fun tool(c: Context, key: String) = p(c).getBoolean("tool_$key", false)
    fun setTool(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean("tool_$key", v).apply()

    // ── Catatan cepat ──
    fun notes(c: Context) = p(c).getString("quick_notes", "") ?: ""
    fun setNotes(c: Context, v: String) = p(c).edit().putString("quick_notes", v).apply()

    // ── Mode performa aktif (sesi) ──
    fun activeMode(c: Context) = PerfMode.from(p(c).getString("active_mode", null) ?: defaultMode(c).name)
    fun setActiveMode(c: Context, m: PerfMode) = p(c).edit().putString("active_mode", m.name).apply()

    fun resetAll(c: Context) {
        p(c).edit().clear().apply()
        GameProfileStore.clear(c)
    }
}

/** Profil per-game. */
data class GameProfile(
    val packageName: String,
    val gameName: String,
    val perfMode: PerfMode = PerfMode.SEIMBANG,
    val touchProtection: Level = Level.OFF,
    val edgeProtection: Level = Level.OFF,
    val notifBlock: Boolean = false,
    /** -1 = jangan ubah */
    val brightness: Int = -1,
    /** "AUTO", "LANDSCAPE", "PORTRAIT" */
    val orientation: String = "AUTO",
    val networkMonitor: Boolean = true,
    val fpsMonitor: Boolean = true,
    val autoOpenPanel: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", packageName); put("name", gameName); put("mode", perfMode.name)
        put("touch", touchProtection.name); put("edge", edgeProtection.name)
        put("notif", notifBlock); put("bright", brightness); put("orient", orientation)
        put("net", networkMonitor); put("fps", fpsMonitor); put("auto", autoOpenPanel)
    }

    companion object {
        fun fromJson(o: JSONObject) = GameProfile(
            packageName = o.optString("pkg"),
            gameName = o.optString("name"),
            perfMode = PerfMode.from(o.optString("mode")),
            touchProtection = Level.from(o.optString("touch")),
            edgeProtection = Level.from(o.optString("edge")),
            notifBlock = o.optBoolean("notif"),
            brightness = o.optInt("bright", -1),
            orientation = o.optString("orient", "AUTO"),
            networkMonitor = o.optBoolean("net", true),
            fpsMonitor = o.optBoolean("fps", true),
            autoOpenPanel = o.optBoolean("auto")
        )
    }
}

/** Penyimpanan profil per-game (SharedPreferences JSON, keyed by package). */
object GameProfileStore {
    private const val PREFS = "game_panel_profiles"
    private const val KEY = "profiles"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun get(c: Context, pkg: String, fallbackName: String = pkg): GameProfile {
        val root = read(c)
        val o = root.optJSONObject(pkg)
        return if (o != null) GameProfile.fromJson(o)
        else GameProfile(pkg, fallbackName, perfMode = PanelSettings.defaultMode(c))
    }

    @Synchronized
    fun put(c: Context, gp: GameProfile) {
        val root = read(c)
        root.put(gp.packageName, gp.toJson())
        p(c).edit().putString(KEY, root.toString()).apply()
    }

    @Synchronized
    fun remove(c: Context, pkg: String) {
        val root = read(c)
        root.remove(pkg)
        p(c).edit().putString(KEY, root.toString()).apply()
    }

    @Synchronized
    fun clear(c: Context) { p(c).edit().clear().apply() }

    private fun read(c: Context): JSONObject {
        val s = p(c).getString(KEY, null) ?: return JSONObject()
        return try { JSONObject(s) } catch (e: Exception) { JSONObject() }
    }

    @Suppress("unused")
    private fun emptyArray() = JSONArray()
}
