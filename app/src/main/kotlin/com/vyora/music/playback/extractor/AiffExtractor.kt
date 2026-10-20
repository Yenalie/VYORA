package com.vyora.music.playback.extractor

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * AIFF and AIFF-C, which Media3 has no extractor for. The big-endian samples are swapped to the
 * little-endian PCM Android plays natively while they are read, so 24-bit, 32-bit and float files keep
 * their full resolution all the way to the hi-res sink instead of being folded to 16 bits.
 *
 * Handles uncompressed `NONE`/`twos`/`sowt`, 24/32-bit integer (`in24`, `in32`, `42ni`, `23ni`) and
 * `fl32`/`fl64` float. Compressed AIFF-C (ima4, ulaw, alaw) is reported as unsupported.
 */
@UnstableApi
class AiffExtractor : Extractor {

    private enum class Layout { S8, BE, LE, BE_FLOAT, LE_FLOAT, BE_DOUBLE }

    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput

    private var headerRead = false
    private var channels = 0
    private var sampleRate = 0
    private var bytesPerSample = 0
    private var layout = Layout.BE
    private var inFrame = 0
    private var outFrame = 0
    private var dataStart = 0L
    private var dataEnd = C.LENGTH_UNSET.toLong()
    private var totalFrames = 0L

    private var inBuffer = ByteArray(0)
    private var outBuffer = ByteArray(0)
    private var pending = 0
    private var framesOut = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(12)
        if (!input.peekFully(head, 0, 12, true)) return false
        return fourCc(head, 0) == "FORM" && fourCc(head, 8).let { it == "AIFF" || it == "AIFC" }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerRead) {
            readHeader(input)
            headerRead = true
            return Extractor.RESULT_CONTINUE
        }
        val left = if (dataEnd == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataEnd - input.position
        if (left <= 0) return Extractor.RESULT_END_OF_INPUT
        val want = minOf((inBuffer.size - pending).toLong(), left).toInt()
        val n = input.read(inBuffer, pending, want)
        if (n == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        pending += n
        val frames = pending / inFrame
        if (frames > 0) {
            val inBytes = frames * inFrame
            val outBytes = convert(inBytes)
            track.sampleData(ParsableByteArray(outBuffer, outBytes), outBytes)
            track.sampleMetadata(framesOut * C.MICROS_PER_SECOND / sampleRate, C.BUFFER_FLAG_KEY_FRAME, outBytes, 0, null)
            framesOut += frames
            System.arraycopy(inBuffer, inBytes, inBuffer, 0, pending - inBytes)
            pending -= inBytes
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readHeader(input: ExtractorInput) {
        val head = ByteArray(12)
        input.readFully(head, 0, 12)
        if (fourCc(head, 0) != "FORM") throw ParserException.createForMalformedContainer("Not an AIFF file", null)
        val aifc = fourCc(head, 8) == "AIFC"
        val chunk = ByteArray(8)
        var haveComm = false
        while (true) {
            input.readFully(chunk, 0, 8)
            val id = fourCc(chunk, 0)
            val size = u32(chunk, 4)
            when (id) {
                "COMM" -> {
                    val body = ByteArray(size.toInt())
                    input.readFully(body, 0, body.size)
                    if (size and 1L == 1L) input.skipFully(1)
                    parseComm(body, aifc)
                    haveComm = true
                }
                "SSND" -> {
                    if (!haveComm) throw ParserException.createForMalformedContainer("SSND before COMM", null)
                    val ssnd = ByteArray(8)
                    input.readFully(ssnd, 0, 8)
                    val offset = u32(ssnd, 0).toInt()
                    if (offset > 0) input.skipFully(offset)
                    dataStart = input.position
                    val soundBytes = size - 8 - offset
                    val fromFrames = totalFrames * inFrame
                    val bytes = when {
                        totalFrames > 0 && soundBytes > 0 -> minOf(fromFrames, soundBytes)
                        totalFrames > 0 -> fromFrames
                        else -> soundBytes
                    }
                    dataEnd = if (bytes > 0) dataStart + bytes else C.LENGTH_UNSET.toLong()
                    if (totalFrames <= 0 && bytes > 0) totalFrames = bytes / inFrame
                    publish()
                    return
                }
                else -> input.skipFully((size + (size and 1L)).toInt())
            }
        }
    }

    private fun parseComm(b: ByteArray, aifc: Boolean) {
        channels = u16(b, 0)
        totalFrames = u32(b, 2)
        val bits = u16(b, 6)
        sampleRate = extended80(b, 8).toInt()
        val type = if (aifc && b.size >= 22) fourCc(b, 18) else "NONE"
        bytesPerSample = (bits + 7) / 8
        layout = when (type) {
            "NONE", "twos", "in24", "in32" -> if (bytesPerSample == 1) Layout.S8 else Layout.BE
            "sowt", "42ni", "23ni" -> if (bytesPerSample == 1) Layout.S8 else Layout.LE
            "fl32", "FL32" -> { bytesPerSample = 4; Layout.BE_FLOAT }
            "fl64", "FL64" -> { bytesPerSample = 8; Layout.BE_DOUBLE }
            else -> throw ParserException.createForUnsupportedContainerFeature("AIFF-C compression $type")
        }
        if (type == "in24") bytesPerSample = 3
        if (type == "in32") bytesPerSample = 4
        if (channels <= 0 || sampleRate <= 0 || bytesPerSample !in 1..8) {
            throw ParserException.createForMalformedContainer("Bad AIFF COMM chunk", null)
        }
        inFrame = channels * bytesPerSample
        outFrame = channels * if (layout == Layout.BE_DOUBLE) 4 else bytesPerSample
    }

    private fun publish() {
        val encoding = when {
            layout == Layout.BE_FLOAT || layout == Layout.BE_DOUBLE || layout == Layout.LE_FLOAT -> C.ENCODING_PCM_FLOAT
            bytesPerSample == 1 -> C.ENCODING_PCM_8BIT
            bytesPerSample == 2 -> C.ENCODING_PCM_16BIT
            bytesPerSample == 3 -> C.ENCODING_PCM_24BIT
            bytesPerSample == 4 -> C.ENCODING_PCM_32BIT
            else -> throw ParserException.createForUnsupportedContainerFeature("AIFF ${bytesPerSample * 8}-bit")
        }
        // About a tenth of a second per sample buffer.
        val frames = (sampleRate / 10).coerceAtLeast(256)
        inBuffer = ByteArray(frames * inFrame)
        outBuffer = ByteArray(frames * outFrame)
        track.format(
            Format.Builder()
                .setContainerMimeType(MIME_AIFF)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channels)
                .setSampleRate(sampleRate)
                .setPcmEncoding(encoding)
                .setAverageBitrate(sampleRate * inFrame * 8)
                .setPeakBitrate(sampleRate * inFrame * 8)
                .setMaxInputSize(outBuffer.size)
                .build()
        )
        val durationUs = if (totalFrames > 0) totalFrames * C.MICROS_PER_SECOND / sampleRate else C.TIME_UNSET
        output.seekMap(
            if (durationUs == C.TIME_UNSET) SeekMap.Unseekable(C.TIME_UNSET, dataStart)
            else object : SeekMap {
                override fun isSeekable() = true
                override fun getDurationUs() = durationUs
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val frame = (timeUs * sampleRate / C.MICROS_PER_SECOND).coerceIn(0L, (totalFrames - 1).coerceAtLeast(0L))
                    return SeekMap.SeekPoints(SeekPoint(frame * C.MICROS_PER_SECOND / sampleRate, dataStart + frame * inFrame))
                }
            }
        )
    }

    private fun convert(inBytes: Int): Int {
        val src = inBuffer
        val dst = outBuffer
        when (layout) {
            Layout.S8 -> for (i in 0 until inBytes) dst[i] = (src[i].toInt() xor 0x80).toByte()
            Layout.LE -> System.arraycopy(src, 0, dst, 0, inBytes)
            Layout.BE, Layout.BE_FLOAT -> {
                val w = bytesPerSample
                var i = 0
                while (i < inBytes) {
                    for (k in 0 until w) dst[i + k] = src[i + w - 1 - k]
                    i += w
                }
            }
            Layout.LE_FLOAT -> System.arraycopy(src, 0, dst, 0, inBytes)
            Layout.BE_DOUBLE -> {
                var i = 0
                var o = 0
                while (i < inBytes) {
                    var bitsL = 0L
                    for (k in 0 until 8) bitsL = (bitsL shl 8) or (src[i + k].toLong() and 0xFF)
                    val f = java.lang.Double.longBitsToDouble(bitsL).toFloat().toRawBits()
                    dst[o] = f.toByte(); dst[o + 1] = (f shr 8).toByte(); dst[o + 2] = (f shr 16).toByte(); dst[o + 3] = (f shr 24).toByte()
                    i += 8
                    o += 4
                }
                return o
            }
        }
        return inBytes
    }

    override fun seek(position: Long, timeUs: Long) {
        pending = 0
        if (position == 0L) {
            headerRead = false
            framesOut = 0
        } else if (inFrame > 0) {
            framesOut = (position - dataStart).coerceAtLeast(0) / inFrame
        }
    }

    override fun release() = Unit

    companion object {
        const val MIME_AIFF = "audio/x-aiff"

        internal fun fourCc(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)
        internal fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        internal fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
        internal fun extended80(b: ByteArray, at: Int): Double = Extended80.decode(b, at)
    }
}

/** The 80-bit IEEE 754 extended float AIFF stores its sample rate in. Kept apart from the Media3 types. */
object Extended80 {
    fun decode(b: ByteArray, at: Int): Double {
        val exponent = ((b[at].toInt() and 0x7F) shl 8) or (b[at + 1].toInt() and 0xFF)
        var mantissa = 0L
        for (k in 0 until 8) mantissa = (mantissa shl 8) or (b[at + 2 + k].toLong() and 0xFF)
        if (exponent == 0 && mantissa == 0L) return 0.0
        // The mantissa is unsigned 64-bit; split it so the top bit isn't read as a sign.
        val m = (mantissa ushr 1).toDouble() * 2.0 + (mantissa and 1L).toDouble()
        val v = m * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
        return if (b[at].toInt() and 0x80 != 0) -v else v
    }
}
