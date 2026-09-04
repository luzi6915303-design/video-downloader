package com.zorro.videodl.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes a finished download into shared storage so it shows up in the
 * phone's Gallery / Files app and survives uninstalling this app.
 *
 * On API 29+ this goes through MediaStore and needs no runtime permission; on
 * older devices it falls back to a direct write, which is why the manifest
 * still declares WRITE_EXTERNAL_STORAGE with a maxSdkVersion.
 */
object MediaStoreSaver {

    const val ALBUM = "VideoDownloader"

    private val AUDIO_EXTS = setOf("m4a", "mp3", "opus", "aac", "flac", "ogg", "wav")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

    /** [uri] is null on API < 29, where there is no MediaStore entry to point at. */
    data class Saved(val displayName: String, val publicPath: String, val uri: String?)

    suspend fun publish(context: Context, source: File): Result<Saved> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = source.extension.lowercase()
            val isAudio = ext in AUDIO_EXTS
            val isImage = ext in IMAGE_EXTS

            val relativeDir = when {
                isAudio -> "${Environment.DIRECTORY_MUSIC}/$ALBUM"
                isImage -> "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                else -> "${Environment.DIRECTORY_MOVIES}/$ALBUM"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, source, relativeDir, isAudio, isImage)
            } else {
                publishLegacy(source, relativeDir)
            }
        }
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        relativeDir: String,
        isAudio: Boolean,
        isImage: Boolean,
    ): Saved {
        val collection = when {
            isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val name = uniqueName(context, collection, source.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(source.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            // Hide the entry from the gallery until the bytes are fully written.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore 拒绝创建条目：$name")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE * 8) }
            } ?: error("无法打开输出流：$name")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }

        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        source.delete()
        return Saved(name, "$relativeDir/$name", uri.toString())
    }

    private fun publishLegacy(source: File, relativeDir: String): Saved {
        val targetDir = File(Environment.getExternalStorageDirectory(), relativeDir).apply { mkdirs() }
        var target = File(targetDir, source.name)
        var i = 1
        while (target.exists()) {
            target = File(targetDir, "${source.nameWithoutExtension} ($i).${source.extension}")
            i++
        }
        source.copyTo(target, overwrite = false)
        source.delete()
        return Saved(target.name, "$relativeDir/${target.name}", null)
    }

    /** Avoids MediaStore silently renaming to "foo (1).mp4" by picking a free name up front. */
    private fun uniqueName(context: Context, collection: android.net.Uri, original: String): String {
        val base = original.substringBeforeLast('.', original)
        val ext = original.substringAfterLast('.', "")
        var candidate = original
        var i = 1
        while (exists(context, collection, candidate) && i < 100) {
            candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            i++
        }
        return candidate
    }

    private fun exists(context: Context, collection: android.net.Uri, name: String): Boolean =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.count > 0 } ?: false

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
