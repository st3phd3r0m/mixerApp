package com.example.reapmixer.viewmodel

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.reapmixer.data.reaper.ReaperProjectParser
import com.example.reapmixer.data.sessions.SessionProjectLink
import com.example.reapmixer.data.sessions.SessionProjectLinkStorage
import com.example.reapmixer.data.sessions.SessionsStorage
import com.example.reapmixer.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SessionsStorage(application.applicationContext)
    private val projectLinkStorage = SessionProjectLinkStorage(application.applicationContext)

    private val _sessions = MutableStateFlow(storage.loadSessions())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    private val _isImportingProject = MutableStateFlow(false)
    val isImportingProject: StateFlow<Boolean> = _isImportingProject.asStateFlow()

    private val _importProgressPercent = MutableStateFlow(0)
    val importProgressPercent: StateFlow<Int> = _importProgressPercent.asStateFlow()

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

    /** Appelée depuis le callback du picker SAF (main thread) pour afficher le spinner immédiatement */
    fun startImportProgressNow() {
        startImportProgress()
    }

    /** Lance l'import du dossier avec un délai de 500ms pour laisser le picker se fermer et Compose recomposer */
    fun importSessionFromReaperFolderWithDelay(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.Main) {
            // Attendre 500ms pour laisser le picker se fermer + Compose recomposer le spinner
            delay(500)

            val startTimeMs = System.currentTimeMillis()
            try {
                // Vérification dossier (IO)
                val root = withContext(Dispatchers.IO) {
                    DocumentFile.fromTreeUri(getApplication(), folderUri)
                }
                if (root == null || !root.isDirectory) {
                    onInvalidFolderUri("Dossier invalide ou non accessible")
                    return@launch
                }
                updateImportProgress(20)

                // Scan des .rpp sur IO thread (bloquant), main thread libre pour recomposer
                val projectFiles = withContext(Dispatchers.IO) {
                    runCatching { findProjectFiles(root) }.getOrNull()
                }
                if (projectFiles == null) {
                    onInvalidFolderUri("Impossible d'ouvrir ce dossier. Re-selectionnez-le.")
                    return@launch
                }
                if (projectFiles.isEmpty()) {
                    _importMessage.value = "Aucun fichier .rpp trouve dans ce dossier"
                    return@launch
                }
                updateImportProgress(35)

                val selected = projectFiles.sortedBy { it.relativePath.lowercase() }.first()

                // Lecture du fichier .rpp sur IO thread
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.openInputStream(selected.file.uri)
                            ?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (content == null) {
                    _importMessage.value = "Import impossible: erreur de lecture"
                    return@launch
                }
                updateImportProgress(50)

                // Parse sur IO thread (CPU)
                val slices = withContext(Dispatchers.IO) {
                    val p = ReaperProjectParser.parseProject(content)
                    ReaperProjectParser.markerSlices(p)
                }
                updateImportProgress(70)

                // Création sessions sur Main thread (StateFlow)
                val baseName = selected.file.name?.removeSuffix(".rpp")?.takeIf { it.isNotBlank() } ?: "Session importee"
                val createdSessions = mutableListOf<Session>()
                val startId = (_sessions.value.maxOfOrNull { it.id } ?: -1) + 1
                _sessions.update { current ->
                    val toCreate = if (slices.isEmpty()) {
                        listOf(Session(startId, baseName))
                    } else {
                        slices.mapIndexed { index, slice ->
                            Session(startId + index, slice.name.takeIf { it.isNotBlank() } ?: "Marker ${slice.index}")
                        }
                    }
                    createdSessions += toCreate
                    val updated = current + toCreate
                    storage.saveSessions(updated)
                    updated
                }
                updateImportProgress(85)

                // Sauvegarde des liens sur IO thread
                withContext(Dispatchers.IO) {
                    if (slices.isEmpty()) {
                        createdSessions.firstOrNull()?.let { session ->
                            projectLinkStorage.save(session.id, SessionProjectLink(projectUri = selected.file.uri, folderUri = folderUri))
                        }
                    } else {
                        slices.forEachIndexed { index, slice ->
                            val session = createdSessions.getOrNull(index) ?: return@forEachIndexed
                            projectLinkStorage.save(session.id, SessionProjectLink(
                                projectUri = selected.file.uri,
                                folderUri = folderUri,
                                markerName = slice.name,
                                markerStartSec = slice.startSec,
                                markerEndSec = slice.endSec
                            ))
                        }
                    }
                }
                updateImportProgress(100)

                val multiHint = if (projectFiles.size > 1) " (plusieurs .rpp detectes, premier selectionne)" else ""
                _importMessage.value = if (slices.isEmpty()) "Session '$baseName' creee$multiHint"
                                       else "${slices.size} sessions creees depuis markers$multiHint"

                // Garder le spinner visible au moins 800ms
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                if (elapsedMs < 800L) delay(800L - elapsedMs)

            } finally {
                finishImportProgress()
            }
        }
    }

    private fun onInvalidFolderUri(message: String) {
        _importMessage.value = message
    }

    private fun startImportProgress() {
        _importProgressPercent.value = 0
        _isImportingProject.value = true
    }

    private fun finishImportProgress() {
        _isImportingProject.value = false
    }

    private fun updateImportProgress(percent: Int) {
        _importProgressPercent.value = percent.coerceIn(0, 100)
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

    companion object {}
}
