package com.zorro.videodl.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One finished download, kept after the task row itself is cleared or the app restarts. */
@Serializable
data class HistoryEntry(
    val id: String,
    val url: String,
    val siteId: String,
    val siteName: String,
    val title: String? = null,
    val qualityLabel: String,
    val savedTo: String,
    /** MediaStore content URI; null on API < 29, or once the user deletes the file. */
    val uri: String? = null,
    val fileCount: Int = 1,
    val finishedAt: Long,
)

/**
 * Persistent log of successful downloads. Deliberately separate from the live
 * task list in [com.zorro.videodl.download.DownloadRepository]: clearing
 * finished rows there tidies the screen, it should not erase the record of
 * what was downloaded and when.
 *
 * A single JSON file is plenty for a few hundred entries and needs no schema.
 */
object DownloadHistory {

    private const val TAG = "DownloadHistory"
    private const val FILE_NAME = "history.json"
    /** Oldest entries drop off past this, so the file never grows without bound. */
    private const val MAX_ENTRIES = 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var file: File? = null

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) = synchronized(lock) {
        if (file != null) return@synchronized
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        if (f.exists()) {
            // A corrupt file costs the history, never the app.
            _entries.value = runCatching { json.decodeFromString<List<HistoryEntry>>(f.readText()) }
                .onFailure { Log.w(TAG, "unreadable history, starting fresh", it) }
                .getOrDefault(emptyList())
        }
    }

    fun record(entry: HistoryEntry) = mutate { list ->
        (listOf(entry) + list.filterNot { it.id == entry.id }).take(MAX_ENTRIES)
    }

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun clear() = mutate { emptyList() }

    private fun mutate(transform: (List<HistoryEntry>) -> List<HistoryEntry>) = synchronized(lock) {
        val next = transform(_entries.value)
        _entries.value = next
        val f = file ?: return@synchronized
        // Write-then-rename so a crash mid-write leaves the previous file intact.
        runCatching {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(next))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "could not save history", it) }
    }
}
