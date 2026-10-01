# Aset desain GameBoost

- `logo.svg` — logo aplikasi (cincin gauge + petir). Versi Android: `app/src/main/res/drawable/ic_gameboost_pro_*.xml`
  (adaptive icon + monochrome untuk tema ikon Android 13+) dan `res/mipmap-anydpi/` untuk Android 7.
- `icons/*.svg` — 72 ikon monoline (grid 24, garis 1.9, ujung bulat). Path yang sama dipakai di:
  - `res/drawable/ic_*.xml` (VectorDrawable, untuk panel overlay & notifikasi)
  - `ui/AppIcons.kt` (ImageVector, untuk Jetpack Compose)

Warna: grafit `#0F1318`, kartu `#161B22`, aksen ember `#FF7A1A`, ok `#4CC38A`, peringatan `#E9B949`, error `#E5534B`.
Tidak ada emoji di UI; teks lama yang masih mengandung emoji dibersihkan lewat `String.stripEmoji()`.
