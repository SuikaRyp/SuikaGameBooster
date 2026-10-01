package com.example.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Pemindai aplikasi (heuristik, offline).
 *
 * Yang dipindai untuk tiap aplikasi:
 *  1. Metadata & manifest: izin berbahaya, layanan aksesibilitas, admin perangkat,
 *     pendengar notifikasi, overlay, sertifikat penanda tangan, sumber pemasangan.
 *  2. Semua APK (base + split): hash SHA-256, lalu SEMUA isi ZIP ditelusuri sampai ke dalam
 *     (arsip di dalam arsip hingga [MAX_NESTED_DEPTH] tingkat) — cek magic bytes tiap berkas
 *     untuk menemukan DEX/APK/ELF yang disembunyikan atau menyamar jadi gambar/teks.
 *  3. Semua file DEX: dicari string indikator (penambang kripto, pengirim data ke bot, dll.).
 *  4. Folder instalasi aplikasi: berkas tambahan yang tidak semestinya ada.
 *
 * Ini BUKAN pengganti antivirus komersial: tidak ada database signature cloud. Hasilnya adalah
 * skor risiko berbasis perilaku + daftar hitam lokal (res/raw/virus_rules.json).
 */
class AppScanner(private val context: Context) {

    companion object {
        private const val TAG = "AppScanner"

        private const val MAX_ENTRIES = 30_000
        private const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
        private const val MAX_NESTED_DEPTH = 2
        private const val MAX_NESTED_ENTRY_BYTES = 64L * 1024 * 1024
        private const val CHUNK = 64 * 1024
        private const val MAX_INSTALL_DIR_FILES = 400

        private val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",
            "com.sec.android.app.samsungapps",
            "com.amazon.venezia",
            "org.fdroid.fdroid",
            "com.huawei.appmarket",
            "com.heytap.market",
            "com.xiaomi.mipicks",
            "com.miui.packageinstaller.market"
        )

        /** Pemasang bawaan = APK dipasang manual dari file (sideload). */
        private val SIDELOAD_INSTALLERS = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.apps.nbu.files",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller"
        )

        private val DATA_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico",
            ".txt", ".json", ".xml", ".html", ".css", ".mp3", ".ogg", ".wav", ".mp4", ".ttf", ".otf"
        )

        private val CLASSES_DEX = Regex("(.*/)?classes\\d*\\.dex")

        private val SKIP_HEAD_EXTENSIONS = listOf(".xml", ".arsc")

        private const val PERM_BIND_ACCESSIBILITY = "android.permission.BIND_ACCESSIBILITY_SERVICE"
        private const val PERM_BIND_DEVICE_ADMIN = "android.permission.BIND_DEVICE_ADMIN"
        private const val PERM_BIND_NOTIF_LISTENER = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    }

    private val rules: RuleSet = RuleSet.load(context)

    private class Ctx {
        val findings = LinkedHashMap<String, Finding>()
        val dexHits = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        var files = 0
        var bytes = 0L
        var entries = 0
        var truncated = false

        fun add(f: Finding) {
            if (!findings.containsKey(f.id)) findings[f.id] = f
        }
    }

    private class NestedInfo {
        var hasManifest = false
        var hasDex = false
    }

    private enum class Magic { DEX, ZIP, ELF, OTHER }

    /**
     * Memindai satu paket. Mengembalikan null jika paket tidak ada / aplikasi ini sendiri.
     * Fungsi ini berat (I/O) — panggil dari thread background.
     */
    @Suppress("DEPRECATION")
    fun scan(packageName: String): ScanResult? {
        if (packageName == context.packageName) return null
        val pm = context.packageManager

        val signingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or signingFlag

        val pi: PackageInfo = try {
            pm.getPackageInfo(packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        } catch (e: Exception) {
            Log.w(TAG, "getPackageInfo gagal untuk $packageName: ${e.message}")
            return null
        }
        val ai: ApplicationInfo = pi.applicationInfo ?: return null

        val c = Ctx()
        val appName = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { packageName }
        val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            pi.versionCode.toLong()
        }

        // ── Sumber pemasangan ──
        val installer: String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                pm.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) { null }
        val sideloaded = installer == null || SIDELOAD_INSTALLERS.contains(installer)
        if (sideloaded && !TRUSTED_INSTALLERS.contains(installer ?: "")) {
            c.add(
                Finding(
                    "sideload", "Dipasang dari luar toko aplikasi resmi",
                    "Aplikasi ini dipasang dari file APK, bukan dari toko aplikasi terpercaya. " +
                        "Malware hampir selalu tersebar lewat cara ini karena tidak melewati pemeriksaan toko.",
                    4, installer ?: "tidak diketahui"
                )
            )
        }

        // ── Daftar hitam nama package ──
        if (rules.blockedPackages.contains(packageName)) {
            c.add(
                Finding(
                    "blocked_pkg", "Ada di daftar hitam aplikasi berbahaya",
                    "Nama package aplikasi ini tercatat sebagai malware di daftar hitam lokal.",
                    100, packageName
                )
            )
        }

        analyzeManifest(pi, ai, appName, packageName, c)
        analyzeSignature(pi, c)

        // ── Pindai isi APK sampai ke dalam ──
        val apkFiles = ArrayList<File>()
        ai.sourceDir?.let { apkFiles.add(File(it)) }
        ai.splitSourceDirs?.forEach { apkFiles.add(File(it)) }
        var apkHash: String? = null
        for ((idx, apk) in apkFiles.withIndex()) {
            if (!apk.canRead()) {
                c.notes.add("Tidak bisa membaca ${apk.name}")
                continue
            }
            val h = sha256(apk)
            if (idx == 0) apkHash = h
            if (h != null && rules.blockedSha256.contains(h)) {
                c.add(
                    Finding(
                        "blocked_hash", "Cocok dengan tanda tangan malware",
                        "Hash SHA-256 file APK ini sama persis dengan malware yang tercatat di daftar hitam.",
                        100, h
                    )
                )
            }
            try {
                scanZip(apk, apk.name, 0, c)
            } catch (e: Exception) {
                Log.w(TAG, "scanZip gagal ${apk.name}: ${e.message}")
                c.notes.add("Sebagian isi ${apk.name} tidak bisa dipindai")
            }
        }
        if (c.truncated) c.notes.add("Pemindaian dibatasi karena ukuran/jumlah berkas sangat besar")

        // ── Folder instalasi ──
        try {
            scanInstallDir(ai, c)
        } catch (e: Exception) {
            Log.w(TAG, "scanInstallDir gagal: ${e.message}")
        }

        // ── Hasil pemindaian DEX → temuan ──
        for (ind in rules.dexIndicators) {
            val where = c.dexHits[ind.id] ?: continue
            c.add(Finding("dex:${ind.id}", ind.title, ind.explanation, ind.weight, where))
        }
        evaluateBankingTargets(pi, packageName, c)

        // ── Ikon disembunyikan + perilaku berisiko ──
        val sumSoFar = c.findings.values.sumOf { it.weight }
        val hasLauncher = try { pm.getLaunchIntentForPackage(packageName) != null } catch (e: Exception) { true }
        if (!hasLauncher && sumSoFar >= 20) {
            c.add(
                Finding(
                    "hidden_icon", "Tidak punya ikon di menu aplikasi",
                    "Aplikasi tanpa ikon tetapi punya kemampuan berisiko bisa berjalan tersembunyi dan sulit ditemukan atau dihapus. " +
                        "Malware sering menyembunyikan ikonnya.",
                    10
                )
            )
        }

        val findings = c.findings.values.sortedByDescending { it.weight }
        val blocked = findings.any { it.id == "blocked_hash" || it.id == "blocked_pkg" || it.id == "blocked_cert" }
        val score = if (blocked) 100 else minOf(100, findings.sumOf { it.weight })
        val level = RiskLevel.fromScore(score)

        return ScanResult(
            packageName = packageName,
            appName = appName,
            versionName = pi.versionName,
            versionCode = versionCode,
            level = level,
            score = score,
            findings = findings,
            scannedAt = System.currentTimeMillis(),
            filesScanned = c.files,
            bytesScanned = c.bytes,
            installer = installer,
            sideloaded = sideloaded,
            sha256 = apkHash,
            notes = c.notes
        )
    }

    // ────────────────────────────────────────────────────────────────
    // Manifest / izin / komponen
    // ────────────────────────────────────────────────────────────────

    private fun analyzeManifest(pi: PackageInfo, ai: ApplicationInfo, appName: String, pkg: String, c: Ctx) {
        val perms: Array<String> = pi.requestedPermissions ?: emptyArray()
        val permFlags: IntArray? = pi.requestedPermissionsFlags

        fun requested(p: String) = perms.contains(p)
        fun granted(p: String): Boolean {
            val i = perms.indexOf(p)
            if (i < 0) return false
            val f = permFlags ?: return false
            return i < f.size && (f[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }
        // Izin yang sudah diberikan bobotnya penuh, yang baru diminta setengah.
        fun w(p: String, full: Int) = if (granted(p)) full else full / 2

        // SMS
        val readSms = requested("android.permission.RECEIVE_SMS") || requested("android.permission.READ_SMS")
        if (readSms && requested("android.permission.INTERNET")) {
            val p = if (granted("android.permission.RECEIVE_SMS")) "android.permission.RECEIVE_SMS" else "android.permission.READ_SMS"
            c.add(
                Finding(
                    "sms_read", "Bisa membaca SMS dan terhubung ke internet",
                    "Aplikasi bisa membaca SMS masuk (termasuk kode OTP bank/e-wallet) dan mengirimnya ke internet. " +
                        "Ini jalur utama pencurian akun dan uang.",
                    w(p, 20), if (granted(p)) "izin sudah diberikan" else "izin diminta"
                )
            )
        }
        if (requested("android.permission.SEND_SMS")) {
            c.add(
                Finding(
                    "sms_send", "Bisa mengirim SMS",
                    "Aplikasi bisa mengirim SMS atas nama kamu — dipakai malware untuk SMS premium (pulsa tersedot) " +
                        "atau menyebarkan tautan penipuan ke kontakmu.",
                    w("android.permission.SEND_SMS", 15),
                    if (granted("android.permission.SEND_SMS")) "izin sudah diberikan" else "izin diminta"
                )
            )
        }

        // Layanan aksesibilitas / pendengar notifikasi
        val services = pi.services ?: emptyArray()
        val a11yService = services.any { it.permission == PERM_BIND_ACCESSIBILITY }
        if (a11yService) {
            val enabled = isAccessibilityEnabledFor(pkg)
            c.add(
                Finding(
                    "accessibility", "Punya layanan aksesibilitas",
                    "Layanan aksesibilitas bisa membaca semua yang tampil di layar dan menekan tombol atas namamu. " +
                        "Trojan perbankan memakainya untuk mencuri PIN/OTP dan menyetujui transaksi. " +
                        if (enabled) "Layanan ini SAAT INI AKTIF." else "Layanan ini belum diaktifkan.",
                    if (enabled) 28 else 18, if (enabled) "aktif" else "belum aktif"
                )
            )
        }
        if (services.any { it.permission == PERM_BIND_NOTIF_LISTENER }) {
            c.add(
                Finding(
                    "notif_listener", "Bisa membaca semua notifikasi",
                    "Aplikasi bisa membaca isi semua notifikasi, termasuk kode OTP dan pesan pribadi dari aplikasi lain.",
                    15
                )
            )
        }

        // Admin perangkat
        val receivers = pi.receivers ?: emptyArray()
        if (receivers.any { it.permission == PERM_BIND_DEVICE_ADMIN }) {
            val active = isDeviceAdminActive(pkg)
            c.add(
                Finding(
                    "device_admin", "Meminta hak admin perangkat",
                    "Admin perangkat bisa mengunci layar, mengganti PIN, dan menghapus data HP. " +
                        "Malware memakainya agar sulit dihapus dan untuk ransomware layar terkunci. " +
                        if (active) "Hak admin SAAT INI AKTIF." else "Hak admin belum diaktifkan.",
                    if (active) 35 else 22, if (active) "aktif" else "belum aktif"
                )
            )
        }

        if (requested("android.permission.SYSTEM_ALERT_WINDOW")) {
            c.add(
                Finding(
                    "overlay", "Bisa menampilkan jendela di atas aplikasi lain",
                    "Dipakai untuk halaman login palsu di atas aplikasi bank (overlay phishing) atau menipu sentuhanmu.",
                    w("android.permission.SYSTEM_ALERT_WINDOW", 10)
                )
            )
        }
        if (requested("android.permission.REQUEST_INSTALL_PACKAGES")) {
            c.add(
                Finding(
                    "install_pkgs", "Bisa memasang aplikasi lain",
                    "Aplikasi bisa meminta pemasangan APK lain. Pola dropper: memasang aplikasi berbahaya setelah kamu percaya.",
                    8
                )
            )
        }
        if (requested("android.permission.MANAGE_EXTERNAL_STORAGE")) {
            c.add(
                Finding(
                    "all_files", "Akses ke seluruh penyimpanan",
                    "Aplikasi bisa membaca dan mengubah semua file di HP — dipakai ransomware untuk mengenkripsi foto/dokumen.",
                    w("android.permission.MANAGE_EXTERNAL_STORAGE", 10)
                )
            )
        }
        if (requested("android.permission.READ_CONTACTS") && requested("android.permission.READ_CALL_LOG") &&
            requested("android.permission.INTERNET")
        ) {
            c.add(
                Finding(
                    "contacts_calls", "Bisa membaca kontak dan riwayat panggilan",
                    "Data kontak dan panggilan bisa dikirim ke internet. Dipakai spyware dan aplikasi pinjol ilegal untuk memeras.",
                    6
                )
            )
        }

        if ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            c.add(
                Finding(
                    "debuggable", "Aplikasi dalam mode debug",
                    "Aplikasi ini bisa dikendalikan/diperiksa dari luar. Aplikasi resmi di toko tidak dirilis dalam mode ini.",
                    8
                )
            )
        }

        // Meniru aplikasi populer
        val nameLower = appName.trim().lowercase(Locale.ROOT)
        for (imp in rules.impersonation) {
            if (imp.label.lowercase(Locale.ROOT) == nameLower && !imp.official.contains(pkg)) {
                c.add(
                    Finding(
                        "impersonation", "Menyamar sebagai \"${imp.label}\"",
                        "Nama aplikasi sama dengan ${imp.label}, tetapi package-nya bukan yang resmi (${imp.official.joinToString()}). " +
                            "Aplikasi tiruan sering dipakai untuk mencuri akun dan data.",
                        30, pkg
                    )
                )
                break
            }
        }
    }

    private fun isAccessibilityEnabledFor(pkg: String): Boolean = try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo.serviceInfo.packageName == pkg } == true
    } catch (e: Exception) { false }

    private fun isDeviceAdminActive(pkg: String): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.activeAdmins?.any { it.packageName == pkg } == true
    } catch (e: Exception) { false }

    // ────────────────────────────────────────────────────────────────
    // Sertifikat penanda tangan
    // ────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun analyzeSignature(pi: PackageInfo, c: Ctx) {
        val sigs: Array<Signature> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val si = pi.signingInfo
                if (si == null) emptyArray()
                else if (si.hasMultipleSigners()) si.apkContentsSigners ?: emptyArray()
                else si.signingCertificateHistory ?: emptyArray()
            } else {
                pi.signatures ?: emptyArray()
            }
        } catch (e: Exception) { emptyArray() }

        for (sig in sigs) {
            try {
                val bytes = sig.toByteArray()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (rules.blockedCertSha256.contains(hash)) {
                    c.add(
                        Finding(
                            "blocked_cert", "Ditandatangani sertifikat malware",
                            "Sertifikat penanda tangan aplikasi ini ada di daftar hitam pembuat malware.",
                            100, hash
                        )
                    )
                }
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
                val subject = cert.subjectX500Principal.name
                if (subject.contains("Android Debug", ignoreCase = true)) {
                    c.add(
                        Finding(
                            "debug_cert", "Ditandatangani dengan sertifikat debug",
                            "Aplikasi dirilis dengan kunci debug milik pengembang, bukan kunci rilis. " +
                                "Ini tanda aplikasi rakitan/modifikasi (repack) atau belum layak dipercaya.",
                            10, subject
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "analisis sertifikat gagal: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Penelusuran ZIP/APK sampai ke dalam
    // ────────────────────────────────────────────────────────────────

    private fun scanZip(file: File, path: String, depth: Int, c: Ctx): NestedInfo {
        val info = NestedInfo()
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            c.notes.add("Arsip tidak valid/rusak: $path")
            return info
        }
        zip.use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                if (c.entries++ > MAX_ENTRIES || c.bytes > MAX_TOTAL_BYTES) {
                    c.truncated = true
                    break
                }
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name
                val lower = name.lowercase(Locale.ROOT)
                c.files++

                if (lower == "androidmanifest.xml") info.hasManifest = true
                if (lower.startsWith("meta-inf/")) continue
                if (SKIP_HEAD_EXTENSIONS.any { lower.endsWith(it) }) continue
                if (e.size in 0L..7L) continue

                val magic = detectMagic(readHead(z, e))
                val fullPath = "$path!/$name"
                val isClassesDex = CLASSES_DEX.matches(lower)

                when (magic) {
                    Magic.DEX -> {
                        info.hasDex = true
                        if (depth > 0 || !isClassesDex) {
                            c.add(
                                Finding(
                                    "hidden_dex", "Kode (DEX) tersembunyi di dalam aplikasi",
                                    "Ditemukan file kode Android di lokasi yang tidak lazim. Pola dropper/packer: " +
                                        "kode berbahaya disimpan terpisah lalu dimuat saat aplikasi berjalan.",
                                    20, fullPath
                                )
                            )
                        }
                        if (DATA_EXTENSIONS.any { lower.endsWith(it) }) addDisguised(c, fullPath)
                        try {
                            z.getInputStream(e).use { scanDexStream(it, fullPath, c) }
                        } catch (ex: Exception) {
                            Log.w(TAG, "scan dex gagal $fullPath: ${ex.message}")
                        }
                    }
                    Magic.ELF -> {
                        if (!lower.startsWith("lib/") || depth > 0) {
                            c.add(
                                Finding(
                                    "hidden_elf", "Program native tersembunyi",
                                    "Ada file program native (ELF) di luar folder library resmi. " +
                                        "Bisa berupa exploit, penambang, atau alat root yang disembunyikan.",
                                    12, fullPath
                                )
                            )
                        }
                        if (DATA_EXTENSIONS.any { lower.endsWith(it) }) addDisguised(c, fullPath)
                    }
                    Magic.ZIP -> {
                        if (DATA_EXTENSIONS.any { lower.endsWith(it) }) addDisguised(c, fullPath)
                        if (depth < MAX_NESTED_DEPTH && e.size <= MAX_NESTED_ENTRY_BYTES) {
                            val nested = scanNested(z, e, fullPath, depth + 1, c)
                            if (nested.hasManifest && nested.hasDex) {
                                c.add(
                                    Finding(
                                        "embedded_apk", "APK lain tersembunyi di dalam aplikasi",
                                        "Ada aplikasi utuh yang disimpan di dalam aplikasi ini. Ini cara klasik dropper: " +
                                            "aplikasi tampak biasa lalu memasang aplikasi berbahaya dari dalam dirinya.",
                                        25, fullPath
                                    )
                                )
                            }
                        }
                    }
                    Magic.OTHER -> Unit
                }
            }
        }
        return info
    }

    private fun addDisguised(c: Ctx, path: String) {
        c.add(
            Finding(
                "disguised", "File berbahaya menyamar sebagai gambar/teks",
                "Ekstensi file terlihat seperti gambar atau dokumen biasa, padahal isinya kode/program. " +
                    "Ini teknik penyamaran yang hampir tidak pernah dipakai aplikasi sah.",
                20, path
            )
        )
    }

    private fun scanNested(z: ZipFile, e: java.util.zip.ZipEntry, fullPath: String, depth: Int, c: Ctx): NestedInfo {
        var tmp: File? = null
        return try {
            val t = File.createTempFile("avscan", ".zip", context.cacheDir)
            tmp = t
            val ok = z.getInputStream(e).use { copyLimited(it, t, MAX_NESTED_ENTRY_BYTES) }
            if (!ok) NestedInfo() else scanZip(t, fullPath, depth, c)
        } catch (ex: Exception) {
            Log.w(TAG, "scanNested gagal $fullPath: ${ex.message}")
            NestedInfo()
        } finally {
            try { tmp?.delete() } catch (ignored: Exception) { }
        }
    }

    private fun copyLimited(input: InputStream, out: File, limit: Long): Boolean {
        var total = 0L
        FileOutputStream(out).use { os ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) return false
                os.write(buf, 0, n)
            }
        }
        return true
    }

    private fun readHead(z: ZipFile, e: java.util.zip.ZipEntry): ByteArray = try {
        z.getInputStream(e).use { ins ->
            val b = ByteArray(8)
            var n = 0
            while (n < b.size) {
                val r = ins.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            b.copyOf(n)
        }
    } catch (ex: Exception) { ByteArray(0) }

    private fun detectMagic(h: ByteArray): Magic {
        if (h.size >= 4) {
            if (h[0] == 0x64.toByte() && h[1] == 0x65.toByte() && h[2] == 0x78.toByte() && h[3] == 0x0A.toByte()) return Magic.DEX
            if (h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() && h[2] == 0x03.toByte() && h[3] == 0x04.toByte()) return Magic.ZIP
            if (h[0] == 0x7F.toByte() && h[1] == 0x45.toByte() && h[2] == 0x4C.toByte() && h[3] == 0x46.toByte()) return Magic.ELF
        }
        return Magic.OTHER
    }

    // ────────────────────────────────────────────────────────────────
    // Pencarian indikator di dalam DEX (streaming, tanpa memuat file penuh)
    // ────────────────────────────────────────────────────────────────

    private fun scanDexStream(input: InputStream, path: String, c: Ctx) {
        val patterns = ArrayList<DexIndicator>(rules.dexIndicators)
        // Paket aplikasi keuangan dicatat sebagai indikator berbobot 0 untuk analisis "menargetkan bank".
        for (b in rules.bankingPackages) patterns.add(DexIndicator("bank:$b", b, 0, "", ""))

        var remaining = patterns.filter { !c.dexHits.containsKey(it.id) }
        if (remaining.isEmpty()) return
        val maxLen = remaining.maxOf { it.pattern.length }

        val buf = ByteArray(CHUNK + maxLen)
        var carry = 0
        while (remaining.isNotEmpty()) {
            val n = input.read(buf, carry, CHUNK)
            if (n < 0) break
            c.bytes += n
            val len = carry + n
            val text = String(buf, 0, len, Charsets.ISO_8859_1)
            for (p in remaining) {
                if (text.contains(p.pattern)) c.dexHits[p.id] = path
            }
            remaining = remaining.filter { !c.dexHits.containsKey(it.id) }
            carry = minOf(maxLen - 1, len)
            if (carry > 0) System.arraycopy(buf, len - carry, buf, 0, carry)
        }
    }

    /** Aplikasi non-keuangan yang memuat banyak nama package bank/e-wallet + izin berbahaya = menargetkan bank. */
    private fun evaluateBankingTargets(pi: PackageInfo, pkg: String, c: Ctx) {
        if (rules.bankingPackages.contains(pkg)) return
        val hits = c.dexHits.keys.filter { it.startsWith("bank:") }
        if (hits.size < 3) return
        val perms: Array<String> = pi.requestedPermissions ?: emptyArray()
        val riskyCapability = c.findings.containsKey("accessibility") ||
            perms.contains("android.permission.SYSTEM_ALERT_WINDOW") ||
            perms.contains("android.permission.RECEIVE_SMS")
        if (!riskyCapability) return
        c.add(
            Finding(
                "bank_target", "Menargetkan aplikasi bank dan e-wallet",
                "Kode aplikasi menyebut ${hits.size} aplikasi bank/e-wallet sekaligus, dan aplikasi ini punya kemampuan " +
                    "membaca layar/SMS atau menimpa layar. Ini pola trojan perbankan yang mencuri PIN, OTP, dan saldo.",
                25, hits.joinToString { it.removePrefix("bank:") }
            )
        )
    }

    // ────────────────────────────────────────────────────────────────
    // Folder instalasi (akar aplikasi)
    // ────────────────────────────────────────────────────────────────

    private fun scanInstallDir(ai: ApplicationInfo, c: Ctx) {
        val src = ai.sourceDir ?: return
        val dir = File(src).parentFile ?: return
        val expected = HashSet<String>()
        expected.add(File(src).name)
        ai.splitSourceDirs?.forEach { expected.add(File(it).name) }

        var visited = 0
        for (f in dir.walkTopDown().maxDepth(4)) {
            if (!f.isFile) continue
            if (++visited > MAX_INSTALL_DIR_FILES) break
            c.files++
            val n = f.name.lowercase(Locale.ROOT)
            val p = f.absolutePath
            if (expected.contains(f.name)) continue
            if (p.contains("/oat/") || p.contains("/lib/")) continue
            val suspicious = n.endsWith(".apk") || n.endsWith(".dex") || n.endsWith(".jar") ||
                n.endsWith(".zip") || n.endsWith(".so") || n.endsWith(".sh") || n.endsWith(".bin")
            if (suspicious) {
                c.add(
                    Finding(
                        "install_dir_extra", "Berkas tidak lazim di folder instalasi",
                        "Di folder tempat aplikasi terpasang ada berkas tambahan yang tidak termasuk paket resmi aplikasi.",
                        15, f.name
                    )
                )
                break
            }
        }
    }

    private fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }
}
