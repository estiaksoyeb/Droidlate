package com.droidlate.app.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidlate.app.core.model.StringEntry
import com.droidlate.app.core.model.SuggestionItem
import com.droidlate.app.core.util.PlaceholderValidator

import com.droidlate.app.ui.theme.StatusGreen
import com.droidlate.app.ui.theme.StatusRed
import com.droidlate.app.ui.theme.StatusYellow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    langFolder: String,
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit
) {
    val strings by viewModel.strings.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val suggestionsMap by viewModel.suggestions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var activePluralBaseKey by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(langFolder) {
        viewModel.loadStrings(langFolder)
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            scope.launch {
                snackbarHostState.showSnackbar(errorMessage ?: "An error occurred", duration = SnackbarDuration.Long)
                viewModel.clearError()
            }
        }
    }

    val filteredItems = remember(strings, selectedFilter, searchQuery) {
        viewModel.getFilteredEditorItems()
    }

    val totalCount = strings.size
    val untranslatedCount = strings.count { it.status == "untranslated" }
    val progress = if (totalCount > 0) ((totalCount - untranslatedCount) * 100) / totalCount else 100

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = langFolder,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$progress% translated ($untranslatedCount remaining)",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter Categories Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                items(FilterCategory.entries) { cat ->
                    val count = when (cat) {
                        FilterCategory.ALL -> strings.size
                        FilterCategory.UNTRANSLATED -> strings.count { it.status == "untranslated" }
                        FilterCategory.OUTDATED -> strings.count { it.status == "outdated" }
                        FilterCategory.WARNINGS -> strings.count { it.status == "warnings" }
                        FilterCategory.ORPHANED -> strings.count { it.status == "orphaned" }
                        FilterCategory.READONLY -> strings.count { it.status == "readonly" }
                    }

                    FilterChip(
                        selected = selectedFilter == cat,
                        onClick = { viewModel.setFilter(cat) },
                        label = { Text("${cat.label} ($count)") }
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search key or text...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Content Area
            if (isLoading && strings.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                if (filteredItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matching strings" else "No strings in this category",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(4.dp)) }

                        items(filteredItems, key = { it.key }) { item ->
                            when (item) {
                                is EditorItem.SingleString -> {
                                    StringEditorCard(
                                        entry = item.entry,
                                        suggestions = suggestionsMap[item.entry.key] ?: emptyList(),
                                        onRequestSuggestions = { viewModel.fetchSuggestions(item.entry.key, item.entry.source) },
                                        onSave = { newTranslation ->
                                            viewModel.saveTranslation(item.entry.key, newTranslation, item.entry.sourceHash) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Saved '${item.entry.key}'", duration = SnackbarDuration.Short)
                                                }
                                            }
                                        },
                                        onPrune = {
                                            viewModel.pruneString(item.entry.key) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Pruned '${item.entry.key}'", duration = SnackbarDuration.Short)
                                                }
                                            }
                                        }
                                    )
                                }
                                is EditorItem.PluralGroup -> {
                                    PluralGroupCard(
                                        group = item,
                                        onClick = { activePluralBaseKey = item.baseKey }
                                    )
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    if (activePluralBaseKey != null) {
        val groupEntries = strings.filter { it.key.startsWith("$activePluralBaseKey#plural#") }
        PluralEditorDialog(
            baseKey = activePluralBaseKey!!,
            entries = groupEntries,
            suggestionsMap = suggestionsMap,
            onDismiss = { activePluralBaseKey = null },
            onRequestSuggestions = { key, source -> viewModel.fetchSuggestions(key, source) },
            onSaveQuantity = { key, value, hash ->
                viewModel.saveTranslation(key, value, hash) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Saved plural form", duration = SnackbarDuration.Short)
                    }
                }
            },
            onPruneQuantity = { key ->
                viewModel.pruneString(key) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Removed plural form", duration = SnackbarDuration.Short)
                    }
                }
            },
            onAddQuantity = { baseKey, qty, src, hash ->
                viewModel.addPluralQuantity(baseKey, qty, src, hash)
            }
        )
    }
}

@Composable
fun PluralGroupCard(
    group: EditorItem.PluralGroup,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.baseKey,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "PLURAL · ${group.totalCount} forms",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // English source reference preview
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "English Source Forms:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    group.entries.take(3).forEach { entry ->
                        val qty = entry.key.substringAfter("#plural#", "other")
                        Text(
                            text = "• $qty: ${entry.source}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (group.entries.size > 3) {
                        Text(
                            text = "+ ${group.entries.size - 3} more forms...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val translatedCount = group.entries.count { it.status == "translated" }
                Text(
                    text = "$translatedCount/${group.totalCount} forms translated",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (group.isAllTranslated) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit Plurals")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluralEditorDialog(
    baseKey: String,
    entries: List<StringEntry>,
    suggestionsMap: Map<String, List<SuggestionItem>>,
    onDismiss: () -> Unit,
    onRequestSuggestions: (key: String, source: String) -> Unit,
    onSaveQuantity: (key: String, value: String, hash: String) -> Unit,
    onPruneQuantity: (key: String) -> Unit,
    onAddQuantity: (baseKey: String, quantity: String, source: String, hash: String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val translatedCount = entries.count { it.status == "translated" }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = baseKey,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$translatedCount/${entries.size} forms translated",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // English Reference Card
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "English Reference Forms",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            entries.forEach { entry ->
                                val qty = entry.key.substringAfter("#plural#", "other")
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = qty,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = entry.source,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Add Plural Quantity Forms Chips
                item {
                    val allCldrQuantities = listOf("zero", "one", "two", "few", "many", "other")
                    val existingQuantities = entries.map { it.key.substringAfter("#plural#") }.toSet()
                    val missingQuantities = allCldrQuantities.filterNot { existingQuantities.contains(it) }

                    if (missingQuantities.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Add missing plural forms for this language (e.g. Arabic, Russian):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 6.dp)
                                ) {
                                    items(missingQuantities) { qty ->
                                        val firstRef = entries.firstOrNull()
                                        SuggestionChip(
                                            onClick = {
                                                onAddQuantity(baseKey, qty, firstRef?.source ?: "", firstRef?.sourceHash ?: "")
                                            },
                                            label = {
                                                Text("+ $qty", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Active Quantity Cards
                items(entries, key = { it.key }) { entry ->
                    PluralQuantityEditorCard(
                        entry = entry,
                        suggestions = suggestionsMap[entry.key] ?: emptyList(),
                        onRequestSuggestions = { onRequestSuggestions(entry.key, entry.source) },
                        onSave = { newTranslation ->
                            onSaveQuantity(entry.key, newTranslation, entry.sourceHash)
                        },
                        onDelete = {
                            onPruneQuantity(entry.key)
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun PluralQuantityEditorCard(
    entry: StringEntry,
    suggestions: List<SuggestionItem>,
    onRequestSuggestions: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    val quantity = entry.key.substringAfter("#plural#", "other")
    var translationText by remember(entry.translation) { mutableStateOf(entry.translation) }
    val isDirty = translationText != entry.translation
    val warnings = remember(entry.source, translationText) {
        PlaceholderValidator.validate(entry.source, translationText)
    }

    LaunchedEffect(entry.key) {
        if (entry.isTranslatable) {
            onRequestSuggestions()
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "quantity=\"$quantity\"",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (quantity == "other") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(Required fallback)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Delete button for optional/redundant quantities
                if (quantity != "other") {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete this plural quantity",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Source Reference
            if (entry.source.isNotEmpty()) {
                Text(
                    text = "Source: ${entry.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Translation TextField
            OutlinedTextField(
                value = translationText,
                onValueChange = { translationText = it },
                placeholder = { Text("Translation for '$quantity'...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(translationText) })
            )

            // QA Warnings
            if (warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                warnings.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusYellow, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(w.message, style = MaterialTheme.typography.labelSmall, color = StatusYellow)
                    }
                }
            }

            // Suggestions
            if (suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(suggestions) { sug ->
                        SuggestionChip(
                            onClick = { translationText = sug.text },
                            label = { Text("${sug.provider}: ${sug.text}", fontSize = 12.sp, maxLines = 1) },
                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save Button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { onSave(translationText) },
                    enabled = isDirty,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (entry.status == "untranslated") "Save" else "Update")
                }
            }
        }
    }
}

@Composable
fun StringEditorCard(
    entry: StringEntry,
    suggestions: List<SuggestionItem>,
    onRequestSuggestions: () -> Unit,
    onSave: (String) -> Unit,
    onPrune: () -> Unit
) {
    val context = LocalContext.current
    var translationText by remember(entry.translation) { mutableStateOf(entry.translation) }
    val isDirty = translationText != entry.translation
    val warnings = remember(entry.source, translationText) {
        PlaceholderValidator.validate(entry.source, translationText)
    }

    LaunchedEffect(entry.key) {
        if (entry.isTranslatable && !entry.isOrphaned) {
            onRequestSuggestions()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Key & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.key,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                val (badgeBg, badgeText, badgeColor) = when (entry.status) {
                    "translated" -> Triple(StatusGreen.copy(alpha = 0.12f), "Translated", StatusGreen)
                    "outdated" -> Triple(StatusYellow.copy(alpha = 0.15f), "Outdated", StatusYellow)
                    "orphaned" -> Triple(StatusRed.copy(alpha = 0.15f), "Orphaned", StatusRed)
                    "readonly" -> Triple(Color.Gray.copy(alpha = 0.15f), "Read-only", Color.Gray)
                    else -> Triple(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), "Untranslated", MaterialTheme.colorScheme.primary)
                }

                Surface(color = badgeBg, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Developer Comment (if any)
            if (!entry.comment.isNullOrBlank() && !entry.isOrphaned) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = entry.comment,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (entry.isOrphaned) {
                // Orphaned Key Layout
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = StatusRed.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Key was removed from English source XML.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (entry.translation.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Current XML Translation:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = entry.translation,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onPrune,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete / Prune Key")
                        }
                    }
                }
            } else if (!entry.isTranslatable) {
                // Non-translatable (read-only)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Value (Non-Translatable):",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = entry.source,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "This string is marked translatable=\"false\" in base XML. It does not require translation.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            } else {
                // Normal Translatable String Layout
                Column {
                    // English Source Reference
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "English (Source):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = entry.source,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Source", entry.source))
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy source",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Translation Input Field
                    OutlinedTextField(
                        value = translationText,
                        onValueChange = { translationText = it },
                        placeholder = { Text("Enter translated text...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSave(translationText) })
                    )

                    // QA Warnings
                    if (warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            warnings.forEach { warning ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StatusYellow,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = warning.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusYellow
                                    )
                                }
                            }
                        }
                    }

                    // Suggestions Chips Row
                    if (suggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Suggestions (tap to apply):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            items(suggestions) { sug ->
                                SuggestionChip(
                                    onClick = { translationText = sug.text },
                                    label = {
                                        Text(
                                            text = "${sug.provider}: ${sug.text}",
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Save Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { onSave(translationText) },
                            enabled = isDirty,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (entry.status == "untranslated") "Save Translation" else "Update")
                        }
                    }
                }
            }
        }
    }
}
