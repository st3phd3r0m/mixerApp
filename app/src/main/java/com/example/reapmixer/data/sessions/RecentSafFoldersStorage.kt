package com.example.reapmixer.data.sessions

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

data class RecentSafFolder(
    val uri: Uri,
    val label: String,
    val relativePath: String,
    val lastUsedAt: Long
)

class RecentSafFoldersStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<RecentSafFolder> {
        val raw = prefs.getString(KEY_RECENTS, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                val uriRaw = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val labelRaw = parts.getOrNull(1).orEmpty()
                val part2 = parts.getOrNull(2).orEmpty()
                val part3 = parts.getOrNull(3).orEmpty()
                // Backward compatible: old format was uri|label|timestamp
                val hasV2 = part3.isNotBlank()
                val relativeRaw = if (hasV2) part2 else ""
                val ts = if (hasV2) part3.toLongOrNull() ?: 0L else part2.toLongOrNull() ?: 0L
                val decodedLabel = Uri.decode(labelRaw).ifBlank { uriRaw.substringAfterLast('/') }
                RecentSafFolder(
                    uri = uriRaw.toUri(),
                    label = decodedLabel,
                    relativePath = Uri.decode(relativeRaw).ifBlank { decodedLabel },
                    lastUsedAt = ts
                )
            }
            .sortedByDescending { it.lastUsedAt }
            .toList()
    }

    fun save(recents: List<RecentSafFolder>) {
        val serialized = recents.joinToString(separator = "\n") { folder ->
            "${folder.uri}|${Uri.encode(folder.label)}|${Uri.encode(folder.relativePath)}|${folder.lastUsedAt}"
        }
        prefs.edit { putString(KEY_RECENTS, serialized) }
    }

    companion object {
        private const val PREFS_NAME = "recent_saf_folders"
        private const val KEY_RECENTS = "folders"
    }
}

