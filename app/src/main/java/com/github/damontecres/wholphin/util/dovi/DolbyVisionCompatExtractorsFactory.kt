@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.util.dovi

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.TrackOutput
import timber.log.Timber
import java.io.EOFException
import java.nio.ByteBuffer

private const val INITIAL_SAMPLE_SIZE = 512 * 1024
private const val TRANSFER_SIZE = 64 * 1024

/**
 * Spare room for libdovi to work in: a converted RPU can come out a few bytes longer than the one
 * it replaces, and it is written into the access unit in place.
 */
private const val CONVERSION_HEADROOM = 16 * 1024

/**
 * Converts Dolby Vision profile 7 video to profile 8.1 as it comes out of the extractor, so that
 * devices which only decode single layer Dolby Vision play it as Dolby Vision instead of falling
 * back to the HDR10 base layer.
 *
 * Two edits make up the conversion, and both are needed. The RPU, the NAL unit of type 62, is
 * rewritten into its profile 8.1 form by libdovi, and the enhancement layer, the NAL units of type
 * 63, is dropped, because a profile 8 decoder handed a two layer stream still decodes only the base
 * layer. On top of that the codec string of the track is rewritten from `dvhe.07` to `dvhe.08`,
 * which is what decides which decoder media3 picks and which profile it configures it with.
 *
 * This sits at the extractor rather than at the decoder because the codec string has to be right
 * before a decoder is chosen. Nothing is re-encoded, and the server does no work.
 *
 * The enhancement layer of a dual layer Matroska remux can also live in block additions rather than
 * in the access units. That case is detected and reported, and the stream is then left alone rather
 * than half converted.
 */
class DolbyVisionCompatExtractorsFactory(
    delegate: ExtractorsFactory,
    private val createConverter: () -> DoviRpuConverter?,
) : ForwardingExtractorsFactory(delegate) {
    override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(super.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { DoviCompatExtractor(extractors[it], createConverter) }
}

private class DoviCompatExtractor(
    delegate: Extractor,
    private val createConverter: () -> DoviRpuConverter?,
) : ForwardingExtractor(delegate) {
    private var doviOutput: DoviCompatExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val wrapped = DoviCompatExtractorOutput(output, createConverter)
        doviOutput = wrapped
        super.init(wrapped)
    }

    override fun seek(
        position: Long,
        timeUs: Long,
    ) {
        doviOutput?.onSeek()
        super.seek(position, timeUs)
    }

    override fun release() {
        doviOutput?.onRelease()
        super.release()
    }
}

internal class DoviCompatExtractorOutput(
    delegate: ExtractorOutput,
    private val createConverter: () -> DoviRpuConverter?,
) : ForwardingExtractorOutput(delegate) {
    private val videoTracks = mutableMapOf<Long, DoviCompatTrackOutput>()

    override fun track(
        id: Int,
        type: Int,
    ): TrackOutput {
        val delegate = super.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO) return delegate
        // Extractors are allowed to ask for the same track more than once and expect the same
        // output back, which has to hold for the wrapper too since it carries per track state.
        val key = (id.toLong() shl 32) or (type.toLong() and 0xFFFFFFFFL)
        return videoTracks.getOrPut(key) { DoviCompatTrackOutput(delegate, createConverter) }
    }

    fun onSeek() = videoTracks.values.forEach { it.onSeek() }

    fun onRelease() = videoTracks.values.forEach { it.onRelease() }
}

/**
 * Assembles each access unit of a Dolby Vision track, converts it, and hands the result to the
 * real track output. Tracks which are not profile 7 Dolby Vision are forwarded untouched.
 *
 * [androidx.media3.extractor.ForwardingTrackOutput] is not usable here: its convenience
 * `sampleData` overloads forward to the delegate rather than to itself, and those are the ones the
 * extractors call, so a subclass would never see the sample data.
 */
internal class DoviCompatTrackOutput(
    private val delegate: TrackOutput,
    private val createConverter: () -> DoviRpuConverter?,
) : TrackOutput {
    private enum class State {
        /** Not a profile 7 Dolby Vision track, or no libdovi: everything is forwarded as it is. */
        INACTIVE,

        /** A profile 7 track whose first access unit has not been looked at yet. */
        UNDECIDED,

        /** Converting the RPU and dropping the enhancement layer. */
        CONVERTING,

        /** A profile 7 track which cannot be converted; access units are forwarded unchanged. */
        PASSTHROUGH,
    }

    private var state = State.INACTIVE
    private var converter: DoviRpuConverter? = null

    /** The access unit being assembled. Direct, because libdovi addresses the buffer itself. */
    private var sample: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_SAMPLE_SIZE)
    private var buffered = 0

    /** Scratch for moving bytes into and out of [sample]. */
    private var transfer = ByteArray(TRANSFER_SIZE)

    /** Bytes of the next access unit which arrived before the current one was announced. */
    private var trailing = ByteArray(0)

    /** The finished access unit, in the form the delegate takes it. */
    private var finished = ByteArray(INITIAL_SAMPLE_SIZE)
    private val finishedWrapper = ParsableByteArray()

    private var codecsBefore: String? = null
    private var codecsAfter: String? = null
    private var samples = 0L
    private var rpusConverted = 0L
    private var rpusFailed = 0L
    private var elBytesDropped = 0L
    private var bytesIn = 0L
    private var bytesOut = 0L

    override fun format(format: Format) {
        val profile8Codecs =
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                doviProfile7ToProfile8(format.codecs)
            } else {
                null
            }
        if (profile8Codecs == null) {
            state = State.INACTIVE
            delegate.format(format)
            return
        }
        val rpuConverter = converter ?: createConverter().also { converter = it }
        if (rpuConverter == null) {
            state = State.INACTIVE
            delegate.format(format)
            return
        }
        state = State.UNDECIDED
        buffered = 0
        codecsBefore = format.codecs
        codecsAfter = profile8Codecs
        Timber.i(
            "Dolby Vision profile 7 video track, rewriting the codec string %s to %s",
            format.codecs,
            profile8Codecs,
        )
        delegate.format(format.buildUpon().setCodecs(profile8Codecs).build())
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (state == State.INACTIVE || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        if (transfer.size < length) transfer = ByteArray(length)
        val read = input.read(transfer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        append(transfer, 0, read)
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int,
    ) {
        if (state == State.INACTIVE || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        append(data.data, data.position, length)
        data.skipBytes(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (state == State.INACTIVE) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        // The access unit is the buffered bytes up to `offset` from the end. Anything else, and an
        // encrypted sample, whose size is not ours to change, is handed over as it came in.
        val trailingBytes = offset
        if (cryptoData != null || trailingBytes < 0 || buffered - trailingBytes != size) {
            passThrough(timeUs, flags, size, offset, cryptoData)
            return
        }
        if (trailingBytes > 0) {
            // Converting can move the tail of the access unit, so hold the bytes of the next one.
            if (trailing.size < trailingBytes) trailing = ByteArray(trailingBytes)
            read(size, trailing, trailingBytes)
        }
        samples++
        bytesIn += size
        val length = transform(size)
        bytesOut += length
        finishedWrapper.reset(finished, length)
        delegate.sampleData(finishedWrapper, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
        delegate.sampleMetadata(timeUs, flags, length, 0, cryptoData)
        buffered = 0
        if (trailingBytes > 0) append(trailing, 0, trailingBytes)
    }

    fun onSeek() {
        buffered = 0
    }

    fun onRelease() {
        if (state == State.INACTIVE || samples == 0L) return
        Timber.i(
            "Dolby Vision profile 7 track finished as %s: codecs %s to %s, %d access units, " +
                "%d RPUs converted, %d failed, %d enhancement layer bytes dropped, %d bytes in, %d bytes out",
            state,
            codecsBefore,
            codecsAfter,
            samples,
            rpusConverted,
            rpusFailed,
            elBytesDropped,
            bytesIn,
            bytesOut,
        )
    }

    /**
     * Converts the assembled access unit and leaves the result in [finished], returning its length.
     */
    private fun transform(size: Int): Int {
        if (state == State.UNDECIDED) decide(size)
        var length = size
        if (state == State.CONVERTING) {
            ensureSampleCapacity(size + CONVERSION_HEADROOM)
            val convertedSize = converter?.convertToProfile8(sample, size) ?: -1
            if (convertedSize in 1..sample.capacity()) {
                length = convertedSize
                rpusConverted++
            } else {
                rpusFailed++
                if (rpusFailed == 1L) {
                    Timber.w("libdovi could not convert an RPU, that access unit is left as it is")
                }
            }
        }
        read(0, finishedOfAtLeast(length), length)
        if (state == State.CONVERTING) {
            val dropped = dropNalUnits(finished, length) { it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER }
            elBytesDropped += length - dropped
            length = dropped
        }
        return length
    }

    /**
     * Looks at the first access unit and decides whether the conversion can go ahead, since the
     * codec string was already rewritten on the strength of what the container declared.
     */
    private fun decide(size: Int) {
        read(0, finishedOfAtLeast(size), size)
        val counts = nalUnitTypeCounts(finished, size)
        val info = converter?.frameInfo(sample, size)
        state =
            when {
                counts[NAL_UNIT_TYPE_RPU] == 0 -> {
                    Timber.e(
                        "No Dolby Vision RPU in the first access unit, NAL units %s. The RPU and the " +
                            "enhancement layer are probably carried in Matroska block additions, which is " +
                            "not supported, so the video is left unconverted",
                        describeNalUnitCounts(counts),
                    )
                    State.PASSTHROUGH
                }

                info == null -> {
                    Timber.e(
                        "The Dolby Vision RPU of the first access unit could not be parsed, NAL units %s, " +
                            "so the video is left unconverted",
                        describeNalUnitCounts(counts),
                    )
                    State.PASSTHROUGH
                }

                info.profile != 7 -> {
                    Timber.w(
                        "The container declares Dolby Vision profile 7 but the RPU says profile %d, " +
                            "so the video is left unconverted",
                        info.profile,
                    )
                    State.PASSTHROUGH
                }

                else -> {
                    Timber.i(
                        "Converting Dolby Vision profile 7 %s to profile 8.1, first access unit NAL units %s",
                        info.elType,
                        describeNalUnitCounts(counts),
                    )
                    State.CONVERTING
                }
            }
    }

    private fun passThrough(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (buffered > 0) {
            read(0, finishedOfAtLeast(buffered), buffered)
            finishedWrapper.reset(finished, buffered)
            delegate.sampleData(finishedWrapper, buffered, TrackOutput.SAMPLE_DATA_PART_MAIN)
            buffered = 0
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun finishedOfAtLeast(length: Int): ByteArray {
        if (finished.size < length) finished = ByteArray(length + length / 2)
        return finished
    }

    private fun append(
        src: ByteArray,
        offset: Int,
        length: Int,
    ) {
        ensureSampleCapacity(buffered + length)
        sample.limit(sample.capacity())
        sample.position(buffered)
        sample.put(src, offset, length)
        buffered += length
    }

    private fun read(
        from: Int,
        into: ByteArray,
        length: Int,
    ) {
        val reader = sample.duplicate()
        reader.limit(reader.capacity())
        reader.position(from)
        reader.limit(from + length)
        reader.get(into, 0, length)
    }

    private fun ensureSampleCapacity(required: Int) {
        if (sample.capacity() >= required) return
        val bigger = ByteBuffer.allocateDirect(required + required / 2)
        sample.limit(buffered)
        sample.position(0)
        bigger.put(sample)
        sample = bigger
    }
}
