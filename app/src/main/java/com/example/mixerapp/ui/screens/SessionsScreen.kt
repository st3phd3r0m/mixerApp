package com.example.mixerapp.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mixerapp.model.Session
import com.example.mixerapp.viewmodel.SessionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel = viewModel(),
    onSessionClick: (Session) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions by viewModel.sessions.collectAsState()
    val recentFolders by viewModel.recentFolders.collectAsState()
    val browserState by viewModel.browserState.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()
    val isImportingProject by viewModel.isImportingProject.collectAsState()
    val importProgressPercent by viewModel.importProgressPercent.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var loadingSessionId by remember { mutableStateOf<Int?>(null) }
    var loadingSessionProgress by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigation déclenchée après un court délai pour laisser le spinner s'afficher
    LaunchedEffect(loadingSessionId) {
        val id = loadingSessionId ?: return@LaunchedEffect
        val totalDurationMs = 420L
        val tickMs = 30L
        val ticks = (totalDurationMs / tickMs).toInt().coerceAtLeast(1)
        loadingSessionProgress = 0

        repeat(ticks) { step ->
            delay(tickMs)
            loadingSessionProgress = (((step + 1) * 100f) / ticks).toInt().coerceIn(0, 100)
        }

        val session = sessions.firstOrNull { it.id == id }
        if (session != null) onSessionClick(session)
        loadingSessionId = null
        loadingSessionProgress = 0
    }

    val folderImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            // Afficher le spinner immédiatement sur le main thread (avant fermeture du picker)
            viewModel.startImportProgressNow()
            // Lancer l'import avec délai pour laisser Compose recomposer
            viewModel.importSessionFromReaperFolderWithDelay(it)
        }
    }

    LaunchedEffect(importMessage) {
        importMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeImportMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("🎛  MixerApp — Sessions") },
                actions = {
                    IconButton(onClick = { folderImportLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Importer dossier Reaper")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nouvelle session")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (recentFolders.isNotEmpty()) {
                item {
                    Text(
                        text = "Dossiers recents",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(recentFolders, key = { it.uri.toString() }) { recent ->
                    RecentFolderItem(
                        label = recent.label,
                        relativePath = recent.relativePath,
                        onImportClick = {
                            viewModel.startImportProgressNow()
                            viewModel.importSessionFromReaperFolderWithDelay(recent.uri)
                        },
                        onBrowseClick = { viewModel.openRecentFolderBrowser(recent.uri) },
                        onRemoveClick = { viewModel.removeRecentFolder(recent.uri) }
                    )
                }
                item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
            }

            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Aucune session.\nAppuyez sur + pour en créer une.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        onClick = { loadingSessionId = session.id },
                        onDelete = { viewModel.removeSession(session.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showDialog) {
        AddSessionDialog(
            onDismiss = { showDialog = false },
            onConfirm = { name ->
                viewModel.addSession(name)
                showDialog = false
            }
        )
    }

    browserState?.let { state ->
        FolderBrowserDialog(
            state = state,
            onDismiss = viewModel::closeFolderBrowser,
            onUpClick = viewModel::browseUp,
            onSortModeChange = viewModel::setBrowserSortMode,
            onOpenFolder = { viewModel.browseInto(it) },
            onOpenProject = { viewModel.importProjectFromBrowser(it) }
        )
    }

    // Overlay spinner import dossier
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
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { importProgressPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.size(56.dp),
                        strokeWidth = 5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Import de la session...")
                    Text("${importProgressPercent.coerceIn(0, 100)}%")
                }
            }
        }
    } else if (loadingSessionId != null) {
        // Overlay spinner chargement session
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { loadingSessionProgress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.size(56.dp),
                        strokeWidth = 5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Chargement de la session...")
                    Text("${loadingSessionProgress.coerceIn(0, 100)}%")
                }
            }
        }
    }
}

@Composable
private fun RecentFolderItem(
    label: String,
    relativePath: String,
    onImportClick: () -> Unit,
    onBrowseClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Icon(Icons.Default.History, contentDescription = null)
        },
        supportingContent = {
            Text(relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onImportClick) { Text("Importer") }
                TextButton(onClick = onBrowseClick) { Text("Parcourir") }
                IconButton(onClick = onRemoveClick) {
                    Icon(Icons.Default.Close, contentDescription = "Supprimer ce dossier recent")
                }
            }
        }
    )
}

@Composable
private fun FolderBrowserDialog(
    state: SessionsViewModel.FolderBrowserState,
    onDismiss: () -> Unit,
    onUpClick: () -> Unit,
    onSortModeChange: (SessionsViewModel.BrowserSortMode) -> Unit,
    onOpenFolder: (android.net.Uri) -> Unit,
    onOpenProject: (android.net.Uri) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Parcourir: ${state.rootLabel}")
                Text(
                    text = state.currentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.sortMode == SessionsViewModel.BrowserSortMode.NAME,
                        onClick = { onSortModeChange(SessionsViewModel.BrowserSortMode.NAME) },
                        label = { Text("Nom") }
                    )
                    FilterChip(
                        selected = state.sortMode == SessionsViewModel.BrowserSortMode.DATE,
                        onClick = { onSortModeChange(SessionsViewModel.BrowserSortMode.DATE) },
                        label = { Text("Date") }
                    )
                }

                if (state.canGoUp) {
                    TextButton(onClick = onUpClick, modifier = Modifier.fillMaxWidth()) {
                        Text(".. Dossier parent")
                    }
                }

                if (state.entries.isEmpty()) {
                    Text(
                        text = "Aucun dossier ou projet .rpp ici",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(state.entries, key = { it.uri.toString() }) { entry ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    if (entry.isDirectory) onOpenFolder(entry.uri) else onOpenProject(entry.uri)
                                },
                                headlineContent = {
                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                        contentDescription = null
                                    )
                                },
                                supportingContent = {
                                    Text(if (entry.isDirectory) "Dossier" else "Projet Reaper")
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer")
            }
        }
    )
}

@Composable
private fun SessionItem(
    session: Session,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(session.name, style = MaterialTheme.typography.titleMedium) },
        leadingContent = {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun AddSessionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle session") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("Créer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
