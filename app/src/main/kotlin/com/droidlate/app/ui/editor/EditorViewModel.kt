package com.droidlate.app.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidlate.app.core.model.StringEntry
import com.droidlate.app.core.model.SuggestionItem
import com.droidlate.app.core.python.PythonEngineManager
import com.droidlate.app.core.util.PlaceholderValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FilterCategory(val label: String) {
    ALL("All"),
    UNTRANSLATED("Untranslated"),
    OUTDATED("Outdated"),
    WARNINGS("Warnings"),
    ORPHANED("Orphaned"),
    READONLY("Read-only")
}

sealed class EditorItem {
    abstract val key: String

    data class SingleString(val entry: StringEntry) : EditorItem() {
        override val key: String get() = entry.key
    }

    data class PluralGroup(
        val baseKey: String,
        val entries: List<StringEntry>,
        val comment: String? = null
    ) : EditorItem() {
        override val key: String get() = baseKey

        val totalCount: Int get() = entries.size
        val untranslatedCount: Int get() = entries.count { it.status == "untranslated" }
        val outdatedCount: Int get() = entries.count { it.status == "outdated" }
        val orphanedCount: Int get() = entries.count { it.status == "orphaned" }
        val isAllTranslated: Boolean get() = untranslatedCount == 0 && outdatedCount == 0
    }
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val engineManager = PythonEngineManager.getInstance(application)

    private val _langFolder = MutableStateFlow("")
    val langFolder: StateFlow<String> = _langFolder.asStateFlow()

    private val _strings = MutableStateFlow<List<StringEntry>>(emptyList())
    val strings: StateFlow<List<StringEntry>> = _strings.asStateFlow()

    private val _selectedFilter = MutableStateFlow(FilterCategory.ALL)
    val selectedFilter: StateFlow<FilterCategory> = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<Map<String, List<SuggestionItem>>>(emptyMap())
    val suggestions: StateFlow<Map<String, List<SuggestionItem>>> = _suggestions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun loadStrings(folder: String) {
        _langFolder.value = folder
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result = engineManager.apiClient.fetchStrings(folder)
            result.onSuccess { list ->
                _strings.value = list
            }.onFailure { ex ->
                _errorMessage.value = ex.message ?: "Failed to load translations"
            }
            _isLoading.value = false
        }
    }

    fun setFilter(filter: FilterCategory) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getFilteredEditorItems(): List<EditorItem> {
        val query = _searchQuery.value.trim()
        val filter = _selectedFilter.value
        val all = _strings.value

        val pluralGroupsMap = linkedMapOf<String, MutableList<StringEntry>>()
        for (entry in all) {
            if (entry.key.contains("#plural#")) {
                val baseKey = entry.key.substringBefore("#plural#")
                pluralGroupsMap.getOrPut(baseKey) { mutableListOf() }.add(entry)
            }
        }

        val allItems = mutableListOf<EditorItem>()
        val seenKeys = mutableSetOf<String>()

        for (entry in all) {
            if (entry.key.contains("#plural#")) {
                val baseKey = entry.key.substringBefore("#plural#")
                if (seenKeys.add("plural:$baseKey")) {
                    val list = pluralGroupsMap[baseKey] ?: emptyList()
                    allItems.add(EditorItem.PluralGroup(baseKey = baseKey, entries = list, comment = list.firstOrNull()?.comment))
                }
            } else {
                if (seenKeys.add("single:${entry.key}")) {
                    allItems.add(EditorItem.SingleString(entry))
                }
            }
        }

        return allItems.filter { item ->
            when (item) {
                is EditorItem.SingleString -> {
                    val entry = item.entry
                    val matchesFilter = when (filter) {
                        FilterCategory.ALL -> true
                        FilterCategory.UNTRANSLATED -> entry.status == "untranslated"
                        FilterCategory.OUTDATED -> entry.status == "outdated"
                        FilterCategory.WARNINGS -> entry.status == "warnings"
                        FilterCategory.ORPHANED -> entry.status == "orphaned"
                        FilterCategory.READONLY -> entry.status == "readonly"
                    }
                    val matchesSearch = if (query.isEmpty()) true else {
                        entry.key.contains(query, ignoreCase = true) ||
                        entry.source.contains(query, ignoreCase = true) ||
                        entry.translation.contains(query, ignoreCase = true) ||
                        (entry.comment?.contains(query, ignoreCase = true) == true)
                    }
                    matchesFilter && matchesSearch
                }
                is EditorItem.PluralGroup -> {
                    val matchesFilter = when (filter) {
                        FilterCategory.ALL -> true
                        FilterCategory.UNTRANSLATED -> item.entries.any { it.status == "untranslated" }
                        FilterCategory.OUTDATED -> item.entries.any { it.status == "outdated" }
                        FilterCategory.WARNINGS -> item.entries.any { it.status == "warnings" }
                        FilterCategory.ORPHANED -> item.entries.any { it.status == "orphaned" }
                        FilterCategory.READONLY -> item.entries.any { it.status == "readonly" }
                    }
                    val matchesSearch = if (query.isEmpty()) true else {
                        item.baseKey.contains(query, ignoreCase = true) ||
                        item.entries.any {
                            it.source.contains(query, ignoreCase = true) ||
                            it.translation.contains(query, ignoreCase = true) ||
                            (it.comment?.contains(query, ignoreCase = true) == true)
                        }
                    }
                    matchesFilter && matchesSearch
                }
            }
        }
    }

    fun getFilteredStrings(): List<StringEntry> {
        val query = _searchQuery.value.trim()
        val filter = _selectedFilter.value

        return _strings.value.filter { entry ->
            val matchesFilter = when (filter) {
                FilterCategory.ALL -> true
                FilterCategory.UNTRANSLATED -> entry.status == "untranslated"
                FilterCategory.OUTDATED -> entry.status == "outdated"
                FilterCategory.WARNINGS -> entry.status == "warnings"
                FilterCategory.ORPHANED -> entry.status == "orphaned"
                FilterCategory.READONLY -> entry.status == "readonly"
            }

            val matchesSearch = if (query.isEmpty()) true else {
                entry.key.contains(query, ignoreCase = true) ||
                entry.source.contains(query, ignoreCase = true) ||
                entry.translation.contains(query, ignoreCase = true) ||
                (entry.comment?.contains(query, ignoreCase = true) == true)
            }

            matchesFilter && matchesSearch
        }
    }


    fun saveTranslation(
        key: String,
        value: String?,
        sourceHash: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val folder = _langFolder.value
            val result = engineManager.apiClient.saveTranslation(folder, key, value, sourceHash)
            result.onSuccess {
                // Update local list state
                _strings.value = _strings.value.map { entry ->
                    if (entry.key == key) {
                        val trimmed = value?.trim()
                        val newStatus = when {
                            trimmed.isNullOrEmpty() -> "untranslated"
                            PlaceholderValidator.validate(entry.source, trimmed).isNotEmpty() -> "warnings"
                            else -> "translated"
                        }
                        entry.copy(translation = trimmed ?: "", status = newStatus)
                    } else entry
                }
                onSuccess()
            }.onFailure { ex ->
                _errorMessage.value = ex.message ?: "Failed to save translation"
            }
            _isSaving.value = false
        }
    }

    fun fetchSuggestions(key: String, sourceText: String) {
        if (_suggestions.value.containsKey(key)) return
        viewModelScope.launch {
            val folder = _langFolder.value
            val result = engineManager.apiClient.fetchSuggestions(sourceText, folder)
            if (result.isNotEmpty()) {
                val current = _suggestions.value.toMutableMap()
                current[key] = result
                _suggestions.value = current
            }
        }
    }

    fun pruneString(key: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val folder = _langFolder.value
            val isLocallyAdded = _strings.value.any { it.key == key && it.translation.isEmpty() }
            val result = engineManager.apiClient.pruneString(folder, key)
            if (result.isSuccess || isLocallyAdded) {
                _strings.value = _strings.value.filterNot { it.key == key }
                onSuccess()
            } else {
                val ex = result.exceptionOrNull()
                _errorMessage.value = ex?.message ?: "Failed to prune string"
            }
            _isLoading.value = false
        }
    }

    fun addPluralQuantity(baseKey: String, quantity: String, referenceSource: String = "", sourceHash: String = "") {
        val newKey = "$baseKey#plural#$quantity"
        if (_strings.value.any { it.key == newKey }) return

        val newEntry = StringEntry(
            key = newKey,
            source = referenceSource,
            sourceHash = sourceHash,
            translation = "",
            status = "untranslated"
        )
        val current = _strings.value.toMutableList()
        val lastPluralIndex = current.indexOfLast { it.key.startsWith("$baseKey#plural#") }
        if (lastPluralIndex != -1) {
            current.add(lastPluralIndex + 1, newEntry)
        } else {
            current.add(newEntry)
        }
        _strings.value = current
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

