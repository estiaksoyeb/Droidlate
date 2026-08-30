package com.droidlate.app.core.model

import java.io.File

/**
 * Represents an Android project workspace imported into Droidlate.
 */
data class ProjectInfo(
    val id: String,
    val name: String,
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val rootDirPath: String,
    val activeResDirPath: String,
    val availableResDirPaths: List<String> = emptyList(),
    val sourceXmlPath: String,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
) {
    val rootDir: File get() = File(rootDirPath)
    val activeResDir: File get() = File(activeResDirPath)
    val sourceXmlFile: File get() = File(sourceXmlPath)
}

/**
 * Information about a specific target language in the project.
 */
data class LanguageInfo(
    val folder: String,
    val locale: String,
    val progress: Int,
    val translated: Int,
    val outdated: Int,
    val untranslated: Int,
    val orphaned: Int,
    val total: Int,
    val targetPath: String? = null
)

/**
 * Represents a single string key-value entry for translation.
 */
data class StringEntry(
    val key: String,
    val source: String,
    val sourceHash: String = "",
    val translation: String = "",
    val comment: String? = null,
    val status: String = "untranslated", // "translated", "untranslated", "outdated", "warnings", "orphaned", "readonly"
    val attrib: Map<String, String> = emptyMap()
) {
    val isTranslatable: Boolean
        get() = attrib["translatable"] != "false"

    val isOrphaned: Boolean
        get() = status == "orphaned"

    val isOutdated: Boolean
        get() = status == "outdated"

    val isUntranslated: Boolean
        get() = status == "untranslated"
}

/**
 * Machine translation or translation memory suggestion.
 */
data class SuggestionItem(
    val provider: String,
    val text: String
)

/**
 * State of a repository ingestion/download task.
 */
sealed class IngestionState {
    object Idle : IngestionState()
    data class Downloading(val progressPercent: Int, val bytesRead: Long, val totalBytes: Long) : IngestionState()
    data class Extracting(val message: String) : IngestionState()
    data class Scanning(val message: String) : IngestionState()
    data class Success(val project: ProjectInfo) : IngestionState()
    data class Error(val errorMessage: String) : IngestionState()
}

/**
 * Result of exporting translated resource files.
 */
data class ExportResult(
    val zipFile: File,
    val fileCount: Int,
    val totalSizeBytes: Long
)

/**
 * Summary of upstream GitHub sync operation.
 */
data class SyncSummary(
    val projectName: String,
    val updatedModulesCount: Int,
    val message: String
)

