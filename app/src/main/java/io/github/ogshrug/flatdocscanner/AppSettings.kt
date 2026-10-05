package io.github.ogshrug.flatdocscanner

import android.content.Context
import androidx.core.content.edit

/** User settings (Settings screen). */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Also save every new scan to the phone's gallery. */
    var saveToGallery: Boolean
        get() = prefs.getBoolean(KEY_SAVE_TO_GALLERY, false)
        set(v) = prefs.edit { putBoolean(KEY_SAVE_TO_GALLERY, v) }

    /** Vibrate when a page is captured. */
    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(v) = prefs.edit { putBoolean(KEY_VIBRATE, v) }

    /** Book mode: true for left-to-right books (left page first), false for right-to-left. */
    var leftPageFirst: Boolean
        get() = prefs.getBoolean(KEY_LEFT_FIRST, true)
        set(v) = prefs.edit { putBoolean(KEY_LEFT_FIRST, v) }

    /** Seconds between shots with the interval timer. */
    var timerSeconds: Int
        get() = prefs.getInt(KEY_TIMER, 3)
        set(v) = prefs.edit { putInt(KEY_TIMER, v) }

    /** Open straight into the camera instead of the library. */
    var startInCamera: Boolean
        get() = prefs.getBoolean(KEY_START_CAMERA, false)
        set(v) = prefs.edit { putBoolean(KEY_START_CAMERA, v) }

    var librarySort: LibrarySort
        get() = runCatching { LibrarySort.valueOf(prefs.getString(KEY_SORT, null)!!) }.getOrDefault(LibrarySort.MODIFIED)
        set(v) = prefs.edit { putString(KEY_SORT, v.name) }

    enum class LibrarySort { MODIFIED, CREATED, NAME }

    companion object {
        val TIMER_CHOICES = listOf(3, 5, 7, 10)

        private const val KEY_SAVE_TO_GALLERY = "save_to_gallery"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_LEFT_FIRST = "left_page_first"
        private const val KEY_TIMER = "timer_seconds"
        private const val KEY_START_CAMERA = "start_in_camera"
        private const val KEY_SORT = "library_sort"
    }
}

val Context.settings: AppSettings get() = AppSettings(this)
