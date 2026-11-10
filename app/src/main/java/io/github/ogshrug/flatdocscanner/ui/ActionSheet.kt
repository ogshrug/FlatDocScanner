package io.github.ogshrug.flatdocscanner.ui

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import io.github.ogshrug.flatdocscanner.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/** A bottom sheet with a grid of actions, each an icon above a short label. */
object ActionSheet {

    data class Action(
        @param:DrawableRes val icon: Int,
        @param:StringRes val label: Int,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        val onClick: () -> Unit,
    )

    fun show(activity: AppCompatActivity, @StringRes title: Int, actions: List<Action>, columns: Int = 5) {
        val dialog = BottomSheetDialog(activity)
        val d = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt(), (24 * d).toInt())
        }
        root.addView(TextView(activity).apply {
            setText(title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding((8 * d).toInt(), 0, 0, (12 * d).toInt())
        })
        val grid = GridLayout(activity).apply { columnCount = columns }
        val ripple = TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        actions.forEach { a ->
            val color = activity.getColor(
                when {
                    !a.enabled -> R.color.text_muted
                    a.destructive -> R.color.error
                    else -> R.color.text_primary
                },
            )
            grid.addView(TextView(activity).apply {
                setText(a.label)
                setCompoundDrawablesWithIntrinsicBounds(0, a.icon, 0, 0)
                TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(color))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setTextColor(color)
                gravity = Gravity.CENTER
                compoundDrawablePadding = (4 * d).toInt()
                setPadding((2 * d).toInt(), (10 * d).toInt(), (2 * d).toInt(), (10 * d).toInt())
                isEnabled = a.enabled
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    dialog.dismiss()
                    a.onClick()
                }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            })
        }
        root.addView(grid)
        dialog.setContentView(root)
        dialog.show()
    }
}
