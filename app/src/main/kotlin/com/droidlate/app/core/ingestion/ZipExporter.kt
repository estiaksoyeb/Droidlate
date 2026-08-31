package com.droidlate.app.core.ingestion

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.droidlate.app.core.model.ExportResult
import com.droidlate.app.core.model.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExporter(private val context: Context) {

    /**
     * Packages modified translation resources and metadata into a shareable ZIP archive.
     */
    suspend fun exportTranslations(
        project: ProjectInfo,
        includeBaseValues: Boolean = true,
        specificLocales: List<String>? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedRepo = project.repo.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        val zipFile = File(exportsDir, "Droidlate_${sanitizedRepo}_${timeStamp}.zip")

        val resDirsToExport = if (project.availableResDirPaths.isNotEmpty()) {
            project.availableResDirPaths.map { File(it) }.filter { it.exists() }
        } else {
            listOf(project.activeResDir)
        }

        var fileCount = 0
        val addedZipEntries = mutableSetOf<String>()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 1. Export values-* folders (and base values/ if requested) preserving relative directory structure
            for (resDir in resDirsToExport) {
                resDir.listFiles()?.forEach { dir ->
                    if (dir.isDirectory) {
                        val dirName = dir.name
                        val shouldInclude = when {
                            dirName == "values" -> includeBaseValues
                            dirName.startsWith("values-") -> {
                                if (specificLocales == null) true
                                else specificLocales.any { dirName == "values-$it" || dirName == it }
                            }
                            else -> false
                        }

                        if (shouldInclude) {
                            dir.listFiles()?.forEach { file ->
                                if (file.isFile && file.name.endsWith(".xml")) {
                                    val entryName = getZipEntryPath(file, project.rootDir)
                                    if (!addedZipEntries.contains(entryName)) {
                                        addedZipEntries.add(entryName)
                                        addFileToZip(zos, file, entryName)
                                        fileCount++
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Discover and export all .translation_metadata folders preserving relative directory structure
            val foundMetadataDirs = mutableSetOf<File>()
            project.rootDir.walkTopDown().forEach { file ->
                if (file.isDirectory && file.name == ".translation_metadata") {
                    foundMetadataDirs.add(file)
                }
            }

            for (metaDir in foundMetadataDirs) {
                metaDir.walkTopDown().forEach { file ->
                    if (file.isFile && file.name.endsWith(".json")) {
                        val entryName = getZipEntryPath(file, project.rootDir)
                        if (!addedZipEntries.contains(entryName)) {
                            addedZipEntries.add(entryName)
                            addFileToZip(zos, file, entryName)
                            fileCount++
                        }
                    }
                }
            }

            // 3. Inject Translator Profile credit metadata if configured
            val userProfile = ProjectRepository(context).getUserProfile()
            if (userProfile.githubUsername.isNotBlank() || userProfile.email.isNotBlank()) {
                val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val translatorJson = """
                    {
                      "translator": {
                        "github_username": "${userProfile.githubUsername.replace("\"", "\\\"")}",
                        "email": "${userProfile.email.replace("\"", "\\\"")}"
                      },
                      "project": "${project.name.replace("\"", "\\\"")}",
                      "exported_at": "$exportedAt",
                      "generator": "Droidlate Mobile Workspace"
                    }
                """.trimIndent()
                addStringToZip(zos, translatorJson, "TRANSLATOR.json")
                fileCount++

                val translatorTxt = """
                    Translated & Exported with Droidlate
                    ====================================
                    Project: ${project.name}
                    Exported At: $exportedAt

                    Contributor Credits:
                    --------------------
                    GitHub: ${userProfile.githubUsername.ifBlank { "(Not provided)" }}
                    Email:  ${userProfile.email.ifBlank { "(Not provided)" }}
                """.trimIndent()
                addStringToZip(zos, translatorTxt, "TRANSLATOR.txt")
                fileCount++
            }
        }

        ExportResult(
            zipFile = zipFile,
            fileCount = fileCount,
            totalSizeBytes = zipFile.length()
        )
    }

    private fun getZipEntryPath(file: File, rootDir: File): String {
        return try {
            val rel = file.relativeTo(rootDir).path.replace("\\", "/")
            val topDirs = rootDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
            if (topDirs.size == 1) {
                val singleRootName = topDirs.first().name
                if (rel.startsWith("$singleRootName/")) {
                    return rel.removePrefix("$singleRootName/")
                }
            }
            rel
        } catch (_: Exception) {
            file.name
        }
    }


    private fun addFileToZip(zos: ZipOutputStream, file: File, entryPath: String) {
        val entry = ZipEntry(entryPath)
        entry.time = file.lastModified()
        entry.size = file.length()
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            fis.copyTo(zos)
        }
        zos.closeEntry()
    }

    private fun addStringToZip(zos: ZipOutputStream, content: String, entryPath: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val entry = ZipEntry(entryPath)
        entry.time = System.currentTimeMillis()
        entry.size = bytes.size.toLong()
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    /**
     * Saves the generated ZIP archive to a user-selected SAF Uri.
     */
    fun saveZipToUri(zipFile: File, destUri: android.net.Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(zipFile).use { input ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates an Android Share Intent with FileProvider permissions.
     */
    fun createShareIntent(zipFile: File, title: String = "Share Translation ZIP"): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, zipFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

