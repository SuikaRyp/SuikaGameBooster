package com.example.security

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun RiskLevel.uiColor(): Color = when (this) {
    RiskLevel.AMAN -> Color(0xFF4CC38A)
    RiskLevel.RENDAH -> Color(0xFF8FA3BD)
    RiskLevel.SEDANG -> Color(0xFFE9B949)
    RiskLevel.TINGGI -> Color(0xFFE5534B)
    RiskLevel.KRITIS -> Color(0xFFFF5A4F)
}

@Composable
fun RiskBadge(level: RiskLevel, modifier: Modifier = Modifier) {
    val c = level.uiColor()
    Surface(
        modifier = modifier,
        color = c.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, c.copy(alpha = 0.4f))
    ) {
        Text(
            level.label.uppercase(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = c
        )
    }
}

/** Kartu satu temuan: judul, bobot, penjelasan bahayanya, dan bukti. */
@Composable
fun FindingCard(f: Finding) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    f.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    if (f.weight >= 100) "DAFTAR HITAM" else "+${f.weight}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RiskLevel.fromScore(f.weight * 2).uiColor()
                )
            }
            Text(
                f.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            f.evidence?.let {
                Text(
                    "Bukti: $it",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
        }
    }
}
