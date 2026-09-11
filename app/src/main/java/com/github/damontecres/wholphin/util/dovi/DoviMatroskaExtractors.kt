@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.util.dovi

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor

/**
 * Receives the raw bytes of a Dolby Vision block addition, with the track number its output was
 * created with and the NAL length field size its samples use. The buffer is reused between calls,
 * so it has to be consumed before returning.
 */
internal fun interface DoviBlockAdditionalListener {
    fun onBlockAdditional(
        trackNumber: Int,
        nalLengthFieldSize: Int,
        data: ByteArray,
        length: Int,
    )
}

/**
 * Captures the block additions of a Dolby Vision video track, which is where a dual layer profile 7
 * remux keeps its enhancement layer and its RPU. The stock extractor discards them, so without this
 * the conversion never sees an RPU in those files and honestly degrades to leaving them alone.
 *
 * Shared by the two Matroska extractors below, which differ only in what else they do. It takes the
 * track's fields rather than the track, because `MatroskaExtractor.Track` is protected and only a
 * subclass may name it.
 */
internal class DoviBlockAdditionalCapture {
    var listener: DoviBlockAdditionalListener? = null

    private var buffer = ByteArray(0)

    /**
     * Reads the block addition and hands it to the listener, or returns false to leave it to the
     * extractor's own handling.
     */
    fun tryCapture(
        trackType: Int,
        hasDolbyVisionConfig: Boolean,
        trackNumber: Int,
        nalLengthFieldSize: Int,
        input: ExtractorInput,
        contentSize: Int,
    ): Boolean {
        val listener = listener ?: return false
        // Only a track which declared a Dolby Vision configuration carries an enhancement layer
        if (trackType != C.TRACK_TYPE_VIDEO || !hasDolbyVisionConfig || contentSize <= 0) {
            return false
        }
        if (buffer.size < contentSize) buffer = ByteArray(contentSize)
        input.readFully(buffer, 0, contentSize)
        listener.onBlockAdditional(trackNumber, nalLengthFieldSize, buffer, contentSize)
        return true
    }
}

/**
 * The Matroska extractor with the Dolby Vision block additions kept rather than discarded.
 */
class DoviMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
) : MatroskaExtractor(subtitleParserFactory, 0) {
    internal val doviCapture = DoviBlockAdditionalCapture()

    override fun handleBlockAdditionalData(
        track: Track,
        blockAdditionalId: Int,
        input: ExtractorInput,
        contentSize: Int,
    ) {
        val dolbyVisionConfig: ByteArray? = track.dolbyVisionConfigBytes
        val captured =
            doviCapture.tryCapture(
                trackType = track.type,
                hasDolbyVisionConfig = dolbyVisionConfig != null,
                trackNumber = track.number,
                nalLengthFieldSize = track.nalUnitLengthFieldLength,
                input = input,
                contentSize = contentSize,
            )
        if (!captured) {
            super.handleBlockAdditionalData(track, blockAdditionalId, input, contentSize)
        }
    }
}

/**
 * The same, on top of the libass Matroska extractor, so that subtitle rendering and the Dolby
 * Vision conversion do not have to be chosen between.
 *
 * `AssMatroskaExtractor` can be subclassed as of ass-media 0.5.1, which is what keeps this to an
 * override rather than a copy of the library's ASS handling.
 */
class DoviAssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler,
) : AssMatroskaExtractor(subtitleParserFactory, assHandler, 0) {
    internal val doviCapture = DoviBlockAdditionalCapture()

    override fun handleBlockAdditionalData(
        track: Track,
        blockAdditionalId: Int,
        input: ExtractorInput,
        contentSize: Int,
    ) {
        val dolbyVisionConfig: ByteArray? = track.dolbyVisionConfigBytes
        val captured =
            doviCapture.tryCapture(
                trackType = track.type,
                hasDolbyVisionConfig = dolbyVisionConfig != null,
                trackNumber = track.number,
                nalLengthFieldSize = track.nalUnitLengthFieldLength,
                input = input,
                contentSize = contentSize,
            )
        if (!captured) {
            super.handleBlockAdditionalData(track, blockAdditionalId, input, contentSize)
        }
    }
}
