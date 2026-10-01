package com.example

import android.provider.Settings
import com.example.ui.AppIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.security.AntivirusManager
import com.example.security.RiskBadge
import com.example.security.ScanResult
import com.example.security.ScanStore
import com.example.security.ThreatAlertActivity
import com.example.security.uiColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tab "Keamanan": kontrol antivirus + daftar hasil pemindaian. */
@Composable
fun SecurityScreen() {
    val context = LocalContext.current
    val results by AntivirusManager.results.collectAsStateWithLifecycle()
    val progress by AntivirusManager.progress.collectAsStateWithLifecycle()
    val realtime by AntivirusManager.realtimeEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { AntivirusManager.start(context) }

    val threats = results.filter { it.level.rank >= AntivirusManager.ALERT_MIN_LEVEL.rank }
    val lastFull = remember(progress.running) { ScanStore.getLastFullScan(context) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")) }
    val canOverlay = Settings.canDrawOverlays(context)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Status keseluruhan ──
        item {
            SectionCard(title = "ANTIVIRUS", icon = AppIcons.Shield) {
                val headline = when {
                    results.isEmpty() -> "Belum ada hasil pemindaian"
                    threats.isNotEmpty() -> "${threats.size} aplikasi berisiko"
                    else -> "Tidak ada aplikasi berisiko"
                }
                val headlineColor = when {
                    results.isEmpty() -> Color.White.copy(alpha = 0.6f)
                    threats.isNotEmpty() -> Color(0xFFE9B949)
                    else -> Color(0xFF4CC38A)
                }
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = headlineColor)
                Text(
                    "${results.size} aplikasi sudah dipindai" +
                        if (lastFull > 0L) " · pindai penuh terakhir ${fmt.format(Date(lastFull))}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Proteksi real-time ──
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, com.example.ui.theme.HairLine)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Proteksi real-time", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Otomatis memindai setiap aplikasi baru yang diunduh/dipasang, sampai ke isi paketnya.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = realtime, onCheckedChange = { AntivirusManager.setRealtime(context, it) })
                    }
                    if (realtime && !canOverlay) {
                        Text(
                            "Izinkan \"Tampil di atas aplikasi lain\" agar peringatan bisa muncul langsung di layar. " +
                                "Tanpa itu, peringatan tetap muncul lewat notifikasi.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE9B949)
                        )
                    }
                }
            }
        }

        // ── Pindai semua ──
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, com.example.ui.theme.HairLine)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { AntivirusManager.scanAll(context) },
                        enabled = !progress.running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (progress.running) "MEMINDAI…" else "PINDAI SEMUA APLIKASI",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    if (progress.running) {
                        LinearProgressIndicator(
                            progress = { if (progress.total == 0) 0f else progress.done / progress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${progress.done}/${progress.total} · ${progress.current}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ── Daftar hasil ──
        item {
            Text(
                "HASIL PEMINDAIAN (${results.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (results.isEmpty()) {
            item {
                Text(
                    "Tekan \"Pindai semua aplikasi\" untuk memeriksa aplikasi yang sudah terpasang. " +
                        "Aplikasi baru akan dipindai otomatis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(results, key = { it.packageName }) { r ->
            ResultRow(r) {
                context.startActivity(ThreatAlertActivity.createIntent(context, r.packageName))
            }
        }
    }
}

@Composable
private fun ResultRow(r: ScanResult, onClick: () -> Unit) {
    val c = r.level.uiColor()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, c.copy(alpha = if (r.level.rank >= AntivirusManager.ALERT_MIN_LEVEL.rank) 0.4f else 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    r.appName,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (r.findings.isEmpty()) "Tidak ada temuan" else "${r.findings.size} temuan · skor ${r.score}/100",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RiskBadge(r.level)
        }
    }
}
