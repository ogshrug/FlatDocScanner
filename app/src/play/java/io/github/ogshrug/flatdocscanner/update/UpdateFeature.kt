package io.github.ogshrug.flatdocscanner.update

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/** The Play build is updated by Play and has no network access, so there is nothing here. */
object UpdateFeature {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun checkOnLaunch(activity: AppCompatActivity, root: View, anchor: View?) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun addToSettings(activity: AppCompatActivity, rows: ViewGroup) = Unit
}
