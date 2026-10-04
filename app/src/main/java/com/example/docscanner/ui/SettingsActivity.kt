package com.example.docscanner.ui

import android.os.Bundle
import android.text.format.Formatter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.AppSettings
import com.example.docscanner.BuildConfig
import com.example.docscanner.R
import com.example.docscanner.databinding.ActivitySettingsBinding
import com.example.docscanner.databinding.ItemSettingBinding
import com.example.docscanner.ocr.OcrSettings
import com.example.docscanner.ocr.Script
import com.example.docscanner.settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Camera, app and about settings. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs: AppSettings by lazy { settings }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        section(R.string.settings_camera)
        switch(R.string.settings_save_gallery, R.string.settings_save_gallery_summary, prefs.saveToGallery) { prefs.saveToGallery = it }
        switch(R.string.settings_vibrate, null, prefs.vibrate) { prefs.vibrate = it }
        choice(
            R.string.settings_two_page_order,
            listOf(true to getString(R.string.settings_left_first), false to getString(R.string.settings_right_first)),
            { prefs.leftPageFirst }, { prefs.leftPageFirst = it },
            short = listOf(getString(R.string.settings_left_first_short), getString(R.string.settings_right_first_short)),
        )
        choice(
            R.string.settings_timer,
            AppSettings.TIMER_CHOICES.map { it to getString(R.string.settings_timer_value, it) },
            { prefs.timerSeconds }, { prefs.timerSeconds = it },
        )

        section(R.string.settings_app)
        choice(
            R.string.settings_start_screen,
            listOf(false to getString(R.string.settings_start_library), true to getString(R.string.settings_start_camera)),
            { prefs.startInCamera }, { prefs.startInCamera = it },
        )
        choice(
            R.string.settings_text_language,
            listOf(Script.LATIN to getString(R.string.script_latin), Script.DEVANAGARI to getString(R.string.script_devanagari)),
            { OcrSettings.script(this) }, { OcrSettings.setScript(this, it) },
            short = listOf(getString(R.string.script_latin_short), getString(R.string.script_devanagari_short)),
        )
        val storage = row(R.string.settings_storage)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { File(filesDir, "documents").walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            value(storage, Formatter.formatShortFileSize(this@SettingsActivity, bytes))
        }

        section(R.string.settings_about)
        value(row(R.string.settings_version), BuildConfig.VERSION_NAME)
        row(R.string.settings_licenses).root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_licenses)
                .setMessage(R.string.licenses_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun section(title: Int) {
        binding.rows.addView(TextView(this).apply {
            setText(title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(getColor(R.color.accent))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 3)
        })
    }

    private fun row(title: Int, summary: Int? = null): ItemSettingBinding {
        val b = ItemSettingBinding.inflate(layoutInflater, binding.rows, true)
        b.title.setText(title)
        if (summary != null) {
            b.summary.setText(summary)
            b.summary.isVisible = true
        }
        return b
    }

    private fun value(b: ItemSettingBinding, text: String) {
        b.value.text = text
        b.value.isVisible = true
    }

    private fun switch(title: Int, summary: Int?, initial: Boolean, onChange: (Boolean) -> Unit) {
        val b = row(title, summary)
        b.toggle.isVisible = true
        b.toggle.isChecked = initial
        b.root.setOnClickListener {
            b.toggle.isChecked = !b.toggle.isChecked
            onChange(b.toggle.isChecked)
        }
    }

    /**
     * A row showing the current choice; tapping it opens a single-choice list. [short] labels,
     * when given, are shown in the row so long option names don't squeeze the title.
     */
    private fun <T> choice(
        title: Int,
        options: List<Pair<T, String>>,
        get: () -> T,
        set: (T) -> Unit,
        short: List<String>? = null,
    ) {
        val b = row(title)
        fun show() {
            val i = options.indexOfFirst { it.first == get() }
            value(b, short?.getOrNull(i) ?: options.getOrNull(i)?.second.orEmpty())
        }
        show()
        b.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == get() }) { d, which ->
                    set(options[which].first)
                    show()
                    d.dismiss()
                }
                .show()
        }
    }
}
