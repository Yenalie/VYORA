# Third-party notices

## BitChord

Portions of VYORA's lyrics timing and rendering implementation are adapted from BitChord, by its upstream contributors:

- Project: https://github.com/kushagrasinghx/BitChord
- Inspected upstream revision: `edc1e6f36febf71a118e649411d2fc21b70ead45`
- License: GNU General Public License, version 3.0 (GPL-3.0), see the upstream [`LICENSE`](https://github.com/kushagrasinghx/BitChord/blob/main/LICENSE).
- Adapted portions: the frame-driven `LyricClock`, the `rememberLyricClock` lifecycle pattern, the two-layer draw-phase lyric sweep, wrapped/RTL text clipping, lyric word/syllable span calculations, TTML word grouping, lrc.red ISRC lookup and strict result matching, BiniLyrics recording identification, LyricsPlus mirror racing and syllable timing, Unison response dispatch, KuGou hash/candidate flow, provider-priority fallback, provider response unwrapping, QQ/QRC/YRC word parsing, syllable-run grouping, and BetterLyrics Portato endpoint ordering.

These portions have been adapted to VYORA's Android playback and lyric models. VYORA's root `LICENSE` is also GPL-3.0. The remaining VYORA code and product identity are not part of the BitChord project.
