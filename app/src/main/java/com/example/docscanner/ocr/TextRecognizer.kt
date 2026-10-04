package com.example.docscanner.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition with ML Kit Text Recognition v2 (both models are bundled, no download). */
object TextRecognizer {

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(page: Bitmap, script: Script): PageText {
        val client = if (script == Script.DEVANAGARI) devanagari else latin
        val result = client.process(InputImage.fromBitmap(page, 0)).await()
        val w = page.width.toDouble()
        val h = page.height.toDouble()
        val blocks = result.textBlocks.map { block ->
            OcrBlock(
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, box.left / w, box.top / h, box.right / w, box.bottom / h)
                },
            )
        }.filter { it.lines.isNotEmpty() }
        return PageText(script, blocks)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}

/** Stores [PageText] as JSON in the page row. */
object PageTextCodec {

    fun encode(text: PageText): String = JSONObject().apply {
        put("script", text.script.name)
        put("blocks", JSONArray().apply {
            text.blocks.forEach { b ->
                put(JSONArray().apply {
                    b.lines.forEach { l ->
                        put(JSONObject().apply {
                            put("t", l.text)
                            put("b", JSONArray(listOf(l.left, l.top, l.right, l.bottom)))
                        })
                    }
                })
            }
        })
    }.toString()

    fun decode(json: String?): PageText? {
        if (json.isNullOrEmpty()) return null
        return runCatching {
            val o = JSONObject(json)
            val blocks = o.getJSONArray("blocks")
            PageText(
                Script.valueOf(o.getString("script")),
                (0 until blocks.length()).map { i ->
                    val lines = blocks.getJSONArray(i)
                    OcrBlock(
                        (0 until lines.length()).map { j ->
                            val l = lines.getJSONObject(j)
                            val b = l.getJSONArray("b")
                            OcrLine(l.getString("t"), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                        },
                    )
                },
            )
        }.getOrNull()
    }
}

/** The text language the user picked; Latin by default. */
object OcrSettings {
    private const val PREFS = "ocr"
    private const val KEY_SCRIPT = "script"

    fun script(context: Context): Script = runCatching {
        Script.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCRIPT, null)!!)
    }.getOrDefault(Script.LATIN)

    fun setScript(context: Context, script: Script) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SCRIPT, script.name).apply()
    }
}
