# Changelog
## 1.9.21
- Mini-card kualitas audio dan sleep timer kini transparan dengan outline tipis saja, tanpa warna fill gelap atau efek Liquid Glass; komentar penjelas terkait kartu dihapus

## 1.9.20
- Permukaan kartu kualitas audio dan sleep timer diganti menjadi kartu datar sederhana dengan fill gelap transparan dan outline tipis, tanpa efek Liquid Glass

## 1.9.19
- Timestamp lagu dikembalikan ke teks biasa di kiri/kanan seperti Apple Music; mini-card kotak kecil hanya untuk kualitas audio dan sleep timer saat aktif, dengan lebar otomatis mengikuti panjang teks

## 1.9.18
- Timestamp dan badge kualitas audio di bawah seekbar disusun ulang mengikuti referensi: timer kiri/kanan dan codec di tengah, masing-masing mini-card kotak 26dp bersudut lembut, bukan capsule/panel lebar

## 1.9.17
- Kartu timer dan kualitas audio di full player kini membungkus isi dan berukuran ringkas, bukan melebar memenuhi baris; chip codec diberi ikon audio dengan Liquid Glass yang lebih halus

## 1.9.16
- Timer berjalan/sisa dipasangkan dalam kartu Liquid Glass dengan pemisah halus; kualitas Lossless/Opus dibuat kartu yang bisa diketuk untuk detail format
- Sleep timer aktif memakai kartu ringkas seukuran, dengan batas lebar dan ellipsis aman untuk layar kecil

## 1.9.15
- Strip lirik full player dikembalikan ke baris tunggal stabil ala BitChord: tanpa panel ekstra, tanpa perubahan tinggi antarbaris, dengan chevron ikon yang lebih ringan

## 1.9.14
- Strip lirik full player dirapikan dengan permukaan rounded yang halus, ikon/teks sejajar, dan padding konsisten; chevron teks yang janggal dihapus

## 1.9.13
- Strip lirik full player kini membungkus teks panjang ke beberapa baris tanpa ellipsis; sapuan karaoke tetap mengikuti baris visual
- Pengaturan Lirik diringkas menjadi pilihan provider yang mudah dipindai dan satu keterangan fallback singkat

## 1.9.12
- Tambah halaman Pengaturan > Lirik untuk memilih sumber prioritas; provider lain tetap menjadi fallback otomatis, dan hasil tersinkron kata dapat mengungguli hasil per baris
- Tambah sakelar pratinjau lirik di bawah judul/artis full player, dengan transisi expand/fade yang ringan dan tetap mempertahankan akses ke layar lirik penuh
- Pilihan provider dipisahkan pada cache agar mengganti sumber tidak menampilkan hasil lama dari preferensi lain

## 1.9.11
- Rantai sumber lirik mengikuti prioritas BitChord: lrc.red langsung berbasis ISRC, BiniLyrics, BetterLyrics/Portato, LyricsPlus, Unison, KuGou, lalu LRCLIB
- Provider dijalankan paralel namun hasil dipilih menurut prioritas; timing kata/suku kata dipertahankan dan jawaban line-sync menjadi fallback
- Cache lirik dinaikkan versinya agar hasil lama yang disimpan tidak menghalangi provider baru

## 1.9.10
- Port lyric clock dan renderer draw-phase BitChord: sampel playback bertimestamp, koreksi drift, seek-aware, serta sweep kata/suku kata yang stabil
- Parser TTML/QRC/YRC kini mempertahankan timing suku kata dan menggabungkannya menjadi kata; BetterLyrics Portato ditambahkan sebagai fallback karaoke
- Cache lirik lawas tanpa versi diperbarui, hasil polos diberi masa simpan terbatas, dan strip player tidak lagi menampilkan teks polos seolah karaoke tersinkron
- Atribusi dan lisensi BitChord dicatat di `THIRD_PARTY_NOTICES.md`

## 1.9.9
- Optimasi sapuan karaoke: layout teks tetap statis, hanya mask highlight yang bergerak saat digambar agar animasi lebih stabil

## 1.9.8
- Menghaluskan clock dan tepi sapuan karaoke supaya highlight vokal tidak bergetar atau berkedip

## 1.9.7
- Sapuan highlight karaoke kini bergerak lebih halus karakter demi karakter mengikuti waktu vokal, sementara teks yang belum dinyanyikan tetap redup

## 1.9.6
- **Karaoke per kata di lyric strip:** kata yang sedang dinyanyikan menyala mengikuti timing vokal dari BetterLyrics atau enhanced LRC
- Bila sumber lirik tidak menyediakan timestamp kata, strip tetap mengikuti lirik per baris seperti biasa

## 1.9.5
- Port sistem lirik BitChord: timing kata/suku kata presisi, rantai provider termasuk lrc.red, pilihan provider, dan opsi strip lirik yang membungkus baris panjang
- Player dirapikan: timer lagu tetap teks biasa; kualitas audio dan sleep timer aktif berupa outline transparan yang mengikuti isi
- **Lyric strip ala BitChord:** satu baris lirik aktif tampil sederhana di bawah judul dan artis; ketuk untuk membuka panel lirik penuh
- Lirik panjang di strip membungkus ke beberapa baris, sementara pergantian lirik memakai transisi fade dan slide yang ringan
- Kartu lirik berbagi mendapat tampilan Liquid Glass baru
- Transisi player dan perpindahan mode Artwork/Lirik/Antrean terasa lebih halus

## 1.9.0
- **Login Discord baru (port dari YumaPlayer):** tidak ada lagi login WebView atau token akun. Tombol "Masuk dengan Discord" membuka halaman persetujuan resmi Discord (OAuth2 + PKCE) di Chrome / aplikasi Discord, lalu otomatis kembali ke VYORA. Token disimpan terenkripsi dan diperbarui otomatis
- **Rich Presence seperti Yuma:** judul lagu, artis, sampul album, sisa waktu, ikon jeda saat lagu dihentikan, tombol "Listen on YouTube Music" dan "Get VYORA". Bisa pilih tipe aktivitas (Listening/Playing), status (Online/Idle/Jangan ganggu), tampil saat dijeda, dan tombol. Ada pratinjau kartu profil Discord
- **Login Spotify (port dari YumaPlayer):** masuk lewat halaman login Spotify (atau tempel cookie sp_dc), lalu lihat Lagu yang Disukai dan semua playlist Spotify. Putar langsung (dicocokkan ke YouTube Music, mulai dalam beberapa detik) atau simpan sebagai playlist VYORA
- **Kecepatan & nada dipindah ke Now Playing:** tombol "1×" di baris bawah pemutar membuka pengaturan kecepatan (preset 0,5×–2×) dan nada. Tidak ada lagi di Pengaturan
- **Rapikan UI ala Apple Music:** tombol bawah Now Playing kini ikon polos dan hanya terisi saat aktif, halaman Discord/Spotify dengan kartu akun bergaya Apple, baris Spotify di Library dan Pengaturan
- Tes baru untuk PKCE, TOTP Spotify, parser GraphQL, dan payload presence

## 1.8.0
- **Server musik (lossless):** sambungkan server Subsonic milikmu sendiri (Navidrome, Gonic, Airsonic, Ampache…) lalu putar file FLAC/ALAC aslinya tanpa dikompres ulang, lengkap dengan pencarian, album, dan acak koleksi
- **Deteksi output lossless** (port dari BitChord): detail format di Now Playing kini menunjukkan output yang dipakai (kabel, USB DAC, HDMI, Bluetooth, speaker) dan apakah output itu benar-benar membawa audio lossless
- **Lirik per kata (karaoke):** sumber BetterLyrics (TTML) dan LRC enhanced, dengan LRCLIB sebagai cadangan. Lirik disimpan di database sehingga langsung muncul dan tetap ada setelah aplikasi ditutup
- **Android Auto** dan penjelajahan media: Favorit, Baru diputar, Playlist, dan Unduhan bisa dibuka dari mobil, plus pencarian suara ("putar … di VYORA")
- **Widget layar utama** Now Playing dengan sampul dan tombol kontrol
- **Scrobbling** ke Last.fm dan ListenBrainz, dengan antrean offline
- **Kecepatan dan nada pemutaran** (0,5×–2×), waktu lirik ikut menyesuaikan
- **Kualitas terpisah untuk Wi-Fi dan data seluler**
- **Impor playlist/album Spotify** publik tanpa akun Spotify
- **Cadangkan & pulihkan** pengaturan, favorit, playlist, dan riwayat Rekap ke satu file
- **Mode hemat daya** untuk Liquid Glass
- Halaman **Lisensi open source** di dalam aplikasi (teks GPL-3.0 lengkap dan daftar pihak ketiga)
- **Hubungi pembuat lewat Telegram** dari Pengaturan > Tentang
- Optimasi: satu koneksi HTTP bersama untuk seluruh aplikasi, baseline profile agar pembukaan pertama lebih cepat, video artwork berhenti saat aplikasi di latar belakang atau layar mati
- Ikon output di Now Playing kini ikon Cast, bukan ikon AirPlay
- Database versi 3 (migrasi otomatis, data lama tetap aman)

## 1.7.0
- **Lossless:** halaman baru di Library untuk memutar file FLAC, ALAC, WAV, dan AIFF dari perangkat dengan kualitas penuh, lengkap dengan filter Lossless dan Hi-Res
- Lencana kualitas di Now Playing: Lossless, Hi-Res Lossless, atau codec stream (Opus/AAC). Ketuk untuk melihat codec, bit depth, sample rate, dan bitrate
- **Rekap:** statistik mendengarkan 7 hari, 30 hari, dan sepanjang waktu, berisi total menit, lagu dan artis teratas, serta grafik harian
- Splash screen beranimasi dan transisi pembukaan aplikasi yang halus, plus perpindahan tab yang lebih rapi
- File lokal bisa di-Cast ke Chromecast
- Keamanan: cookie login tidak lagi tertinggal di WebView dan tidak ikut backup; pembaruan hanya dipasang jika tanda tangan dan checksum cocok dengan rilis resmi
- Peringatan risiko akun di pengaturan Discord
- Mesin build diperbarui (AGP 9.4, Gradle 9.8, Media3 1.11) dan database disiapkan untuk migrasi otomatis
- Perbaikan dan perapian kode

## 1.6.0
- Kartu lirik: pilih sampai 6 baris lirik, lalu jadikan gambar dengan latar artwork, warna lagu, atau gelap. Bisa disimpan ke galeri atau langsung dibagikan
- Unduh lagu untuk diputar offline, satu per satu atau semua lagu di Favorit dan playlist, dengan opsi hanya lewat Wi-Fi
- Halaman Diunduh baru di Library, lengkap dengan progres unduhan
- Putar ke Chromecast dan speaker Google Cast dari tombol output di Now Playing
- Discord Rich Presence: tampilkan lagu yang sedang diputar di profil Discord
- VYORA kini meminta izin notifikasi di Android 13 ke atas agar info pembaruan dan unduhan bisa muncul
- Perbaikan dan perapian kode

## 1.5.0
- Pembaruan otomatis di dalam aplikasi: VYORA memeriksa versi baru di GitHub saat dibuka dan dua kali sehari
- Notifikasi saat ada versi baru, ketuk untuk langsung mengunduh dan memasang
- Menu baru Pengaturan > Pembaruan untuk memeriksa manual dan mengatur pemeriksaan otomatis
- Unduhan dicek dulu sebelum dipasang, jadi hanya versi VYORA yang lebih baru dengan tanda tangan yang sama yang bisa dipasang

## 1.4.1
- Latar ambient kini tampil lebih jelas mengikuti warna lagu
- Latar Now Playing bergerak lebih halus dan terasa hidup
- Ukuran cache langsung mengikuti pengaturan
- Pengaturan tersimpan lebih andal

## 1.4.0
- Library pindah ke database baru yang lebih cepat dan rapi
- Masuk dengan akun YouTube Music dan sinkron favorit, playlist, serta album

## 1.3.0
- Kualitas suara lebih jernih, tidak lagi tiba-tiba teredam
- Video musik tampil sebagai artwork
- Tampilan dirapikan, termasuk saat Liquid Glass dimatikan
