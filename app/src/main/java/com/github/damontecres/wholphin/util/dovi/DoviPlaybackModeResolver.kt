package com.github.damontecres.wholphin.util.dovi

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Build
import android.view.Display
import androidx.core.content.ContextCompat
import com.github.damontecres.wholphin.preferences.DoviConversionMode
import com.github.damontecres.wholphin.util.profile.MediaCodecCapabilitiesTest
import timber.log.Timber

/**
 * Turns the user's Dolby Vision setting into what playback should actually do with a profile 7
 * track, and says in the log how it got there.
 *
 * The device's own claims decide only [DoviConversionMode.DOVI_CONVERSION_AUTO]. Picking a mode by
 * hand is followed as given: a decoder advertising profile 7 does not necessarily render the
 * enhancement layer, and several put out the HDR10 base layer instead, which is the complaint the
 * conversion exists to answer.
 */
fun resolveDoviPlaybackMode(
    setting: DoviConversionMode,
    context: Context,
    mediaTest: MediaCodecCapabilitiesTest,
): DoviPlaybackMode {
    val decodesProfile7 = mediaTest.supportsHevcDolbyVisionEL()
    val decodesSingleLayer = mediaTest.supportsHevcDolbyVision()
    val displayDolbyVision = displaySupportsDolbyVision(context)

    val mode =
        when (setting) {
            DoviConversionMode.DOVI_CONVERSION_PROFILE_8_1 -> {
                DoviPlaybackMode.CONVERT_TO_PROFILE_8_1
            }

            DoviConversionMode.DOVI_CONVERSION_HEVC -> {
                DoviPlaybackMode.STRIP_TO_HEVC
            }

            DoviConversionMode.DOVI_CONVERSION_AUTO -> {
                when {
                    // Without a Dolby Vision display there is nothing for the metadata to drive
                    !displayDolbyVision -> DoviPlaybackMode.STRIP_TO_HEVC

                    decodesProfile7 -> DoviPlaybackMode.NATIVE

                    decodesSingleLayer -> DoviPlaybackMode.CONVERT_TO_PROFILE_8_1

                    else -> DoviPlaybackMode.STRIP_TO_HEVC
                }
            }

            DoviConversionMode.DOVI_CONVERSION_NATIVE,
            DoviConversionMode.UNRECOGNIZED,
            -> {
                DoviPlaybackMode.NATIVE
            }
        }

    Timber.i(
        "Dolby Vision profile 7 handling: setting=%s, mode=%s, decoder claims profile 7=%s, " +
            "single layer=%s, display Dolby Vision=%s, decoders=%s",
        setting,
        mode,
        decodesProfile7,
        decodesSingleLayer,
        displayDolbyVision,
        describeDolbyVisionDecoders(mediaTest),
    )
    return mode
}

/** Whether the display the app is on accepts Dolby Vision over its connection. */
private fun displaySupportsDolbyVision(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    return try {
        val display = ContextCompat.getDisplayOrDefault(context)
        hdrTypes(display).contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
    } catch (ex: Exception) {
        Timber.w(ex, "Could not read the display's HDR capabilities")
        false
    }
}

private fun hdrTypes(display: Display): IntArray {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return display.mode.supportedHdrTypes
    }
    @Suppress("DEPRECATION")
    return display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
}

/**
 * The Dolby Vision profiles the device's decoders advertise, for the log. Which profile a decoder
 * claims is the one thing that explains why a file plays the way it does, and it is not otherwise
 * visible from a device.
 */
private fun describeDolbyVisionDecoders(mediaTest: MediaCodecCapabilitiesTest): String =
    mediaTest
        .dolbyVisionDecoderProfiles()
        .takeIf { it.isNotEmpty() }
        ?.entries
        ?.joinToString { (name, profiles) ->
            "$name[${profiles.joinToString { describeDolbyVisionProfile(it) }}]"
        }
        ?: "none"

private fun describeDolbyVisionProfile(profile: Int): String =
    when (profile) {
        CodecProfileLevel.DolbyVisionProfileDvavPer -> "P0"
        CodecProfileLevel.DolbyVisionProfileDvavPen -> "P1"
        CodecProfileLevel.DolbyVisionProfileDvheDer -> "P2"
        CodecProfileLevel.DolbyVisionProfileDvheDen -> "P3"
        CodecProfileLevel.DolbyVisionProfileDvheDtr -> "P4"
        CodecProfileLevel.DolbyVisionProfileDvheStn -> "P5"
        CodecProfileLevel.DolbyVisionProfileDvheDth -> "P6"
        CodecProfileLevel.DolbyVisionProfileDvheDtb -> "P7"
        CodecProfileLevel.DolbyVisionProfileDvheSt -> "P8"
        CodecProfileLevel.DolbyVisionProfileDvavSe -> "P9"
        else -> "0x${profile.toString(16)}"
    }
