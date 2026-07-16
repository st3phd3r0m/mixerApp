package com.example.reapmixer.data.sessions

import android.content.Context
import android.net.Uri

data class SessionProjectLink(
    val projectUri: Uri,
    val folderUri: Uri?,
    val markerName: String? = null,
    val markerStartSec: Double? = null,
    val markerEndSec: Double? = null
)

class SessionProjectLinkStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(sessionId: Int): SessionProjectLink? {
        val raw = prefs.getString(key(sessionId), null) ?: return null
        val parts = raw.split('|')
        val project = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val folder = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val markerName = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(Uri::decode)
        val markerStartSec = parts.getOrNull(3)?.toDoubleOrNull()
        val markerEndSec = parts.getOrNull(4)?.toDoubleOrNull()
        return SessionProjectLink(
            projectUri = Uri.parse(project),
            folderUri = folder?.let(Uri::parse),
            markerName = markerName,
            markerStartSec = markerStartSec,
            markerEndSec = markerEndSec
        )
    }

    fun save(sessionId: Int, link: SessionProjectLink) {
        val serialized = buildString {
            append(link.projectUri.toString())
            append('|')
            append(link.folderUri?.toString().orEmpty())
            append('|')
            append(link.markerName?.let(Uri::encode).orEmpty())
            append('|')
            append(link.markerStartSec?.toString().orEmpty())
            append('|')
            append(link.markerEndSec?.toString().orEmpty())
        }
        prefs.edit().putString(key(sessionId), serialized).apply()
    }

    fun clear(sessionId: Int) {
        prefs.edit().remove(key(sessionId)).apply()
    }

    companion object {
        private const val PREFS_NAME = "session_project_links"
        private fun key(sessionId: Int) = "session_$sessionId"
    }
}

