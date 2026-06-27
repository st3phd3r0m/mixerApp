package com.example.mixerapp.viewmodel

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mixerapp.data.reaper.ReaperProjectParser
import com.example.mixerapp.data.sessions.RecentSafFolder
import com.example.mixerapp.data.sessions.RecentSafFoldersStorage
import com.example.mixerapp.data.sessions.SessionProjectLink
import com.example.mixerapp.data.sessions.SessionProjectLinkStorage
import com.example.mixerapp.data.sessions.SessionsStorage
import com.example.mixerapp.model.Session
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
    private val recentFoldersStorage = RecentSafFoldersStorage(application.applicationContext)
    private val browserStack = ArrayDeque<DocumentFile>()
    private var browserRootUri: Uri? = null
    private var browserRootLabel: String = ""
    private var browserSortMode: BrowserSortMode = BrowserSortMode.NAME

    private val _sessions = MutableStateFlow(storage.loadSessions())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    private val _recentFolders = MutableStateFlow(recentFoldersStorage.load())
    val recentFolders: StateFlow<List<RecentSafFolder>> = _recentFolders.asStateFlow()

    private val _browserState = MutableStateFlow<FolderBrowserState?>(null)
    val browserState: StateFlow<FolderBrowserState?> = _browserState.asStateFlow()

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
                    onInvalidFolderUri(folderUri, "Dossier invalide ou non accessible")
                    return@launch
                }
                rememberRecentFolder(root)
                updateImportProgress(20)

                // Scan des .rpp sur IO thread (bloquant), main thread libre pour recomposer
                val projectFiles = withContext(Dispatchers.IO) {
                    runCatching { findProjectFiles(root) }.getOrNull()
                }
                if (projectFiles == null) {
                    onInvalidFolderUri(folderUri, "Impossible d'ouvrir ce dossier. Re-selectionnez-le.")
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

    fun openRecentFolderBrowser(folderUri: Uri) {
        viewModelScope.launch {
            val root = DocumentFile.fromTreeUri(getApplication(), folderUri)
            if (root == null || !root.isDirectory) {
                onInvalidFolderUri(folderUri, "Ce dossier recent n'est plus accessible. Re-selectionnez-le.")
                return@launch
            }

            openFolderBrowser(root)
        }
    }

    private fun openFolderBrowser(root: DocumentFile) {
            browserStack.clear()
            browserStack.addLast(root)
            browserRootUri = root.uri
            browserRootLabel = root.name?.takeIf { it.isNotBlank() } ?: "Dossier"
            browserSortMode = BrowserSortMode.NAME
            rememberRecentFolder(root)
            refreshBrowserState()
    }

    fun closeFolderBrowser() {
        _browserState.value = null
        browserStack.clear()
        browserRootUri = null
        browserRootLabel = ""
    }

    fun browseInto(folderUri: Uri) {
        val current = browserStack.lastOrNull() ?: return
        val next = runCatching {
            current.listFiles().firstOrNull { it.isDirectory && it.uri == folderUri }
        }.getOrNull()

        if (next == null) {
            _importMessage.value = "Dossier introuvable"
            return
        }

        browserStack.addLast(next)
        refreshBrowserState()
    }

    fun browseUp() {
        if (browserStack.size <= 1) return
        browserStack.removeLast()
        refreshBrowserState()
    }

    fun setBrowserSortMode(mode: BrowserSortMode) {
        if (browserSortMode == mode) return
        browserSortMode = mode
        refreshBrowserState()
    }

    fun importProjectFromBrowser(projectUri: Uri) {
        val rootUri = browserRootUri ?: return
        val projectName = _browserState.value
            ?.entries
            ?.firstOrNull { it.uri == projectUri }
            ?.name

        // Affiche le spinner de progression EN PREMIER pour qu'il soit visible
        startImportProgress()

        // ENSUITE ferme le dialog pour ne pas masquer le spinner
        closeFolderBrowser()

        viewModelScope.launch {
            try {
                updateImportProgress(30)
                importProjectFromFile(
                    projectUri = projectUri,
                    projectDisplayName = projectName,
                    folderUri = rootUri,
                    multiProjectHint = false
                )
                updateImportProgress(100)
            } finally {
                finishImportProgress()
            }
        }
    }

    fun removeRecentFolder(folderUri: Uri) {
        _recentFolders.update { current ->
            val updated = current.filterNot { it.uri == folderUri }
            recentFoldersStorage.save(updated)
            updated
        }
    }

    private fun importProjectFromFile(
        projectUri: Uri,
        projectDisplayName: String?,
        folderUri: Uri,
        multiProjectHint: Boolean
    ) {
        updateImportProgress(45)
        val content = runCatching {
            getApplication<Application>().contentResolver.openInputStream(projectUri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalStateException("Impossible de lire ${projectDisplayName ?: "le projet"}")
        }.getOrElse {
            _importMessage.value = "Import impossible: ${it.message ?: "erreur de lecture"}"
            return
        }

        val project = ReaperProjectParser.parseProject(content)
        val slices = ReaperProjectParser.markerSlices(project)
        val baseName = projectDisplayName
            ?.removeSuffix(".rpp")
            ?.takeIf { it.isNotBlank() }
            ?: "Session importee"

        val createdSessions = mutableListOf<Session>()
        val startId = (_sessions.value.maxOfOrNull { it.id } ?: -1) + 1
        updateImportProgress(60)

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
            val session = createdSessions.firstOrNull() ?: return
            projectLinkStorage.save(
                session.id,
                SessionProjectLink(
                    projectUri = projectUri,
                    folderUri = folderUri
                )
            )
        } else {
            slices.forEachIndexed { index, slice ->
                val session = createdSessions.getOrNull(index) ?: return@forEachIndexed
                projectLinkStorage.save(
                    session.id,
                    SessionProjectLink(
                        projectUri = projectUri,
                        folderUri = folderUri,
                        markerName = slice.name,
                        markerStartSec = slice.startSec,
                        markerEndSec = slice.endSec
                    )
                )
            }
        }
        updateImportProgress(85)

        val multiHint = if (multiProjectHint) {
            " (plusieurs .rpp detectes, premier selectionne)"
        } else {
            ""
        }

        _importMessage.value = if (slices.isEmpty()) {
            "Session '$baseName' creee$multiHint"
        } else {
            "${slices.size} sessions creees depuis markers$multiHint"
        }
        updateImportProgress(100)
    }

    private fun onInvalidFolderUri(folderUri: Uri, message: String) {
        removeRecentFolder(folderUri)
        closeFolderBrowser()
        _importMessage.value = message
    }

    private fun rememberRecentFolder(folder: DocumentFile) {
        val label = folder.name?.takeIf { it.isNotBlank() } ?: "Dossier"
        val relativePath = buildRelativePath(folder.uri)
        rememberRecentFolder(folder.uri, label, relativePath)
    }

    private fun rememberRecentFolder(folderUri: Uri, label: String, relativePath: String) {
        val now = System.currentTimeMillis()
        _recentFolders.update { current ->
            val updated = (
                listOf(RecentSafFolder(folderUri, label, relativePath, now)) + current.filterNot { it.uri == folderUri }
            )
                .sortedByDescending { it.lastUsedAt }
                .take(MAX_RECENT_FOLDERS)
            recentFoldersStorage.save(updated)
            updated
        }
    }

    private fun refreshBrowserState() {
        val rootUri = browserRootUri ?: return
        val current = browserStack.lastOrNull() ?: return
        val entries = runCatching {
            current.listFiles()
                .mapNotNull { child ->
                    val name = child.name ?: return@mapNotNull null
                    val isProject = child.isFile && name.endsWith(".rpp", ignoreCase = true)
                    if (!child.isDirectory && !isProject) return@mapNotNull null

                    BrowserEntry(
                        uri = child.uri,
                        name = name,
                        isDirectory = child.isDirectory,
                        lastModified = child.lastModified()
                    )
                }
                .sortedWith(browserComparator())
        }.getOrElse {
            onInvalidFolderUri(rootUri, "Acces au dossier perdu. Merci de le re-selectionner.")
            return
        }

        val currentLabel = current.name ?: browserRootLabel
        _browserState.value = FolderBrowserState(
            rootUri = rootUri,
            rootLabel = browserRootLabel,
            currentLabel = currentLabel,
            entries = entries,
            canGoUp = browserStack.size > 1,
            sortMode = browserSortMode
        )
    }

    private fun browserComparator(): Comparator<BrowserEntry> {
        val base = compareBy<BrowserEntry> { !it.isDirectory }
        return when (browserSortMode) {
            BrowserSortMode.NAME -> base.thenBy { it.name.lowercase() }
            BrowserSortMode.DATE -> base.thenByDescending { it.lastModified }.thenBy { it.name.lowercase() }
        }
    }

    private fun buildRelativePath(uri: Uri): String {
        val full = uri.path.orEmpty()
        if (full.isBlank()) return "Dossier"
        val marker = "/tree/"
        val idx = full.indexOf(marker)
        val raw = if (idx >= 0) full.substring(idx + marker.length) else full.substringAfterLast('/')
        return Uri.decode(raw).replace(':', '/').trim('/').ifBlank { "Dossier" }
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

    data class BrowserEntry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long
    )

    enum class BrowserSortMode { NAME, DATE }

    data class FolderBrowserState(
        val rootUri: Uri,
        val rootLabel: String,
        val currentLabel: String,
        val entries: List<BrowserEntry>,
        val canGoUp: Boolean,
        val sortMode: BrowserSortMode
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

    companion object {
        private const val MAX_RECENT_FOLDERS = 6
    }
}
