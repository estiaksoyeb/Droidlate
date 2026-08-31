package com.droidlate.app.core.ingestion

import android.content.Context
import com.droidlate.app.core.model.IngestionState
import com.droidlate.app.core.model.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class GitHubDownloader(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class RepoCoordinates(
        val owner: String,
        val repo: String,
        val branch: String? = null
    ) {
        val id: String get() {
            val base = "${owner}_${repo}".lowercase().replace("[^a-z0-9_]".toRegex(), "_")
            return if (!branch.isNullOrBlank()) {
                val branchSuffix = branch.lowercase().replace("[^a-z0-9_]".toRegex(), "_")
                "${base}_${branchSuffix}"
            } else {
                base
            }
        }
    }

    /**
     * Parses various GitHub URL formats and shorthand inputs.
     * Examples:
     * - "skydoves/Pokedex"
     * - "skydoves/Pokedex@feature/ui"
     * - "https://github.com/skydoves/Pokedex"
     * - "https://github.com/skydoves/Pokedex.git"
     * - "https://github.com/skydoves/Pokedex/tree/feature/new-branch"
     * - "https://github.com/skydoves/Pokedex/archive/refs/heads/main.zip"
     */
    fun parseRepoUrl(input: String): RepoCoordinates? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 1. Shorthand: "owner/repo" or "owner/repo@branch" (branch may contain slashes)
        val shorthandRegex = Regex("""^([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:@([a-zA-Z0-9_./-]+))?$""")
        val shorthandMatch = shorthandRegex.matchEntire(trimmed)
        if (shorthandMatch != null) {
            val owner = shorthandMatch.groupValues[1]
            val repo = shorthandMatch.groupValues[2].removeSuffix(".git")
            val branch = shorthandMatch.groupValues[3].takeIf { it.isNotEmpty() }
            return RepoCoordinates(owner, repo, branch)
        }

        // 2. Full GitHub URL: https://github.com/owner/repo/...
        val urlTreeRegex = Regex("""https?://github\.com/([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:/(?:tree|archive/refs/heads)/(.+?))?(?:\.git|/)?$""")
        val urlMatch = urlTreeRegex.matchEntire(trimmed)
        if (urlMatch != null) {
            val owner = urlMatch.groupValues[1]
            val repo = urlMatch.groupValues[2].removeSuffix(".git")
            val rawBranch = urlMatch.groupValues[3].takeIf { it.isNotEmpty() }
            val branch = rawBranch?.removeSuffix(".zip")?.removeSuffix("/")
            return RepoCoordinates(owner, repo, branch)
        }

        return null
    }

    /**
     * Downloads and extracts a GitHub repository with progress updates.
     */
    fun downloadRepository(coordinates: RepoCoordinates): Flow<IngestionState> = flow {
        emit(IngestionState.Downloading(0, 0, -1))

        // Persistent projects storage in filesDir (not volatile cacheDir)
        val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
        val targetDir = File(projectsDir, coordinates.id)
        val tempZipFile = File(context.cacheDir, "${coordinates.id}_download.zip")

        try {
            // Build GitHub API or direct archive URL
            val downloadUrl = if (coordinates.branch != null) {
                "https://api.github.com/repos/${coordinates.owner}/${coordinates.repo}/zipball/${coordinates.branch}"
            } else {
                "https://api.github.com/repos/${coordinates.owner}/${coordinates.repo}/zipball"
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Droidlate-Android-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                // Fallback to direct github archive if rate limited or API issue
                val fallbackBranch = coordinates.branch ?: "main"
                val fallbackUrl = "https://github.com/${coordinates.owner}/${coordinates.repo}/archive/refs/heads/$fallbackBranch.zip"
                val fallbackResponse = okHttpClient.newCall(
                    Request.Builder().url(fallbackUrl).header("User-Agent", "Droidlate-Android-App").build()
                ).execute()

                if (!fallbackResponse.isSuccessful) {
                    emit(IngestionState.Error("Failed to download repository: HTTP ${response.code} / ${fallbackResponse.code}"))
                    return@flow
                }
                saveStreamWithProgress(fallbackResponse.body!!.byteStream(), fallbackResponse.body!!.contentLength(), tempZipFile) { pct, read, total ->
                    emit(IngestionState.Downloading(pct, read, total))
                }
            } else {
                val body = response.body ?: throw IllegalStateException("Empty response body from GitHub")
                saveStreamWithProgress(body.byteStream(), body.contentLength(), tempZipFile) { pct, read, total ->
                    emit(IngestionState.Downloading(pct, read, total))
                }
            }

            // Extract archive
            emit(IngestionState.Extracting("Extracting repository files..."))
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            unzipSafe(tempZipFile, targetDir)
            tempZipFile.delete()

            // Locate Android resources
            emit(IngestionState.Scanning("Scanning for Android strings.xml resources..."))
            val projectInfo = discoverProjectResources(coordinates, targetDir)

            if (projectInfo != null) {
                emit(IngestionState.Success(projectInfo))
            } else {
                emit(IngestionState.Error("No valid Android 'res/values/strings.xml' found in repository."))
            }

        } catch (e: Exception) {
            tempZipFile.delete()
            emit(IngestionState.Error(e.message ?: "Unknown error occurred during download"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Imports a local ZIP archive from an InputStream.
     */
    suspend fun importLocalZip(inputStream: InputStream, projectName: String): ProjectInfo? = withContext(Dispatchers.IO) {
        val sanitizedName = projectName.replace("[^a-zA-Z0-9_.-]".toRegex(), "_").removeSuffix(".zip")
        val uniqueId = "local_${sanitizedName}_${System.currentTimeMillis()}"
        val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
        val targetDir = File(projectsDir, uniqueId)
        targetDir.mkdirs()

        val tempZip = File(context.cacheDir, "${uniqueId}_import.zip")
        try {
            FileOutputStream(tempZip).use { out ->
                inputStream.copyTo(out)
            }

            unzipSafe(tempZip, targetDir)
            tempZip.delete()

            val coordinates = RepoCoordinates(owner = "Local", repo = sanitizedName, branch = null)
            discoverProjectResources(coordinates, targetDir)
        } catch (e: Exception) {
            tempZip.delete()
            null
        }
    }

    private suspend fun saveStreamWithProgress(
        input: InputStream,
        contentLength: Long,
        destFile: File,
        onProgress: suspend (pct: Int, read: Long, total: Long) -> Unit
    ) {
        FileOutputStream(destFile).use { output ->
            val buffer = ByteArray(8192)
            var bytesRead: Long = 0
            var read: Int
            var lastReportTime = System.currentTimeMillis()

            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                bytesRead += read

                val now = System.currentTimeMillis()
                if (now - lastReportTime > 150) { // report every 150ms
                    lastReportTime = now
                    val pct = if (contentLength > 0) ((bytesRead * 100) / contentLength).toInt() else -1
                    onProgress(pct, bytesRead, contentLength)
                }
            }
            onProgress(100, bytesRead, contentLength)
        }
    }

    private fun unzipSafe(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            val canonicalDestDirPath = destDir.canonicalPath

            while (zis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry ?: break
                val normalizedName = currentEntry.name.replace('\\', '/').removePrefix("/")
                if (normalizedName.isEmpty()) {
                    zis.closeEntry()
                    continue
                }

                val newFile = File(destDir, normalizedName)
                val canonicalDestPath = newFile.canonicalPath

                // ZipSlip vulnerability protection
                if (!canonicalDestPath.startsWith(canonicalDestDirPath)) {
                    throw SecurityException("Zip entry is outside of target directory: ${currentEntry.name}")
                }

                if (currentEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Discovers Android resource folders containing `values/strings.xml`.
     */
    fun discoverProjectResources(coordinates: RepoCoordinates, rootDir: File): ProjectInfo? {
        val foundResDirs = mutableListOf<File>()

        // Recursively find all `values/strings.xml`
        rootDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.equals("strings.xml", ignoreCase = true)) {
                val parent = file.parentFile // values/
                if (parent != null && parent.name.equals("values", ignoreCase = true)) {
                    val resDir = parent.parentFile // res/
                    if (resDir != null && !foundResDirs.contains(resDir)) {
                        foundResDirs.add(resDir)
                    }
                }
            }
        }

        // Fallback: Check if strings.xml is in a non-standard or top-level directory
        if (foundResDirs.isEmpty()) {
            val allStringsFiles = rootDir.walkTopDown().filter { it.isFile && it.name.equals("strings.xml", ignoreCase = true) }.toList()
            if (allStringsFiles.isNotEmpty()) {
                val candidate = allStringsFiles.first()
                val parent = candidate.parentFile
                if (parent != null) {
                    if (parent.name.equals("values", ignoreCase = true)) {
                        parent.parentFile?.let { foundResDirs.add(it) }
                    } else {
                        val syntheticValues = File(parent, "values")
                        syntheticValues.mkdirs()
                        val localTarget = File(syntheticValues, "strings.xml")
                        if (!localTarget.exists()) {
                            candidate.copyTo(localTarget, overwrite = true)
                        }
                        foundResDirs.add(parent)
                    }
                }
            }
        }

        if (foundResDirs.isEmpty()) {
            return null
        }

        // Prioritize standard module paths:
        // 1. app/src/main/res
        // 2. src/main/res
        // 3. Any path containing "src/main/res"
        // 4. First discovered res directory
        val prioritizedResDir = foundResDirs.minByOrNull { dir ->
            val path = dir.absolutePath.replace("\\", "/")
            when {
                path.endsWith("app/src/main/res") -> 0
                path.contains("/src/main/res") -> 1
                path.endsWith("/res") -> 2
                else -> 3
            }
        } ?: foundResDirs.first()

        val sourceXml = File(prioritizedResDir, "values/strings.xml")

        return ProjectInfo(
            id = rootDir.name,
            name = if (coordinates.owner.equals("Local", ignoreCase = true)) coordinates.repo else "${coordinates.owner}/${coordinates.repo}",
            owner = coordinates.owner,
            repo = coordinates.repo,
            branch = coordinates.branch,
            rootDirPath = rootDir.absolutePath,
            activeResDirPath = prioritizedResDir.absolutePath,
            availableResDirPaths = foundResDirs.map { it.absolutePath },
            sourceXmlPath = sourceXml.absolutePath,
            lastOpenedTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Pulls latest upstream GitHub commits and safely merges base English strings.xml
     * resources without touching existing translated values-* files or metadata sidecars.
     */
    suspend fun syncRepository(project: ProjectInfo): Result<com.droidlate.app.core.model.SyncSummary> = withContext(Dispatchers.IO) {
        if (project.isLocal) {
            return@withContext Result.failure(IllegalStateException("Cannot sync a local project without a linked GitHub repository URL."))
        }

        val coordinates = RepoCoordinates(owner = project.owner, repo = project.repo, branch = project.branch)
        val stagingDir = File(context.cacheDir, "staging_${project.id}_${System.currentTimeMillis()}")
        val tempZip = File(context.cacheDir, "staging_${project.id}.zip")

        try {
            // 1. Download latest archive from GitHub
            val downloadUrl = if (coordinates.branch != null) {
                "https://api.github.com/repos/${coordinates.owner}/${coordinates.repo}/zipball/${coordinates.branch}"
            } else {
                "https://api.github.com/repos/${coordinates.owner}/${coordinates.repo}/zipball"
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Droidlate-Android-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val fallbackBranch = coordinates.branch ?: "main"
                val fallbackUrl = "https://github.com/${coordinates.owner}/${coordinates.repo}/archive/refs/heads/$fallbackBranch.zip"
                val fallbackResponse = okHttpClient.newCall(
                    Request.Builder().url(fallbackUrl).header("User-Agent", "Droidlate-Android-App").build()
                ).execute()

                if (!fallbackResponse.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to download upstream archive: HTTP ${fallbackResponse.code}"))
                }
                FileOutputStream(tempZip).use { out ->
                    fallbackResponse.body!!.byteStream().copyTo(out)
                }
            } else {
                val body = response.body ?: return@withContext Result.failure(Exception("Empty body from GitHub"))
                FileOutputStream(tempZip).use { out ->
                    body.byteStream().copyTo(out)
                }
            }

            // 2. Extract into staging
            stagingDir.mkdirs()
            unzipSafe(tempZip, stagingDir)
            tempZip.delete()

            // 3. Locate upstream base resources
            val upstreamProject = discoverProjectResources(coordinates, stagingDir)
                ?: return@withContext Result.failure(Exception("No Android string resources found in upstream repository"))

            var updatedModules = 0

            // 4. Safely copy ONLY base values/ into local project modules
            for (upstreamResPath in upstreamProject.availableResDirPaths) {
                val upstreamResDir = File(upstreamResPath)
                val upstreamBaseValues = File(upstreamResDir, "values")
                if (!upstreamBaseValues.exists()) continue

                val matchingLocalResDir = findMatchingLocalResDir(project, upstreamResDir, upstreamProject.rootDir)

                val localBaseValues = File(matchingLocalResDir, "values")
                localBaseValues.mkdirs()

                upstreamBaseValues.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.copyTo(File(localBaseValues, file.name), overwrite = true)
                    }
                }
                updatedModules++
            }

            // 5. Clean up staging
            stagingDir.deleteRecursively()

            Result.success(
                com.droidlate.app.core.model.SyncSummary(
                    projectName = project.name,
                    updatedModulesCount = updatedModules,
                    message = "Synced successfully! Upstream base strings updated."
                )
            )

        } catch (e: Exception) {
            tempZip.delete()
            stagingDir.deleteRecursively()
            Result.failure(e)
        }
    }

    private fun findMatchingLocalResDir(project: ProjectInfo, upstreamResDir: File, upstreamRootDir: File): File {
        val relPath = upstreamResDir.relativeToOrNull(upstreamRootDir)?.path?.replace("\\", "/")
        if (relPath != null) {
            val directLocal = File(project.rootDir, relPath)
            if (directLocal.exists()) return directLocal

            // Strip the top-level archive directory (e.g. repo-commitSha/app/src/main/res)
            val segments = relPath.split("/").filter { it.isNotEmpty() }
            if (segments.size > 1) {
                val strippedRelPath = segments.drop(1).joinToString("/")
                val strippedLocal = File(project.rootDir, strippedRelPath)
                if (strippedLocal.exists()) return strippedLocal

                val matched = project.availableResDirPaths.find { localPath ->
                    val normLocal = localPath.replace("\\", "/")
                    normLocal.endsWith(strippedRelPath) || (segments.size >= 3 && normLocal.endsWith(segments.takeLast(3).joinToString("/")))
                }
                if (matched != null) return File(matched)
            }
        }
        return project.activeResDir
    }
}

