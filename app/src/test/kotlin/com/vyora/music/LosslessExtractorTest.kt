package com.vyora.music

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.vyora.music.data.local.AudioHeaders
import com.vyora.music.playback.extractor.AiffExtractor
import com.vyora.music.playback.extractor.DsdExtractor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessExtractorTest {

    private class Capture : ExtractorOutput, TrackOutput {
        var format: Format? = null
        var seekMap: SeekMap? = null
        val data = ByteArrayOutputStream()
        val times = mutableListOf<Long>()
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val b = ByteArray(length)
            val n = input.read(b, 0, length)
            if (n > 0) data.write(b, 0, n)
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            this.data.write(data.data, data.position, length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times += timeUs
        }
    }

    private fun run(extractor: Extractor, file: ByteArray): Capture {
        val out = Capture()
        val source = object : DataReader {
            var at = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (at >= file.size) return C.RESULT_END_OF_INPUT
                val n = minOf(length, file.size - at)
                System.arraycopy(file, at, buffer, offset, n)
                at += n
                return n
            }
        }
        val input = DefaultExtractorInput(source, 0, file.size.toLong())
        assertTrue(extractor.sniff(input))
        input.resetPeekPosition()
        extractor.init(out)
        val holder = PositionHolder()
        var guard = 0
        while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) check(++guard < 100_000)
        return out
    }

    private fun extended80(rate: Int): ByteArray {
        // 80-bit extended: exponent 16383 + floor(log2), mantissa left-justified with explicit integer bit.
        var exp = 0
        while ((1L shl (exp + 1)) <= rate) exp++
        val mantissa = rate.toLong() shl (63 - exp)
        val b = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        b.putShort((16383 + exp).toShort())
        b.putLong(mantissa)
        return b.array()
    }

    private fun aiff24(rate: Int, frames: Int): Pair<ByteArray, IntArray> {
        val samples = IntArray(frames * 2) { i -> ((i / 2) * 997 % 8_000_000) - 4_000_000 + (i and 1) * 12345 }
        val comm = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN)
            .putShort(2).putInt(frames).putShort(24).put(extended80(rate)).array()
        val sound = ByteArrayOutputStream()
        samples.forEach { s -> sound.write(s shr 16); sound.write(s shr 8); sound.write(s) }
        val ssnd = ByteBuffer.allocate(8 + sound.size()).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).put(sound.toByteArray()).array()
        val body = ByteArrayOutputStream()
        fun chunk(id: String, data: ByteArray) {
            body.write(id.toByteArray()); body.write(ByteBuffer.allocate(4).putInt(data.size).array()); body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        chunk("COMM", comm)
        chunk("ID3 ", ByteArray(7))
        chunk("SSND", ssnd)
        val file = ByteArrayOutputStream()
        file.write("FORM".toByteArray()); file.write(ByteBuffer.allocate(4).putInt(4 + body.size()).array())
        file.write("AIFF".toByteArray()); file.write(body.toByteArray())
        return file.toByteArray() to samples
    }

    @Test fun aiff24BitIsSwappedToLittleEndianWithoutLoss() {
        val (file, samples) = aiff24(96_000, 20_000)
        val out = run(AiffExtractor(), file)
        val f = requireNotNull(out.format)
        assertEquals(96_000, f.sampleRate)
        assertEquals(2, f.channelCount)
        assertEquals(C.ENCODING_PCM_24BIT, f.pcmEncoding)
        assertEquals(20_000L * 1_000_000 / 96_000, out.seekMap!!.durationUs)
        val pcm = out.data.toByteArray()
        assertEquals(samples.size * 3, pcm.size)
        for (i in samples.indices) {
            val v = (pcm[i * 3].toInt() and 0xFF) or ((pcm[i * 3 + 1].toInt() and 0xFF) shl 8) or (pcm[i * 3 + 2].toInt() shl 16)
            assertEquals(samples[i], v)
        }
        assertEquals(0L, out.times.first())
        val parsed = requireNotNull(AudioHeaders.parseAiff(file.copyOf(AudioHeaders.PROBE_BYTES)))
        assertEquals(96_000, parsed.sampleRateHz)
        assertEquals(24, parsed.bitDepth)
    }

    @Test fun commonAiffSampleRatesDecode() {
        for (rate in intArrayOf(44_100, 48_000, 88_200, 176_400, 192_000, 352_800)) {
            assertEquals(rate.toDouble(), com.vyora.music.playback.extractor.Extended80.decode(extended80(rate), 0), 0.0)
        }
    }

    /** A 1 kHz sine at -6 dB, delta-sigma modulated to DSD64 in a DSF container. */
    private fun dsf(seconds: Double): ByteArray {
        val rate = 2_822_400
        val bits = (rate * seconds).toLong()
        val bytesPerCh = ((bits + 7) / 8).toInt()
        val block = 4096
        val blocks = (bytesPerCh + block - 1) / block
        val chData = Array(2) { ByteArray(blocks * block) }
        for (ch in 0 until 2) {
            var i1 = 0.0; var i2 = 0.0; var y = 0.0
            for (n in 0 until bits.toInt()) {
                val x = 0.5 * sin(2 * PI * 1000 * n / rate)
                i1 += x - y; i2 += i1 - y
                val bit = if (i2 >= 0) 1 else 0
                y = if (bit == 1) 1.0 else -1.0
                if (bit == 1) chData[ch][n / 8] = (chData[ch][n / 8].toInt() or (1 shl (n % 8))).toByte()
            }
        }
        val data = ByteArrayOutputStream()
        for (b in 0 until blocks) for (ch in 0 until 2) data.write(chData[ch], b * block, block)
        val le = { n: Int -> ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN) }
        val out = ByteArrayOutputStream()
        out.write("DSD ".toByteArray()); out.write(le(24).putLong(28).putLong(0).putLong(0).array())
        out.write("fmt ".toByteArray())
        out.write(le(48).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2).putInt(rate).putInt(1).putLong(bits).putInt(block).putInt(0).array())
        out.write("data".toByteArray()); out.write(le(8).putLong(12L + data.size()).array())
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    @Test fun dsfDecodesToCleanPcm() {
        val file = dsf(0.5)
        val parsed = requireNotNull(AudioHeaders.parseDsf(file.copyOf(AudioHeaders.PROBE_BYTES)))
        assertEquals(2_822_400, parsed.sampleRateHz)
        val out = run(DsdExtractor(), file)
        val f = requireNotNull(out.format)
        assertEquals(88_200, f.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, f.pcmEncoding)
        val pcm = ByteBuffer.wrap(out.data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val left = FloatArray(pcm.remaining() / 2) { pcm.get(it * 2) }
        assertEquals(44_100, left.size)
        // Skip the filter's start-up, then the peak should sit near the 0.5 the sine was modulated at...
        val steady = left.copyOfRange(2_000, left.size - 2_000)
        val peak = steady.maxOf { abs(it) }
        assertTrue("peak $peak", peak in 0.42f..0.58f)
        // ...and match a fitted 1 kHz sine closely, i.e. the modulator's noise was filtered off.
        var bestErr = Double.MAX_VALUE
        for (shift in 0 until 89) {
            var err = 0.0
            for (i in steady.indices) {
                val ref = 0.5 * sin(2 * PI * 1000 * (i + 2_000 - shift) / 88_200.0)
                err += (steady[i] - ref) * (steady[i] - ref)
            }
            bestErr = minOf(bestErr, err / steady.size)
        }
        assertTrue("rms error ${Math.sqrt(bestErr)}", Math.sqrt(bestErr) < 0.02)
        assertFalse(steady.any { it.isNaN() })
    }
}
