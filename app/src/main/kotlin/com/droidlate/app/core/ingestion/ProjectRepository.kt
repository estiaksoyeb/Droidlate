package com.droidlate.app.core.ingestion

import android.content.Context
import com.droidlate.app.core.model.ProjectInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ProjectRepository(private val context: Context) {

    private val gson = Gson()
    private val metaFile = File(context.filesDir, "recent_projects.json")

    suspend fun getRecentProjects(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        if (!metaFile.exists()) return@withContext emptyList()
        try {
            val json = metaFile.readText()
            val type = object : TypeToken<List<ProjectInfo>>() {}.type
            val list: List<ProjectInfo>? = gson.fromJson(json, type)
            list?.filter { File(it.activeResDirPath).exists() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveProject(project: ProjectInfo) = withContext(Dispatchers.IO) {
        val current = getRecentProjects().toMutableList()
        current.removeAll { it.id == project.id }
        current.add(0, project.copy(lastOpenedTimestamp = System.currentTimeMillis()))
        try {
            metaFile.writeText(gson.toJson(current.take(15)))
        } catch (_: Exception) {}
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val current = getRecentProjects().toMutableList()
        val toDelete = current.find { it.id == projectId }
        if (toDelete != null) {
            toDelete.rootDir.deleteRecursively()
            current.remove(toDelete)
            try {
                metaFile.writeText(gson.toJson(current))
            } catch (_: Exception) {}
        }
    }

    fun getPinnedLanguages(projectId: String): Set<String> {
        val prefs = context.getSharedPreferences("droidlate_pins", Context.MODE_PRIVATE)
        return prefs.getStringSet("pins_$projectId", emptySet()) ?: emptySet()
    }

    fun setPinnedLanguages(projectId: String, pinned: Set<String>) {
        val prefs = context.getSharedPreferences("droidlate_pins", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("pins_$projectId", pinned).apply()
    }

    fun getUserProfile(): com.droidlate.app.core.model.UserProfile {
        val prefs = context.getSharedPreferences("droidlate_user_profile", Context.MODE_PRIVATE)
        return com.droidlate.app.core.model.UserProfile(
            githubUsername = prefs.getString("github_username", "") ?: "",
            email = prefs.getString("email", "") ?: ""
        )
    }

    fun saveUserProfile(profile: com.droidlate.app.core.model.UserProfile) {
        val prefs = context.getSharedPreferences("droidlate_user_profile", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("github_username", profile.githubUsername.trim())
            .putString("email", profile.email.trim())
            .apply()
    }
}
