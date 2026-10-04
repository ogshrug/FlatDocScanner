package com.example.docscanner.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BookGeometryTest {

    @Test
    fun fitsAStraightSpine() {
        val pts = (100..900 step 20).map { y -> Pt(700.0, y.toDouble()) }
        val spine = Spine.fit(pts, 1400, 1000)!!
        assertEquals(0.5, spine.xTop, 1e-6)
        assertEquals(0.5, spine.xBottom, 1e-6)
    }

    @Test
    fun fitsATiltedSpineDespiteOutliers() {
        val r = Random(1)
        // Gutter runs from x=650 at the top to x=750 at the bottom of a 1000 px tall photo.
        val good = (100..900 step 20).map { y -> Pt(650 + 0.1 * y + r.nextDouble(-2.0, 2.0), y.toDouble()) }
        val bad = List(12) { Pt(r.nextDouble(450.0, 950.0), r.nextDouble(100.0, 900.0)) }
        val spine = Spine.fit((good + bad).shuffled(r), 1400, 1000)!!
        assertEquals(650 / 1400.0, spine.xTop, 0.005)
        assertEquals(750 / 1400.0, spine.xBottom, 0.005)
        assertEquals(700 / 1400.0, spine.xAt(0.5), 0.005)
    }

    @Test
    fun rejectsScatteredPoints() {
        val r = Random(2)
        val noise = List(40) { Pt(r.nextDouble(400.0, 1000.0), r.nextDouble(0.0, 1000.0)) }
        assertNull(Spine.fit(noise, 1400, 1000))
        assertNull(Spine.fit(listOf(Pt(1.0, 1.0)), 1400, 1000))
    }

    @Test
    fun middleOfQuadExtendsToPhotoEdges() {
        val q = Quad(Pt(0.1, 0.2), Pt(0.9, 0.2), Pt(0.9, 0.8), Pt(0.1, 0.8))
        assertEquals(Spine(0.5, 0.5), Spine.middleOf(q))
        val tilted = Quad(Pt(0.1, 0.2), Pt(0.8, 0.2), Pt(1.0, 0.8), Pt(0.2, 0.8)) // top mid .45, bottom mid .6
        val s = Spine.middleOf(tilted)
        assertEquals(0.45, s.xAt(0.2), 1e-9)
        assertEquals(0.6, s.xAt(0.8), 1e-9)
    }

    @Test
    fun spineSerializeRoundTrips() {
        val s = Spine(0.48, 0.52)
        assertEquals(s, Spine.deserialize(s.serialize()))
        assertNull(Spine.deserialize("0.5"))
        assertNull(Spine.deserialize(null))
    }

    @Test
    fun gutterGainsLiftOnlyTheInnerShadow() {
        // Paper at 230 everywhere, darkening to 120 over the last 10% (spine on the right).
        val levels = DoubleArray(200) { x -> if (x < 180) 230.0 else 230.0 - (x - 180) * 5.5 }
        val g = Gutter.gains(levels, innerRight = true)
        assertTrue((0 until 160).all { g[it] == 1.0 })
        assertTrue(g[199] > 1.5)
        assertTrue(g.all { it in 1.0..2.5 })
    }

    @Test
    fun gutterGainsMirrorForRightPages() {
        val levels = DoubleArray(200) { x -> if (x >= 20) 230.0 else 120.0 + x * 5.5 }
        val g = Gutter.gains(levels, innerRight = false)
        assertTrue(g[0] > 1.5)
        assertTrue((40 until 200).all { g[it] == 1.0 })
    }

    @Test
    fun gutterGainsLeaveAnEvenPageAlone() {
        val g = Gutter.gains(DoubleArray(100) { 225.0 }, innerRight = true)
        assertTrue(g.all { it == 1.0 })
        assertNotNull(g)
    }
}
