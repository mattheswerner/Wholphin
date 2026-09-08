package com.github.damontecres.wholphin.util.dovi

import com.suyashbelekar.exoplayerhdrutils.libdovi.ElType
import com.suyashbelekar.exoplayerhdrutils.libdovi.LibDovi
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * The kind of enhancement layer a profile 7 stream carries. A minimal enhancement layer holds no
 * picture data, a full one does, and neither is any use to a decoder which only speaks profile 8.
 */
enum class DoviElType {
    NONE,
    MEL,
    FEL,
}

/**
 * What the Dolby Vision RPU of an access unit says about the stream it belongs to.
 */
data class DoviFrameInfo(
    val profile: Int,
    val elType: DoviElType,
    val hasHdr10Plus: Boolean,
)

/**
 * The RPU operations the profile 7 conversion needs, kept behind an interface so that the media3
 * plumbing around it can be tested without loading a native library.
 *
 * Both calls read the access unit from the start of [frame], not from its position, because the
 * native side addresses the buffer directly. The buffer has to be a direct one.
 */
interface DoviRpuConverter {
    /**
     * Parses the first RPU in the access unit, or returns null if it holds none.
     */
    fun frameInfo(
        frame: ByteBuffer,
        size: Int,
    ): DoviFrameInfo?

    /**
     * Converts every profile 7 RPU in the access unit to its profile 8.1 form in place and returns
     * the new size of the access unit, or -1 if the conversion failed.
     *
     * The conversion can make the access unit slightly longer, so [frame] needs spare capacity
     * beyond [size].
     */
    fun convertToProfile8(
        frame: ByteBuffer,
        size: Int,
    ): Int
}

/**
 * A [DoviRpuConverter] backed by libdovi, through the JNI binding of the ExoPlayer HDR Utils
 * library. libdovi is the RPU parser and writer from
 * [dovi_tool](https://github.com/quietvoid/dovi_tool).
 */
class LibDoviRpuConverter private constructor(
    private val libDovi: LibDovi,
) : DoviRpuConverter {
    override fun frameInfo(
        frame: ByteBuffer,
        size: Int,
    ): DoviFrameInfo? {
        val info = libDovi.getFrameInfo(frame, size) ?: return null
        if (info.doviProfile == 0) return null
        return DoviFrameInfo(
            profile = info.doviProfile,
            elType =
                when (info.doviElType) {
                    ElType.FEL -> DoviElType.FEL
                    ElType.MEL -> DoviElType.MEL
                    ElType.NONE -> DoviElType.NONE
                },
            hasHdr10Plus = info.hasHdr10Plus,
        )
    }

    override fun convertToProfile8(
        frame: ByteBuffer,
        size: Int,
    ): Int = libDovi.processHevcFrame(frame, size, CONVERT_TO_PROFILE_8, false)

    companion object {
        /** The transform libdovi applies to an RPU: convert profile 7 to profile 8.1. */
        private const val CONVERT_TO_PROFILE_8 = 1

        /**
         * Loads libdovi, or returns null if it is not available for this device's ABI, in which
         * case the stream is left as it is rather than half converted.
         */
        fun createOrNull(): LibDoviRpuConverter? =
            try {
                LibDoviRpuConverter(LibDovi())
            } catch (ex: Throwable) {
                Timber.e(ex, "Could not load libdovi, Dolby Vision profile 7 conversion is unavailable")
                null
            }
    }
}
