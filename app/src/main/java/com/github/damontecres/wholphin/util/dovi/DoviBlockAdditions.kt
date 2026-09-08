package com.github.damontecres.wholphin.util.dovi

/**
 * Finds the Dolby Vision RPU in the raw bytes of a Matroska block addition.
 *
 * A dual layer profile 7 remux, which is what a UHD Blu-ray usually becomes, keeps its enhancement
 * layer and its RPU in `BlockAdditional` elements rather than in the access units. The NAL units in
 * there are length prefixed with the track's NAL length field size, not framed with Annex B start
 * codes the way the samples themselves reach the track output.
 *
 * Anything which does not add up is treated as a framing this cannot read, and null is returned
 * rather than a guess at where a NAL unit begins.
 */
fun findRpuInBlockAdditional(
    data: ByteArray,
    length: Int,
    nalLengthFieldSize: Int,
): NalRange? {
    if (nalLengthFieldSize !in 1..4) return null
    var position = 0
    var rpu: NalRange? = null
    while (position + nalLengthFieldSize <= length) {
        var unitLength = 0
        for (i in 0 until nalLengthFieldSize) {
            unitLength = (unitLength shl 8) or (data[position + i].toInt() and 0xFF)
        }
        val unitStart = position + nalLengthFieldSize
        if (unitLength <= 0 || unitStart + unitLength > length) return rpu
        if (((data[unitStart].toInt() and 0x7E) shr 1) == NAL_UNIT_TYPE_RPU) {
            rpu = NalRange(unitStart, unitLength)
        }
        position = unitStart + unitLength
    }
    return rpu
}
