package io.github.ogshrug.flatdocscanner.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.github.ogshrug.flatdocscanner.BuildConfig
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.databinding.ItemSettingBinding
import io.github.ogshrug.flatdocscanner.settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * The GitHub build's self-updater.
 *
 * It asks GitHub's public API for the repository's latest release, and if that is newer than
 * this build, downloads the APK for this phone, checks it, and hands it to the system
 * installer, which asks the user before installing. Nothing else in the app uses the network,
 * and nothing about the user or their scans is sent: the two requests are plain downloads.
 *
 * A download is installed only if all of these hold:
 *  - its SHA-256 matches the one GitHub lists for the file (when GitHub lists one);
 *  - it is this app (same package name) and a higher version than the one running;
 *  - it is signed with the same certificate as the app that is running.
 * Android's installer enforces the last two again by itself.
 */
object UpdateFeature {

    const val AVAILABLE = true

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val TIMEOUT_MILLIS = 15_000
    private const val APK_MIME = "application/vnd.android.package-archive"

    private class Asset(val name: String, val url: String, val size: Long, val sha256: String?)
    private class Release(val version: String, val notes: String, val asset: Asset?)

    /** The reason a download was thrown away, shown to the user. */
    private class Rejected(val reason: Int) : IOException()

    /**
     * Called when the library comes to the front. At most once a day, and only if the user
     * hasn't switched it off, looks for a newer version and offers it in a bar. Says nothing
     * when there is no update or no connection.
     */
    fun checkOnLaunch(activity: AppCompatActivity, root: View, anchor: View?) {
        val prefs = activity.settings
        val now = System.currentTimeMillis()
        if (!prefs.autoUpdateCheck || now - prefs.lastUpdateCheck in 0 until DAY_MILLIS) return
        prefs.lastUpdateCheck = now
        activity.lifecycleScope.launch {
            val release = runCatching { latestRelease() }.getOrNull() ?: return@launch
            if (!isNewer(release.version, BuildConfig.VERSION_NAME)) return@launch
            Snackbar.make(root, activity.getString(R.string.update_available, release.version), 10_000)
                .apply { if (anchor != null && anchor.isShown) anchorView = anchor }
                .setAction(R.string.update_action) { offer(activity, release) }
                .show()
        }
    }

    /** Adds "Check for updates" and "Check automatically" to the About section of Settings. */
    fun addToSettings(activity: AppCompatActivity, rows: ViewGroup) {
        val prefs = activity.settings

        val check = ItemSettingBinding.inflate(activity.layoutInflater, rows, true)
        check.title.setText(R.string.update_check)
        check.value.isVisible = true
        check.root.setOnClickListener {
            check.root.isEnabled = false
            check.value.setText(R.string.update_checking)
            activity.lifecycleScope.launch {
                val release = runCatching { latestRelease() }.getOrNull()
                check.root.isEnabled = true
                when {
                    release == null -> check.value.setText(R.string.update_failed)
                    isNewer(release.version, BuildConfig.VERSION_NAME) -> {
                        check.value.text = activity.getString(R.string.update_available, release.version)
                        offer(activity, release)
                    }
                    else -> check.value.setText(R.string.update_current)
                }
            }
        }

        val auto = ItemSettingBinding.inflate(activity.layoutInflater, rows, true)
        auto.title.setText(R.string.update_auto)
        auto.summary.setText(R.string.update_auto_summary)
        auto.summary.isVisible = true
        auto.toggle.isVisible = true
        auto.toggle.isSaveEnabled = false
        auto.toggle.isChecked = prefs.autoUpdateCheck
        auto.root.setOnClickListener {
            auto.toggle.isChecked = !auto.toggle.isChecked
            prefs.autoUpdateCheck = auto.toggle.isChecked
        }
    }

    // ---- Finding the release

    private suspend fun latestRelease(): Release = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FlatDocScanner")
        }
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            val apks = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
                .map {
                    Asset(
                        name = it.getString("name"),
                        url = it.getString("browser_download_url"),
                        size = it.optLong("size"),
                        // "sha256:<hex>", on releases made since GitHub began listing digests.
                        sha256 = it.optString("digest").removePrefix("sha256:").takeIf { d -> d.length == 64 },
                    )
                }
            Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                asset = pickAsset(apks),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The APK built for this phone's CPU, else the one that carries every CPU type, from
     * the set built with the same engine as this app (Ferrite builds carry "ferrite" in
     * their file names).
     */
    private fun pickAsset(all: List<Asset>): Asset? {
        val ferrite = BuildConfig.ENGINE == "ferrite"
        val apks = all.filter { ("ferrite" in it.name) == ferrite }
        val wanted = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            else -> "universal"
        }
        return apks.firstOrNull { wanted in it.name }
            ?: apks.firstOrNull { "universal" in it.name }
            ?: apks.singleOrNull()
    }

    /** Compares dotted version numbers: 1.10.0 is newer than 1.9.3. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ---- Offering, downloading, installing

    private fun offer(activity: AppCompatActivity, release: Release) {
        if (activity.isFinishing || activity.isDestroyed) return
        val asset = release.asset
        val notes = plain(release.notes).ifEmpty { activity.getString(R.string.update_no_notes) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(if (asset == null) activity.getString(R.string.update_no_file) else notes.take(2000))
            .setNegativeButton(R.string.update_later, null)
            .apply {
                if (asset != null) setPositiveButton(R.string.update_action) { _, _ -> download(activity, asset) }
            }
            .show()
    }

    /** Release notes are written in Markdown; a dialog shows text, so drop the markup. */
    internal fun plain(markdown: String): String =
        markdown.lines().joinToString("\n") { line ->
            line.replace(Regex("^#+\\s*"), "").replace("**", "").replace("`", "")
        }.trim()

    private fun download(activity: AppCompatActivity, asset: Asset) {
        val d = activity.resources.displayMetrics.density
        val bar = LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val size = TextView(activity).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
            addView(bar)
            addView(size)
        }
        lateinit var dialog: AlertDialog
        val job = activity.lifecycleScope.launch {
            val result = runCatching {
                val file = fetch(activity, asset) { done, total ->
                    activity.runOnUiThread {
                        if (total > 0) {
                            bar.setProgressCompat((done * 1000 / total).toInt(), true)
                            size.text = activity.getString(
                                R.string.update_size,
                                Formatter.formatShortFileSize(activity, done),
                                Formatter.formatShortFileSize(activity, total),
                            )
                        }
                    }
                }
                withContext(Dispatchers.IO) { verify(activity, file) }
                file
            }
            dialog.dismiss()
            result.onSuccess { install(activity, it) }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                File(activity.cacheDir, "updates").deleteRecursively()
                val message = (error as? Rejected)?.reason ?: R.string.update_download_failed
                MaterialAlertDialogBuilder(activity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading, asset.name))
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job.cancel() }
            .show()
        bar.max = 1000
    }

    /** Downloads [asset] into the cache and checks its SHA-256. Returns the file. */
    private suspend fun fetch(context: Context, asset: Asset, progress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            // The name only labels the file; never let it choose where the file goes.
            val file = File(dir, "update.apk")
            val connection = (URL(asset.url).openConnection() as HttpsURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("User-Agent", "FlatDocScanner")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0L
                        progress(0, total)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (done - shown >= 512 * 1024) {
                                shown = done
                                progress(done, total)
                            }
                        }
                        progress(done, total)
                    }
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            } finally {
                connection.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (asset.sha256 != null && !asset.sha256.equals(actual, ignoreCase = true)) {
                file.delete()
                throw Rejected(R.string.update_unverified)
            }
            file
        }

    /** Refuses anything that is not a newer version of this very app, signed like it. */
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, signatureFlags())
        val installed = pm.getPackageInfo(context.packageName, signatureFlags())
        val genuine = archive != null &&
            archive.packageName == context.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed) &&
            signers(archive).let { it.isNotEmpty() && it == signers(installed) }
        if (!genuine) {
            file.delete()
            throw Rejected(R.string.update_unverified)
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }

    /** SHA-256 of each certificate the package is signed with. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else null
        // An APK file read before Android 13 can come back without signingInfo.
        val all = modern?.takeIf { it.isNotEmpty() } ?: info.signatures
        val sha = MessageDigest.getInstance("SHA-256")
        return all.orEmpty().map { s -> sha.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Hands the file to the system installer, which shows its own confirmation. */
    private fun install(activity: AppCompatActivity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.update_download_failed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
