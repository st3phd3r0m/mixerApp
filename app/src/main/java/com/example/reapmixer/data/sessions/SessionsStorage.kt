package com.example.reapmixer.data.sessions

import android.content.Context
import android.net.Uri
import com.example.reapmixer.model.Session
import androidx.core.content.edit

class SessionsStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSessions(): List<Session> {
        val raw = prefs.getString(KEY_SESSIONS, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .mapNotNull { line ->
                val sep = line.indexOf('|')
                if (sep <= 0) return@mapNotNull null
                val id = line.substring(0, sep).toIntOrNull() ?: return@mapNotNull null
                val encodedName = line.substring(sep + 1)
                Session(id = id, name = Uri.decode(encodedName))
            }
            .sortedBy { it.id }
            .toList()
    }

    fun saveSessions(sessions: List<Session>) {
        val serialized = sessions.joinToString(separator = "\n") { session ->
            "${session.id}|${Uri.encode(session.name)}"
        }
        prefs.edit { putString(KEY_SESSIONS, serialized) }
    }

    companion object {
        private const val PREFS_NAME = "sessions_prefs"
        private const val KEY_SESSIONS = "sessions_list"
    }
}

