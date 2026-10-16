# Panduan Developer

## Build

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # app/build/outputs/apk/release/ (R8, ditandatangani)
./gradlew testDebugUnitTest  # unit test
./gradlew lintDebug          # lint; gagal kalau ada error baru di luar app/lint-baseline.xml
```

Butuh JDK 17 dan Android SDK 36 (AGP 9.4, Gradle 9.8, Kotlin bawaan AGP). Lokasi SDK diisi
Android Studio di `local.properties`. Kalau sengaja menerima peringatan lint lama, perbarui
baseline dengan `./gradlew updateLintBaseline`.

## Signing

`app/keystore/release-key.jks` + `app/keystore.properties` adalah **kunci UJI COBA** untuk
build percobaan, bukan untuk rilis Play Store. Untuk rilis sungguhan buat keystore baru dan
simpan di luar repo (atau pakai variabel `VYORA_KEYSTORE`, `VYORA_KEYSTORE_PASSWORD`,
`VYORA_KEY_ALIAS`, `VYORA_KEY_PASSWORD`). Keduanya ada di `.gitignore`, jadi tidak ikut
ke GitHub. Tanpa keystore, `assembleRelease` tetap jalan tapi APK-nya belum ditandatangani;
pakai `assembleDebug` untuk mencoba.

## Rilis & pembaruan otomatis

Rilis dibuat oleh GitHub Actions (`.github/workflows/release.yml`):

1. Naikkan `versionCode` dan `versionName` di `app/build.gradle`.
2. Tulis catatan versi di `CHANGELOG.md` dengan judul `## <versionName>`.
3. Commit, lalu buat dan push tag: `git tag v1.5.0 && git push origin v1.5.0`.

Workflow mengecek tag cocok dengan `versionName`, menjalankan unit test, build APK yang
ditandatangani, lalu membuat GitHub Release berisi `VYORA-<versi>-release.apk`, file `.sha256`,
dan catatan dari CHANGELOG. Kunci diambil dari secrets repo `VYORA_KEYSTORE_BASE64`,
`VYORA_KEYSTORE_PASSWORD`, `VYORA_KEY_ALIAS`, `VYORA_KEY_PASSWORD`.

**Selalu tanda tangani dengan kunci yang sama.** Android menolak memasang APK dengan kunci
lain di atas versi yang sudah terpasang, dan updater di aplikasi juga mengecek hal ini.

Di aplikasi, `data/update/` menangani pembaruan:

- `ReleaseApi` membaca `api.github.com/repos/Yenalie/VYORA/releases/latest` (draft dan
  pre-release diabaikan) dan memilih aset `.apk`.
- `UpdateManager` membandingkan tag dengan `BuildConfig.VERSION_NAME`, mengunduh APK ke
  `cache/updates/`, memastikan package, versionCode, dan sertifikatnya cocok, lalu membuka
  installer sistem lewat `FileProvider`.
- `UpdateWorker` (WorkManager) memeriksa tiap 12 jam saat online dan menampilkan notifikasi
  sekali per versi. Pemeriksaan juga jalan saat aplikasi dibuka (paling sering tiap 3 jam).
- Pengguna bisa memeriksa manual atau mematikan pemeriksaan otomatis di Pengaturan > Pembaruan.

## Struktur

```
com.vyora.music
├── MainActivity.kt, VyoraApp.kt   Activity (tema, bahasa) dan Application (cache gambar Coil)
├── core/                          utilitas lintas modul (LocaleHelper)
├── data/
│   ├── youtube/                   NewPipe: pencarian, playlist, radio, pemilihan stream
│   │   ├── YouTubeClient          pencarian & halaman
│   │   ├── StreamResolver         ekstraksi stream + cache per video + header per klien
│   │   ├── StreamSelector         aturan memilih audio/video (tanpa DRC, track asli)
│   │   ├── SimpleItem             model + format JSON bersama
│   │   └── YouTubeConfig          semua konstanta YouTube
│   ├── db/                        Room: entity, DAO, VyoraDatabase, impor data 1.x
│   ├── update/                    pembaruan aplikasi dari GitHub Releases
│   │   ├── ReleaseApi, AppVersion  baca rilis terbaru + bandingkan versi
│   │   ├── UpdateManager          cek, unduh, verifikasi, pasang, notifikasi
│   │   └── UpdateWorker           pemeriksaan berkala di latar belakang
│   ├── account/                   akun YouTube Music + sinkronisasi
│   │   ├── AccountStore           sesi login (dienkripsi Android Keystore)
│   │   ├── YouTubeMusicApi        request InnerTube WEB_REMIX yang sudah login
│   │   ├── InnerTubeParser        pembaca respons (diuji dengan data asli)
│   │   └── SyncEngine             push perubahan + tarik favorit/playlist/album
│   ├── library/                   LibraryManager & SearchHistory (di atas Room), home feed
│   ├── lyrics/                    lirik LRCLIB
│   └── settings/                  AppSettings + SettingsRepository
├── playback/
│   ├── PlayerManager, MediaItems  sisi UI (MediaController)
│   ├── service/                   PlaybackService, Crossfader, QueueStore, urutan shuffle
│   ├── source/                    DataSource YouTube + MediaCache (cache lagu di disk)
│   ├── audio/                     equalizer 10 band (DynamicsProcessing + limiter)
│   └── video/                     pemutar video artwork (tanpa suara, sinkron lagu)
└── ui/
    ├── VyoraAppRoot.kt            navigasi, bilah tab, mini player, sheet pemutar
    ├── screens/                   Home, Search, Library, Artist, Playlist, Settings
    ├── player/                    Now Playing, mini player, video artwork
    ├── components/, design/       komponen bersama, glass ringan, ambient
    ├── glass/                     mesin Liquid Glass (vendored, lihat NOTICE.md)
    └── theme/                     warna, palet, tipografi
```

## Penyimpanan

| Data | Lokasi |
|---|---|
| Favorit, riwayat, playlist, album tersimpan, riwayat pencarian | Room `databases/vyora.db` |
| Antrean terakhir | Room, tabel `queue` + `queue_state` |
| Perubahan yang belum terkirim ke YouTube | Room, tabel `sync_actions` |
| Sesi akun YouTube | SharedPreferences `vyora_account` (AES-GCM, kunci di Android Keystore, tidak ikut backup) |
| Pengaturan | SharedPreferences `vyora_settings` (ditulis tertunda 300 ms) |
| Cache lagu & video artwork | `cache/media_cache` (LRU, ukuran diatur di Pengaturan) |
| Cache gambar | `cache/image_cache` (LRU 80 MB) |
| Lagu yang diunduh | `files/offline_audio` (tanpa eviction, indeks di `exoplayer_internal.db`) |
| Artwork lagu yang diunduh | `files/offline_art` |
| Sesi Discord | SharedPreferences `vyora_discord` (AES-GCM, kunci di Android Keystore, tidak ikut backup) |
| Statistik Rekap | Room, tabel `play_events` (skema v2) |
| Data WebView login (cookie) | `app_webview/`, dihapus setelah login dan tidak ikut backup |

Skema Room tiap versi diekspor ke `app/schemas/`. Langkah mengubah skema:

1. Ubah entity, naikkan `VyoraDatabase.VERSION`.
2. Perubahan aditif (tabel/kolom baru dengan default): tambah `AutoMigration(from = N, to = N + 1)`.
   Rename/hapus: `AutoMigration` dengan `AutoMigrationSpec`. Selain itu: tulis `Migration` di `Migrations.ALL`.
3. Build sekali supaya KSP mengekspor `app/schemas/.../<N+1>.json`, lalu commit file itu.
4. Tambah kasus di `MigrationTest` (Robolectric + `MigrationTestHelper`).

Jangan pakai destructive fallback, ini library pengguna. Data dari VYORA 1.x
(DataStore, SharedPreferences, `queue.json`) dipindah otomatis sekali saat pertama dibuka.

## Akun YouTube Music

Pengaturan > Akun. Login lewat halaman Google di WebView (cookie sesi diambil setelah
mendarat di music.youtube.com), atau tempel header Cookie dari browser. Yang disinkronkan
dua arah: lagu yang disukai, playlist milik sendiri (buat, ganti nama, hapus, tambah/hapus
lagu), playlist orang lain dan album yang disimpan. Riwayat putar tidak dikirim. Perubahan
saat offline diantre dan dikirim saat online. Sinkron otomatis saat aplikasi dibuka jika
terakhir lebih dari 30 menit lalu.

## Unduhan, Cast, dan Discord

- Unduhan memakai Media3 `DownloadManager` (`data/download/Downloads.kt`) dengan foreground service
  `VyoraDownloadService`. Audio offline selalu kualitas tinggi dan dibaca dulu oleh `YouTubeDataSource`
  sebelum ke jaringan.
- Cast memakai Media3 `CastPlayer` dengan receiver bawaan Google. Audio dialirkan lewat `CastProxy`,
  server HTTP lokal ber-token di jaringan Wi-Fi yang sama, karena URL stream YouTube terikat ke perangkat.
- Discord Rich Presence (`data/discord/`) terhubung ke gateway Discord dengan sesi akun pengguna dan
  mengirim aktivitas tipe Listening. Ini bukan API resmi, jadi tampil hanya teks tanpa artwork.
- Kartu lirik digambar di `ui/player/LyricsCard.kt` (1080x1920) lalu disimpan ke `Pictures/VYORA`
  atau dibagikan lewat FileProvider `${applicationId}.files`.
