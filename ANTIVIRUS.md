# Antivirus GameBoost Pro

Fitur: memindai setiap aplikasi baru (dan hasil update) secara otomatis, sampai ke dalam paket APK.

## Alur
1. `AntivirusManager.start()` (dipanggil dari `MainActivity` dan `GameBoostService`) mendaftarkan receiver `PACKAGE_ADDED`/`PACKAGE_REMOVED`.
2. Aplikasi baru → `AppScanner.scan(pkg)`:
   - manifest: izin SMS/overlay/semua-file, layanan aksesibilitas, admin perangkat, pendengar notifikasi, mode debug, meniru nama app populer
   - sertifikat penanda tangan + hash SHA-256 APK (cek daftar hitam)
   - **seluruh isi ZIP** (base + split APK, arsip di dalam arsip sampai 2 tingkat): cek magic bytes tiap berkas → DEX/APK/ELF tersembunyi atau menyamar jadi gambar/teks
   - string indikator di semua DEX (penambang kripto, bot Telegram/Discord, dll.)
   - folder instalasi aplikasi
3. Skor 0–100 → `AMAN / RENDAH / SEDANG / TINGGI / KRITIS`.
4. Risiko ≥ SEDANG → `ThreatAlertActivity` (pop-up bila izin overlay ada, selalu notifikasi) dengan penjelasan bahaya + tombol **HAPUS APLIKASI** / **BIARKAN APLIKASI**.

## Menambah aturan
Edit `app/src/main/res/raw/virus_rules.json` (hash APK, hash sertifikat, nama package, indikator DEX) tanpa ubah kode.

## Batasan
Heuristik offline, tanpa database signature cloud. Bukan pengganti antivirus komersial; bisa ada false positive/negative.
