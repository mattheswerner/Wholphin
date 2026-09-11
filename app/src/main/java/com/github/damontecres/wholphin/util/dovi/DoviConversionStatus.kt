package com.github.damontecres.wholphin.util.dovi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Dolby Vision conversion is doing, for the playback debug overlay.
 *
 * Whether a profile 7 stream was converted, left alone, or never arrived is otherwise only visible
 * in a log, which is a poor place to look when the picture on the television is the question. It is
 * one line, written by the extractor and read by the overlay.
 */
object DoviConversionStatus {
    private val _summary = MutableStateFlow<String?>(null)

    /** Null while nothing has been played, or while the conversion is switched off. */
    val summary: StateFlow<String?> = _summary.asStateFlow()

    fun set(value: String?) {
        _summary.value = value
    }
}
