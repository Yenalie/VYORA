# Third-party notices

VYORA is distributed under GPL-3.0 (see `LICENSE`); its source must be made available to anyone you
give the app to. Parts of the user interface are vendored from, or adapted from, the open-source
projects below. Original copyright headers are kept at the top of each vendored file.

| Component | Where in VYORA | Source | License |
|---|---|---|---|
| Backdrop engine (blur, lens, highlight, shaders) | `ui/glass/backdrop/`, `ui/glass/interaction/` | Kyant0/backdrop v2.0.0 — https://github.com/Kyant0/backdrop | Apache-2.0 |
| Continuous-corner shapes | `ui/glass/shapes/` | Kyant0/Capsule — https://github.com/Kyant0/Capsule | Apache-2.0 |
| Floating tab bar | `ui/glass/floatingtabbar/` | elyesmansour/compose-floating-tab-bar v1.0.1 — https://github.com/elyesmansour/compose-floating-tab-bar | Apache-2.0 |
| Lossless output detection | `data/local/LosslessOutput.kt` | Ported from BitChord — https://github.com/kushagrasinghx/BitChord (`playback/audio/LosslessOutput.kt`) | GPL-3.0 |
| Word-synced lyrics approach (BetterLyrics TTML, then LRCLIB) | `data/lyrics/` | Approach from YumaPlayer / ArchiveTune — https://github.com/MuwMx/YumaPlayer (re-implemented, no code copied) | GPL-3.0 |
| Liquid-glass modifier, `VyoraNavBar.kt`, `VyoraMiniAccessory.kt` | `ui/glass/GlassEffect.kt`, `ui/glass/VyoraNavBar.kt`, `ui/player/VyoraMiniAccessory.kt` | Adapted from Convx — https://github.com/cosmictaserdev-creator/Convx | GPL-3.0 |

## Libraries

| Library | License |
|---|---|
| AndroidX / Jetpack Compose / Media3 | Apache-2.0 |
| NewPipe Extractor — https://github.com/TeamNewPipe/NewPipeExtractor | GPL-3.0 |
| OkHttp | Apache-2.0 |
| Coil | Apache-2.0 |
| AndroidX ProfileInstaller | Apache-2.0 |
| Lyrics: BetterLyrics — https://lyrics-api.boidu.dev | Public API |
| Lyrics: LRCLIB — https://lrclib.net | Public API |

## Services and protocols

| Service | Used for |
|---|---|
| Subsonic API — https://www.subsonic.org/pages/api.jsp (Navidrome, Gonic, Airsonic, Ampache…) | Streaming original lossless files from your own server |
| Last.fm — https://www.last.fm/api, ListenBrainz — https://listenbrainz.org | Optional scrobbling (your own API key / token) |
| Spotify public embed pages | Reading public playlist track lists for import (no account) |

VYORA does not include any resolver for paid lossless catalogues (Qobuz, Tidal, Deezer, Amazon Music):
streaming those without a subscription is not licensed, so the lossless sources are your device and your own server.
