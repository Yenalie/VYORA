<div align="center">

<img src="playstore-icon.png" width="120" alt="VYORA" />

# VYORA

**Pemutar musik Android yang bersih, cepat, dan cantik, dengan desain bergaya iOS 26 Liquid Glass.**

Dengarkan jutaan lagu dari katalog YouTube Music, simpan favoritmu, dan sinkronkan dengan akun YouTube Music kamu.

[![Download APK](https://img.shields.io/badge/Download-APK%201.4.1-FA2D48?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Yenalie/VYORA/raw/main/release/VYORA-1.4.1-release.apk)
[![Release](https://img.shields.io/github/v/release/Yenalie/VYORA?style=for-the-badge&color=0A84FF)](https://github.com/Yenalie/VYORA/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-30D158?style=for-the-badge)](LICENSE)

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-FF6F00)

</div>

---

## Tampilan

<p align="center">
  <img src="docs/screenshots/home.png" width="23%" alt="Home" />
  <img src="docs/screenshots/now_playing.png" width="23%" alt="Now Playing" />
  <img src="docs/screenshots/queue.png" width="23%" alt="Antrean" />
  <img src="docs/screenshots/player_menu.png" width="23%" alt="Menu lagu" />
</p>
<p align="center">
  <img src="docs/screenshots/library.png" width="23%" alt="Library" />
  <img src="docs/screenshots/search.png" width="23%" alt="Cari" />
  <img src="docs/screenshots/artist.png" width="23%" alt="Artis" />
  <img src="docs/screenshots/account.png" width="23%" alt="Akun YouTube Music" />
</p>
<p align="center">
  <img src="docs/screenshots/settings.png" width="23%" alt="Pengaturan" />
  <img src="docs/screenshots/settings_2.png" width="23%" alt="Pengaturan audio" />
</p>

## Fitur

### Musik
- Cari lagu, album, artis, dan playlist dari katalog YouTube Music
- Lirik yang berjalan mengikuti lagu
- Autoplay: lagu serupa terus diputar setelah antrean habis
- Antrean lengkap: putar berikutnya, putar terakhir, acak, ulangi
- Sleep timer, crossfade antar lagu, dan lewati bagian hening

### Tampilan
- Desain bergaya iOS 26 dengan efek **Liquid Glass** yang bisa diatur sesukamu
- **Latar ambient** yang ikut warna lagu yang sedang diputar
- **Now Playing** dengan latar artwork yang bergerak pelan
- **Video musik** langsung tampil sebagai artwork, dan warna latarnya ikut warna video
- Tema terang, gelap, atau ikut sistem, dengan 8 pilihan warna aksen

### Suara
- Equalizer 10 band dengan berbagai preset
- Bass, surround, dan loudness
- Limiter bawaan agar suara tetap jernih, tidak pecah atau teredam
- Tiga pilihan kualitas streaming, termasuk mode hemat data

### Library & Akun
- Simpan lagu favorit, buat playlist, dan simpan album
- Masuk dengan akun YouTube Music untuk sinkron dua arah: lagu yang disukai, playlist, dan album
- Perubahan saat offline otomatis dikirim begitu online
- Sesi akun disimpan terenkripsi di perangkat

### Lainnya
- Lagu yang pernah diputar disimpan di cache, jadi lebih hemat kuota
- Tersedia dalam 5 bahasa: Indonesia, Inggris, Melayu, Spanyol, dan Jepang

## Download

Unduh APK terbaru di halaman [Releases](https://github.com/Yenalie/VYORA/releases) atau
langsung [di sini](https://github.com/Yenalie/VYORA/raw/main/release/VYORA-1.4.1-release.apk).

**Kebutuhan:** Android 10 atau lebih baru. Efek Liquid Glass penuh butuh Android 12 ke atas.
Di perangkat yang lebih lama, tampilannya otomatis menyesuaikan.

> Build ini masih tahap uji coba dan belum tersedia di Play Store.

## Build dari source

```bash
git clone https://github.com/Yenalie/VYORA.git
cd VYORA
./gradlew assembleDebug
```

Butuh JDK 17 dan Android SDK 36. Detail struktur project, penyimpanan data, dan signing ada
di [Panduan Developer](docs/DEVELOPMENT.md).

## Dibangun dengan

- [Jetpack Compose](https://developer.android.com/jetpack/compose) dan Material 3
- [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer, MediaSession)
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [Room](https://developer.android.com/training/data-storage/room)
- [Coil](https://github.com/coil-kt/coil)
- [LRCLIB](https://lrclib.net) untuk lirik
- Efek glass dari [Kyant0/backdrop](https://github.com/Kyant0/backdrop)

## Lisensi

VYORA dirilis di bawah lisensi [GPL-3.0](LICENSE). Daftar komponen pihak ketiga ada di [NOTICE.md](NOTICE.md).

## Disclaimer

VYORA adalah proyek independen dan tidak berafiliasi dengan, didukung, atau disponsori oleh
YouTube maupun Google. Semua merek dagang adalah milik pemiliknya masing-masing.
