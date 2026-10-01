package com.example.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tingkat risiko hasil pemindaian.
 * Skor 0–100 dipetakan ke tingkat lewat [fromScore].
 */
enum class RiskLevel(
    val label: String,
    val rank: Int,
    val headline: String,
    val advice: String
) {
    AMAN(
        "Aman", 0,
        "Tidak ditemukan tanda bahaya",
        "Pemindaian tidak menemukan perilaku mencurigakan pada aplikasi ini."
    ),
    RENDAH(
        "Rendah", 1,
        "Sedikit mencurigakan",
        "Ada beberapa ciri yang sering dipakai malware, tetapi juga umum di aplikasi biasa. Risiko kecil, cukup dipantau."
    ),
    SEDANG(
        "Sedang", 2,
        "Aplikasi mencurigakan",
        "Aplikasi ini punya kemampuan yang sering disalahgunakan untuk mencuri data atau mengganggu HP. " +
            "Jika kamu tidak mengenalnya atau tidak benar-benar membutuhkannya, sebaiknya dihapus."
    ),
    TINGGI(
        "Tinggi", 3,
        "Aplikasi berbahaya",
        "Aplikasi ini menunjukkan beberapa perilaku khas malware. Data pribadi, akun, atau uangmu bisa terancam. " +
            "Sangat disarankan untuk menghapusnya sekarang."
    ),
    KRITIS(
        "Kritis", 4,
        "Aplikasi sangat berbahaya",
        "Aplikasi ini cocok dengan daftar hitam atau menunjukkan indikasi malware yang sangat kuat. " +
            "Segera hapus dan ganti password akun penting (bank, e-wallet, email) jika sudah sempat dibuka."
    );

    companion object {
        fun fromScore(score: Int): RiskLevel = when {
            score >= 70 -> KRITIS
            score >= 45 -> TINGGI
            score >= 25 -> SEDANG
            score >= 10 -> RENDAH
            else -> AMAN
        }

        fun fromName(name: String?): RiskLevel = values().firstOrNull { it.name == name } ?: AMAN
    }
}

/** Satu temuan mencurigakan beserta penjelasan seberapa berbahaya. */
data class Finding(
    val id: String,
    val title: String,
    val explanation: String,
    val weight: Int,
    val evidence: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("explanation", explanation)
        put("weight", weight)
        if (evidence != null) put("evidence", evidence)
    }

    companion object {
        fun fromJson(o: JSONObject): Finding = Finding(
            id = o.optString("id"),
            title = o.optString("title"),
            explanation = o.optString("explanation"),
            weight = o.optInt("weight"),
            evidence = if (o.has("evidence")) o.optString("evidence") else null
        )
    }
}

data class ScanResult(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val level: RiskLevel,
    val score: Int,
    val findings: List<Finding>,
    val scannedAt: Long,
    val filesScanned: Int,
    val bytesScanned: Long,
    val installer: String?,
    val sideloaded: Boolean,
    val sha256: String?,
    val notes: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        if (versionName != null) put("versionName", versionName)
        put("versionCode", versionCode)
        put("level", level.name)
        put("score", score)
        put("findings", JSONArray().also { arr -> findings.forEach { arr.put(it.toJson()) } })
        put("scannedAt", scannedAt)
        put("filesScanned", filesScanned)
        put("bytesScanned", bytesScanned)
        if (installer != null) put("installer", installer)
        put("sideloaded", sideloaded)
        if (sha256 != null) put("sha256", sha256)
        put("notes", JSONArray().also { arr -> notes.forEach { arr.put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ScanResult {
            val fArr = o.optJSONArray("findings") ?: JSONArray()
            val findings = ArrayList<Finding>()
            for (i in 0 until fArr.length()) {
                val item = fArr.optJSONObject(i) ?: continue
                findings.add(Finding.fromJson(item))
            }
            val nArr = o.optJSONArray("notes") ?: JSONArray()
            val notes = ArrayList<String>()
            for (i in 0 until nArr.length()) notes.add(nArr.optString(i))
            return ScanResult(
                packageName = o.optString("packageName"),
                appName = o.optString("appName"),
                versionName = if (o.has("versionName")) o.optString("versionName") else null,
                versionCode = o.optLong("versionCode"),
                level = RiskLevel.fromName(o.optString("level")),
                score = o.optInt("score"),
                findings = findings,
                scannedAt = o.optLong("scannedAt"),
                filesScanned = o.optInt("filesScanned"),
                bytesScanned = o.optLong("bytesScanned"),
                installer = if (o.has("installer")) o.optString("installer") else null,
                sideloaded = o.optBoolean("sideloaded"),
                sha256 = if (o.has("sha256")) o.optString("sha256") else null,
                notes = notes
            )
        }
    }
}
