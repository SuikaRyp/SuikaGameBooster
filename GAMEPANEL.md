# Game Booster Panel (edge swipe)

Paket: `com.example.gamepanel`. Panel lama (`FloatingPanelManager`) TIDAK diubah dan tetap jalan.

## Alur
`GameBoostService` → `GamePanelController.onGameChanged(pkg)` (dari `repository.simulatedGame`) →
terapkan profil game → `EdgeGuards` (strip tipis di tepi kanan) → swipe kanan→kiri → `PanelWindow` (slide-in)
→ tombol `—` → `BubbleOverlay`. Tidak ada jendela overlay sama sekali saat tidak ada game aktif.

## Yang benar-benar bekerja tanpa root
Edge swipe, panel + animasi, HUD, bubble, kunci sentuh, proteksi tepi/atas-bawah, crosshair, catatan/timer/stopwatch/kalkulator,
RAM/baterai/layar/jaringan (API resmi), ping/loss/download/upload, DND & blokir notifikasi (NotificationManager),
kecerahan & kunci rotasi (WRITE_SETTINGS), volume/mute, rekam layar (MediaProjection), screenshot (aksi global Aksesibilitas, Android 9+),
tolak panggilan (peran Call Screening, Android 10+), profil per-game (SharedPreferences), daftar game (Room lama).

## Butuh Shizuku (jika tidak: tampil "N/A", tidak ada angka palsu)
FPS game (dumpsys SurfaceFlinger), CPU usage (Android 8+ memblokir /proc/stat), GPU usage/frekuensi, frekuensi CPU, visualisasi sentuhan.

## Batasan yang disengaja
- Sentuhan yang jatuh tepat di strip tepi tidak diteruskan ke game (batas overlay Android).
- Wi-Fi/Bluetooth: Android 10+ tidak mengizinkan app mengubahnya → membuka panel/pengaturan sistem.
- Sampling rate touch hardware & governor CPU tidak disentuh.
- "Prioritas game" hanya menjeda aktivitas internal app (antivirus latar belakang); proses lain tidak dimatikan.

## Saklar Panel Manager
Dasbor → kartu **Panel Manager** → "Aktifkan Panel Manager" (`PanelSettings.panelEnabled`, default ON).
OFF = `GamePanelController` melepas semua overlay (edge swipe, panel, HUD, bubble) dan tidak memasangnya lagi saat game terdeteksi.
Berlaku langsung walau game sedang berjalan; ON lagi → panel dipasang ulang untuk game yang sedang aktif.

## Optimasi performa (nyata, tanpa root)
Semua lewat Shizuku, diverifikasi baca-ulang, dipulihkan saat boost OFF / keluar game (backup di SharedPreferences,
jadi tetap dipulihkan walau app mati di tengah sesi).
- **Data Saver Game** (`cmd netpolicy`) — data latar aplikasi lain diblokir; game + app ini di-whitelist. Toggle di Dasbor.
- **Game Mode PERFORMANCE** (`cmd game mode performance <pkg>`, Android 12+) — efek tergantung dukungan game/OEM.
- **Tutup proses cache** (`am kill-all`) — aman; menggantikan `am force-stop` massal yang bisa mematikan keyboard/Shizuku/mapper.
- **FF Mouse** — Mode Mobilador (pointer_speed maks, long-press cepat) kini deterministik; `show_touches`/`pointer_location` dimatikan.
- Refresh rate, animasi 0, `disable_window_blurs`, stop scan BLE/Wi-Fi, `low_power_trigger_level 0`, `max_cached_processes`.

Dibuang karena TIDAK berefek: `debug.hwui.*`, `debug.sf.*`, `debug.gl.msaa` (system property, bukan Settings.Global),
`wifi_watchdog_on`, `wifi_scan_interval_ms`, `wifi_power_save`, `wifi_low_latency_mode`, `wifi_bt_coexistence`.
Dibuang karena berisiko: `bluetooth_disabled_profiles`. Toggle MSAA 4x dihapus.
Catatan jujur: governor CPU (`/sys/.../scaling_governor`) butuh root; tanpa root dilewati otomatis (sudah ada di kode).

## Tampilan (v2)
- Semua emoji di UI diganti ikon SVG/vektor (`design/icons`, `res/drawable/ic_*.xml`, `ui/AppIcons.kt`).
- Logo baru: cincin gauge + petir; adaptive icon + monochrome (Android 13+), fallback Android 7 di `mipmap-anydpi`.
- Palet baru grafit + satu aksen ember (`ui/theme/Color.kt`, `Pal` di `gamepanel/Ui.kt`); tanpa glow/gradien neon.
- Judul pakai sans-serif-condensed sistem, label tanpa monospace & tanpa huruf kapital semua.
- Pilihan tema panel: Api (default), Hijau, Emas, Biru (kunci tersimpan lama tetap kompatibel).
