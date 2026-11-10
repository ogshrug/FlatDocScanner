package io.github.ogshrug.flatdocscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ogshrug.flatdocscanner.scan.Dewarper
import io.github.ogshrug.flatdocscanner.scan.PageDetector
import io.github.ogshrug.flatdocscanner.scan.PageFilter
import io.github.ogshrug.flatdocscanner.scan.PageMode
import io.github.ogshrug.flatdocscanner.scan.PageSpec
import io.github.ogshrug.flatdocscanner.scan.ScanPipeline
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * Times the slow parts of scanning on whatever device runs it and writes the results to
 * logcat under the tag SPEED. Nothing is asserted: the numbers are for comparing builds on
 * the same phone.
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=io.github.ogshrug.flatdocscanner.SpeedDeviceTest
 *     adb logcat -d -s SPEED
 */
@RunWith(AndroidJUnit4::class)
class SpeedDeviceTest {

    /** A light page on a dark table, at the given size. */
    private fun photo(w: Int, h: Int): Bitmap {
        val photo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(70, 52, 40))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 243, 236) }
            drawRect(w * 0.18f, h * 0.15f, w * 0.84f, h * 0.86f, paint)
            paint.color = Color.rgb(30, 30, 30)
            paint.textSize = h / 40f
            for (i in 0 until 18) drawText("The quick brown fox jumps over the lazy dog", w * 0.24f, h * (0.22f + i * 0.033f), paint)
        }
        return photo
    }

    private fun report(name: String, millis: List<Double>) {
        val sorted = millis.sorted()
        val median = sorted[sorted.size / 2]
        Log.i("SPEED", "%-34s median %7.1f ms   min %7.1f   max %7.1f   (n=%d)".format(name, median, sorted.first(), sorted.last(), sorted.size))
    }

    private inline fun time(block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    }

    @Test
    fun liveDetection() {
        // The size of a camera analysis frame.
        val frame = Mat().also { Utils.bitmapToMat(photo(720, 960), it) }
        report("live detect, first call", listOf(time { PageDetector.detectLive(frame) }))
        repeat(10) { PageDetector.detectLive(frame) }
        report("live detect", List(60) { time { PageDetector.detectLive(frame) } })
        frame.release()
    }

    @Test
    fun finishingAPage() {
        // A 12-megapixel page, as a phone camera captures it.
        val source = photo(3000, 4000)
        val looks = listOf(
            "finish, original" to PageSpec(null, PageMode.FLAT, filter = PageFilter.ORIGINAL, removeFingers = false),
            "finish, colour filter" to PageSpec(null, PageMode.FLAT, filter = PageFilter.COLOR, removeFingers = false),
            "finish, colour + fingers" to PageSpec(null, PageMode.FLAT, filter = PageFilter.COLOR, removeFingers = true),
        )
        for ((name, spec) in looks) {
            val times = List(7) {
                // A fresh copy each time: older builds recycled the bitmap they were given.
                val geometry = source.copy(Bitmap.Config.ARGB_8888, true)
                val t = time {
                    val out = ScanPipeline.finish(geometry, spec)
                    if (out !== geometry && !out.isRecycled) out.recycle()
                }
                if (!geometry.isRecycled) geometry.recycle()
                t
            }
            report(name, times.drop(1))
        }
    }

    @Test
    fun flatteningACurvedPage() {
        val source = photo(3000, 4000)
        report("dewarp, first call", listOf(time { Dewarper.flatten(source, 2400).recycle() }))
        report("dewarp", List(4) { time { Dewarper.flatten(source, 2400).recycle() } })
    }
}
