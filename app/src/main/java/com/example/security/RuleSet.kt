package com.example.security

import android.content.Context
import com.example.R
import org.json.JSONObject

/** Indikator string yang dicari di dalam file DEX (kode aplikasi). */
data class DexIndicator(
    val id: String,
    val pattern: String,
    val weight: Int,
    val title: String,
    val explanation: String
)

/** Aplikasi yang memakai nama populer tetapi package-nya bukan yang resmi. */
data class Impersonation(val label: String, val official: Set<String>)

/**
 * Aturan deteksi yang dimuat dari res/raw/virus_rules.json.
 * File itu bisa diedit/ditambah tanpa mengubah kode (hash daftar hitam, indikator baru, dll).
 */
class RuleSet(
    val blockedSha256: Set<String>,
    val blockedCertSha256: Set<String>,
    val blockedPackages: Set<String>,
    val impersonation: List<Impersonation>,
    val dexIndicators: List<DexIndicator>,
    val bankingPackages: Set<String>
) {
    companion object {
        @Volatile
        private var cached: RuleSet? = null

        fun load(context: Context): RuleSet {
            cached?.let { return it }
            val text = context.resources.openRawResource(R.raw.virus_rules)
                .bufferedReader().use { it.readText() }
            val rs = parse(text)
            cached = rs
            return rs
        }

        fun parse(text: String): RuleSet {
            val root = JSONObject(text)
            fun strSet(key: String, lower: Boolean): Set<String> {
                val arr = root.optJSONArray(key) ?: return emptySet()
                val out = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) out.add(if (lower) v.lowercase() else v)
                }
                return out
            }

            val imp = ArrayList<Impersonation>()
            root.optJSONArray("impersonation")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val offArr = o.optJSONArray("official") ?: continue
                    val off = HashSet<String>()
                    for (j in 0 until offArr.length()) off.add(offArr.optString(j))
                    imp.add(Impersonation(o.optString("label"), off))
                }
            }

            val dex = ArrayList<DexIndicator>()
            root.optJSONArray("dex_indicators")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pattern = o.optString("pattern")
                    if (pattern.isEmpty()) continue
                    dex.add(
                        DexIndicator(
                            id = o.optString("id", "ind$i"),
                            pattern = pattern,
                            weight = o.optInt("weight", 5),
                            title = o.optString("title"),
                            explanation = o.optString("explanation")
                        )
                    )
                }
            }

            return RuleSet(
                blockedSha256 = strSet("blocked_sha256", true),
                blockedCertSha256 = strSet("blocked_cert_sha256", true),
                blockedPackages = strSet("blocked_packages", false),
                impersonation = imp,
                dexIndicators = dex,
                bankingPackages = strSet("banking_packages", false)
            )
        }
    }
}
