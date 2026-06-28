package com.example.mixerapp.ui.screens

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mixerapp.R
import com.example.mixerapp.model.AudioMode
import com.example.mixerapp.model.LimiterConfiguration
import com.example.mixerapp.model.TrackState
import com.example.mixerapp.viewmodel.MixerViewModel
import com.example.mixerapp.viewmodel.MixerViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.log10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(
    sessionId: Int,
    sessionName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: MixerViewModel = viewModel(
        key = "mixer_$sessionId",
        factory = MixerViewModelFactory(application, sessionId)
    )

    val tracks by viewModel.tracks.collectAsState()
    val visibleTrackCount by viewModel.visibleTrackCount.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val projectPositionMs by viewModel.projectPositionMs.collectAsState()
    val projectDurationMs by viewModel.projectDurationMs.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()
    val pendingProjectChoices by viewModel.pendingProjectChoices.collectAsState()
    val isImportingProject by viewModel.isImportingProject.collectAsState()
    val importProgressPercent by viewModel.importProgressPercent.collectAsState()
    val importProjectLabel by viewModel.importProjectLabel.collectAsState()
    val limiterConfig by viewModel.limiterConfig.collectAsState()
    val masterVolume by viewModel.masterVolume.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val sessionImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importReaperProject(it)
        }
    }

    val folderImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importReaperProjectFromFolder(it)
        }
    }

    // Un launcher par piste (doit être créé au niveau Composable, pas dans une lambda)
    val fileLaunchers = (0 until MixerViewModel.TRACK_COUNT).map { trackId ->
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                viewModel.loadAudio(trackId, it)
            }
        }
    }

    LaunchedEffect(importMessage) {
        importMessage?.let { message ->
            val job = launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Indefinite
                )
            }
            delay(1000)
            job.cancel()
            viewModel.consumeImportMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(sessionName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { sessionImportLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Importer session")
                    }
                    IconButton(onClick = { folderImportLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Importer dossier session")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                TransportBar(
                    isPlaying = isPlaying,
                    projectPositionMs = projectPositionMs,
                    projectDurationMs = projectDurationMs,
                    onPlay = viewModel::playAll,
                    onPause = viewModel::pauseAll,
                    onStop = viewModel::stopAll
                )
                LaserProgressBar(
                    playbackProgress = playbackProgress,
                    projectPositionMs = projectPositionMs,
                    projectDurationMs = projectDurationMs,
                    canSeek = projectDurationMs > 0L,
                    onSeek = viewModel::seekToProgress
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            if (visibleTrackCount < MixerViewModel.TRACK_COUNT) {
                Text(
                    text = stringResource(
                        R.string.imported_tracks_count,
                        visibleTrackCount,
                        MixerViewModel.TRACK_COUNT
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)

            // ── Pistes ───────────────────────────────────────────────────────
            tracks.take(visibleTrackCount).forEach { track ->
                TrackRow(
                    track = track,
                    onLoadClick = { fileLaunchers[track.id].launch(arrayOf("audio/*")) },
                    onVolumeChange = { viewModel.setVolume(track.id, it) },
                    onToggleMute = { viewModel.toggleMute(track.id) },
                    onToggleSolo = { viewModel.toggleSolo(track.id) },
                    onAudioModeChange = { viewModel.setAudioMode(track.id, it) },
                    onRetryClick = { viewModel.retryLoadAudio(track.id) }
                )
                HorizontalDivider()
            }

            MasterBusControls(
                limiterConfig = limiterConfig,
                onLimiterConfigChange = viewModel::updateLimiterConfig,
                masterVolume = masterVolume,
                onMasterVolumeChange = viewModel::setMasterVolume
            )

            Button(
                onClick = viewModel::saveProject,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save in .rpp file")
            }
        }
    }

    if (pendingProjectChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::cancelProjectChoice,
            title = { Text("Choisir le projet .rpp") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(pendingProjectChoices, key = { it.projectUri.toString() }) { choice ->
                        TextButton(
                            onClick = {
                                viewModel.chooseProjectFromFolder(choice.projectUri, choice.folderUri)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = choice.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = choice.relativePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::cancelProjectChoice) {
                    Text("Annuler")
                }
            }
        )
    }

    if (isImportingProject) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { (importProgressPercent.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.size(56.dp),
                        strokeWidth = 5.dp
                    )
                    Text(
                        text = "Import du projet...",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    importProjectLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = "${importProgressPercent.coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Master Bus (Limiter & Volume)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MasterBusControls(
    limiterConfig: LimiterConfiguration,
    onLimiterConfigChange: (LimiterConfiguration) -> Unit,
    masterVolume: Float,
    onMasterVolumeChange: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Master Bus",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Réduire" else "Détails")
                }
            }

            // Master Volume Slider
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = "Gain",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp)
                )
                val currentDb = linearToDb(masterVolume)
                Slider(
                    value = currentDb,
                    onValueChange = { onMasterVolumeChange(dbToLinear(it)) },
                    valueRange = -60f..12f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.tertiary,
                        activeTrackColor = MaterialTheme.colorScheme.tertiary
                    )
                )

                Text(
                    text = if (masterVolume <= 0.0001f) "-inf" else "%.1f".format(currentDb),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(42.dp),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    onClick = { onMasterVolumeChange(1.0f) },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(width = 36.dp, height = 24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "0dB",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = limiterConfig.isEnabled,
                        onCheckedChange = { onLimiterConfigChange(limiterConfig.copy(isEnabled = it)) }
                    )
                    Text(
                        "Limiter",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                LimiterSlider(
                    label = "Threshold",
                    value = limiterConfig.thresholdDb,
                    valueRange = -30f..0f,
                    unit = "dB",
                    onValueChange = { onLimiterConfigChange(limiterConfig.copy(thresholdDb = it)) }
                )
                LimiterSlider(
                    label = "Ceiling",
                    value = limiterConfig.ceilingDb,
                    valueRange = -20f..0f,
                    unit = "dB",
                    onValueChange = { onLimiterConfigChange(limiterConfig.copy(ceilingDb = it)) }
                )
                LimiterSlider(
                    label = "Release",
                    value = limiterConfig.releaseMs,
                    valueRange = 1f..1000f,
                    unit = "ms",
                    onValueChange = { onLimiterConfigChange(limiterConfig.copy(releaseMs = it)) }
                )
            }
        }
    }
}

@Composable
private fun LimiterSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("${value.toInt()} $unit", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.height(24.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Barre de transport
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TransportBar(
    isPlaying: Boolean,
    projectPositionMs: Long,
    projectDurationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Transport", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))

            FilledTonalButton(
                onClick = if (isPlaying) onPause else onPlay,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isPlaying)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(if (isPlaying) "⏸ Pause" else "▶ Play")
            }

            FilledTonalButton(onClick = onStop) {
                Text("⏹ Stop")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMillis(projectPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatMillis(projectDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LaserProgressBar(
    playbackProgress: Float,
    projectPositionMs: Long,
    projectDurationMs: Long,
    canSeek: Boolean,
    onSeek: (Float) -> Unit
) {
    var sliderProgress by remember { mutableStateOf(playbackProgress.coerceIn(0f, 1f)) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(playbackProgress) {
        if (!isDragging) {
            sliderProgress = playbackProgress.coerceIn(0f, 1f)
        }
    }

    val dragTargetPositionMs = (projectDurationMs * sliderProgress.coerceIn(0f, 1f)).toLong()
    val displayPositionMs = if (isDragging) dragTargetPositionMs else projectPositionMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        if (isDragging) {
            Text(
                text = "${formatMillis(displayPositionMs)} / ${formatMillis(projectDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        Slider(
            value = sliderProgress,
            onValueChange = {
                isDragging = true
                sliderProgress = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(sliderProgress)
            },
            enabled = canSeek,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF8A5CFF),
                activeTrackColor = Color(0xFF3DDCFF),
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.outline,
                disabledActiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

private fun formatMillis(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

private fun linearToDb(linear: Float): Float {
    if (linear <= 0f) return -60f
    return (20f * log10(linear.coerceAtLeast(1e-4f))).coerceIn(-60f, 12f)
}

private fun dbToLinear(db: Float): Float {
    if (db <= -59.9f) return 0f
    return 10f.pow(db / 20f)
}

// ─────────────────────────────────────────────────────────────────────────────
// Rang piste
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrackRow(
    track: TrackState,
    onLoadClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onAudioModeChange: (AudioMode) -> Unit,
    onRetryClick: () -> Unit
) {
    val isEffectivelyMuted = track.isMuted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ── Ligne 1 : nom + boutons de contrôle ──────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Indicateur de chargement
            Box(
                modifier = Modifier.size(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (track.isLoaded) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    modifier = Modifier.fillMaxSize()
                ) {}
            }

            Text(
                text = track.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                color = if (isEffectivelyMuted)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else
                    MaterialTheme.colorScheme.onSurface
            )

            // Bouton charger fichier
            IconButton(onClick = onLoadClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = "Charger audio",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Bouton MUTE
            TrackToggleButton(
                label = "M",
                active = track.isMuted,
                activeColor = MaterialTheme.colorScheme.errorContainer,
                activeContentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onToggleMute
            )

            // Bouton SOLO
            TrackToggleButton(
                label = "S",
                active = track.isSolo,
                activeColor = Color(0xFFFFF176), // jaune
                activeContentColor = Color(0xFF333300),
                onClick = onToggleSolo
            )

            // Sélecteur de mode canal ST / L / R
            AudioModeSelector(
                current = track.audioMode,
                onSelect = onAudioModeChange
            )
        }

        // ── Ligne 2 : slider volume ───────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Vol",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
            val currentDb = linearToDb(track.volume)
            Slider(
                value = currentDb,
                onValueChange = { onVolumeChange(dbToLinear(it)) },
                valueRange = -60f..12f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = if (isEffectivelyMuted)
                        MaterialTheme.colorScheme.outlineVariant
                    else
                        MaterialTheme.colorScheme.primary
                )
            )

            Text(
                text = if (track.volume <= 0.0001f) "-inf" else "%.1f".format(currentDb),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(42.dp),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Bouton Reset à 0dB
            Surface(
                onClick = { onVolumeChange(1.0f) }, // 0dB = 1.0 linéaire
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(width = 36.dp, height = 24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "0dB",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }

        if (track.playbackError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = track.playbackError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                if (track.uri != null) {
                    TextButton(onClick = onRetryClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Reessayer")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composables utilitaires
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrackToggleButton(
    label: String,
    active: Boolean,
    activeColor: Color,
    activeContentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) activeContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(width = 32.dp, height = 28.dp),
        tonalElevation = if (active) 0.dp else 1.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun AudioModeSelector(
    current: AudioMode,
    onSelect: (AudioMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        AudioMode.entries.forEach { mode ->
            val selected = current == mode
            Surface(
                onClick = { onSelect(mode) },
                shape = MaterialTheme.shapes.extraSmall,
                color = if (selected)
                    MaterialTheme.colorScheme.tertiaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected)
                    MaterialTheme.colorScheme.onTertiaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(width = 30.dp, height = 28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
