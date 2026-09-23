package com.knowyourcase.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

class AppUpdateManager(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner
) {
    private val client = OkHttpClient()
    private var downloadId: Long? = null
    private var downloadedApk: File? = null
    private var receiverRegistered = false

    fun checkForUpdate(silent: Boolean = true) {
        lifecycleOwner.lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() } ?: return@launch
                if (isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    showUpdateDialog(release)
                } else if (!silent) {
                    Toast.makeText(activity, "KnowYourCase is up to date", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                if (!silent) {
                    Toast.makeText(activity, "Could not check for updates", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/litmaspatra/KnowYourCase/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KnowYourCase-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            val notes = json.optString("body")
            val assets: JSONArray = json.optJSONArray("assets") ?: return null

            var apkUrl: String? = null
            var apkName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkName = name
                    break
                }
            }
            if (tag.isBlank() || apkUrl.isNullOrBlank()) return null
            return ReleaseInfo(tag, notes, apkUrl, apkName ?: "KnowYourCase-$tag.apk")
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val summary = release.notes
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(5)
            .joinToString("\n")
            .take(900)

        AlertDialog.Builder(activity)
            .setTitle("Update available • v${release.version}")
            .setMessage(
                if (summary.isBlank()) "A newer version of KnowYourCase is available."
                else "A newer version is available.\n\n$summary"
            )
            .setNegativeButton("Later", null)
            .setPositiveButton("Download & install") { _, _ ->
                downloadUpdate(release)
            }
            .show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        val apk = File(dir, release.fileName)
        if (apk.exists()) apk.delete()
        downloadedApk = apk

        registerDownloadReceiver()

        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("KnowYourCase ${release.version}")
            .setDescription("Downloading app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apk))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
        Toast.makeText(activity, "Update download started", Toast.LENGTH_SHORT).show()
    }

    private fun registerDownloadReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            activity,
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId != downloadId) return
            val apk = downloadedApk ?: return
            if (!apk.exists()) {
                Toast.makeText(activity, "Update download failed", Toast.LENGTH_SHORT).show()
                return
            }
            installApk(apk)
        }
    }

    private fun installApk(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Allow app updates")
                .setMessage("Android needs permission for KnowYourCase to install its downloaded update. Enable “Allow from this source”, then return to the app.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(installIntent)
    }

    fun retryPendingInstall() {
        val apk = downloadedApk ?: return
        if (apk.exists() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    activity.packageManager.canRequestPackageInstalls())) {
            installApk(apk)
        }
    }

    fun close() {
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(downloadReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private data class ReleaseInfo(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        val fileName: String
    )
}
