package com.example.security

import android.content.Context
import com.example.ui.AppIcons
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Layar peringatan antivirus.
 *
 * Muncul saat aplikasi baru dinilai berisiko (>= SEDANG). Menampilkan tingkat bahaya,
 * penjelasan tiap temuan, lalu pilihan:
 *   • HAPUS APLIKASI → membuka dialog uninstall sistem (konfirmasi tetap dari pengguna)
 *   • BIARKAN        → peringatan untuk versi ini tidak dimunculkan lagi
 * Bisa juga dibuka dari tab Keamanan untuk melihat detail aplikasi mana pun.
 */
class ThreatAlertActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PACKAGE = "extra_package"

        fun createIntent(context: Context, pkg: String): Intent =
            Intent(context, ThreatAlertActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    private var currentPackage by mutableStateOf<String?>(null)
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentPackage = intent?.getStringExtra(EXTRA_PACKAGE)

        setContent {
            MyApplicationTheme {
                AlertHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PACKAGE)?.let { currentPackage = it }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // cek ulang apakah aplikasi sudah benar-benar dihapus
    }

    @Composable
    private fun AlertHost() {
        val pkg = currentPackage
        val tick = resumeTick

        // Jika aplikasi sudah tidak terpasang (mis. selesai dihapus), bersihkan dan lanjut ke berikutnya.
        LaunchedEffect(pkg, tick) {
            if (pkg != null && !AntivirusManager.isInstalled(this@ThreatAlertActivity, pkg)) {
                ScanStore.removeResult(this@ThreatAlertActivity, pkg)
                AntivirusManager.cancelAlertNotification(this@ThreatAlertActivity, pkg)
                AntivirusManager.onUserDecision(this@ThreatAlertActivity)
                Toast.makeText(this@ThreatAlertActivity, "Aplikasi sudah dihapus", Toast.LENGTH_SHORT).show()
                goToNextOrFinish(pkg)
            }
        }

        val result = remember(pkg, tick) { pkg?.let { ScanStore.getResult(this, it) } }
        if (result == null) {
            if (pkg == null) {
                LaunchedEffect(Unit) { finish() }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F1318)) {}
            return
        }

        AlertContent(
            r = result,
            onDelete = { uninstall(result.packageName) },
            onIgnore = {
                ScanStore.ignore(this, result.packageName, result.versionCode)
                AntivirusManager.cancelAlertNotification(this, result.packageName)
                AntivirusManager.onUserDecision(this)
                Toast.makeText(this, "Dibiarkan. Peringatan tidak muncul lagi untuk versi ini.", Toast.LENGTH_SHORT).show()
                goToNextOrFinish(result.packageName)
            },
            onClose = { goToNextOrFinish(result.packageName) }
        )
    }

    private fun uninstall(pkg: String) {
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka layar hapus. Hapus manual lewat Pengaturan → Aplikasi.", Toast.LENGTH_LONG).show()
        }
    }

    private fun goToNextOrFinish(handled: String) {
        ScanStore.removePending(this, handled)
        val next = ScanStore.pendingList(this).firstOrNull {
            it != handled && AntivirusManager.isInstalled(this, it) && ScanStore.getResult(this, it) != null
        }
        if (next != null) currentPackage = next else finish()
    }
}

@Composable
private fun AlertContent(
    r: ScanResult,
    onDelete: () -> Unit,
    onIgnore: () -> Unit,
    onClose: () -> Unit
) {
    val color = r.level.uiColor()
    val isThreat = r.level.rank >= AntivirusManager.ALERT_MIN_LEVEL.rank
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F1318)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(20.dp))

                // ── Header ──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isThreat) AppIcons.Alert else AppIcons.CheckCircle,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        r.level.headline.uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = color,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        r.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        r.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }

                // ── Skor risiko ──
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Skor risiko", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                        Text("${r.score}/100 · ${r.level.label}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
                    }
                    LinearProgressIndicator(
                        progress = { r.score / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = color,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                }

                // ── Seberapa berbahaya ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Seberapa berbahaya?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
                        Text(r.level.advice, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                }

                // ── Temuan ──
                if (r.findings.isNotEmpty()) {
                    Text(
                        "TEMUAN (${r.findings.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    r.findings.forEach { FindingCard(it) }
                }

                // ── Info pemindaian ──
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val src = when {
                        r.installer == null -> "file APK (tanpa toko aplikasi)"
                        r.sideloaded -> "installer bawaan (APK manual)"
                        else -> r.installer
                    }
                    InfoLine("Versi", r.versionName ?: "-")
                    InfoLine("Sumber pemasangan", src)
                    InfoLine("Dipindai", "${r.filesScanned} berkas · ${r.bytesScanned / 1024 / 1024} MB kode")
                    InfoLine("Waktu", fmt.format(Date(r.scannedAt)))
                    r.sha256?.let { InfoLine("SHA-256", it.take(24) + "…") }
                    r.notes.forEach { InfoLine("Catatan", it) }
                }
                Text(
                    "Pemindaian ini heuristik dan offline: tanda risiko bukan bukti pasti bahwa aplikasi itu malware, " +
                        "dan aplikasi yang lolos belum tentu 100% aman.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Tombol aksi ──
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isThreat) {
                    Button(
                        onClick = onDelete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5534B), contentColor = Color.White)
                    ) {
                        Text("HAPUS APLIKASI", fontWeight = FontWeight.ExtraBold)
                    }
                    OutlinedButton(
                        onClick = onIgnore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("BIARKAN APLIKASI", fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                    }
                    Text(
                        "\"Biarkan\" berlaku untuk versi ini saja. Jika aplikasi diperbarui, akan dipindai ulang.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Button(
                        onClick = onClose,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("TUTUP", fontWeight = FontWeight.ExtraBold)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("HAPUS APLIKASI", fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f), modifier = Modifier.weight(0.35f))
        Text(value ?: "-", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(0.65f))
    }
}
