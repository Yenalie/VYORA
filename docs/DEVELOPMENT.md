# Panduan Developer

## Build

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # app/build/outputs/apk/release/ (R8, ditandatangani)
./gradlew testDebugUnitTest  # unit test
```

Butuh JDK 17 dan Android SDK 36. Lokasi SDK diisi Android Studio di `local.properties`.

## Signing

`app/keystore/release-key.jks` + `app/keystore.properties` adalah **kunci UJI COBA** untuk
build percobaan, bukan untuk rilis Play Store. Untuk rilis sungguhan buat keystore baru dan
simpan di luar repo (atau pakai variabel `VYORA_KEYSTORE`, `VYORA_KEYSTORE_PASSWORD`,
`VYORA_KEY_ALIAS`, `VYORA_KEY_PASSWORD`). Keduanya ada di `.gitignore`, jadi tidak ikut
ke GitHub. Tanpa keystore, `assembleRelease` tetap jalan tapi APK-nya belum ditandatangani;
pakai `assembleDebug` untuk mencoba.

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

Skema Room tiap versi diekspor ke `app/schemas/`. Menaikkan versi database wajib disertai
`Migration` (jangan pakai destructive fallback, ini library pengguna). Data dari VYORA 1.x
(DataStore, SharedPreferences, `queue.json`) dipindah otomatis sekali saat pertama dibuka.

## Akun YouTube Music

Pengaturan > Akun. Login lewat halaman Google di WebView (cookie sesi diambil setelah
mendarat di music.youtube.com), atau tempel header Cookie dari browser. Yang disinkronkan
dua arah: lagu yang disukai, playlist milik sendiri (buat, ganti nama, hapus, tambah/hapus
lagu), playlist orang lain dan album yang disimpan. Riwayat putar tidak dikirim. Perubahan
saat offline diantre dan dikirim saat online. Sinkron otomatis saat aplikasi dibuka jika
terakhir lebih dari 30 menit lalu.

