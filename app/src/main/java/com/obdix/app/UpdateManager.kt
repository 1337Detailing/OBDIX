package com.obdix.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.obdix.app.core.ReleaseVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AvailableUpdate(val version: ReleaseVersion, val url: String)

object UpdateManager {
    private const val OWNER = "1337Detailing"
    private const val REPO = "OBDIX"

    suspend fun check(current: String): AvailableUpdate? = withContext(Dispatchers.IO) {
        val local = ReleaseVersion.parse(current) ?: return@withContext null
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OBDIX-Android")
        }
        try {
            if (connection.responseCode != 200) error("GitHub : HTTP ${connection.responseCode}")
            val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = ReleaseVersion.parse(payload.optString("tag_name")) ?: return@withContext null
            if (version <= local) return@withContext null
            val assets = payload.getJSONArray("assets")
            val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?: return@withContext null
            val uri = Uri.parse(apkUrl)
            if (uri.scheme != "https" || uri.host != "github.com" ||
                !uri.path.orEmpty().startsWith("/$OWNER/$REPO/releases/download/")
            ) return@withContext null
            AvailableUpdate(version, apkUrl)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(context: Context, update: AvailableUpdate): File = withContext(Dispatchers.IO) {
        val target = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
            "OBDIX-v${update.version.major}.${update.version.minor}.${update.version.patch}.apk")
        val connection = (URL(update.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == 200) { "Téléchargement impossible" }
            val maxBytes = 150L * 1024 * 1024
            var copied = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        copied += count
                        check(copied <= maxBytes) { "APK trop volumineux" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(copied > 50_000) { "APK vide ou incomplet" }
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun requestInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
