package com.github.damontecres.wholphin.util.dovi

/**
 * The NAL unit type carrying the Dolby Vision RPU, the metadata describing how to map the base
 * layer to the display.
 */
const val NAL_UNIT_TYPE_RPU = 62

/**
 * The NAL unit type carrying the Dolby Vision enhancement layer, which only a profile 7 decoder
 * knows what to do with.
 */
const val NAL_UNIT_TYPE_ENHANCEMENT_LAYER = 63

private const val DOVI_PROFILE_8 = "08"

/** Where in a buffer a NAL unit sits. */
data class NalRange(
    val offset: Int,
    val length: Int,
)

/**
 * What to do with a Dolby Vision profile 7 track during playback.
 */
enum class DoviPlaybackMode {
    /** Leave the stream alone, for a device which really does decode profile 7. */
    NATIVE,

    /** Rewrite the RPU to profile 8.1 and drop the enhancement layer. */
    CONVERT_TO_PROFILE_8_1,

    /** Drop the RPU and the enhancement layer, leaving the HDR10 base layer as plain HEVC. */
    STRIP_TO_HEVC,
}

/**
 * Rewrites a Dolby Vision profile 7 codec string into its profile 8 form, e.g. `dvhe.07.06` into
 * `dvhe.08.06`, and returns null for anything which is not profile 7.
 *
 * The codec string is what decides which decoder plays the track: media3 turns it into a
 * `CodecProfileLevel` to pick a decoder, and passes that profile to `MediaCodec` when configuring
 * it. A device whose Dolby Vision decoder only advertises profile 8 rejects a profile 7 track, so
 * converting the bitstream without also rewriting the codec string converts nothing anybody sees.
 */
fun doviProfile7ToProfile8(codecs: String?): String? {
    if (codecs == null) return null
    val parts = codecs.split('.')
    if (parts.size < 2) return null
    val fourCc = parts[0].lowercase()
    if (fourCc != "dvhe" && fourCc != "dvh1") return null
    if (parts[1].toIntOrNull() != 7) return null
    return (listOf(parts[0], DOVI_PROFILE_8) + parts.subList(2, parts.size)).joinToString(".")
}

/** Whether [codecs] describes Dolby Vision profile 7. */
fun isDoviProfile7(codecs: String?): Boolean = doviProfile7ToProfile8(codecs) != null

/**
 * Calls [onNalUnit] once per Annex B NAL unit found in `data[0, size)`, with the bounds of the unit
 * including its start code, so that `[from, to)` covers every byte of the access unit exactly once.
 *
 * Bytes ahead of the first start code, if any, are not reported. Emulation prevention makes a start
 * code impossible inside a payload, so a plain scan for `00 00 01` finds unit boundaries and nothing
 * else.
 *
 * Both extractors which matter here hand over Annex B: `Mp4Extractor` replaces the NAL length field
 * of an MP4 sample with a start code, and `MatroskaExtractor` does the same.
 */
inline fun forEachNalUnit(
    data: ByteArray,
    size: Int,
    onNalUnit: (type: Int, from: Int, to: Int) -> Unit,
) {
    var unitStart = -1
    var unitType = -1
    var zeros = 0
    var i = 0
    while (i < size) {
        val b = data[i].toInt()
        if (b == 0) {
            zeros++
            i++
            continue
        }
        if (b == 1 && zeros >= 2 && i + 2 < size) {
            // The start code is three or four bytes; a fourth leading zero belongs to it, further
            // zeros are trailing padding of the unit before it.
            val startCode = i - if (zeros >= 3) 3 else 2
            if (unitStart >= 0) onNalUnit(unitType, unitStart, startCode)
            unitStart = startCode
            unitType = (data[i + 1].toInt() and 0x7E) shr 1
            // Skip the two byte NAL unit header, which cannot contain a start code.
            i += 3
            zeros = 0
            continue
        }
        zeros = 0
        i++
    }
    if (unitStart >= 0) onNalUnit(unitType, unitStart, size)
}

/**
 * Removes every NAL unit of a type [shouldDrop] accepts from `data[0, size)`, moving the units which
 * are kept so that they stay contiguous, and returns the new size.
 *
 * Nothing is moved and [size] is returned unchanged while no unit has been dropped, so this costs a
 * scan on a stream which has nothing to remove.
 */
inline fun dropNalUnits(
    data: ByteArray,
    size: Int,
    shouldDrop: (type: Int) -> Boolean,
): Int {
    var writePos = -1
    forEachNalUnit(data, size) { type, from, to ->
        if (shouldDrop(type)) {
            if (writePos < 0) writePos = from
        } else if (writePos >= 0) {
            System.arraycopy(data, from, data, writePos, to - from)
            writePos += to - from
        }
    }
    return if (writePos < 0) size else writePos
}

/**
 * Counts the NAL units in `data[0, size)` by type, indexed by type. Used to report what an access
 * unit actually contained when a conversion does not go as expected.
 */
fun nalUnitTypeCounts(
    data: ByteArray,
    size: Int,
): IntArray {
    val counts = IntArray(64)
    forEachNalUnit(data, size) { type, _, _ ->
        if (type in counts.indices) counts[type]++
    }
    return counts
}

/**
 * Renders [counts] as `type=count` pairs for the types which occur, e.g. `32=1, 33=1, 34=1, 62=1`.
 */
fun describeNalUnitCounts(counts: IntArray): String =
    counts
        .withIndex()
        .filter { it.value > 0 }
        .joinToString { "${it.index}=${it.value}" }
