package io.github.ogshrug.flatdocscanner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeatureTest {

    @Test
    fun comparesVersionsByNumberNotByText() {
        assertTrue(UpdateFeature.isNewer("1.2.0", "1.1.0"))
        assertTrue(UpdateFeature.isNewer("1.10.0", "1.9.3"))
        assertTrue(UpdateFeature.isNewer("2.0", "1.9.9"))
        assertTrue(UpdateFeature.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun theSameOrAnOlderVersionIsNotAnUpdate() {
        assertFalse(UpdateFeature.isNewer("1.2.0", "1.2.0"))
        assertFalse(UpdateFeature.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateFeature.isNewer("1.1.9", "1.2.0"))
        assertFalse(UpdateFeature.isNewer("nonsense", "1.2.0"))
    }

    @Test
    fun releaseNotesLoseTheirMarkup() {
        assertEquals("What is new\n\nLook\n- Themes with code", UpdateFeature.plain("## What is new\n\n**Look**\n- Themes with `code`\n"))
    }
}
