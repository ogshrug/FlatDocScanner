package com.example.docscanner.scan

import kotlin.math.abs

/**
 * Decides when to fire the shutter from a stream of analysed camera frames.
 *
 * Captures when a page has been large enough, sharp and still for [Config.steadyMillis].
 * After a capture it disarms, and only re-arms once something visibly changes: the page
 * leaves the frame (a hand turning it) or the outline jumps. On top of that a capture is
 * refused while the page content still matches the last capture, so wobbling the phone
 * over the same page never produces a duplicate.
 *
 * Pure Kotlin so it can be unit-tested; all coordinates are fractions of the frame.
 */
class AutoCapture(private val config: Config = Config()) {

    data class Config(
        /** How long the page must stay still before capturing. */
        val steadyMillis: Long = 700,
        /** Largest corner movement between frames that still counts as still (fraction of frame). */
        val maxJitter: Double = 0.012,
        /** Smallest page area worth capturing (fraction of frame). */
        val minCoverage: Double = 0.2,
        /**
         * Variance-of-Laplacian floor. A frame below it only counts as blurry if it is also well
         * below the sharpest recent frame ([relativeBlur]), so pages with little text still capture.
         */
        val minSharpness: Double = 40.0,
        val relativeBlur: Double = 0.6,
        /** Corner jump that counts as "the page changed" and re-arms capture. */
        val rearmMotion: Double = 0.06,
        /** How long the page must be out of view to re-arm capture. */
        val rearmLostMillis: Long = 250,
        /** Signature distance below which a page counts as the one already captured. */
        val sameContentDistance: Double = 0.3,
        /** Minimum gap between two captures. */
        val cooldownMillis: Long = 1200,
    )

    enum class Status { NO_PAGE, TOO_SMALL, BLURRY, HOLD_STILL, CAPTURING, TURN_PAGE }

    data class Frame(
        val quad: Quad?,
        val sharpness: Double,
        /** Small normalised thumbnail of the page area, see [signatureDistance]. */
        val signature: FloatArray?,
        val timeMillis: Long,
    )

    data class Decision(val status: Status, val progress: Float, val capture: Boolean)

    private var armed = true
    private var previous: Quad? = null
    private var steadySince: Long? = null
    private var lostSince: Long? = null
    private var lastCaptureAt = Long.MIN_VALUE / 2
    private var lastSignature: FloatArray? = null
    private var peakSharpness = 0.0

    fun reset() {
        armed = true
        previous = null
        steadySince = null
        lostSince = null
        lastSignature = null
        peakSharpness = 0.0
    }

    /** Call when the user captured this page by hand, so auto-capture doesn't take it again. */
    fun onManualCapture(signature: FloatArray?, timeMillis: Long) {
        armed = false
        lastSignature = signature
        lastCaptureAt = timeMillis
        steadySince = null
    }

    /** Call when a capture asked for by [onFrame] failed, so the same page can be retried. */
    fun onCaptureFailed() {
        armed = true
        lastSignature = null
        steadySince = null
    }

    fun onFrame(frame: Frame): Decision {
        val now = frame.timeMillis
        val quad = frame.quad
        val motion = if (quad != null) previous?.let { maxCornerMotion(it, quad) } else null
        previous = quad

        if (quad == null) {
            peakSharpness = 0.0
            if (lostSince == null) lostSince = now
            if (!armed && now - lostSince!! >= config.rearmLostMillis) armed = true
            steadySince = null
            return Decision(if (armed) Status.NO_PAGE else Status.TURN_PAGE, 0f, false)
        }
        lostSince = null

        if (!armed) {
            if (motion != null && motion >= config.rearmMotion) armed = true
            if (!armed) return Decision(Status.TURN_PAGE, 0f, false)
        }

        if (quad.area() < config.minCoverage) {
            steadySince = null
            return Decision(Status.TOO_SMALL, 0f, false)
        }
        if (motion == null || motion > config.maxJitter) {
            steadySince = now
            return Decision(Status.HOLD_STILL, 0f, false)
        }
        peakSharpness = maxOf(peakSharpness * PEAK_DECAY, frame.sharpness)
        if (frame.sharpness < config.minSharpness && frame.sharpness < peakSharpness * config.relativeBlur) {
            steadySince = null
            return Decision(Status.BLURRY, 0f, false)
        }

        val since = steadySince ?: now.also { steadySince = it }
        val progress = ((now - since).toFloat() / config.steadyMillis).coerceIn(0f, 1f)
        if (progress < 1f || now - lastCaptureAt < config.cooldownMillis) {
            return Decision(Status.HOLD_STILL, progress, false)
        }

        val last = lastSignature
        val sig = frame.signature
        if (last != null && sig != null && signatureDistance(last, sig) < config.sameContentDistance) {
            // Still the page we already have: wait for a real page turn.
            armed = false
            return Decision(Status.TURN_PAGE, 0f, false)
        }

        armed = false
        lastCaptureAt = now
        lastSignature = sig
        steadySince = null
        return Decision(Status.CAPTURING, 1f, true)
    }

    companion object {
        /** Per-frame decay of the remembered peak sharpness (about 2 s half-life at 15 fps). */
        private const val PEAK_DECAY = 0.98

        fun maxCornerMotion(a: Quad, b: Quad): Double =
            a.points.zip(b.points).maxOf { (p, q) -> p.distanceTo(q) }

        /** Mean absolute difference of two zero-mean, unit-variance signatures (0 = identical). */
        fun signatureDistance(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return Double.MAX_VALUE
            var sum = 0.0
            for (i in a.indices) sum += abs(a[i] - b[i])
            return sum / a.size
        }

        /** Normalises raw pixel values to zero mean and unit variance so lighting changes don't count. */
        fun normalizeSignature(values: FloatArray): FloatArray {
            val mean = values.average()
            var variance = 0.0
            for (v in values) variance += (v - mean) * (v - mean)
            val std = kotlin.math.sqrt(variance / values.size).coerceAtLeast(1e-6)
            return FloatArray(values.size) { ((values[it] - mean) / std).toFloat() }
        }
    }
}
