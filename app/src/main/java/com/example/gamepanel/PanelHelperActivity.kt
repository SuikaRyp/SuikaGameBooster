package com.example.gamepanel

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Activity transparan singkat untuk dialog izin resmi yang HARUS berasal dari Activity:
 *  • MediaProjection (rekam layar)
 *  • Peran Call Screening (tolak panggilan)
 */
class PanelHelperActivity : ComponentActivity() {

    companion object {
        const val ACTION_PROJECTION = "com.example.gamepanel.REQUEST_PROJECTION"
        const val ACTION_CALL_ROLE = "com.example.gamepanel.REQUEST_CALL_ROLE"
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val i = Intent(this, ScreenRecordService::class.java)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_CODE, r.resultCode)
                .putExtra(ScreenRecordService.EXTRA_DATA, r.data)
            ContextCompat.startForegroundService(this, i)
        } else {
            Toast.makeText(this, "Izin rekam layar ditolak", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = ToolActions.hasCallRole(this)
        Toast.makeText(
            this,
            if (ok) "Penolak panggilan siap dipakai" else "Izin penyaring panggilan tidak diberikan",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_PROJECTION -> {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            ACTION_CALL_ROLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = getSystemService(ROLE_SERVICE) as RoleManager
                    if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    } else {
                        Toast.makeText(this, "Fitur tidak tersedia pada perangkat ini.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    Toast.makeText(this, "Fitur tidak tersedia pada perangkat ini (butuh Android 10+).", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            else -> finish()
        }
    }
}
