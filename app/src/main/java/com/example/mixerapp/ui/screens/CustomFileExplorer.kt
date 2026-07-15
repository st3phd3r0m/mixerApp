@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.mixerapp.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExplorerUsage {
    SessionsReaper,
    MixerProject,
    MixerAudio
}

/** Entrée légère : pas de DocumentFile, uniquement les métadonnées issues du cursor. */
private data class ExplorerEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val isSelectable: Boolean
)

/** Élément de la pile de navigation : URI du dossier + nom lisible. */
private data class FolderEntry(val uri: Uri, val name: String)

@Composable
fun CustomFileExplorerDialog(
    usage: ExplorerUsage,
    persistedTreeUris: List<Uri>,
    preferredRootUri: Uri?,
    onDismiss: () -> Unit,
    onRequestRootAccess: () -> Unit,
    onSelect: (selectedUri: Uri, currentFolderUri: Uri?) -> Unit
) {
    val context = LocalContext.current
    val stack = remember { mutableStateListOf<FolderEntry>() }
    var entries by remember { mutableStateOf<List<ExplorerEntry>>(emptyList()) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentFolder = stack.lastOrNull()
    val selectCurrentFolder = usage == ExplorerUsage.SessionsReaper

    // Initialisation de la racine
    LaunchedEffect(persistedTreeUris, preferredRootUri) {
        val roots = withContext(Dispatchers.IO) {
            persistedTreeUris.mapNotNull { uri ->
                val df = DocumentFile.fromTreeUri(context, uri)
                if (df?.isDirectory == true) FolderEntry(uri = df.uri, name = df.name ?: "Racine") else null
            }
        }

        if (roots.isEmpty()) {
            stack.clear()
            entries = emptyList()
            selectedFileUri = null
            errorMessage = "Aucun dossier autorisé. Appuyez sur 'Ajouter un dossier racine'."
            return@LaunchedEffect
        }

        val target = roots.firstOrNull { it.uri == preferredRootUri } ?: roots.first()
        stack.clear()
        stack.add(target)
        selectedFileUri = null
        errorMessage = null
    }

    // Listage rapide du dossier courant via une seule requête ContentResolver
    LaunchedEffect(currentFolder?.uri, usage) {
        val folder = currentFolder ?: return@LaunchedEffect
        isLoading = true
        errorMessage = null
        selectedFileUri = null

        val result = withContext(Dispatchers.IO) {
            runCatching { fastListChildren(context, folder.uri, usage) }
        }

        result.onSuccess { loaded ->
            entries = loaded
        }.onFailure {
            entries = emptyList()
            errorMessage = "Impossible de lire ce dossier."
        }

        isLoading = false
    }

    val selectEnabled = if (selectCurrentFolder) {
        currentFolder != null && !isLoading
    } else {
        selectedFileUri != null && !isLoading
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── En-tête ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (stack.size > 1) {
                                stack.removeAt(stack.lastIndex)
                                selectedFileUri = null
                            }
                        },
                        enabled = stack.size > 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Dossier parent")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentFolder?.name ?: "Explorateur")
                        Text(
                            text = currentFolder?.uri?.toString() ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onRequestRootAccess) {
                        Text("Ajouter un dossier racine")
                    }
                }

                HorizontalDivider()

                // ── Corps ─────────────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        errorMessage != null -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = errorMessage ?: "Erreur",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = onRequestRootAccess) {
                                    Text("Ajouter un dossier racine")
                                }
                            }
                        }

                        isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                if (stack.size > 1) {
                                    item {
                                        ListItem(
                                            modifier = Modifier.clickable {
                                                stack.removeAt(stack.lastIndex)
                                                selectedFileUri = null
                                            },
                                            headlineContent = { Text("..") },
                                            supportingContent = { Text("Dossier parent") }
                                        )
                                        HorizontalDivider()
                                    }
                                }

                                items(entries, key = { it.uri.toString() }) { entry ->
                                    val isSelected = selectedFileUri == entry.uri
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            if (entry.isDirectory) {
                                                stack.add(FolderEntry(uri = entry.uri, name = entry.name))
                                                selectedFileUri = null
                                            } else if (entry.isSelectable) {
                                                selectedFileUri = entry.uri
                                            }
                                        },
                                        headlineContent = { Text(entry.name) },
                                        supportingContent = {
                                            when {
                                                !entry.isDirectory && !entry.isSelectable ->
                                                    Text("Visible uniquement. Sélectionnez le dossier parent.")
                                                !entry.isDirectory && isSelected ->
                                                    Text("Fichier sélectionné")
                                            }
                                        },
                                        leadingContent = {
                                            Icon(
                                                imageVector = if (entry.isDirectory) Icons.Default.FolderOpen
                                                              else Icons.AutoMirrored.Filled.InsertDriveFile,
                                                contentDescription = null,
                                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                                       else MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    )
                                    HorizontalDivider()
                                }

                                if (entries.isEmpty()) {
                                    item {
                                        Text(
                                            text = "Aucun élément correspondant dans ce dossier.",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // ── Barre d'actions ───────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("ANNULER") }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = {
                            if (selectCurrentFolder) {
                                currentFolder?.uri?.let { uri -> onSelect(uri, uri) }
                            } else {
                                selectedFileUri?.let { uri -> onSelect(uri, currentFolder?.uri) }
                            }
                        },
                        enabled = selectEnabled
                    ) {
                        Text("SELECTIONNER")
                    }
                }
            }
        }
    }
}

/**
 * Liste les enfants d'un dossier en une **seule requête ContentResolver** via DocumentsContract.
 * Évite les N+1 queries de DocumentFile.listFiles() (isDirectory, name, isFile = 3 requêtes/fichier).
 */
private fun fastListChildren(context: Context, folderUri: Uri, usage: ExplorerUsage): List<ExplorerEntry> {
    // Pour la racine d'un tree URI, getDocumentId peut lever une exception → fallback sur getTreeDocumentId
    val docId = try {
        DocumentsContract.getDocumentId(folderUri)
    } catch (_: Exception) {
        DocumentsContract.getTreeDocumentId(folderUri)
    }

    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    val dirs = mutableListOf<ExplorerEntry>()
    val files = mutableListOf<ExplorerEntry>()

    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIdx   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

        while (cursor.moveToNext()) {
            val childDocId = cursor.getString(idIdx)   ?: continue
            val name       = cursor.getString(nameIdx) ?: continue
            val mime       = cursor.getString(mimeIdx)
            val isDir      = mime == DocumentsContract.Document.MIME_TYPE_DIR

            // URI document construit depuis la racine du tree (toujours navigable)
            val childUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, childDocId)

            if (isDir) {
                dirs.add(ExplorerEntry(uri = childUri, name = name, isDirectory = true, isSelectable = false))
            } else if (matchesUsage(name, usage)) {
                files.add(ExplorerEntry(uri = childUri, name = name, isDirectory = false,
                    isSelectable = usage != ExplorerUsage.SessionsReaper))
            }
        }
    }

    return dirs.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
}

private fun matchesUsage(name: String?, usage: ExplorerUsage): Boolean {
    val lowerName = name?.lowercase() ?: return false
    return when (usage) {
        ExplorerUsage.SessionsReaper,
        ExplorerUsage.MixerProject -> lowerName.endsWith(".rpp") || lowerName.endsWith(".rpp-bak")
        ExplorerUsage.MixerAudio   -> lowerName.endsWith(".wav")
    }
}

