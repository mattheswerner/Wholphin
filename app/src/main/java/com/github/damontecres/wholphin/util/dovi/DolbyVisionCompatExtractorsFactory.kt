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
import androidx.media3.extractor.mkv.MatroskaExtractor
import timber.log.Timber
import java.io.EOFException
import java.nio.ByteBuffer

private const val INITIAL_SAMPLE_SIZE = 512 * 1024
private const val TRANSFER_SIZE = 64 * 1024

/** The Annex B start code the extractors frame every NAL unit with. */
private val NAL_START_CODE = byteArrayOf(0, 0, 0, 1)

/**
 * Spare room for libdovi to work in: a converted RPU can come out a few bytes longer than the one
 * it replaces, and it is written into the access unit in place.
 */
private const val CONVERSION_HEADROOM = 16 * 1024

/**
 * Rewrites Dolby Vision profile 7 video as it comes out of the extractor, so that a device which
 * cannot use the enhancement layer still gets a stream it plays correctly.
 *
 * In [DoviPlaybackMode.CONVERT_TO_PROFILE_8_1] three things happen, and all three are needed. The
 * RPU, the NAL unit of type 62, is rewritten to its profile 8.1 form by libdovi. The enhancement
 * layer, the NAL units of type 63, is dropped, because a profile 8 decoder handed a two layer
 * stream still decodes only the base layer. And the codec string is rewritten from `dvhe.07` to
 * `dvhe.08`, which is what decides which decoder media3 picks and which profile it configures it
 * with.
 *
 * In [DoviPlaybackMode.STRIP_TO_HEVC] the RPU and the enhancement layer are both dropped and the
 * track is presented as plain HEVC, leaving the HDR10 base layer. That needs no libdovi at all, and
 * is what a display without Dolby Vision wants.
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
    private val mode: DoviPlaybackMode,
    private val createMatroskaExtractor: (() -> Extractor)?,
    private val createConverter: () -> DoviRpuConverter?,
) : ForwardingExtractorsFactory(delegate) {
    override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(super.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) {
            // The Matroska extractor is replaced rather than wrapped, because the block additions a
            // dual layer remux keeps its enhancement layer in are read by a protected method, which
            // only a subclass can get at. The replacement keeps its place in the sniffing order.
            val extractor = extractors[it]
            val replacement =
                if (extractor is MatroskaExtractor) createMatroskaExtractor?.invoke() else null
            DoviCompatExtractor(replacement ?: extractor, mode, createConverter)
        }
}

private class DoviCompatExtractor(
    private val underlying: Extractor,
    private val mode: DoviPlaybackMode,
    private val createConverter: () -> DoviRpuConverter?,
) : ForwardingExtractor(underlying) {
    private var doviOutput: DoviCompatExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val wrapped = DoviCompatExtractorOutput(output, mode, createConverter)
        doviOutput = wrapped
        val listener = DoviBlockAdditionalListener(wrapped::onBlockAdditional)
        when (underlying) {
            is DoviMatroskaExtractor -> underlying.doviCapture.listener = listener
            is DoviAssMatroskaExtractor -> underlying.doviCapture.listener = listener
            else -> Unit
        }
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
    private val mode: DoviPlaybackMode,
    private val createConverter: () -> DoviRpuConverter?,
) : ForwardingExtractorOutput(delegate) {
    private val videoTracks = mutableMapOf<Int, DoviCompatTrackOutput>()

    override fun track(
        id: Int,
        type: Int,
    ): TrackOutput {
        val delegate = super.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO) return delegate
        // Extractors are allowed to ask for the same track more than once and expect the same
        // output back, which has to hold for the wrapper too since it carries per track state.
        return videoTracks.getOrPut(id) { DoviCompatTrackOutput(delegate, mode, createConverter) }
    }

    /**
     * A block addition of a Dolby Vision track, which in a dual layer remux is where the RPU and
     * the enhancement layer live. Matroska reads these before it announces the sample they belong
     * to, so the RPU is there in time to join the access unit.
     */
    fun onBlockAdditional(
        trackNumber: Int,
        nalLengthFieldSize: Int,
        data: ByteArray,
        length: Int,
    ) {
        videoTracks[trackNumber]?.onBlockAdditional(nalLengthFieldSize, data, length)
    }

    fun onSeek() = videoTracks.values.forEach { it.onSeek() }

    fun onRelease() = videoTracks.values.forEach { it.onRelease() }
}

/**
 * Assembles each access unit of a Dolby Vision track, rewrites it, and hands the result to the real
 * track output. Tracks which are not profile 7 Dolby Vision are forwarded untouched.
 *
 * [androidx.media3.extractor.ForwardingTrackOutput] is not usable here: its convenience
 * `sampleData` overloads forward to the delegate rather than to itself, and those are the ones the
 * extractors call, so a subclass would never see the sample data.
 */
internal class DoviCompatTrackOutput(
    private val delegate: TrackOutput,
    private val mode: DoviPlaybackMode,
    private val createConverter: () -> DoviRpuConverter?,
) : TrackOutput {
    private enum class State {
        /** Not a profile 7 Dolby Vision track, or nothing to convert with: forwarded as it is. */
        INACTIVE,

        /** A profile 7 track whose first access unit has not been looked at yet. */
        UNDECIDED,

        /** Converting the RPU and dropping the enhancement layer. */
        CONVERTING,

        /** Dropping the RPU and the enhancement layer. */
        STRIPPING,

        /** A profile 7 track which cannot be rewritten; access units are forwarded unchanged. */
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

    /** The RPU of the block addition belonging to the access unit being assembled, if it had one. */
    private var pendingRpu = ByteArray(0)
    private var pendingRpuLength = 0

    /** Holds the RPU on its own as an Annex B frame, which is the shape the converter reads. */
    private var rpuFrame: ByteBuffer = ByteBuffer.allocateDirect(CONVERSION_HEADROOM)

    /** Whether the RPU arrives in block additions and has to be put into the access unit. */
    private var injectRpu = false

    private var codecsBefore: String? = null
    private var codecsAfter: String? = null
    private var rpuSource = "none"
    private var samples = 0L
    private var rpusConverted = 0L
    private var rpusDropped = 0L
    private var elBytesDropped = 0L
    private var bytesIn = 0L
    private var bytesOut = 0L
    private var conversionVerified = false

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
        codecsBefore = format.codecs
        buffered = 0
        when (mode) {
            DoviPlaybackMode.STRIP_TO_HEVC -> {
                // Dropping the Dolby Vision layer needs no RPU parsing, so no native library either
                state = State.STRIPPING
                codecsAfter = null
                Timber.i("Dolby Vision profile 7 video track, stripping it to plain HEVC")
                delegate.format(
                    format
                        .buildUpon()
                        .setSampleMimeType(MimeTypes.VIDEO_H265)
                        .setCodecs(null)
                        .build(),
                )
            }

            DoviPlaybackMode.CONVERT_TO_PROFILE_8_1 -> {
                val rpuConverter = converter ?: createConverter().also { converter = it }
                if (rpuConverter == null) {
                    // Relabelling a stream nothing can convert would only mislead the decoder
                    state = State.INACTIVE
                    Timber.w("No libdovi, leaving the Dolby Vision profile 7 track as it is")
                    delegate.format(format)
                    return
                }
                state = State.UNDECIDED
                codecsAfter = profile8Codecs
                Timber.i(
                    "Dolby Vision profile 7 video track, rewriting the codec string %s to %s",
                    format.codecs,
                    profile8Codecs,
                )
                delegate.format(format.buildUpon().setCodecs(profile8Codecs).build())
            }

            DoviPlaybackMode.NATIVE -> {
                state = State.INACTIVE
                delegate.format(format)
            }
        }
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (state == State.INACTIVE) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            giveUpOnPart(sampleDataPart)
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
        if (state == State.INACTIVE) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            giveUpOnPart(sampleDataPart)
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
            // Rewriting can move the tail of the access unit, so hold the bytes of the next one.
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

    /**
     * Keeps the RPU out of a block addition of the access unit currently being assembled. Matroska
     * reads block additions before it announces the sample, so it is there when the sample is.
     */
    fun onBlockAdditional(
        nalLengthFieldSize: Int,
        data: ByteArray,
        length: Int,
    ) {
        if (state == State.INACTIVE || mode != DoviPlaybackMode.CONVERT_TO_PROFILE_8_1) return
        val rpu = findRpuInBlockAdditional(data, length, nalLengthFieldSize) ?: return
        if (pendingRpu.size < rpu.length) pendingRpu = ByteArray(rpu.length)
        System.arraycopy(data, rpu.offset, pendingRpu, 0, rpu.length)
        pendingRpuLength = rpu.length
    }

    fun onSeek() {
        buffered = 0
        pendingRpuLength = 0
    }

    fun onRelease() {
        if (state == State.INACTIVE || samples == 0L) return
        Timber.i(
            "Dolby Vision profile 7 track finished as %s: codecs %s to %s, RPU from %s, " +
                "%d access units, %d RPUs converted, %d RPUs dropped, " +
                "%d enhancement layer bytes dropped, %d bytes in, %d bytes out",
            state,
            codecsBefore,
            codecsAfter,
            rpuSource,
            samples,
            rpusConverted,
            rpusDropped,
            elBytesDropped,
            bytesIn,
            bytesOut,
        )
    }

    /**
     * Rewrites the assembled access unit and leaves the result in [finished], returning its length.
     */
    private fun transform(size: Int): Int {
        if (state == State.UNDECIDED) decide(size)
        var length = size
        if (state == State.CONVERTING && !injectRpu) {
            ensureSampleCapacity(size + CONVERSION_HEADROOM)
            val convertedSize = converter?.convertToProfile8(sample, size) ?: -1
            if (convertedSize in 1..sample.capacity()) {
                length = convertedSize
                rpusConverted++
            } else {
                rpusDropped++
            }
            if (!conversionVerified) verifyConversion(length)
        }
        read(0, finishedOfAtLeast(length), length)
        when (state) {
            State.CONVERTING -> {
                val kept = dropNalUnits(finished, length) { it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER }
                elBytesDropped += length - kept
                length = kept
                if (injectRpu) length = injectPendingRpu(length)
                pendingRpuLength = 0
            }

            State.STRIPPING -> {
                // A profile 7 RPU in a stream presented as profile 8.1, or as HEVC, is worse than
                // no RPU at all, so both it and the enhancement layer go.
                val kept =
                    dropNalUnits(finished, length) {
                        it == NAL_UNIT_TYPE_RPU || it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER
                    }
                elBytesDropped += length - kept
                length = kept
            }

            else -> {
                Unit
            }
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
        val inBand = counts[NAL_UNIT_TYPE_RPU] > 0
        injectRpu = !inBand && pendingRpuLength > 0
        val info = if (inBand) converter?.frameInfo(sample, size) else pendingRpuFrameInfo()
        state =
            when {
                !inBand && pendingRpuLength == 0 -> {
                    Timber.e(
                        "No Dolby Vision RPU in the first access unit and none in a block addition, " +
                            "NAL units %s, so the video is left unconverted",
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
                    rpuSource = if (inBand) "in band" else "block additions"
                    Timber.i(
                        "Converting Dolby Vision profile 7 %s to profile 8.1, RPU from %s, " +
                            "first access unit NAL units %s",
                        info.elType,
                        rpuSource,
                        describeNalUnitCounts(counts),
                    )
                    State.CONVERTING
                }
            }
    }

    /**
     * Lays the block addition's RPU out on its own as an Annex B frame, which is the shape the
     * converter reads, and returns its length.
     */
    private fun loadPendingRpuFrame(): Int {
        val length = NAL_START_CODE.size + pendingRpuLength
        if (rpuFrame.capacity() < length + CONVERSION_HEADROOM) {
            rpuFrame = ByteBuffer.allocateDirect(length + CONVERSION_HEADROOM)
        }
        rpuFrame.limit(rpuFrame.capacity())
        rpuFrame.position(0)
        rpuFrame.put(NAL_START_CODE)
        rpuFrame.put(pendingRpu, 0, pendingRpuLength)
        return length
    }

    private fun pendingRpuFrameInfo(): DoviFrameInfo? {
        if (pendingRpuLength == 0) return null
        return converter?.frameInfo(rpuFrame, loadPendingRpuFrame())
    }

    /**
     * Converts the block addition's RPU and appends it to the access unit, where an RPU belongs,
     * returning the new length. An RPU which does not convert is left out rather than added as
     * profile 7 to a stream announced as profile 8.1.
     */
    private fun injectPendingRpu(length: Int): Int {
        if (pendingRpuLength == 0) {
            rpusDropped++
            return length
        }
        val loaded = loadPendingRpuFrame()
        val converted = converter?.convertToProfile8(rpuFrame, loaded) ?: -1
        if (converted !in 1..rpuFrame.capacity()) {
            rpusDropped++
            return length
        }
        if (converter?.frameInfo(rpuFrame, converted)?.profile == 7) {
            rpusDropped++
            return length
        }
        val out = finishedOfAtLeast(length + converted)
        val reader = rpuFrame.duplicate()
        reader.limit(reader.capacity())
        reader.position(0)
        reader.limit(converted)
        reader.get(out, length, converted)
        rpusConverted++
        return length + converted
    }

    /**
     * Checks that the first converted access unit really did come back as profile 8, since libdovi
     * reports an RPU it could not convert by handing it back unchanged. Carrying on would leave a
     * profile 7 RPU in a stream the decoder was told is profile 8.1, which is how a conversion
     * fails while looking like it worked. Dropping the RPUs instead gives up the Dolby Vision
     * metadata and keeps the HDR10 base layer, which is where playback started.
     */
    private fun verifyConversion(size: Int) {
        conversionVerified = true
        val info = converter?.frameInfo(sample, size)
        if (info != null && info.profile == 7) {
            Timber.e("libdovi left the RPU at profile 7, dropping the Dolby Vision metadata instead")
            state = State.STRIPPING
        }
    }

    /**
     * Sample data which is not the access unit itself, such as supplemental or encryption data, has
     * to keep its place relative to the bytes around it. Rather than guess at that framing, the
     * track gives up on rewriting as soon as any turns up.
     */
    private fun giveUpOnPart(sampleDataPart: Int) {
        if (state == State.INACTIVE) return
        Timber.w(
            "Sample data part %d on a Dolby Vision track, leaving the rest of the stream alone",
            sampleDataPart,
        )
        flushBuffered()
        state = State.INACTIVE
    }

    private fun passThrough(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        flushBuffered()
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun flushBuffered() {
        if (buffered <= 0) return
        read(0, finishedOfAtLeast(buffered), buffered)
        finishedWrapper.reset(finished, buffered)
        delegate.sampleData(finishedWrapper, buffered, TrackOutput.SAMPLE_DATA_PART_MAIN)
        buffered = 0
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
