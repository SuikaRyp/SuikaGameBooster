package com.example.gamepanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rekam layar resmi (MediaProjection + MediaRecorder), video saja.
 * Berjalan sebagai foreground service bertipe mediaProjection selama merekam, lalu berhenti sendiri.
 * Hasil disimpan ke Movies/GameBoost (MediaStore di Android 10+).
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"
        const val ACTION_START = "com.example.gamepanel.REC_START"
        const val ACTION_STOP = "com.example.gamepanel.REC_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_record_channel"
        private const val NOTIF_ID = 4417

        @Volatile var isRecording = false
            private set
        @Volatile var onStateChanged: (() -> Unit)? = null

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, ScreenRecordService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "stop: ${e.message}")
            }
        }
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var outFile: File? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                if (data == null || !startRecording(code, data)) {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                finishRecording()
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rekam Layar", NotificationManager.IMPORTANCE_LOW))
        }
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle("Merekam layar…")
            .setContentText("Ketuk Berhenti untuk menyimpan rekaman")
            .setOngoing(true)
            .addAction(0, "Berhenti", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startRecording(code: Int, data: Intent): Boolean {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data) ?: return false
            projection = proj
            // Android 14+ mewajibkan callback terdaftar sebelum membuat VirtualDisplay
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { finishRecording(); stopSelf() }
                }
            }, main)

            val dm = resources.displayMetrics
            val scale = minOf(1f, 1920f / maxOf(dm.widthPixels, dm.heightPixels))
            val w = ((dm.widthPixels * scale).toInt()) and 1.inv()
            val h = ((dm.heightPixels * scale).toInt()) and 1.inv()

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder = rec
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(8_000_000)

            val name = "GameBoost_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GameBoost")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
                outUri = uri
                val p = contentResolver.openFileDescriptor(uri, "w") ?: return false
                pfd = p
                rec.setOutputFile(p.fileDescriptor)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val f = File(dir, name)
                outFile = f
                rec.setOutputFile(f.absolutePath)
            }
            rec.prepare()

            display = proj.createVirtualDisplay(
                "GameBoostRec", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
            )
            rec.start()
            isRecording = true
            onStateChanged?.invoke()
            Log.i(TAG, "Rekaman dimulai ${w}x$h")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mulai rekam: ${e.message}")
            Toast.makeText(this, "Gagal merekam layar: ${e.message}", Toast.LENGTH_LONG).show()
            releaseAll(deleteOutput = true)
            return false
        }
    }

    private fun finishRecording() {
        if (recorder == null && projection == null) return
        var saved = false
        try {
            recorder?.stop()
            saved = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "stop: rekaman terlalu singkat/gagal: ${e.message}")
        }
        releaseAll(deleteOutput = !saved)
        if (saved) Toast.makeText(this, "Rekaman disimpan di Movies/GameBoost", Toast.LENGTH_LONG).show()
    }

    private fun releaseAll(deleteOutput: Boolean) {
        try { display?.release() } catch (e: Exception) { /* abaikan */ }
        display = null
        try { recorder?.reset(); recorder?.release() } catch (e: Exception) { /* abaikan */ }
        recorder = null
        try { pfd?.close() } catch (e: Exception) { /* abaikan */ }
        pfd = null
        try { projection?.stop() } catch (e: Exception) { /* abaikan */ }
        projection = null

        val uri = outUri
        if (uri != null) {
            try {
                if (deleteOutput) contentResolver.delete(uri, null, null)
                else {
                    val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, cv, null, null)
                }
            } catch (e: Exception) { Log.w(TAG, "finalize uri: ${e.message}") }
        }
        outUri = null
        if (deleteOutput) { try { outFile?.delete() } catch (e: Exception) { /* abaikan */ } }
        outFile = null

        isRecording = false
        onStateChanged?.invoke()
    }

    override fun onDestroy() {
        finishRecording()
        isRecording = false
        super.onDestroy()
    }
}
