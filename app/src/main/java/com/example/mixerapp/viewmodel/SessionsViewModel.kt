package com.example.mixerapp.viewmodel

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mixerapp.data.reaper.ReaperProjectParser
import com.example.mixerapp.data.sessions.SessionProjectLink
import com.example.mixerapp.data.sessions.SessionProjectLinkStorage
import com.example.mixerapp.data.sessions.SessionsStorage
import com.example.mixerapp.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SessionsStorage(application.applicationContext)
    private val projectLinkStorage = SessionProjectLinkStorage(application.applicationContext)

    private val _sessions = MutableStateFlow(storage.loadSessions())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun addSession(name: String) {
        val newId = (_sessions.value.maxOfOrNull { it.id } ?: -1) + 1
        _sessions.update { current ->
            val updated = current + Session(newId, name.ifBlank { "Session ${newId + 1}" })
            storage.saveSessions(updated)
            updated
        }
    }

    fun removeSession(sessionId: Int) {
        _sessions.update { current ->
            val updated = current.filter { it.id != sessionId }
            storage.saveSessions(updated)
            projectLinkStorage.clear(sessionId)
            updated
        }
    }

    fun importSessionFromReaperFolder(folderUri: Uri) {
        viewModelScope.launch {
            val root = DocumentFile.fromTreeUri(getApplication(), folderUri)
            if (root == null || !root.isDirectory) {
                _importMessage.value = "Dossier invalide"
                return@launch
            }

            val projectFiles = findProjectFiles(root)
            if (projectFiles.isEmpty()) {
                _importMessage.value = "Aucun fichier .rpp trouve dans ce dossier"
                return@launch
            }

            val selected = projectFiles.sortedBy { it.relativePath.lowercase() }.first()
            val content = runCatching {
                getApplication<Application>().contentResolver.openInputStream(selected.file.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("Impossible de lire ${selected.file.name ?: "le projet"}")
            }.getOrElse {
                _importMessage.value = "Import impossible: ${it.message ?: "erreur de lecture"}"
                return@launch
            }

            val project = ReaperProjectParser.parseProject(content)
            val slices = ReaperProjectParser.markerSlices(project)
            val baseName = selected.file.name
                ?.removeSuffix(".rpp")
                ?.takeIf { it.isNotBlank() }
                ?: "Session importee"

            val createdSessions = mutableListOf<Session>()
            val startId = (_sessions.value.maxOfOrNull { it.id } ?: -1) + 1

            _sessions.update { current ->
                val toCreate = if (slices.isEmpty()) {
                    listOf(Session(startId, baseName))
                } else {
                    slices.mapIndexed { index, slice ->
                        val safeName = slice.name.takeIf { it.isNotBlank() } ?: "Marker ${slice.index}"
                        Session(startId + index, safeName)
                    }
                }
                createdSessions += toCreate
                val updated = current + toCreate
                storage.saveSessions(updated)
                updated
            }

            if (slices.isEmpty()) {
                val session = createdSessions.firstOrNull() ?: return@launch
                projectLinkStorage.save(
                    session.id,
                    SessionProjectLink(
                        projectUri = selected.file.uri,
                        folderUri = folderUri
                    )
                )
            } else {
                slices.forEachIndexed { index, slice ->
                    val session = createdSessions.getOrNull(index) ?: return@forEachIndexed
                    projectLinkStorage.save(
                        session.id,
                        SessionProjectLink(
                            projectUri = selected.file.uri,
                            folderUri = folderUri,
                            markerName = slice.name,
                            markerStartSec = slice.startSec,
                            markerEndSec = slice.endSec
                        )
                    )
                }
            }

            val multiProjectHint = if (projectFiles.size > 1) {
                " (plusieurs .rpp detectes, premier selectionne)"
            } else {
                ""
            }

            _importMessage.value = if (slices.isEmpty()) {
                "Session '$baseName' creee$multiProjectHint"
            } else {
                "${slices.size} sessions creees depuis markers$multiProjectHint"
            }
        }
    }

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    private data class ProjectCandidate(
        val file: DocumentFile,
        val relativePath: String
    )

    private fun findProjectFiles(root: DocumentFile): List<ProjectCandidate> {
        val result = mutableListOf<ProjectCandidate>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to "")

        while (stack.isNotEmpty()) {
            val (current, relBase) = stack.removeFirst()
            current.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                val relPath = if (relBase.isEmpty()) childName else "$relBase/$childName"

                when {
                    child.isDirectory -> stack.add(child to relPath)
                    child.isFile && childName.endsWith(".rpp", ignoreCase = true) -> {
                        result += ProjectCandidate(child, relPath)
                    }
                }
            }
        }

        return result
    }
}

