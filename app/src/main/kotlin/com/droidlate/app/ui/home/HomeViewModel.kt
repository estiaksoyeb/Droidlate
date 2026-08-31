package com.droidlate.app.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidlate.app.core.ingestion.GitHubDownloader
import com.droidlate.app.core.ingestion.ProjectRepository
import com.droidlate.app.core.model.IngestionState
import com.droidlate.app.core.model.ProjectInfo
import com.droidlate.app.core.python.PythonEngineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = GitHubDownloader(application)
    private val repository = ProjectRepository(application)
    private val engineManager = PythonEngineManager.getInstance(application)
    private val notificationHelper = com.droidlate.app.core.notification.NotificationHelper.getInstance(application)

    private val _repoUrlInput = MutableStateFlow("")
    val repoUrlInput: StateFlow<String> = _repoUrlInput.asStateFlow()

    private val _ingestionState = MutableStateFlow<IngestionState>(IngestionState.Idle)
    val ingestionState: StateFlow<IngestionState> = _ingestionState.asStateFlow()

    private val _recentProjects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val recentProjects: StateFlow<List<ProjectInfo>> = _recentProjects.asStateFlow()

    init {
        refreshRecents()
        viewModelScope.launch {
            // Warm up Python engine in background
            engineManager.startEngine()
        }
    }

    fun onUrlChanged(newUrl: String) {
        _repoUrlInput.value = newUrl
    }

    fun refreshRecents() {
        viewModelScope.launch {
            _recentProjects.value = repository.getRecentProjects()
        }
    }

    fun downloadRepository(onSuccess: (ProjectInfo) -> Unit) {
        val coords = downloader.parseRepoUrl(_repoUrlInput.value)
        if (coords == null) {
            _ingestionState.value = IngestionState.Error("Invalid GitHub repository URL or shorthand. Example: skydoves/Pokedex")
            return
        }

        viewModelScope.launch {
            downloader.downloadRepository(coords).collectLatest { state ->
                _ingestionState.value = state
                if (state is IngestionState.Success) {
                    repository.saveProject(state.project)
                    engineManager.startEngine(state.project.activeResDirPath)
                    notificationHelper.showImportSuccess(state.project.name)
                    refreshRecents()
                    onSuccess(state.project)
                }
            }
        }
    }

    fun importLocalZip(uri: Uri, onSuccess: (ProjectInfo) -> Unit) {
        viewModelScope.launch {
            _ingestionState.value = IngestionState.Extracting("Importing local ZIP archive...")
            val contentResolver = getApplication<Application>().contentResolver
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val projectName = uri.lastPathSegment ?: "ImportedProject"
                val project = downloader.importLocalZip(inputStream, projectName)
                if (project != null) {
                    repository.saveProject(project)
                    engineManager.startEngine(project.activeResDirPath)
                    notificationHelper.showImportSuccess(project.name)
                    _ingestionState.value = IngestionState.Success(project)
                    refreshRecents()
                    onSuccess(project)
                } else {
                    _ingestionState.value = IngestionState.Error("Failed to import or no Android strings.xml found in ZIP.")
                }
            } else {
                _ingestionState.value = IngestionState.Error("Could not open selected file.")
            }
        }
    }

    fun openExistingProject(project: ProjectInfo, onReady: (ProjectInfo) -> Unit) {
        viewModelScope.launch {
            repository.saveProject(project)
            engineManager.startEngine(project.activeResDirPath)
            onReady(project)
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            refreshRecents()
        }
    }

    fun resetIngestionState() {
        _ingestionState.value = IngestionState.Idle
    }
}
