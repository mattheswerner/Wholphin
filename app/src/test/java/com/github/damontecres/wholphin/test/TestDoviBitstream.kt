package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.util.dovi.NAL_UNIT_TYPE_ENHANCEMENT_LAYER
import com.github.damontecres.wholphin.util.dovi.NAL_UNIT_TYPE_RPU
import com.github.damontecres.wholphin.util.dovi.describeNalUnitCounts
import com.github.damontecres.wholphin.util.dovi.doviProfile7ToProfile8
import com.github.damontecres.wholphin.util.dovi.dropNalUnits
import com.github.damontecres.wholphin.util.dovi.forEachNalUnit
import com.github.damontecres.wholphin.util.dovi.nalUnitTypeCounts
import org.junit.Assert
import org.junit.Test

/**
 * An Annex B NAL unit: a start code, the two byte HEVC NAL unit header and a payload.
 */
private fun nalUnit(
    type: Int,
    payload: ByteArray = byteArrayOf(0x11, 0x22, 0x33),
    fourByteStartCode: Boolean = true,
): ByteArray {
    val startCode = if (fourByteStartCode) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
    val header = byteArrayOf(((type shl 1) and 0x7E).toByte(), 0x01)
    return startCode + header + payload
}

private fun accessUnit(vararg units: ByteArray): ByteArray = units.reduce { a, b -> a + b }

class TestDoviBitstream {
    @Test
    fun codecStringOfProfile7IsRewrittenToProfile8() {
        Assert.assertEquals("dvhe.08.06", doviProfile7ToProfile8("dvhe.07.06"))
        Assert.assertEquals("dvh1.08.06", doviProfile7ToProfile8("dvh1.07.06"))
        Assert.assertEquals("dvhe.08.09", doviProfile7ToProfile8("dvhe.07.09"))
    }

    @Test
    fun codecStringOfAnythingElseIsLeftAlone() {
        Assert.assertNull(doviProfile7ToProfile8(null))
        Assert.assertNull(doviProfile7ToProfile8("dvhe.08.06"))
        Assert.assertNull(doviProfile7ToProfile8("dvhe.05.06"))
        Assert.assertNull(doviProfile7ToProfile8("hvc1.2.4.L153.B0"))
        Assert.assertNull(doviProfile7ToProfile8("dvhe"))
        Assert.assertNull(doviProfile7ToProfile8(""))
    }

    @Test
    fun everyNalUnitIsFoundExactlyOnce() {
        val units = listOf(nalUnit(32), nalUnit(33), nalUnit(34), nalUnit(19), nalUnit(NAL_UNIT_TYPE_RPU))
        val data = accessUnit(*units.toTypedArray())
        val found = mutableListOf<Triple<Int, Int, Int>>()
        forEachNalUnit(data, data.size) { type, from, to -> found.add(Triple(type, from, to)) }

        Assert.assertEquals(listOf(32, 33, 34, 19, NAL_UNIT_TYPE_RPU), found.map { it.first })
        // The reported ranges have to tile the access unit with no gaps and no overlap
        Assert.assertEquals(0, found.first().second)
        Assert.assertEquals(data.size, found.last().third)
        found.zipWithNext().forEach { (a, b) -> Assert.assertEquals(a.third, b.second) }
    }

    @Test
    fun threeByteStartCodesAreFoundToo() {
        val data =
            accessUnit(
                nalUnit(32, fourByteStartCode = false),
                nalUnit(NAL_UNIT_TYPE_RPU, fourByteStartCode = false),
            )
        Assert.assertEquals(listOf(32, NAL_UNIT_TYPE_RPU), typesOf(data))
    }

    @Test
    fun trailingZerosStayWithTheUnitTheyFollow() {
        // Padding after a unit belongs to it, only the start code itself moves with the next one
        val padded = nalUnit(32) + byteArrayOf(0, 0, 0, 0)
        val data = padded + nalUnit(NAL_UNIT_TYPE_RPU)
        val found = mutableListOf<Triple<Int, Int, Int>>()
        forEachNalUnit(data, data.size) { type, from, to -> found.add(Triple(type, from, to)) }

        Assert.assertEquals(listOf(32, NAL_UNIT_TYPE_RPU), found.map { it.first })
        Assert.assertEquals(padded.size, found[0].third)
    }

    @Test
    fun enhancementLayerIsDropped() {
        val kept =
            accessUnit(
                nalUnit(32),
                nalUnit(33),
                nalUnit(19, ByteArray(64) { it.toByte() }),
                nalUnit(NAL_UNIT_TYPE_RPU, byteArrayOf(0x25, 0x00)),
            )
        val data =
            accessUnit(
                nalUnit(32),
                nalUnit(33),
                nalUnit(19, ByteArray(64) { it.toByte() }),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER, ByteArray(200) { 0x7F }),
                nalUnit(NAL_UNIT_TYPE_RPU, byteArrayOf(0x25, 0x00)),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER, ByteArray(120) { 0x5A }),
            )

        val size = dropNalUnits(data, data.size) { it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER }

        Assert.assertEquals(kept.size, size)
        Assert.assertArrayEquals(kept, data.copyOf(size))
    }

    @Test
    fun anAccessUnitWithNothingToDropIsUntouched() {
        val data = accessUnit(nalUnit(32), nalUnit(19), nalUnit(NAL_UNIT_TYPE_RPU))
        val original = data.copyOf()

        val size = dropNalUnits(data, data.size) { it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER }

        Assert.assertEquals(original.size, size)
        Assert.assertArrayEquals(original, data)
    }

    @Test
    fun droppingHandlesTheFirstAndTheLastUnit() {
        val data =
            accessUnit(
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER),
                nalUnit(19),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER),
            )
        val size = dropNalUnits(data, data.size) { it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER }

        Assert.assertArrayEquals(nalUnit(19), data.copyOf(size))
    }

    @Test
    fun everythingCanBeDropped() {
        val data = accessUnit(nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER), nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER))
        Assert.assertEquals(0, dropNalUnits(data, data.size) { it == NAL_UNIT_TYPE_ENHANCEMENT_LAYER })
    }

    @Test
    fun emulationPreventedPayloadsDoNotSplitAUnit() {
        // 00 00 03 is how an encoder keeps 00 00 01 out of a payload, so this is one unit, not two
        val data = nalUnit(19, byteArrayOf(0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x03, 0x02))
        Assert.assertEquals(listOf(19), typesOf(data))
    }

    @Test
    fun nalUnitsAreCounted() {
        val data =
            accessUnit(
                nalUnit(32),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER),
                nalUnit(NAL_UNIT_TYPE_ENHANCEMENT_LAYER),
                nalUnit(NAL_UNIT_TYPE_RPU),
            )
        val counts = nalUnitTypeCounts(data, data.size)

        Assert.assertEquals(1, counts[32])
        Assert.assertEquals(2, counts[NAL_UNIT_TYPE_ENHANCEMENT_LAYER])
        Assert.assertEquals(1, counts[NAL_UNIT_TYPE_RPU])
        Assert.assertEquals("32=1, 62=1, 63=2", describeNalUnitCounts(counts))
    }

    @Test
    fun dataWithoutAStartCodeIsLeftAlone() {
        val data = ByteArray(32) { 0x42 }
        Assert.assertEquals(data.size, dropNalUnits(data, data.size) { true })
        Assert.assertTrue(typesOf(data).isEmpty())
    }

    private fun typesOf(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        forEachNalUnit(data, data.size) { type, _, _ -> types.add(type) }
        return types
    }
}
