package org.bestdroid.tts

import kotlin.math.roundToInt

/**
 * Translates the pitch/rate Android asks for into the parameters the
 * engine understands. Ported from iBestSpeech Shared/EngineParameters.swift.
 *
 * The engine's `rate` scales utterance *duration* as (rate+100)/100, so a
 * larger value is SLOWER speech; Android's rate runs the other way
 * (100 = normal, 200 = twice as fast). Android's neutral 100 maps to the
 * engine's nominal 0. A larger engine `pitch` is a higher F0 (same way
 * round); Android's neutral 100 maps to the engine default 80, not 50 —
 * below 50 the engine leaves the voiced range and buzzes.
 */
object EngineParameters {
    const val DEFAULT_PITCH = 80
    const val MIN_PITCH = 50
    const val MAX_PITCH = 140

    /** Engine's fastest setting; values below -100 saturate here. */
    const val FASTEST_RATE = -100
    /** About 3.6x nominal duration; beyond this speech stops being usable. */
    const val SLOWEST_RATE = 300

    /**
     * Neutral pace in wpm, measured on-device (36-word sentence, 2006ENG,
     * 137,564 frames at 11,025 Hz = 12.48 s = 173 wpm, rounded).
     */
    const val NEUTRAL_WPM = 170
    /** Floor matching TrueVoice's slowest row. */
    const val SLOWEST_WPM = 46
    /** Ceiling matching TrueVoice's fastest row. */
    const val FASTEST_WPM = 400

    /**
     * Engine `rate` for an Android speech rate in percent (100 = normal).
     *
     * Pace-compatible with TrueVoice: the target wpm follows TrueVoice's
     * own two-segment formula (same neutral shape, same 46/400 rails), and
     * the engine value is read off the measured pace curve below, so
     * TalkBack at 161% speaks ~310 wpm on both engines instead of
     * BestDroid lagging at ~216.
     *
     * Measured anchors (36-word reference sentence, 2006ENG, post pause
     * shortening): engine rate -> wpm. The curve is steep then saturating,
     * so a straight duration rule undershoots the middle: -45 was predicted
     * 310 wpm and measured 281, -72 predicted 310 and measured 394.
     */
    fun engineRate(androidRatePercent: Int): Int {
        val factor = (androidRatePercent / 100.0).coerceIn(0.25, 4.0)
        val target = (if (factor <= 1.0) {
            NEUTRAL_WPM - (1.0 - factor) * 2.0 * (NEUTRAL_WPM - SLOWEST_WPM)
        } else {
            NEUTRAL_WPM + (factor - 1.0) * (FASTEST_WPM - NEUTRAL_WPM)
        }).coerceIn(SLOWEST_WPM.toDouble(), FASTEST_WPM.toDouble())
        return rateForWpm(target)
    }

    /** Pace anchors: (wpm, engine rate), slowest first. */
    private val PACE_ANCHORS = arrayOf(
        47.0 to 300, // iOS table: 3.62x nominal duration
        90.0 to 100, // iOS table: 1.88x nominal duration
        170.0 to 0, // nominal, measured on-device
        281.0 to -45, // measured on-device
        394.0 to -72, // measured on-device
        459.0 to -100 // iOS table: 0.37x nominal duration
    )

    /** Engine rate for a target wpm by piecewise-linear inversion. */
    fun rateForWpm(targetWpm: Double): Int {
        if (targetWpm <= PACE_ANCHORS.first().first) return SLOWEST_RATE
        for (i in 1 until PACE_ANCHORS.size) {
            val (w0, r0) = PACE_ANCHORS[i - 1]
            val (w1, r1) = PACE_ANCHORS[i]
            if (targetWpm <= w1) {
                val t = (targetWpm - w0) / (w1 - w0)
                return (r0 + t * (r1 - r0)).roundToInt()
                    .coerceIn(FASTEST_RATE, SLOWEST_RATE)
            }
        }
        return FASTEST_RATE
    }

    /**
     * Engine `pitch` for an Android pitch in percent (100 = normal).
     * Two segments meeting at neutral: 25-100 maps 50-80, 100-400 maps
     * 80-140, so an untouched voice sounds untouched.
     */
    fun enginePitch(androidPitchPercent: Int): Int {
        val p = androidPitchPercent.coerceIn(25, 400)
        val engine = if (p <= 100) {
            DEFAULT_PITCH - ((100 - p) / 75.0) * (DEFAULT_PITCH - MIN_PITCH)
        } else {
            DEFAULT_PITCH + ((p - 100) / 300.0) * (MAX_PITCH - DEFAULT_PITCH)
        }
        return engine.roundToInt().coerceIn(MIN_PITCH, MAX_PITCH)
    }
}
