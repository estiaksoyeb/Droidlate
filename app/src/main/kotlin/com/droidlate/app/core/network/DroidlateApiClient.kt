package com.droidlate.app.core.network

import com.droidlate.app.core.model.LanguageInfo
import com.droidlate.app.core.model.StringEntry
import com.droidlate.app.core.model.SuggestionItem
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class DroidlateApiClient(private val baseUrl: String = "http://127.0.0.1:5000/") {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val gson = GsonBuilder().setLenient().create()

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val apiService: DroidlateApiService = retrofit.create(DroidlateApiService::class.java)

    /**
     * Checks if localhost Flask server is reachable and accepting TCP connections.
     */
    suspend fun isServerAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Any HTTP response (200, 404, etc.) proves the Flask TCP socket is active
            apiService.getProject()
            true
        } catch (e: Exception) {
            false
        }
    }


    suspend fun fetchProjectLanguages(): Result<List<LanguageInfo>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getProject()
            if (response.isSuccessful && response.body() != null) {
                val apiLanguages = response.body()!!.languages ?: emptyList()
                val mapped = apiLanguages.map {
                    LanguageInfo(
                        folder = it.folder,
                        locale = it.locale,
                        progress = it.progress,
                        translated = it.translated,
                        outdated = it.outdated,
                        untranslated = it.untranslated,
                        orphaned = it.orphaned,
                        total = it.total,
                        targetPath = it.targetPath
                    )
                }
                Result.success(mapped)
            } else {
                val rawErr = response.errorBody()?.string() ?: ""
                val msg = if (rawErr.contains("<title>500") || rawErr.contains("Internal Server Error")) {
                    "Workspace initialization in progress. Tap refresh in a moment."
                } else if (rawErr.isNotBlank()) {
                    rawErr
                } else {
                    "Failed to fetch project: HTTP ${response.code()}"
                }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchStrings(langFolder: String): Result<List<StringEntry>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getStrings(langFolder)
            if (response.isSuccessful && response.body() != null) {
                val apiStrings = response.body()!!.strings
                val mapped = apiStrings.map {
                    StringEntry(
                        key = it.key,
                        source = it.source,
                        sourceHash = it.sourceHash ?: "",
                        translation = it.translation ?: "",
                        comment = it.comment,
                        status = it.status,
                        attrib = it.attrib ?: emptyMap()
                    )
                }
                Result.success(mapped)
            } else {
                val rawErr = response.errorBody()?.string() ?: ""
                val msg = if (rawErr.contains("<title>500") || rawErr.contains("Internal Server Error")) {
                    "Server is processing resource strings. Please retry."
                } else if (rawErr.isNotBlank()) {
                    rawErr
                } else {
                    "Failed to fetch strings: HTTP ${response.code()}"
                }
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun saveTranslation(
        langFolder: String,
        key: String,
        value: String?,
        sourceHash: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = TranslateApiRequest(lang = langFolder, key = key, value = value, sourceHash = sourceHash)
            val response = apiService.saveTranslation(req)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                val err = response.body()?.error ?: response.body()?.message ?: response.errorBody()?.string() ?: "Translation save failed"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSuggestions(text: String, targetLang: String): List<SuggestionItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SuggestionItem>()
        if (text.isBlank()) return@withContext emptyList()

        // 1. Fetch from Python engine (Local TM, MyMemory, etc.)
        try {
            val response = apiService.getSuggestions(text = text, src = "values", tgt = targetLang)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.suggestions.forEach {
                    result.add(SuggestionItem(provider = it.provider, text = it.text))
                }
            }
        } catch (_: Exception) {}

        // 2. If Google Translate is not in results (due to Python client=gtx 429 block), fetch Google Translate directly
        if (result.none { it.provider.contains("Google", ignoreCase = true) }) {
            try {
                val isoLang = androidFolderToIso(targetLang)
                val googleText = fetchGoogleTranslate(text, isoLang)
                if (!googleText.isNullOrBlank() && result.none { it.text.equals(googleText, ignoreCase = true) }) {
                    result.add(0, SuggestionItem(provider = "Google Translate", text = googleText))
                }
            } catch (_: Exception) {}
        }

        result
    }

    private fun fetchGoogleTranslate(text: String, targetIso: String): String? {
        return try {
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=$targetIso&q=$encoded"
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val jsonElem = com.google.gson.JsonParser.parseString(body)
                if (jsonElem.isJsonArray && jsonElem.asJsonArray.size() > 0) {
                    jsonElem.asJsonArray[0].asString
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun androidFolderToIso(folder: String): String {
        val clean = folder.removePrefix("values-")
        return when {
            clean.contains("-r") -> clean.replace("-r", "-")
            clean == "values" || clean == "default" -> "en"
            else -> clean
        }
    }


    suspend fun addLanguage(localeCode: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val trimmed = localeCode.trim()
            val normalized = if (trimmed.matches(Regex("^[a-z]{2,3}-[a-zA-Z]{2}$"))) {
                val parts = trimmed.split("-")
                "${parts[0].lowercase()}-r${parts[1].uppercase()}"
            } else {
                trimmed
            }
            val req = AddLanguageApiRequest(locale = normalized)
            val response = apiService.addLanguage(req)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.folder ?: "values-$normalized")
            } else {
                val err = response.body()?.error ?: response.errorBody()?.string() ?: "Failed to add language"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pruneString(langFolder: String, key: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = PruneApiRequest(lang = langFolder, key = key)
            val response = apiService.pruneString(req)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                val err = response.body()?.error ?: response.errorBody()?.string() ?: "Failed to prune string"
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
