package com.droidlate.app.ui.dashboard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidlate.app.core.ingestion.ProjectRepository
import com.droidlate.app.core.ingestion.ZipExporter
import com.droidlate.app.core.model.ExportResult
import com.droidlate.app.core.model.LanguageInfo
import com.droidlate.app.core.model.ProjectInfo
import com.droidlate.app.core.python.PythonEngineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val exporter = ZipExporter(application)
    private val downloader = com.droidlate.app.core.ingestion.GitHubDownloader(application)
    private val engineManager = PythonEngineManager.getInstance(application)
    private val notificationHelper = com.droidlate.app.core.notification.NotificationHelper.getInstance(application)

    private val _project = MutableStateFlow<ProjectInfo?>(null)
    val project: StateFlow<ProjectInfo?> = _project.asStateFlow()

    private val _languages = MutableStateFlow<List<LanguageInfo>>(emptyList())
    val languages: StateFlow<List<LanguageInfo>> = _languages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _syncSuccessMessage = MutableStateFlow<String?>(null)
    val syncSuccessMessage: StateFlow<String?> = _syncSuccessMessage.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _pinnedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val pinnedLanguages: StateFlow<Set<String>> = _pinnedLanguages.asStateFlow()


    fun loadProject(projectId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _pinnedLanguages.value = repository.getPinnedLanguages(projectId)

            val recents = repository.getRecentProjects()
            val target = recents.find { it.id == projectId }

            if (target != null) {
                _project.value = target
                // Phase 1: Set and verify Python workspace in memory
                val ready = engineManager.prepareWorkspace(target.activeResDirPath)
                if (ready) {
                    // Phase 2: Ingest language string records
                    loadLanguagesInternal()
                } else {
                    _errorMessage.value = "Failed to initialize localization workspace"
                    _isLoading.value = false
                }
            } else {
                _errorMessage.value = "Project not found in cache"
                _isLoading.value = false
            }
        }
    }

    fun switchResModule(newResDirPath: String) {
        val current = _project.value ?: return
        val updated = current.copy(activeResDirPath = newResDirPath)
        _project.value = updated
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            repository.saveProject(updated)
            // Phase 1: Update workspace
            val ready = engineManager.prepareWorkspace(newResDirPath)
            if (ready) {
                // Phase 2: Ingest language string records
                loadLanguagesInternal()
            } else {
                _isLoading.value = false
            }
        }
    }

    fun fetchLanguages() {
        val currentProj = _project.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            engineManager.prepareWorkspace(currentProj.activeResDirPath)
            loadLanguagesInternal()
        }
    }

    private suspend fun loadLanguagesInternal() {
        val result = engineManager.apiClient.fetchProjectLanguages()
        result.onSuccess { list ->
            _languages.value = list
        }.onFailure { ex ->
            _errorMessage.value = ex.message ?: "Failed to load project languages"
        }
        _isLoading.value = false
    }


    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun togglePinLanguage(folder: String) {
        val currentProj = _project.value ?: return
        val currentPins = _pinnedLanguages.value.toMutableSet()
        if (currentPins.contains(folder)) {
            currentPins.remove(folder)
        } else {
            currentPins.add(folder)
        }
        _pinnedLanguages.value = currentPins
        repository.setPinnedLanguages(currentProj.id, currentPins)
    }

    fun addLanguage(localeCode: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = engineManager.apiClient.addLanguage(localeCode)
            result.onSuccess {
                fetchLanguages()
                onComplete()
            }.onFailure { ex ->
                _errorMessage.value = ex.message ?: "Failed to add language"
                _isLoading.value = false
            }
        }
    }

    fun exportZip(onExportComplete: (ExportResult) -> Unit) {
        val proj = _project.value ?: return
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val result = exporter.exportTranslations(proj)
                notificationHelper.showExportSuccess(proj.name, result.zipFile.name)
                onExportComplete(result)
            } catch (e: Exception) {
                _errorMessage.value = "Export failed: ${e.message}"
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun syncWithGitHub() {
        val proj = _project.value ?: return
        viewModelScope.launch {
            _isSyncing.value = true
            _errorMessage.value = null
            _syncSuccessMessage.value = null
            notificationHelper.showSyncOngoing(proj.name, "Syncing upstream commits from GitHub...")

            val result = downloader.syncRepository(proj)
            result.onSuccess { summary ->
                // Refresh workspace and recalculate language diffs
                val ready = engineManager.prepareWorkspace(proj.activeResDirPath)
                if (ready) {
                    loadLanguagesInternal()
                }
                _syncSuccessMessage.value = summary.message
                notificationHelper.showSyncSuccess(proj.name, summary.message)
            }.onFailure { ex ->
                val error = ex.message ?: "Failed to sync repository"
                _errorMessage.value = "Sync failed: $error"
                notificationHelper.showSyncFailed(proj.name, error)
            }
            _isSyncing.value = false
        }
    }

    fun triggerShareIntent(result: ExportResult) {
        val intent = exporter.createShareIntent(result.zipFile)
        getApplication<Application>().startActivity(intent)
    }

    fun saveZipToUri(zipFile: java.io.File, destUri: android.net.Uri): Boolean {
        return exporter.saveZipToUri(zipFile, destUri)
    }

    fun clearSyncSuccessMessage() {
        _syncSuccessMessage.value = null
    }


    fun clearError() {
        _errorMessage.value = null
    }
}


