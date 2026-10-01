package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AntivirusRulesTest {

    private val rulesFile = File("src/main/res/raw/virus_rules.json")

    @Test
    fun riskLevel_dipetakan_dari_skor() {
        assertEquals(RiskLevel.AMAN, RiskLevel.fromScore(0))
        assertEquals(RiskLevel.RENDAH, RiskLevel.fromScore(10))
        assertEquals(RiskLevel.SEDANG, RiskLevel.fromScore(25))
        assertEquals(RiskLevel.TINGGI, RiskLevel.fromScore(45))
        assertEquals(RiskLevel.KRITIS, RiskLevel.fromScore(100))
    }

    @Test
    fun virus_rules_json_valid_dan_terisi() {
        val rs = RuleSet.parse(rulesFile.readText())
        assertTrue("indikator DEX harus ada", rs.dexIndicators.isNotEmpty())
        assertTrue("daftar impersonation harus ada", rs.impersonation.isNotEmpty())
        assertTrue("id indikator harus unik", rs.dexIndicators.map { it.id }.toSet().size == rs.dexIndicators.size)
        assertTrue("bobot indikator > 0", rs.dexIndicators.all { it.weight > 0 })
    }

    @Test
    fun scanResult_roundtrip_json() {
        val r = ScanResult(
            packageName = "a.b", appName = "AB", versionName = "1.0", versionCode = 3,
            level = RiskLevel.TINGGI, score = 50,
            findings = listOf(Finding("x", "Judul", "Penjelasan", 20, "bukti")),
            scannedAt = 1L, filesScanned = 2, bytesScanned = 3L,
            installer = null, sideloaded = true, sha256 = "abc", notes = listOf("n")
        )
        val back = ScanResult.fromJson(r.toJson())
        assertEquals(r, back)
    }
}
