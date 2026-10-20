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
 * DSD audio in Sony's DSF and Philips' DSDIFF (.dff) containers. Android can't take a 1-bit stream, so
 * it's converted to 32-bit float PCM at 88.2 kHz (176.4 kHz for DSD128 and up) with a windowed-sinc
 * low-pass, the way desktop players without DoP do it. DST-compressed DSDIFF isn't supported.
 */
@UnstableApi
class DsdExtractor : Extractor {

    private enum class Container { DSF, DFF }

    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput

    private var headerRead = false
    private var container = Container.DSF
    private var channels = 0
    private var dsdRate = 0
    private var lsbFirst = true
    private var blockSize = 0
    private var dataStart = 0L
    private var dataEnd = 0L
    private var bytesPerChannel = 0L

    private lateinit var decimator: DsdDecimator
    private var inBuffer = ByteArray(0)
    private var outBuffer = ByteArray(0)
    private var pending = 0
    private var framesOut = 0L
    private var bytesInPerChannel = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(16)
        if (!input.peekFully(head, 0, 16, true)) return false
        val id = AiffExtractor.fourCc(head, 0)
        return id == "DSD " || (id == "FRM8" && AiffExtractor.fourCc(head, 12) == "DSD ")
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerRead) {
            val head = ByteArray(4)
            input.peekFully(head, 0, 4)
            if (AiffExtractor.fourCc(head, 0) == "DSD ") readDsf(input) else readDff(input)
            headerRead = true
            publish()
            return Extractor.RESULT_CONTINUE
        }
        val left = dataEnd - input.position
        if (left <= 0) return Extractor.RESULT_END_OF_INPUT
        return if (container == Container.DSF) readDsfBlock(input, left) else readDffChunk(input, left)
    }

    /** DSF: each block holds [blockSize] bytes of one channel, LSB-first, channels one after another. */
    private fun readDsfBlock(input: ExtractorInput, left: Long): Int {
        val group = blockSize * channels
        if (left < group) return Extractor.RESULT_END_OF_INPUT
        if (!input.readFully(inBuffer, 0, group, true)) return Extractor.RESULT_END_OF_INPUT
        val valid = (bytesPerChannel - bytesInPerChannel).coerceIn(0L, blockSize.toLong()).toInt()
        if (valid == 0) return Extractor.RESULT_END_OF_INPUT
        val frames = decimator.processPlanar(inBuffer, blockSize, valid, outBuffer)
        bytesInPerChannel += blockSize
        emit(frames)
        return Extractor.RESULT_CONTINUE
    }

    /** DSDIFF: channel bytes interleaved one by one, MSB-first. */
    private fun readDffChunk(input: ExtractorInput, left: Long): Int {
        val want = minOf((inBuffer.size - pending).toLong(), left).toInt()
        val n = input.read(inBuffer, pending, want)
        if (n == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        pending += n
        val step = channels * decimator.bytesPerOutput
        val usable = pending / step * step
        if (usable > 0) {
            val frames = decimator.processInterleaved(inBuffer, usable, outBuffer)
            emit(frames)
            System.arraycopy(inBuffer, usable, inBuffer, 0, pending - usable)
            pending -= usable
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun emit(frames: Int) {
        if (frames <= 0) return
        val bytes = frames * channels * 4
        track.sampleData(ParsableByteArray(outBuffer, bytes), bytes)
        track.sampleMetadata(framesOut * C.MICROS_PER_SECOND / decimator.outputRate, C.BUFFER_FLAG_KEY_FRAME, bytes, 0, null)
        framesOut += frames
    }

    private fun readDsf(input: ExtractorInput) {
        container = Container.DSF
        val dsd = ByteArray(28)
        input.readFully(dsd, 0, 28)
        val fmtHead = ByteArray(12)
        input.readFully(fmtHead, 0, 12)
        if (AiffExtractor.fourCc(fmtHead, 0) != "fmt ") throw ParserException.createForMalformedContainer("DSF without fmt", null)
        val fmtSize = le64(fmtHead, 4).toInt()
        val fmt = ByteArray(fmtSize - 12)
        input.readFully(fmt, 0, fmt.size)
        channels = le32(fmt, 12).toInt()
        dsdRate = le32(fmt, 16).toInt()
        lsbFirst = le32(fmt, 20) == 1L
        val samples = le64(fmt, 24)
        blockSize = le32(fmt, 32).toInt()
        val dataHead = ByteArray(12)
        input.readFully(dataHead, 0, 12)
        if (AiffExtractor.fourCc(dataHead, 0) != "data") throw ParserException.createForMalformedContainer("DSF without data", null)
        dataStart = input.position
        dataEnd = dataStart + le64(dataHead, 4) - 12
        bytesPerChannel = (samples + 7) / 8
        if (channels !in 1..8 || dsdRate <= 0 || blockSize <= 0) throw ParserException.createForMalformedContainer("Bad DSF header", null)
    }

    private fun readDff(input: ExtractorInput) {
        container = Container.DFF
        lsbFirst = false
        val form = ByteArray(16)
        input.readFully(form, 0, 16)
        val head = ByteArray(12)
        while (true) {
            input.readFully(head, 0, 12)
            val id = AiffExtractor.fourCc(head, 0)
            val size = be64(head, 4)
            when (id) {
                "PROP" -> {
                    val body = ByteArray(size.toInt())
                    input.readFully(body, 0, body.size)
                    parseProp(body)
                }
                "DSD " -> {
                    dataStart = input.position
                    dataEnd = dataStart + size
                    if (channels <= 0 || dsdRate <= 0) throw ParserException.createForMalformedContainer("DSDIFF without PROP", null)
                    bytesPerChannel = size / channels
                    return
                }
                "DST " -> throw ParserException.createForUnsupportedContainerFeature("DST-compressed DSDIFF")
                else -> input.skipFully((size + (size and 1L)).toInt())
            }
            if (size and 1L == 1L && id == "PROP") input.skipFully(1)
        }
    }

    private fun parseProp(b: ByteArray) {
        var at = 4 // "SND "
        while (at + 12 <= b.size) {
            val id = AiffExtractor.fourCc(b, at)
            val size = be64(b, at + 4).toInt()
            val body = at + 12
            when (id) {
                "FS  " -> dsdRate = AiffExtractor.u32(b, body).toInt()
                "CHNL" -> channels = AiffExtractor.u16(b, body)
                "CMPR" -> if (AiffExtractor.fourCc(b, body) != "DSD ") {
                    throw ParserException.createForUnsupportedContainerFeature("DST-compressed DSDIFF")
                }
            }
            at = body + size + (size and 1)
        }
    }

    private fun publish() {
        decimator = DsdDecimator(channels, dsdRate, lsbFirst)
        if (container == Container.DSF) {
            inBuffer = ByteArray(blockSize * channels)
            outBuffer = ByteArray(blockSize / decimator.bytesPerOutput * channels * 4)
        } else {
            val perChannel = 4096
            inBuffer = ByteArray(perChannel * channels)
            outBuffer = ByteArray(perChannel / decimator.bytesPerOutput * channels * 4)
        }
        track.format(
            Format.Builder()
                .setContainerMimeType(MIME_DSD)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setCodecs("dsd" + dsdRate / 44_100)
                .setChannelCount(channels)
                .setSampleRate(decimator.outputRate)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setAverageBitrate(dsdRate * channels)
                .setPeakBitrate(dsdRate * channels)
                .setMaxInputSize(outBuffer.size)
                .build()
        )
        val durationUs = bytesPerChannel * 8 * C.MICROS_PER_SECOND / dsdRate
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val byte = (timeUs * (dsdRate / 8) / C.MICROS_PER_SECOND).coerceIn(0L, (bytesPerChannel - 1).coerceAtLeast(0L))
                val aligned: Long
                val position: Long
                if (container == Container.DSF) {
                    aligned = byte / blockSize * blockSize
                    position = dataStart + aligned * channels
                } else {
                    val step = decimator.bytesPerOutput.toLong()
                    aligned = byte / step * step
                    position = dataStart + aligned * channels
                }
                return SeekMap.SeekPoints(SeekPoint(aligned * 8 * C.MICROS_PER_SECOND / dsdRate, position))
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        pending = 0
        if (position == 0L) {
            headerRead = false
            framesOut = 0
            bytesInPerChannel = 0
            return
        }
        if (!::decimator.isInitialized) return
        decimator.reset()
        val perChannel = (position - dataStart).coerceAtLeast(0) / channels
        bytesInPerChannel = perChannel
        framesOut = perChannel / decimator.bytesPerOutput
    }

    override fun release() = Unit

    companion object {
        const val MIME_DSD = "audio/x-dsd"

        private fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        private fun le64(b: ByteArray, at: Int): Long = le32(b, at) or (le32(b, at + 4) shl 32)
        private fun be64(b: ByteArray, at: Int): Long = (AiffExtractor.u32(b, at) shl 32) or AiffExtractor.u32(b, at + 4)
    }
}

/**
 * 1-bit DSD to float PCM. A Blackman-windowed sinc FIR is evaluated only at the output instants, using
 * one 256-entry table per byte of filter taps so each output sample costs a table lookup per input byte
 * in the window rather than a multiply per bit.
 */
internal class DsdDecimator(private val channels: Int, dsdRate: Int, private val lsbFirst: Boolean) {
    /** 88.2 kHz for DSD64, 176.4 kHz for DSD128 and faster. */
    val outputRate: Int = if (dsdRate >= 2 * DSD64) 176_400 else 88_200
    private val factor = (dsdRate / outputRate).coerceAtLeast(8)
    val bytesPerOutput = factor / 8
    private val groups = (minOf(factor * 32, MAX_TAPS) / 8).coerceAtLeast(16)
    private val table = FloatArray(groups * 256)
    private val rings = Array(channels) { ByteArray(groups * 2) }
    private val writeAt = IntArray(channels)
    private val phase = IntArray(channels)

    init {
        val taps = groups * 8
        val cutoff = minOf(30_000.0, outputRate * 0.36) / dsdRate
        val h = DoubleArray(taps)
        val mid = (taps - 1) / 2.0
        for (k in 0 until taps) {
            val x = k - mid
            val sinc = if (x == 0.0) 2 * cutoff else Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x)
            val w = 0.42 - 0.5 * Math.cos(2 * Math.PI * k / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * k / (taps - 1))
            h[k] = sinc * w
        }
        val sum = h.sum()
        for (g in 0 until groups) for (b in 0 until 256) {
            var acc = 0.0
            for (i in 0 until 8) {
                val bit = if (lsbFirst) (b shr i) and 1 else (b shr (7 - i)) and 1
                acc += if (bit == 1) h[g * 8 + i] else -h[g * 8 + i]
            }
            table[g * 256 + b] = (acc / sum).toFloat()
        }
        reset()
    }

    fun reset() {
        // 0x69 holds as many ones as zeros: DSD silence, so a seek doesn't start with a DC thump.
        rings.forEach { it.fill(0x69) }
        writeAt.fill(0)
        phase.fill(0)
    }

    /** Push one byte for [ch]; returns the next PCM sample when one falls due, NaN otherwise. */
    private fun push(ch: Int, byte: Byte): Float {
        val ring = rings[ch]
        val w = writeAt[ch]
        ring[w] = byte
        ring[w + groups] = byte
        writeAt[ch] = if (w + 1 == groups) 0 else w + 1
        val p = phase[ch] + 1
        if (p < bytesPerOutput) { phase[ch] = p; return Float.NaN }
        phase[ch] = 0
        // Oldest byte first: the window is ring[w+1 .. w+groups].
        var acc = 0f
        var idx = w + 1
        var t = 0
        for (g in 0 until groups) {
            acc += table[t + (ring[idx].toInt() and 0xFF)]
            idx++
            t += 256
        }
        return acc
    }

    fun processPlanar(src: ByteArray, blockSize: Int, valid: Int, dst: ByteArray): Int {
        var frames = 0
        for (ch in 0 until channels) {
            var f = 0
            val base = ch * blockSize
            for (j in 0 until valid) {
                val s = push(ch, src[base + j])
                if (!s.isNaN()) { putFloat(dst, (f * channels + ch) * 4, s); f++ }
            }
            frames = f
        }
        return frames
    }

    fun processInterleaved(src: ByteArray, bytes: Int, dst: ByteArray): Int {
        var f = 0
        var i = 0
        while (i < bytes) {
            var produced = false
            for (ch in 0 until channels) {
                val s = push(ch, src[i + ch])
                if (!s.isNaN()) { putFloat(dst, (f * channels + ch) * 4, s); produced = true }
            }
            if (produced) f++
            i += channels
        }
        return f
    }

    private fun putFloat(dst: ByteArray, at: Int, v: Float) {
        val bits = v.coerceIn(-1f, 1f).toRawBits()
        dst[at] = bits.toByte()
        dst[at + 1] = (bits shr 8).toByte()
        dst[at + 2] = (bits shr 16).toByte()
        dst[at + 3] = (bits shr 24).toByte()
    }

    private companion object {
        const val DSD64 = 2_822_400
        const val MAX_TAPS = 2048
    }
}
