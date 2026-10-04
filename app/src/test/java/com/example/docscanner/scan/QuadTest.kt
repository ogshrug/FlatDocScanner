package com.example.docscanner.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadTest {

    private val tl = Pt(10.0, 20.0)
    private val tr = Pt(110.0, 15.0)
    private val br = Pt(120.0, 220.0)
    private val bl = Pt(5.0, 210.0)

    @Test
    fun ordersCornersWhateverTheInputOrder() {
        val expected = Quad(tl, tr, br, bl)
        val inputs = listOf(
            listOf(tl, tr, br, bl),
            listOf(br, tl, bl, tr),
            listOf(bl, br, tr, tl),
            listOf(tr, bl, tl, br),
        )
        for (input in inputs) assertEquals(expected, Quad.fromUnordered(input))
    }

    @Test
    fun ordersARotatedPage() {
        // A page rotated ~45 degrees: no corner is clearly "top-left" by x or y alone.
        val q = Quad.fromUnordered(listOf(Pt(100.0, 0.0), Pt(200.0, 100.0), Pt(100.0, 200.0), Pt(0.0, 100.0)))
        assertTrue(q.isConvex())
        assertEquals(20000.0, q.area(), 1e-9)
    }

    @Test
    fun outputSizeUsesLongerOppositeEdges() {
        val q = Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(90.0, 200.0), Pt(10.0, 200.0))
        val (w, h) = q.outputSize()
        assertEquals(100, w)
        assertEquals(200, h)
    }

    @Test
    fun detectsCrossedCorners() {
        assertTrue(Quad(tl, tr, br, bl).isConvex())
        assertFalse(Quad(tl, br, tr, bl).isConvex())
    }

    @Test
    fun normalizeRoundTrips() {
        val q = Quad(tl, tr, br, bl)
        val back = q.normalized(400, 300).denormalized(400, 300)
        q.points.zip(back.points).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-9)
            assertEquals(a.y, b.y, 1e-9)
        }
    }

    @Test
    fun serializeRoundTrips() {
        val q = Quad(tl, tr, br, bl)
        assertEquals(q, Quad.deserialize(q.serialize()))
        assertNull(Quad.deserialize("1,2,3"))
        assertNull(Quad.deserialize(null))
    }

    @Test
    fun clampKeepsCornersInsideImage() {
        val q = Quad(Pt(-5.0, -5.0), Pt(500.0, 0.0), Pt(500.0, 500.0), Pt(0.0, 500.0)).clampedTo(400, 300)
        assertEquals(Pt(0.0, 0.0), q.tl)
        assertEquals(Pt(400.0, 300.0), q.br)
    }

    @Test
    fun completesAMissingCorner() {
        val q = Quad.completing(listOf(Pt(0.0, 0.0), Pt(10.0, 1.0), null, Pt(1.0, 20.0)))!!
        assertEquals(Pt(11.0, 21.0), q.br)
        val full = Quad.completing(listOf(tl, tr, br, bl))!!
        assertEquals(Quad(tl, tr, br, bl), full)
        assertNull(Quad.completing(listOf(tl, null, null, bl)))
    }

    @Test
    fun rejectsSliversAndAcceptsPerspectivePages() {
        assertTrue(Quad(tl, tr, br, bl).anglesWithin())
        // A page seen at a strong angle: still a plausible outline.
        assertTrue(Quad(Pt(30.0, 0.0), Pt(70.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)).anglesWithin())
        // A near-degenerate sliver.
        assertFalse(Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 5.0), Pt(90.0, 4.0)).anglesWithin())
    }

    @Test
    fun countsSidesAlongTheBorder() {
        assertEquals(0, Quad(tl, tr, br, bl).sidesOnBorder(400, 300))
        assertEquals(4, Quad.inset(400, 300, 0.0).sidesOnBorder(400, 300))
        val bottomStrip = Quad(Pt(0.0, 200.0), Pt(399.0, 210.0), Pt(399.0, 299.0), Pt(0.0, 299.0))
        assertEquals(3, bottomStrip.sidesOnBorder(400, 300))
    }

    @Test
    fun insetQuadIsInsideImage() {
        val q = Quad.inset(1000, 800, 0.1)
        assertEquals(Pt(100.0, 80.0), q.tl)
        assertEquals(Pt(900.0, 720.0), q.br)
    }
}
