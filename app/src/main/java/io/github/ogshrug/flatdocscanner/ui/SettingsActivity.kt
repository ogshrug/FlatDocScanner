package io.github.ogshrug.flatdocscanner.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.ogshrug.flatdocscanner.AppSettings
import io.github.ogshrug.flatdocscanner.AppTheme
import io.github.ogshrug.flatdocscanner.Appearance
import io.github.ogshrug.flatdocscanner.BuildConfig
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.Themes
import io.github.ogshrug.flatdocscanner.databinding.ActivitySettingsBinding
import io.github.ogshrug.flatdocscanner.databinding.ItemSettingBinding
import io.github.ogshrug.flatdocscanner.ocr.OcrSettings
import io.github.ogshrug.flatdocscanner.ocr.Script
import io.github.ogshrug.flatdocscanner.scan.ModelEngine
import io.github.ogshrug.flatdocscanner.settings
import io.github.ogshrug.flatdocscanner.update.UpdateFeature
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
        enableAppEdgeToEdge()
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
            AppSettings.TIMER_CHOICES.map { it to resources.getQuantityString(R.plurals.settings_timer_value, it, it) },
            { prefs.timerSeconds }, { prefs.timerSeconds = it },
        )

        section(R.string.settings_look)
        themeRow()
        choice(
            R.string.settings_appearance,
            Appearance.entries.map { it to getString(it.label) },
            { prefs.appearance }, { Themes.setAppearance(this, it) },
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
        value(row(R.string.settings_engine), ModelEngine.NAME)
        if (UpdateFeature.AVAILABLE) UpdateFeature.addToSettings(this, binding.rows)
        row(R.string.settings_licenses).root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_licenses)
                .setMessage(R.string.licenses_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** The theme row: opens a list of the themes, each with a swatch of its own colours. */
    private fun themeRow() {
        val b = row(R.string.settings_theme)
        value(b, getString(prefs.theme.label))
        b.root.setOnClickListener {
            val d = resources.displayMetrics.density
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * d).toInt(), 0, 0)
            }
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_theme)
                .setView(ScrollView(this).apply { addView(list) })
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            AppTheme.entries.forEach { option ->
                // The option's own colours, in the light or dark this screen is showing.
                val themed = ContextThemeWrapper(this, option.overlay)
                val swatch = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themed.themeColor(androidx.appcompat.R.attr.colorPrimary))
                    setStroke((5 * d).toInt(), themed.themeColor(android.R.attr.colorBackground))
                }
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themed.themeColor(com.google.android.material.R.attr.colorOutline))
                }
                val icon = LayerDrawable(arrayOf(ring, swatch)).apply {
                    val inset = (1 * d).toInt()
                    setLayerInset(1, inset, inset, inset, inset)
                    setBounds(0, 0, (30 * d).toInt(), (30 * d).toInt())
                }
                list.addView(TextView(this).apply {
                    setText(option.label)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    if (option == prefs.theme) setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER_VERTICAL
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = (16 * d).toInt()
                    setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        dialog.dismiss()
                        if (option != prefs.theme) Themes.setTheme(this@SettingsActivity, option)
                    }
                })
            }
            dialog.show()
        }
    }

    private fun section(title: Int) {
        binding.rows.addView(TextView(this).apply {
            setText(title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 3)
        })
    }

    private fun row(title: Int, summary: Int? = null): ItemSettingBinding {
        val b = ItemSettingBinding.inflate(layoutInflater, binding.rows, true)
        b.title.setText(title)
        // Every row's switch has the same view id, so Android's own state restore (after a
        // theme change re-creates the screen) would give them all the last row's state.
        // The settings are the source of truth; nothing here needs restoring.
        b.toggle.isSaveEnabled = false
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
