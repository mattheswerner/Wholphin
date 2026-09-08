@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.test

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import com.github.damontecres.wholphin.util.dovi.DoviCompatTrackOutput
import com.github.damontecres.wholphin.util.dovi.DoviElType
import com.github.damontecres.wholphin.util.dovi.DoviFrameInfo
import com.github.damontecres.wholphin.util.dovi.DoviRpuConverter
import com.github.damontecres.wholphin.util.dovi.NAL_UNIT_TYPE_ENHANCEMENT_LAYER
import com.github.damontecres.wholphin.util.dovi.NAL_UNIT_TYPE_RPU
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private fun nalUnit(
    type: Int,
    payload: ByteArray = byteArrayOf(0x11, 0x22, 0x33),
): ByteArray = byteArrayOf(0, 0, 0, 1) + byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01) + payload

private fun accessUnit(vararg units: ByteArray): ByteArray = units.reduce { a, b -> a + b }

private fun doviFormat(codecs: String) =
    Format
        .Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs(codecs)
        .build()

/** Records what the extractor's real track output is handed. */
private class RecordingTrackOutput : TrackOutput {
    var format: Format? = null
    val samples = mutableListOf<ByteArray>()
    private val pending = ByteArrayOutputStream()

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val data = ByteArray(length)
        val read = input.read(data, 0, length)
        if (read > 0) pending.write(data, 0, read)
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int,
    ) {
        val bytes = ByteArray(length)
        data.readBytes(bytes, 0, length)
        pending.write(bytes)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        val all = pending.toByteArray()
        pending.reset()
        samples.add(all.copyOfRange(all.size - offset - size, all.size - offset))
        if (offset > 0) pending.write(all, all.size - offset, offset)
    }
}

private class FakeRpuConverter(
    private val info: DoviFrameInfo?,
    private val convert: (ByteBuffer, Int) -> Int = { _, size -> size },
) : DoviRpuConverter {
    var conversions = 0

    override fun frameInfo(
        frame: ByteBuffer,
        size: Int,
    ): DoviFrameInfo? = info

    override fun convertToProfile8(
        frame: ByteBuffer,
        size: Int,
    ): Int {
        conversions++
        return convert(frame, size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TestDoviCompatTrackOutput {
    private val profile7Info = DoviFrameInfo(profile = 7, elType = DoviElType.FEL, hasHdr10Plus = false)

    private fun writeSample(
        output: TrackOutput,
        data: ByteArray,
    ) {
        output.sampleData(ParsableByteArray(data, data.size), data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, data.size, 0, null)
    }

    @Test
    fun profile7TrackIsRelabelledAndLosesItsEnhancementLayer() {
        val delegate = RecordingTrackOutput()
        val converter = FakeRpuConverter(profile7Info)
        val output = DoviCompatTrackOutput(delegate) { converter }

        output.format(doviFormat("dvhe.07.06"))
        val sample =
            accessUnit(
                nalUnit(32),
                nalUnit(19, ByteArray(48) { it.toByte() }),
                nalUnit(NAL_UNIT_TYPE_RPU, byteArrayOf(0x25)),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER, ByteArray(96) { 0x7F }),
            )
        writeSample(output, sample)

        Assert.assertEquals("dvhe.08.06", delegate.format?.codecs)
        Assert.assertEquals(1, converter.conversions)
        val expected =
            accessUnit(
                nalUnit(32),
                nalUnit(19, ByteArray(48) { it.toByte() }),
                nalUnit(NAL_UNIT_TYPE_RPU, byteArrayOf(0x25)),
            )
        Assert.assertArrayEquals(expected, delegate.samples.single())
    }

    @Test
    fun theSizeTheConverterReportsIsWhatIsPassedOn() {
        val delegate = RecordingTrackOutput()
        // Stands in for a conversion which makes the access unit longer, as a rewritten RPU can
        val filler = nalUnit(38, byteArrayOf(0x7F.toByte()))
        val converter =
            FakeRpuConverter(profile7Info) { frame, size ->
                frame.limit(frame.capacity())
                frame.position(size)
                frame.put(filler)
                size + filler.size
            }
        val output = DoviCompatTrackOutput(delegate) { converter }

        output.format(doviFormat("dvhe.07.06"))
        writeSample(
            output,
            accessUnit(
                nalUnit(19),
                nalUnit(NAL_UNIT_TYPE_RPU),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER, ByteArray(32) { 0x3C }),
            ),
        )

        Assert.assertArrayEquals(
            accessUnit(nalUnit(19), nalUnit(NAL_UNIT_TYPE_RPU), filler),
            delegate.samples.single(),
        )
    }

    @Test
    fun anAccessUnitWithNoRpuIsLeftAsItIs() {
        val delegate = RecordingTrackOutput()
        val converter = FakeRpuConverter(profile7Info)
        val output = DoviCompatTrackOutput(delegate) { converter }

        output.format(doviFormat("dvhe.07.06"))
        // A dual layer Matroska remux carries the RPU in block additions, so the access unit has none
        val sample = accessUnit(nalUnit(32), nalUnit(19, ByteArray(64) { 0x11 }))
        writeSample(output, sample)
        writeSample(output, sample)

        Assert.assertEquals(0, converter.conversions)
        Assert.assertArrayEquals(sample, delegate.samples[0])
        Assert.assertArrayEquals(sample, delegate.samples[1])
    }

    @Test
    fun tracksWhichAreNotProfile7AreForwardedUntouched() {
        val delegate = RecordingTrackOutput()
        val output = DoviCompatTrackOutput(delegate) { throw AssertionError("libdovi must not be loaded") }

        output.format(doviFormat("dvhe.08.06"))
        val sample = accessUnit(nalUnit(19), nalUnit(NAL_UNIT_TYPE_RPU))
        writeSample(output, sample)

        Assert.assertEquals("dvhe.08.06", delegate.format?.codecs)
        Assert.assertArrayEquals(sample, delegate.samples.single())
    }

    @Test
    fun withoutLibdoviTheStreamIsNotTouchedAtAll() {
        val delegate = RecordingTrackOutput()
        val output = DoviCompatTrackOutput(delegate) { null }

        output.format(doviFormat("dvhe.07.06"))
        val sample = accessUnit(nalUnit(19), nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER))
        writeSample(output, sample)

        // Relabelling a stream nothing can convert would only mislead the decoder
        Assert.assertEquals("dvhe.07.06", delegate.format?.codecs)
        Assert.assertArrayEquals(sample, delegate.samples.single())
    }

    @Test
    fun sampleDataArrivingInPiecesIsReassembled() {
        val delegate = RecordingTrackOutput()
        val converter = FakeRpuConverter(profile7Info)
        val output = DoviCompatTrackOutput(delegate) { converter }

        output.format(doviFormat("dvhe.07.06"))
        val head = accessUnit(nalUnit(32), nalUnit(19, ByteArray(40) { it.toByte() }))
        val tail = accessUnit(nalUnit(NAL_UNIT_TYPE_RPU), nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER))
        output.sampleData(ParsableByteArray(head, head.size), head.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleData(ParsableByteArray(tail, tail.size), tail.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, head.size + tail.size, 0, null)

        Assert.assertArrayEquals(head + nalUnit(NAL_UNIT_TYPE_RPU), delegate.samples.single())
    }

    @Test
    fun bytesOfTheNextAccessUnitAreKeptForIt() {
        val delegate = RecordingTrackOutput()
        val converter = FakeRpuConverter(profile7Info)
        val output = DoviCompatTrackOutput(delegate) { converter }

        output.format(doviFormat("dvhe.07.06"))
        val first = accessUnit(nalUnit(19), nalUnit(NAL_UNIT_TYPE_RPU), nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER))
        val second = accessUnit(nalUnit(19, ByteArray(16) { 0x2A }), nalUnit(NAL_UNIT_TYPE_RPU))
        // The extractor hands over both, then announces the first with the second still to come
        output.sampleData(
            ParsableByteArray(first + second, first.size + second.size),
            first.size + second.size,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, first.size, second.size, null)
        output.sampleMetadata(1000L, C.BUFFER_FLAG_KEY_FRAME, second.size, 0, null)

        Assert.assertArrayEquals(accessUnit(nalUnit(19), nalUnit(NAL_UNIT_TYPE_RPU)), delegate.samples[0])
        Assert.assertArrayEquals(second, delegate.samples[1])
    }
}
