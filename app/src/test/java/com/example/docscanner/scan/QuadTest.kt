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
    fun insetQuadIsInsideImage() {
        val q = Quad.inset(1000, 800, 0.1)
        assertEquals(Pt(100.0, 80.0), q.tl)
        assertEquals(Pt(900.0, 720.0), q.br)
    }
}
