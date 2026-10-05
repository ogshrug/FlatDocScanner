package io.github.ogshrug.flatdocscanner.scan

import io.github.ogshrug.flatdocscanner.scan.AutoCapture.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureTest {

    private val page = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))
    private val pageA = AutoCapture.normalizeSignature(FloatArray(64) { (it % 8).toFloat() })
    private val pageB = AutoCapture.normalizeSignature(FloatArray(64) { (it / 8).toFloat() })

    private var clock = 0L

    private fun AutoCapture.feed(
        quad: Quad? = page,
        sharpness: Double = 100.0,
        signature: FloatArray? = pageA,
        stepMillis: Long = 50,
    ): AutoCapture.Decision {
        clock += stepMillis
        return onFrame(AutoCapture.Frame(quad, sharpness, signature, clock))
    }

    /** Feeds still frames until a capture fires or [maxMillis] passes; returns whether it fired. */
    private fun AutoCapture.holdStill(
        signature: FloatArray? = pageA,
        maxMillis: Long = 3000,
        sharpness: Double = 100.0,
    ): Boolean {
        val end = clock + maxMillis
        while (clock < end) if (feed(signature = signature, sharpness = sharpness).capture) return true
        return false
    }

    @Test
    fun capturesAfterHoldingStill() {
        val ac = AutoCapture()
        val first = ac.feed()
        assertEquals(Status.HOLD_STILL, first.status)
        assertFalse(first.capture)
        assertTrue(ac.holdStill())
    }

    @Test
    fun doesNotCaptureBeforeSteadyTime() {
        val ac = AutoCapture(AutoCapture.Config(steadyMillis = 1000))
        repeat(10) { assertFalse(ac.feed().capture) } // 500 ms
    }

    @Test
    fun neverCapturesTheSamePageTwiceWithoutAChange() {
        val ac = AutoCapture()
        assertTrue(ac.holdStill())
        assertFalse(ac.holdStill(maxMillis = 5000))
        assertEquals(Status.TURN_PAGE, ac.feed().status)
    }

    @Test
    fun capturesNextPageAfterPageLeavesView() {
        val ac = AutoCapture()
        assertTrue(ac.holdStill(pageA))
        repeat(8) { ac.feed(quad = null) } // hand turns the page: 400 ms with no page
        assertTrue(ac.holdStill(pageB))
    }

    @Test
    fun capturesNextPageAfterOutlineJumps() {
        val ac = AutoCapture()
        assertTrue(ac.holdStill(pageA))
        val shifted = page.scaled(0.8)
        ac.feed(quad = shifted, signature = pageB)
        assertTrue(ac.holdStill(pageB))
    }

    @Test
    fun ignoresMovementBackToTheSamePage() {
        val ac = AutoCapture()
        assertTrue(ac.holdStill(pageA))
        repeat(8) { ac.feed(quad = null) }
        // Same content again, e.g. the user only lifted the phone.
        assertFalse(ac.holdStill(pageA))
    }

    @Test
    fun blurryFramesBlockCapture() {
        val ac = AutoCapture()
        ac.feed(sharpness = 300.0)
        ac.feed(sharpness = 300.0) // sharp first, then focus is lost
        val d = ac.feed(sharpness = 5.0)
        assertEquals(Status.BLURRY, d.status)
        repeat(20) { assertFalse(ac.feed(sharpness = 5.0).capture) } // 1 s, peak still remembered
    }

    @Test
    fun plainPagesWithLittleDetailStillCapture() {
        val ac = AutoCapture()
        // Never sharper than 15: low because the page is plain, not because it is blurry.
        assertTrue(ac.holdStill(sharpness = 15.0))
    }

    @Test
    fun manualCaptureIsNotRepeatedAutomatically() {
        val ac = AutoCapture()
        ac.feed()
        ac.onManualCapture(pageA, clock)
        assertFalse(ac.holdStill(pageA, maxMillis = 5000))
        repeat(8) { ac.feed(quad = null) }
        assertTrue(ac.holdStill(pageB))
    }

    @Test
    fun smallPagesAreRejected() {
        val ac = AutoCapture()
        val tiny = Quad(Pt(0.4, 0.4), Pt(0.5, 0.4), Pt(0.5, 0.5), Pt(0.4, 0.5))
        assertEquals(Status.TOO_SMALL, ac.feed(quad = tiny).status)
        repeat(60) { assertFalse(ac.feed(quad = tiny).capture) }
    }

    @Test
    fun jitterRestartsTheTimer() {
        val ac = AutoCapture(AutoCapture.Config(steadyMillis = 500))
        repeat(8) { ac.feed() } // 400 ms steady
        val wobble = ac.feed(quad = page.scaled(1.03)) // 3% wobble
        assertEquals(Status.HOLD_STILL, wobble.status)
        assertEquals(0f, wobble.progress, 0f)
        assertTrue(ac.feed(quad = page.scaled(1.03)).progress < 0.15f)
    }

    @Test
    fun noPageReportsNoPage() {
        assertEquals(Status.NO_PAGE, AutoCapture().feed(quad = null).status)
    }

    @Test
    fun failedCaptureCanBeRetried() {
        val ac = AutoCapture()
        assertTrue(ac.holdStill())
        ac.onCaptureFailed()
        assertTrue(ac.holdStill())
    }

    @Test
    fun signatureDistanceIgnoresBrightnessAndContrast() {
        val raw = FloatArray(64) { (it % 8).toFloat() }
        val brighter = FloatArray(64) { raw[it] * 1.5f + 40f }
        val d = AutoCapture.signatureDistance(
            AutoCapture.normalizeSignature(raw), AutoCapture.normalizeSignature(brighter),
        )
        assertEquals(0.0, d, 1e-4)
        assertTrue(AutoCapture.signatureDistance(pageA, pageB) > 0.3)
    }
}
