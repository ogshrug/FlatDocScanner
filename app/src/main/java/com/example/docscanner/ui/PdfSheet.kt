package com.example.docscanner.ui

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.example.docscanner.R
import com.example.docscanner.databinding.SheetCreatePdfBinding
import com.example.docscanner.export.PdfOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Create PDF": file name (with quick name templates), paper size, quality, colour and page
 * order. The last choices are remembered for next time.
 */
object PdfSheet {

    fun show(activity: AppCompatActivity, defaultName: String, onCreate: (name: String, options: PdfOptions) -> Unit) {
        val b = SheetCreatePdfBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity).apply { setContentView(b.root) }
        val prefs = activity.getSharedPreferences("pdf", AppCompatActivity.MODE_PRIVATE)
        val saved = PdfOptions(
            pageSize = enumOr(prefs.getString("size", null), PdfOptions.PageSize.FIT),
            quality = enumOr(prefs.getString("quality", null), PdfOptions.Quality.HIGH),
            color = enumOr(prefs.getString("color", null), PdfOptions.ColorMode.AS_SCANNED),
        )

        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        b.name.setText("${defaultName}_$date")
        listOf(
            R.string.pdf_template_name_date to "${defaultName}_$date",
            R.string.pdf_template_name to defaultName,
            R.string.pdf_template_date to date,
        ).forEach { (label, value) ->
            b.nameTemplates.addView(Chip(activity).apply {
                setText(label)
                setOnClickListener { b.name.setText(value); b.name.setSelection(value.length) }
            })
        }

        val sizes = choices(b.pageSize, saved.pageSize,
            PdfOptions.PageSize.FIT to R.string.pdf_size_fit,
            PdfOptions.PageSize.A4 to R.string.pdf_size_a4,
            PdfOptions.PageSize.LETTER to R.string.pdf_size_letter)
        val qualities = choices(b.quality, saved.quality,
            PdfOptions.Quality.MAXIMUM to R.string.pdf_quality_max,
            PdfOptions.Quality.HIGH to R.string.pdf_quality_high,
            PdfOptions.Quality.SMALL to R.string.pdf_quality_small)
        val colors = choices(b.color, saved.color,
            PdfOptions.ColorMode.AS_SCANNED to R.string.pdf_color_scanned,
            PdfOptions.ColorMode.GRAYSCALE to R.string.filter_gray,
            PdfOptions.ColorMode.BLACK_AND_WHITE to R.string.filter_bw)
        val orders = choices(b.order, false, false to R.string.pdf_order_forward, true to R.string.pdf_order_reverse)

        b.createButton.setOnClickListener {
            val name = b.name.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                b.nameLayout.error = activity.getString(R.string.pdf_name_required)
                return@setOnClickListener
            }
            val options = PdfOptions(sizes(), qualities(), colors(), orders())
            prefs.edit {
                putString("size", options.pageSize.name)
                putString("quality", options.quality.name)
                putString("color", options.color.name)
            }
            dialog.dismiss()
            onCreate(name, options)
        }
        dialog.show()
    }

    /** Fills [group] with one chip per choice and returns a getter for the selected value. */
    private fun <T> choices(group: ChipGroup, selected: T, vararg items: Pair<T, Int>): () -> T {
        val ids = items.map { (value, label) ->
            val chip = Chip(group.context, null, com.google.android.material.R.attr.chipStyle).apply {
                id = android.view.View.generateViewId()
                setText(label)
                isCheckable = true
                isCheckedIconVisible = true
            }
            group.addView(chip)
            if (value == selected) chip.isChecked = true
            chip.id to value
        }
        return { ids.firstOrNull { it.first == group.checkedChipId }?.second ?: items.first().first }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(default)
}
