package com.example.mixerapp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val importMessage by viewModel.importMessage.collectAsState()
    val isImportingProject by viewModel.isImportingProject.collectAsState()
    val importProgressPercent by viewModel.importProgressPercent.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var showExplorer by remember { mutableStateOf(false) }
    var preferredExplorerRoot by remember { mutableStateOf<Uri?>(null) }
    var persistedTreeUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var loadingSessionId by remember { mutableStateOf<Int?>(null) }
    var loadingSessionProgress by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val refreshPersistedTreeUris = {
        persistedTreeUris = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, permission.uri) }
                    .getOrNull()
                    ?.takeIf { it.isDirectory }
                    ?.uri
            }
            .distinct()
            .toList()
    }

    val openCustomExplorer = {
        refreshPersistedTreeUris()
        preferredExplorerRoot = persistedTreeUris.firstOrNull()
        showExplorer = true
    }

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
        session?.let { onSessionClick(it) }
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
            refreshPersistedTreeUris()
            preferredExplorerRoot = it
            showExplorer = true
        }
    }

    LaunchedEffect(Unit) {
        refreshPersistedTreeUris()
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
                title = { Text("🎛  MixerApp — Sessions") },
                actions = {
                    IconButton(onClick = openCustomExplorer) {
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
            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Aucune session.\nAppuyez sur + pour en créer une, ou importez un projet Reaper.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = openCustomExplorer) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Importer un projet Reaper")
                            }
                        }
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
            onDismiss = { if (showDialog) showDialog = false },
            onConfirm = { name ->
                viewModel.addSession(name)
                if (showDialog) showDialog = false
            }
        )
    }

    if (showExplorer) {
        CustomFileExplorerDialog(
            usage = ExplorerUsage.SessionsReaper,
            persistedTreeUris = persistedTreeUris,
            preferredRootUri = preferredExplorerRoot,
            onDismiss = { showExplorer = false },
            onRequestRootAccess = { folderImportLauncher.launch(null) },
            onSelect = { selectedFolderUri, _ ->
                showExplorer = false
                viewModel.startImportProgressNow()
                viewModel.importSessionFromReaperFolderWithDelay(selectedFolderUri)
            }
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
