package com.example.mixerapp.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import com.example.mixerapp.data.reaper.ReaperProjectParser
import com.example.mixerapp.data.reaper.ReaperTrackData
import com.example.mixerapp.data.sessions.SessionProjectLink
import com.example.mixerapp.data.sessions.SessionProjectLinkStorage
import com.example.mixerapp.audio.ChannelModeAudioProcessor
import com.example.mixerapp.model.AudioMode
import com.example.mixerapp.model.LimiterConfiguration
import com.example.mixerapp.model.TrackState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MixerViewModel(
    application: Application,
    val sessionId: Int
) : AndroidViewModel(application) {

    private data class MarkerRange(
        val startSec: Double,
        val endSec: Double?
    )

    private val sessionProjectLinkStorage = SessionProjectLinkStorage(application.applicationContext)

    data class PendingProjectChoice(
        val name: String,
        val relativePath: String,
        val projectUri: Uri,
        val folderUri: Uri
    )

    companion object {
        const val TRACK_COUNT = 6
    }

    // ────────────────────────── État UI ──────────────────────────────────────

    private val _tracks = MutableStateFlow(
        List(TRACK_COUNT) { i -> TrackState(id = i) }
    )
    val tracks: StateFlow<List<TrackState>> = _tracks.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    private val _visibleTrackCount = MutableStateFlow(TRACK_COUNT)
    val visibleTrackCount: StateFlow<Int> = _visibleTrackCount.asStateFlow()

    private val _pendingProjectChoices = MutableStateFlow<List<PendingProjectChoice>>(emptyList())
    val pendingProjectChoices: StateFlow<List<PendingProjectChoice>> = _pendingProjectChoices.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _projectPositionMs = MutableStateFlow(0L)
    val projectPositionMs: StateFlow<Long> = _projectPositionMs.asStateFlow()

    private val _projectDurationMs = MutableStateFlow(0L)
    val projectDurationMs: StateFlow<Long> = _projectDurationMs.asStateFlow()

    private val _isImportingProject = MutableStateFlow(false)
    val isImportingProject: StateFlow<Boolean> = _isImportingProject.asStateFlow()

    private val _importProgressPercent = MutableStateFlow(0)
    val importProgressPercent: StateFlow<Int> = _importProgressPercent.asStateFlow()

    private val _importProjectLabel = MutableStateFlow<String?>(null)
    val importProjectLabel: StateFlow<String?> = _importProjectLabel.asStateFlow()

    private val _limiterConfig = MutableStateFlow(LimiterConfiguration())
    val limiterConfig: StateFlow<LimiterConfiguration> = _limiterConfig.asStateFlow()

    private val _masterVolume = MutableStateFlow(1.0f)
    val masterVolume: StateFlow<Float> = _masterVolume.asStateFlow()


    private var progressJob: Job? = null
    private var playStartRealtimeMs: Long = 0L
    private var virtualStartPositionMs: Long = 0L   // Position virtuelle au démarrage/resume
    private var lastPausedPositionMs: Long = 0L      // Position virtuelle sauvée lors du pause
    private val pendingOffsetJobs = mutableMapOf<Int, Job>()  // Jobs de démarrage décalé

    // ────────────────────────── Couche audio ──────────────────────────────────

    private data class TrackPlayer(
        val player: ExoPlayer,
        val processor: ChannelModeAudioProcessor
    )

    private val trackPlayers: List<TrackPlayer> = buildPlayers(application.applicationContext)

    init {
        restoreLinkedProjectIfAny()
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayers(context: Context): List<TrackPlayer> =
        List(TRACK_COUNT) { trackId ->
            val processor = ChannelModeAudioProcessor()
            processor.limiterConfig = _limiterConfig.value
            processor.volume = _tracks.value[trackId].volume
            processor.masterVolume = _masterVolume.value
            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(processor))
                        .build()
                }
            }
            val player = ExoPlayer.Builder(context, renderersFactory).build().also {
                it.repeatMode = Player.REPEAT_MODE_OFF
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            clearTrackError(trackId)
                        }
                        handlePlaybackEndedIfNeeded()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        setTrackError(trackId, toFriendlyPlaybackError(error))
                    }
                })
            }
            TrackPlayer(player, processor)
        }

    // ────────────────────────── Actions piste ─────────────────────────────────

    fun loadAudio(trackId: Int, uri: Uri) {
        _tracks.update { list ->
            list.map {
                if (it.id == trackId) it.copy(uri = uri, isLoaded = true, playbackError = null) else it
            }
        }
        trackPlayers[trackId].player.run {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            if (_isPlaying.value) play()
        }
        updatePlaybackProgress()
    }

    fun retryLoadAudio(trackId: Int) {
        val uri = _tracks.value.getOrNull(trackId)?.uri ?: return
        loadAudio(trackId, uri)
    }

    fun setVolume(trackId: Int, volume: Float) {
        _tracks.update { list ->
            list.map { if (it.id == trackId) it.copy(volume = volume) else it }
        }
        applyVolume(trackId, _tracks.value)
    }

    fun toggleMute(trackId: Int) {
        _tracks.update { list ->
            list.map { if (it.id == trackId) it.copy(isMuted = !it.isMuted) else it }
        }
        applyAllVolumes(_tracks.value)
    }

    fun toggleSolo(trackId: Int) {
        _tracks.update { list ->
            list.map { if (it.id == trackId) it.copy(isSolo = !it.isSolo) else it }
        }
        applyAllVolumes(_tracks.value)
    }

    fun setAudioMode(trackId: Int, mode: AudioMode) {
        _tracks.update { list ->
            list.map { if (it.id == trackId) it.copy(audioMode = mode) else it }
        }
        // Mise à jour immédiate du processeur audio (thread-safe via @Volatile)
        trackPlayers[trackId].processor.audioMode = mode
    }

    fun importReaperProject(
        projectUri: Uri,
        persistLink: Boolean = true,
        markerStartSec: Double? = null,
        markerEndSec: Double? = null,
        markerName: String? = null
    ) {
        viewModelScope.launch {
            startImportProgress(projectUri.lastPathSegment)
            try {
                val content = runCatching {
                    getApplication<Application>().contentResolver.openInputStream(projectUri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw IllegalStateException("Impossible de lire le fichier projet")
                }.getOrElse {
                    _importMessage.value = "Import impossible: ${it.message ?: "erreur de lecture"}"
                    return@launch
                }
                updateImportProgress(20)

                val markerRange = if (markerStartSec != null) MarkerRange(markerStartSec, markerEndSec) else null
                val project = ReaperProjectParser.parseProject(content)
                val parsedTracks = markerRange?.let {
                    ReaperProjectParser.tracksForMarker(project, it.startSec, it.endSec)
                } ?: ReaperProjectParser.parse(content)
                if (parsedTracks.isEmpty()) {
                    _importMessage.value = "Aucune piste exploitable trouvee dans ce projet"
                    return@launch
                }
                updateImportProgress(35)

                val loadedAudioCount = applyImportedSession(parsedTracks) { _, track ->
                    resolveSourceFileUri(projectUri, track.sourceFile)
                }

                if (persistLink) {
                    sessionProjectLinkStorage.save(
                        sessionId,
                        SessionProjectLink(
                            projectUri = projectUri,
                            folderUri = null,
                            markerName = markerName,
                            markerStartSec = markerRange?.startSec,
                            markerEndSec = markerRange?.endSec
                        )
                    )
                }

                val importedCount = parsedTracks.take(TRACK_COUNT).size
                val missingAudio = importedCount - loadedAudioCount
                _importMessage.value = if (missingAudio > 0) {
                    "Session importee: $importedCount piste(s), $loadedAudioCount audio(s) charges. " +
                        "$missingAudio fichier(s) introuvable(s): utilise 'Importer dossier session'."
                } else {
                    "Session importee: $importedCount piste(s), $loadedAudioCount audio(s) charges"
                }
                updateImportProgress(100)
            } finally {
                finishImportProgress()
            }
        }
    }

    fun chooseProjectFromFolder(
        projectUri: Uri,
        folderUri: Uri,
        persistLink: Boolean = true,
        markerStartSec: Double? = null,
        markerEndSec: Double? = null,
        markerName: String? = null
    ) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val root = DocumentFile.fromTreeUri(getApplication(), folderUri)
            if (root == null || !root.isDirectory) {
                _pendingProjectChoices.value = emptyList()
                _importMessage.value = "Dossier invalide"
                return@launch
            }
            val projectFile = DocumentFile.fromSingleUri(getApplication(), projectUri)
            if (projectFile == null || !projectFile.isFile) {
                _pendingProjectChoices.value = emptyList()
                _importMessage.value = "Fichier .rpp invalide"
                return@launch
            }

            _pendingProjectChoices.value = emptyList()
            importReaperProjectDocument(
                root,
                projectFile,
                resolver,
                persistLink,
                markerStartSec,
                markerEndSec,
                markerName
            )
        }
    }

    fun cancelProjectChoice() {
        _pendingProjectChoices.value = emptyList()
        _importMessage.value = "Import annule"
    }

    private fun importReaperProjectDocument(
        root: DocumentFile,
        projectFile: DocumentFile,
        resolver: android.content.ContentResolver,
        persistLink: Boolean = true,
        markerStartSec: Double? = null,
        markerEndSec: Double? = null,
        markerName: String? = null
    ) {
        viewModelScope.launch {
            startImportProgress(projectFile.name)
            try {
                val content = runCatching {
                    resolver.openInputStream(projectFile.uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw IllegalStateException("Impossible de lire ${projectFile.name ?: "le projet"}")
                }.getOrElse {
                    _importMessage.value = "Import impossible: ${it.message ?: "erreur de lecture"}"
                    return@launch
                }
                updateImportProgress(20)

                val markerRange = if (markerStartSec != null) MarkerRange(markerStartSec, markerEndSec) else null
                val project = ReaperProjectParser.parseProject(content)
                val parsedTracks = markerRange?.let {
                    ReaperProjectParser.tracksForMarker(project, it.startSec, it.endSec)
                } ?: ReaperProjectParser.parse(content)
                if (parsedTracks.isEmpty()) {
                    _importMessage.value = "Aucune piste exploitable trouvee dans ce projet"
                    return@launch
                }
                updateImportProgress(35)

                val indexedFiles = buildAudioIndex(root)
                val loadedAudioCount = applyImportedSession(parsedTracks) { _, track ->
                    resolveSourceFileInTree(track.sourceFile, indexedFiles)
                }

                if (persistLink) {
                    sessionProjectLinkStorage.save(
                        sessionId,
                        SessionProjectLink(
                            projectUri = projectFile.uri,
                            folderUri = root.uri,
                            markerName = markerName,
                            markerStartSec = markerRange?.startSec,
                            markerEndSec = markerRange?.endSec
                        )
                    )
                }

                val importedCount = parsedTracks.take(TRACK_COUNT).size
                _importMessage.value = "Import ${projectFile.name ?: "dossier"} OK: $importedCount piste(s), $loadedAudioCount audio(s) charges"
                updateImportProgress(100)
            } finally {
                finishImportProgress()
            }
        }
    }

    private fun applyImportedSession(
        parsedTracks: List<ReaperTrackData>,
        sourceResolver: (Int, ReaperTrackData) -> Uri?
    ): Int {
        val limited = parsedTracks.take(TRACK_COUNT)
        _visibleTrackCount.value = limited.size
        var loadedAudioCount = 0
        updateImportProgress(40)

        _tracks.update { current ->
            current.map { existing ->
                val imported = limited.getOrNull(existing.id)
                if (imported == null) {
                    existing.copy(
                        uri = null,
                        isLoaded = false,
                        playbackError = null,
                        isMuted = false,
                        isSolo = false,
                        volume = 0.8f,
                        audioMode = AudioMode.STEREO
                    )
                } else {
                    existing.copy(
                        name = imported.name?.takeIf { it.isNotBlank() } ?: "Track ${existing.id + 1}",
                        volume = (imported.volume ?: 0.8f).coerceAtLeast(0f),
                        isMuted = imported.isMuted ?: false,
                        isSolo = imported.isSolo ?: false,
                        audioMode = ReaperProjectParser.panToAudioMode(imported.pan),
                        uri = null,
                        isLoaded = false,
                        playbackError = null,
                        startOffsetMs = (imported.startOffsetSec * 1000.0).toLong().coerceAtLeast(0L)
                    )
                }
            }
        }

        _tracks.value.forEachIndexed { index, track ->
            trackPlayers[index].processor.audioMode = track.audioMode
        }

        limited.forEachIndexed { index, track ->
            val resolvedUri = sourceResolver(index, track)
            if (resolvedUri != null) {
                loadAudio(index, resolvedUri)
                loadedAudioCount++
            }
            if (limited.isNotEmpty()) {
                val step = ((index + 1) * 60) / limited.size
                updateImportProgress(40 + step)
            }
        }

        applyAllVolumes(_tracks.value)
        return loadedAudioCount
    }

    private fun restoreLinkedProjectIfAny() {
        val link = sessionProjectLinkStorage.load(sessionId) ?: return
        if (link.folderUri != null) {
            chooseProjectFromFolder(
                link.projectUri,
                link.folderUri,
                persistLink = false,
                markerStartSec = link.markerStartSec,
                markerEndSec = link.markerEndSec,
                markerName = link.markerName
            )
        } else {
            importReaperProject(
                link.projectUri,
                persistLink = false,
                markerStartSec = link.markerStartSec,
                markerEndSec = link.markerEndSec,
                markerName = link.markerName
            )
        }
    }

    private fun buildAudioIndex(root: DocumentFile): Map<String, Uri> {
        val index = mutableMapOf<String, Uri>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            current.listFiles().forEach { child ->
                when {
                    child.isDirectory -> stack.add(child)
                    child.isFile -> {
                        val name = child.name?.trim()?.lowercase() ?: return@forEach
                        if (name.endsWith(".wav") && name !in index) {
                            index[name] = child.uri
                        }
                    }
                }
            }
        }
        return index
    }

    private fun resolveSourceFileInTree(sourceFile: String?, audioIndex: Map<String, Uri>): Uri? {
        val source = sourceFile?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = source.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return audioIndex[key]
    }

    private fun startImportProgress(projectLabel: String?) {
        _importProjectLabel.value = projectLabel?.takeIf { it.isNotBlank() }
        _importProgressPercent.value = 0
        _isImportingProject.value = true
    }

    private fun finishImportProgress() {
        _isImportingProject.value = false
        _importProjectLabel.value = null
    }

    private fun updateImportProgress(percent: Int) {
        _importProgressPercent.value = percent.coerceIn(0, 100)
    }

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    // ────────────────────────── Master Limiter ───────────────────────────────

    fun updateLimiterConfig(config: LimiterConfiguration) {
        _limiterConfig.value = config
        // Update audio processors
        trackPlayers.forEach { it.processor.limiterConfig = config }
    }

    fun saveProject() {
        val link = sessionProjectLinkStorage.load(sessionId) ?: return
        val projectUri = link.projectUri
        val resolver = getApplication<Application>().contentResolver

        viewModelScope.launch {
            try {
                // 1. Vérifier que le fichier est accessible
                val originalContent = resolver.openInputStream(projectUri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Impossible de lire le projet pour sauvegarde")

                // 2. Préparer les mises à jour
                val currentTracks = _tracks.value
                val updates = currentTracks.map { track ->
                    ReaperProjectParser.TrackUpdate(
                        volume = track.volume,
                        pan = ReaperProjectParser.audioModeToPan(track.audioMode),
                        isMuted = track.isMuted,
                        isSolo = track.isSolo
                    )
                }

                // 3. Générer le nouveau contenu
                val newContent = ReaperProjectParser.updateProjectSettings(originalContent, updates)

                // 4. Écrire le fichier avec gestion des permissions
                try {
                    resolver.openOutputStream(projectUri, "rwt")?.use { out ->
                        out.write(newContent.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    _importMessage.value = "Done !"
                } catch (_: SecurityException) {
                    _importMessage.value = "Erreur de permission: Impossible d'écrire dans le fichier. Vérifiez que l'application a les permissions nécessaires."
                } catch (e: Exception) {
                    _importMessage.value = "Erreur lors de la sauvegarde: ${e.message ?: "erreur inconnue"}"
                }
            } catch (_: SecurityException) {
                _importMessage.value = "Erreur de permission: Impossible d'accéder au fichier. Vérifiez que l'application a les permissions nécessaires."
            } catch (e: Exception) {
                _importMessage.value = "Erreur lors de la sauvegarde : ${e.message}"
            }
        }
    }

    fun setMasterVolume(volume: Float) {
        _masterVolume.value = volume
        trackPlayers.forEach { it.processor.masterVolume = volume }
    }

    // ────────────────────────── Transport global ──────────────────────────────

    fun playAll() {
        val loadedTracks = _tracks.value.filter { it.isLoaded }
        if (loadedTracks.isEmpty()) {
            _isPlaying.value = false
            return
        }

        _isPlaying.value = true
        val resumeFromMs = lastPausedPositionMs
        playStartRealtimeMs = System.currentTimeMillis()
        virtualStartPositionMs = resumeFromMs

        // Annuler les jobs de démarrage décalé en attente
        pendingOffsetJobs.values.forEach { it.cancel() }
        pendingOffsetJobs.clear()

        loadedTracks.forEach { track ->
            val tp = trackPlayers[track.id]
            val offsetMs = track.startOffsetMs

            if (offsetMs <= resumeFromMs) {
                // Cette piste devrait déjà jouer: seek à la bonne position dans le fichier
                val filePositionMs = resumeFromMs - offsetMs
                if (filePositionMs > 0L) {
                    tp.player.seekTo(filePositionMs)
                } else if (tp.player.playbackState == Player.STATE_ENDED) {
                    tp.player.seekTo(0)
                }
                tp.player.play()
            } else {
                // Cette piste démarre plus tard: programmer le démarrage décalé
                tp.player.seekTo(0)
                tp.player.pause()
                val delayMs = offsetMs - resumeFromMs
                val job = viewModelScope.launch {
                    delay(delayMs)
                    if (_isPlaying.value) tp.player.play()
                }
                pendingOffsetJobs[track.id] = job
            }
        }
        startProgressTicker()
    }

    fun pauseAll() {
        lastPausedPositionMs = _projectPositionMs.value
        _isPlaying.value = false
        pendingOffsetJobs.values.forEach { it.cancel() }
        pendingOffsetJobs.clear()
        trackPlayers.forEach { it.player.pause() }
        stopProgressTicker()
        updatePlaybackProgress()
    }

    fun stopAll() {
        lastPausedPositionMs = 0L
        virtualStartPositionMs = 0L
        _isPlaying.value = false
        pendingOffsetJobs.values.forEach { it.cancel() }
        pendingOffsetJobs.clear()
        trackPlayers.forEach { tp ->
            tp.player.pause()
            tp.player.seekTo(0)
        }
        stopProgressTicker()
        updatePlaybackProgress(forcePositionZero = true)
    }

    fun seekToProgress(progress: Float) {
        val durationMs = _projectDurationMs.value
        if (durationMs <= 0L) return
        val clampedProgress = progress.coerceIn(0f, 1f)
        val targetMs = (durationMs * clampedProgress).toLong().coerceIn(0L, durationMs)

        // Annuler les jobs décalés en attente
        pendingOffsetJobs.values.forEach { it.cancel() }
        pendingOffsetJobs.clear()

        lastPausedPositionMs = targetMs
        virtualStartPositionMs = targetMs
        playStartRealtimeMs = System.currentTimeMillis()
        _projectPositionMs.value = targetMs

        _tracks.value.filter { it.isLoaded }.forEach { track ->
            val tp = trackPlayers[track.id]
            val offsetMs = track.startOffsetMs
            if (targetMs >= offsetMs) {
                tp.player.seekTo(targetMs - offsetMs)
                if (_isPlaying.value) tp.player.play()
            } else {
                tp.player.seekTo(0)
                tp.player.pause()
                if (_isPlaying.value) {
                    val delayMs = offsetMs - targetMs
                    val job = viewModelScope.launch {
                        delay(delayMs)
                        if (_isPlaying.value) tp.player.play()
                    }
                    pendingOffsetJobs[track.id] = job
                }
            }
        }
    }

    private fun handlePlaybackEndedIfNeeded() {
        if (!_isPlaying.value) return

        val loadedIndices = _tracks.value
            .filter { it.isLoaded }
            .map { it.id }

        if (loadedIndices.isEmpty()) return

        val allEnded = loadedIndices.all { index ->
            trackPlayers[index].player.playbackState == Player.STATE_ENDED
        }

        if (allEnded) {
            _isPlaying.value = false
            stopProgressTicker()
            updatePlaybackProgress()
        }
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (isActive && _isPlaying.value) {
                updatePlaybackProgress()
                delay(120)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updatePlaybackProgress(forcePositionZero: Boolean = false) {
        val loadedTracks = _tracks.value.filter { it.isLoaded }

        if (loadedTracks.isEmpty()) {
            _projectDurationMs.value = 0L
            _projectPositionMs.value = 0L
            _playbackProgress.value = 0f
            return
        }

        // Durée totale = max(durée fichier + offset) sur toutes les pistes chargées
        var durationMs = 0L
        loadedTracks.forEach { track ->
            val player = trackPlayers[track.id].player
            val d = player.duration
            if (d != C.TIME_UNSET && d > 0) {
                val virtualDuration = d + track.startOffsetMs
                if (virtualDuration > durationMs) durationMs = virtualDuration
            }
        }

        // Position virtuelle = temps écoulé depuis le dernier play (horloge temps-réel)
        val positionMs = when {
            forcePositionZero -> 0L
            _isPlaying.value && playStartRealtimeMs > 0L -> {
                val elapsed = System.currentTimeMillis() - playStartRealtimeMs
                (virtualStartPositionMs + elapsed).coerceAtLeast(0L)
            }
            else -> lastPausedPositionMs
        }

        val clampedPosition = if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs
        _projectDurationMs.value = durationMs.coerceAtLeast(0L)
        _projectPositionMs.value = clampedPosition.coerceAtLeast(0L)
        _playbackProgress.value =
            if (durationMs > 0L) (clampedPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            else 0f
    }

    private fun setTrackError(trackId: Int, message: String) {
        _tracks.update { list ->
            list.map { track ->
                if (track.id == trackId) track.copy(playbackError = message) else track
            }
        }
        _importMessage.value = "${_tracks.value[trackId].name}: $message"
    }

    private fun clearTrackError(trackId: Int) {
        _tracks.update { list ->
            list.map { track ->
                if (track.id == trackId) track.copy(playbackError = null) else track
            }
        }
    }

    private fun toFriendlyPlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                "Fichier inaccessible (permission ou emplacement)."
            }

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                "Format audio non supporte par l'appareil."
            }

            else -> {
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Lecture impossible pour ce fichier audio."
            }
        }
    }

    // ────────────────────────── Volume effectif ───────────────────────────────

    /** Volume réel en tenant compte du mute et du solo. */
    private fun effectiveVolume(track: TrackState, allTracks: List<TrackState>): Float {
        if (track.isMuted) return 0f
        val anySolo = allTracks.any { it.isSolo }
        if (anySolo && !track.isSolo) return 0f
        return track.volume
    }

    private fun applyAllVolumes(tracks: List<TrackState>) {
        tracks.forEach { track ->
            val vol = effectiveVolume(track, tracks)
            trackPlayers[track.id].processor.volume = vol
            trackPlayers[track.id].player.volume = 1f
        }
    }

    private fun applyVolume(trackId: Int, tracks: List<TrackState>) {
        val vol = effectiveVolume(tracks[trackId], tracks)
        trackPlayers[trackId].processor.volume = vol
        trackPlayers[trackId].player.volume = 1f
    }

    private fun resolveSourceFileUri(projectUri: Uri, sourceFile: String?): Uri? {
        val source = sourceFile?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (source.startsWith("/")) {
            val file = File(source)
            if (file.exists()) return Uri.fromFile(file)
        }

        if (projectUri.scheme == "file") {
            val projectFile = File(projectUri.path ?: return null)
            val candidate = File(projectFile.parentFile, source)
            if (candidate.exists()) return Uri.fromFile(candidate)
        }

        if (projectUri.scheme == "content") {
            val absoluteProjectPath = guessAbsolutePathFromDocumentUri(projectUri)
            if (absoluteProjectPath != null) {
                val candidate = File(File(absoluteProjectPath).parentFile, source)
                if (candidate.exists()) return Uri.fromFile(candidate)
            }
        }
        return null
    }

    // Heuristique utile avec ExternalStorageProvider pour reconstituer un chemin absolu.
    private fun guessAbsolutePathFromDocumentUri(uri: Uri): String? {
        return runCatching {
            val documentId = DocumentsContract.getDocumentId(uri)
            val parts = documentId.split(":", limit = 2)
            if (parts.size != 2) return null
            val volume = parts[0]
            val relPath = parts[1]

            when {
                volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0/$relPath"
                else -> "/storage/$volume/$relPath"
            }
        }.getOrNull()
    }

    // ────────────────────────── Cycle de vie ──────────────────────────────────

    override fun onCleared() {
        stopProgressTicker()
        trackPlayers.forEach { it.player.release() }
        super.onCleared()
    }
}

// Factory pour injecter sessionId + sessionName sans Hilt
class MixerViewModelFactory(
    private val application: Application,
    private val sessionId: Int
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MixerViewModel(application, sessionId) as T
}
