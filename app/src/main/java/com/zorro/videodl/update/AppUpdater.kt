package com.zorro.videodl.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.zorro.videodl.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the server's latest.json describes. Written by tools/publish-update.sh. */
@Serializable
data class Release(
    val versionCode: Int,
    val versionName: String,
    /** APK file name, resolved relative to latest.json. */
    val apk: String,
    val size: Long,
    val sha256: String,
    val notes: List<String> = emptyList(),
    val publishedAt: String = "",
)

/**
 * Self-update from our own server rather than GitHub, which is unreliable
 * from mainland China networks.
 *
 * Android still has the final say: the system installer shows its own
 * confirmation and refuses any APK not signed with the same key.
 */
object AppUpdater {
    private const val AUTHORITY_SUFFIX = ".updates"
    private val json = Json { ignoreUnknownKeys = true }

    /** The release if it is newer than this build, null if we are current. */
    suspend fun check(): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            // Debug builds carry a ".debug" package suffix, so a release APK would
            // install beside them instead of over them.
            if (BuildConfig.DEBUG) return@runCatching null
            val release = json.decodeFromString<Release>(get(URL(BuildConfig.UPDATE_URL)).use {
                it.inputStream.bufferedReader().readText()
            })
            release.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    /** Downloads into the cache and refuses anything whose size or SHA-256 is off. */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update-${release.versionCode}.apk")
            val digest = MessageDigest.getInstance("SHA-256")

            get(URL(URL(BuildConfig.UPDATE_URL), release.apk)).use { conn ->
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            val percent = (done * 100 / release.size.coerceAtLeast(1)).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }

            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (target.length() != release.size || !hex.equals(release.sha256, ignoreCase = true)) {
                target.delete()
                error("安装包校验没通过，可能没下载完整，请重试")
            }
            target
        }
    }

    /** Android 8+ asks the user once to let this app install other apps. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun get(url: URL): Connection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            error("更新服务器返回 $code")
        }
        return Connection(conn)
    }

    private class Connection(private val conn: HttpURLConnection) : AutoCloseable {
        val inputStream get() = conn.inputStream
        override fun close() = conn.disconnect()
    }
}
