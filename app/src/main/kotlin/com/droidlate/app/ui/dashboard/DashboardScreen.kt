package com.droidlate.app.ui.dashboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidlate.app.core.model.ExportResult
import com.droidlate.app.core.model.LanguageInfo
import com.droidlate.app.core.model.ProjectInfo
import com.droidlate.app.ui.theme.StatusGreen
import com.droidlate.app.ui.theme.StatusRed
import com.droidlate.app.ui.theme.StatusYellow
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    projectId: String,
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val project by viewModel.project.collectAsState()
    val languages by viewModel.languages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val syncSuccessMessage by viewModel.syncSuccessMessage.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val pinnedLanguages by viewModel.pinnedLanguages.collectAsState()

    var showAddLanguageDialog by remember { mutableStateOf(false) }
    var showSyncConfirmDialog by remember { mutableStateOf(false) }
    var showLinkAndSyncDialog by remember { mutableStateOf(false) }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var showDashboardHelpDialog by remember { mutableStateOf(false) }
    var pendingExportResult by remember { mutableStateOf<ExportResult?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && pendingExportResult != null) {
            val saved = viewModel.saveZipToUri(pendingExportResult!!.zipFile, uri)
            scope.launch {
                if (saved) {
                    snackbarHostState.showSnackbar("Export ZIP saved to storage successfully!", duration = SnackbarDuration.Short)
                } else {
                    snackbarHostState.showSnackbar("Failed to save ZIP to storage.", duration = SnackbarDuration.Long)
                }
            }
        }
    }

    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            scope.launch {
                snackbarHostState.showSnackbar(errorMessage ?: "An error occurred", duration = SnackbarDuration.Long)
                viewModel.clearError()
            }
        }
    }

    LaunchedEffect(syncSuccessMessage) {
        if (syncSuccessMessage != null) {
            scope.launch {
                snackbarHostState.showSnackbar(syncSuccessMessage ?: "Synced successfully!", duration = SnackbarDuration.Short)
                viewModel.clearSyncSuccessMessage()
            }
        }
    }

    val filteredLanguages = remember(languages, searchQuery, pinnedLanguages) {
        val base = if (searchQuery.isBlank()) languages
        else languages.filter {
            it.folder.contains(searchQuery, ignoreCase = true) ||
            it.locale.contains(searchQuery, ignoreCase = true)
        }
        base.sortedWith(
            compareByDescending<LanguageInfo> { pinnedLanguages.contains(it.folder) }
                .thenBy { it.folder }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project?.name ?: "Project Dashboard",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val activeRel = project?.let { proj ->
                            try {
                                val rel = proj.activeResDir.relativeTo(proj.rootDir).path.replace("\\", "/")
                                val parts = rel.split("/")
                                if (parts.size > 2 && parts[0].contains("-")) parts.drop(1).joinToString("/") else rel
                            } catch (_: Exception) {
                                proj.activeResDir.name
                            }
                        } ?: "Resource Workspace"
                        Text(
                            text = activeRel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Sync with GitHub action
                    IconButton(
                        onClick = {
                            if (project?.isLocal == true) {
                                showLinkAndSyncDialog = true
                            } else {
                                showSyncConfirmDialog = true
                            }
                        },
                        enabled = !isSyncing && !isLoading
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "Sync from GitHub")
                        }
                    }
                    IconButton(onClick = { viewModel.fetchLanguages() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = {
                            viewModel.exportZip { result ->
                                pendingExportResult = result
                                showExportOptionsDialog = true
                            }
                        },
                        enabled = !isExporting && languages.isNotEmpty()
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.FileDownload, contentDescription = "Export Translations")
                        }
                    }
                    IconButton(onClick = { showDashboardHelpDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Dashboard Guide",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddLanguageDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Language")
            }
        }
    ) { paddingValues ->


        if (isLoading && languages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Multi-module selector chip row (if project has multiple res dirs)
                if ((project?.availableResDirPaths?.size ?: 0) > 1) {
                    item {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = "Resource Module:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                items(project!!.availableResDirPaths) { resPath ->
                                    val isSelected = resPath == project!!.activeResDirPath
                                    val chipLabel = try {
                                        val rel = File(resPath).relativeTo(project!!.rootDir).path.replace("\\", "/")
                                        val parts = rel.split("/")
                                        if (parts.size > 2 && parts[0].contains("-")) parts.drop(1).joinToString("/") else rel
                                    } catch (_: Exception) {
                                        File(resPath).parentFile?.name?.let { "$it/res" } ?: "res"
                                    }
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.switchResModule(resPath) },
                                        label = { Text(chipLabel) }
                                    )
                                }
                            }
                        }
                    }
                }


                // Summary Card
                item {
                    val totalKeys = languages.firstOrNull()?.total ?: 0
                    val avgProgress = if (languages.isNotEmpty()) languages.map { it.progress }.average().toInt() else 0
                    val totalOutdated = languages.sumOf { it.outdated }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Localization Progress",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "$totalKeys source string keys across ${languages.size} locales",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "$avgProgress%",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LinearProgressIndicator(
                                progress = { avgProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )

                            if (totalOutdated > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StatusYellow,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "$totalOutdated outdated strings need re-translation",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusYellow
                                    )
                                }
                            }
                        }
                    }
                }

                // Search Bar
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        placeholder = { Text("Filter languages (e.g. 'es', 'fr')...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Languages List
                if (filteredLanguages.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (searchQuery.isNotEmpty()) "No matching languages" else "No target languages found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap the + button below to add your first language.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    items(filteredLanguages, key = { it.folder }) { lang ->
                        LanguageCardItem(
                            language = lang,
                            isPinned = pinnedLanguages.contains(lang.folder),
                            onTogglePin = { viewModel.togglePinLanguage(lang.folder) },
                            onClick = { onLanguageSelected(lang.folder) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
                }
            }
        }
    }

    if (showAddLanguageDialog) {
        AddLanguageDialog(
            onDismiss = { showAddLanguageDialog = false },
            onConfirm = { localeCode ->
                viewModel.addLanguage(localeCode) {
                    showAddLanguageDialog = false
                }
            }
        )
    }

    if (showSyncConfirmDialog && project != null) {
        SyncConfirmDialog(
            project = project!!,
            onDismiss = { showSyncConfirmDialog = false },
            onConfirm = {
                showSyncConfirmDialog = false
                viewModel.syncWithGitHub()
            },
            onChangeRepo = {
                showSyncConfirmDialog = false
                showLinkAndSyncDialog = true
            }
        )
    }

    if (showLinkAndSyncDialog && project != null) {
        LinkAndSyncDialog(
            initialUrl = project!!.remoteCoordinatesFormatted,
            isLocalProject = project!!.isLocal,
            onDismiss = { showLinkAndSyncDialog = false },
            onConfirm = { urlInput ->
                showLinkAndSyncDialog = false
                viewModel.linkAndSyncWithGitHub(urlInput)
            }
        )
    }

    if (showExportOptionsDialog && pendingExportResult != null) {
        ExportOptionsDialog(
            exportResult = pendingExportResult!!,
            onDismiss = {
                showExportOptionsDialog = false
            },
            onShare = {
                showExportOptionsDialog = false
                viewModel.triggerShareIntent(pendingExportResult!!)
            },
            onSaveToStorage = {
                showExportOptionsDialog = false
                createDocumentLauncher.launch(pendingExportResult!!.zipFile.name)
            }
        )
    }

    if (showDashboardHelpDialog) {
        DashboardHelpDialog(onDismiss = { showDashboardHelpDialog = false })
    }
}

@Composable
fun ExportOptionsDialog(
    exportResult: ExportResult,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSaveToStorage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Translations")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Packaged translation resources and metadata ledger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = exportResult.zipFile.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Includes ${exportResult.fileCount} files (strings.xml + .translation_metadata)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val sizeKb = (exportResult.totalSizeBytes / 1024.0).let { "%.1f KB".format(it) }
                        Text(
                            text = "Archive size: $sizeKb",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onSaveToStorage,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save to Device Storage")
                }

                androidx.compose.material3.OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share via App")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}


@Composable
fun SyncConfirmDialog(
    project: ProjectInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onChangeRepo: () -> Unit
) {
    val repoLabel = if (!project.branch.isNullOrBlank()) "${project.owner}/${project.repo}@${project.branch}" else "${project.owner}/${project.repo}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync with Upstream GitHub") },
        text = {
            Column {
                Text(
                    text = "Pull latest commits from '$repoLabel'?",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Base English string files will be updated to detect new, modified, and deleted keys.\n• All existing target translations in 'values-*/' and metadata are 100% preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = onChangeRepo,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Change Repository / Branch", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Sync Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LinkAndSyncDialog(
    initialUrl: String = "",
    isLocalProject: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isLocalProject) "Link Upstream Repository" else "Change Upstream Repository")
        },
        text = {
            Column {
                Text(
                    text = if (isLocalProject) {
                        "This project was imported from a local ZIP file without a repository URL. Enter a GitHub repository to sync base English strings."
                    } else {
                        "Enter the GitHub repository URL or shorthand to sync this project with."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it.trim() },
                    placeholder = { Text("e.g. estiaksoyeb/TypeAssist") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                if (clipboard?.hasPrimaryClip() == true && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                                    val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    urlInput = text.trim()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste from clipboard"
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Formats: 'owner/repo', 'owner/repo@branch', or full GitHub URL.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (urlInput.isNotBlank()) onConfirm(urlInput) },
                enabled = urlInput.isNotBlank()
            ) {
                Text(if (isLocalProject) "Link & Sync" else "Update & Sync")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


@Composable
fun LanguageCardItem(
    language: LanguageInfo,
    isPinned: Boolean = false,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPinned) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isPinned) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (language.progress == 100) StatusGreen.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = language.locale.take(2).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (language.progress == 100) StatusGreen else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = language.folder,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (isPinned) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "PINNED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${language.translated} of ${language.total} translated",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${language.progress}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (language.progress == 100) StatusGreen else MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (isPinned) "Unpin language" else "Pin language",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { language.progress / 100f },
                color = if (language.progress == 100) StatusGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            // Status badges
            if (language.untranslated > 0 || language.outdated > 0 || language.orphaned > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (language.untranslated > 0) {
                        BadgePill(text = "${language.untranslated} untranslated", color = MaterialTheme.colorScheme.primary)
                    }
                    if (language.outdated > 0) {
                        BadgePill(text = "${language.outdated} outdated", color = StatusYellow)
                    }
                    if (language.orphaned > 0) {
                        BadgePill(text = "${language.orphaned} orphaned", color = StatusRed)
                    }
                }
            }
        }
    }
}

@Composable
fun BadgePill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun AddLanguageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var localeInput by remember { mutableStateOf("") }
    val commonLocales = listOf("es", "fr", "de", "it", "pt-rBR", "ru", "zh-rCN", "ja", "ko", "ar", "hi", "bn")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Language") },
        text = {
            Column {
                Text(
                    text = "Enter an Android locale code (e.g. 'es', 'fr', 'zh-rCN').",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = localeInput,
                    onValueChange = { localeInput = it.trim() },
                    placeholder = { Text("e.g. es") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Common Locales:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    items(commonLocales) { loc ->
                        SuggestionChip(
                            onClick = { localeInput = loc },
                            label = { Text(loc, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (localeInput.isNotBlank()) onConfirm(localeInput) },
                enabled = localeInput.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DashboardHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dashboard Guide") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardHelpRow(
                    icon = Icons.Default.Add,
                    title = "Add Language",
                    description = "Tap '+' to add a new target locale (e.g. 'es', 'fr', 'pt-rBR')."
                )
                DashboardHelpRow(
                    icon = Icons.Default.ChevronRight,
                    title = "Translate",
                    description = "Tap any language card to open and edit strings."
                )
                DashboardHelpRow(
                    icon = Icons.Default.Sync,
                    title = "Sync",
                    description = "Pull latest source updates from GitHub without overwriting translations."
                )
                DashboardHelpRow(
                    icon = Icons.Default.FileDownload,
                    title = "Export",
                    description = "Package all translated strings.xml files into a ZIP archive."
                )
                DashboardHelpRow(
                    icon = Icons.Default.Folder,
                    title = "Modules",
                    description = "Switch resource folders using the top chips in multi-module projects."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun DashboardHelpRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
