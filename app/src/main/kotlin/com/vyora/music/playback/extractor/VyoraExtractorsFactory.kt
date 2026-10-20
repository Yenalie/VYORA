package com.vyora.music.playback.extractor

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/** Media3's own extractors plus the lossless containers it lacks: AIFF/AIFF-C and DSD (DSF, DSDIFF). */
@UnstableApi
class VyoraExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = withLossless(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        withLossless(defaults.createExtractors(uri, responseHeaders))

    // Ours go first: their sniffers only accept their own magic numbers, so nothing else is shadowed.
    private fun withLossless(base: Array<Extractor>): Array<Extractor> = arrayOf<Extractor>(AiffExtractor(), DsdExtractor()) + base
}
