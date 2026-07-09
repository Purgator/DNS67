package fr.arichard.adblocker.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import fr.arichard.adblocker.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater backed by GitHub Releases.
 *
 * Once a day (throttled, from the running VPN service or app open) it fetches the
 * latest-release metadata; when a newer version exists it downloads the APK in the
 * background (unmetered networks only) and posts a notification whose tap opens the
 * system installer. Android verifies the APK is signed with the same key before
 * installing, so a tampered file can never replace the app.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val API_URL = "https://api.github.com/repos/Purgator/DNS67/releases/latest"
    private const val ASSET_NAME = "DNS67.apk"
    private const val MAX_APK_BYTES = 50L * 1024 * 1024
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 2

    enum class Status { UP_TO_DATE, UPDATE_READY, UPDATE_DEFERRED, ERROR }
    data class Result(val status: Status, val version: String? = null, val detail: String? = null)

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * Daily throttled check. Downloads only over unmetered networks and notifies
     * at most once per new version. Call from a background thread.
     */
    fun maybeDailyCheck(context: Context) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        if (!prefs.autoUpdate) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return

        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm != null && !cm.isActiveNetworkMetered
        val result = check(appContext, allowDownload = unmetered)
        if (result.status == Status.UPDATE_READY && prefs.notifiedUpdateVersion != result.version) {
            prefs.notifiedUpdateVersion = result.version
            notifyUpdateReady(appContext, result.version!!)
        }
    }

    /**
     * Checks GitHub for a newer release and, if [allowDownload], fetches its APK
     * into the cache. Call from a background thread.
     */
    @Synchronized
    fun check(context: Context, allowDownload: Boolean): Result {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        return try {
            val json = JSONObject(httpGet(API_URL))
            prefs.lastUpdateCheck = System.currentTimeMillis()

            val remote = json.getString("tag_name").removePrefix("v").trim()
            val current = currentVersion(appContext)
            cleanupOldApks(appContext, current)
            if (!isNewer(remote, current)) return Result(Status.UP_TO_DATE, current)

            var assetUrl: String? = null
            var assetSize = -1L
            val assets = json.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                if (asset.optString("name") == ASSET_NAME) {
                    assetUrl = asset.optString("browser_download_url")
                    assetSize = asset.optLong("size", -1)
                    break
                }
            }
            if (assetUrl.isNullOrEmpty() || !assetUrl.startsWith("https://")) {
                return Result(Status.ERROR, remote, "release has no $ASSET_NAME asset")
            }
            if (assetSize !in 1..MAX_APK_BYTES) {
                return Result(Status.ERROR, remote, "unexpected asset size $assetSize")
            }

            val apk = apkFile(appContext, remote)
            if (apk.isFile && apk.length() == assetSize) return Result(Status.UPDATE_READY, remote)
            if (!allowDownload) return Result(Status.UPDATE_DEFERRED, remote)

            download(assetUrl, apk, assetSize)
            Log.i(TAG, "Downloaded update $remote (${apk.length()} bytes)")
            Result(Status.UPDATE_READY, remote)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            Result(Status.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Intent that opens the system installer for the downloaded update. */
    fun installIntent(context: Context, version: String): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile(context, version)
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun notifyUpdateReady(context: Context, version: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val pending = PendingIntent.getActivity(
            context, 3, installIntent(context, version), PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.update_ready_title, version))
                .setContentText(context.getString(R.string.update_ready_text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
    }

    /** True when [remote] is a strictly newer dotted version than [local]. */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun apkFile(context: Context, version: String): File =
        File(context.cacheDir, "updates/DNS67-v$version.apk").apply { parentFile?.mkdirs() }

    /** Removes cached update APKs that are no longer newer than the installed app. */
    private fun cleanupOldApks(context: Context, current: String) {
        File(context.cacheDir, "updates").listFiles()?.forEach { file ->
            val version = file.name.removePrefix("DNS67-v").removeSuffix(".apk")
            if (!isNewer(version, current)) file.delete()
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "DNS67-updater")
            if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, expectedSize: Long) {
        val tmp = File(target.path + ".tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "DNS67-updater")
                if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            if (tmp.length() != expectedSize) {
                throw Exception("size mismatch: got ${tmp.length()}, expected $expectedSize")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw Exception("could not move downloaded file")
        } finally {
            tmp.delete()
        }
    }
}
