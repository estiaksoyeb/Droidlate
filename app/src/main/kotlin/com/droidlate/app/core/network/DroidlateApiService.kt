package com.droidlate.app.core.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// --- Request & Response Models for Droidlate Python Engine REST API ---

data class ProjectApiResponse(
    @SerializedName("mode") val mode: String?,
    @SerializedName("res_dir") val resDir: String?,
    @SerializedName("source_file") val sourceFile: String?,
    @SerializedName("languages") val languages: List<LanguageApiItem>?
)

data class LanguageApiItem(
    @SerializedName("folder") val folder: String,
    @SerializedName("locale") val locale: String,
    @SerializedName("progress") val progress: Int,
    @SerializedName("translated") val translated: Int,
    @SerializedName("outdated") val outdated: Int,
    @SerializedName("untranslated") val untranslated: Int,
    @SerializedName("orphaned") val orphaned: Int,
    @SerializedName("total") val total: Int,
    @SerializedName("target_path") val targetPath: String?
)

data class StringsApiResponse(
    @SerializedName("locale") val locale: String,
    @SerializedName("strings") val strings: List<StringApiItem>
)

data class StringApiItem(
    @SerializedName("key") val key: String,
    @SerializedName("source") val source: String,
    @SerializedName("source_hash") val sourceHash: String?,
    @SerializedName("translation") val translation: String?,
    @SerializedName("comment") val comment: String?,
    @SerializedName("status") val status: String,
    @SerializedName("attrib") val attrib: Map<String, String>?
)

data class TranslateApiRequest(
    @SerializedName("lang") val lang: String?,
    @SerializedName("key") val key: String,
    @SerializedName("value") val value: String?,
    @SerializedName("source_hash") val sourceHash: String? = null
)

data class AddLanguageApiRequest(
    @SerializedName("locale") val locale: String
)

data class PruneApiRequest(
    @SerializedName("lang") val lang: String?,
    @SerializedName("key") val key: String
)

data class SuggestionApiResponse(
    @SerializedName("suggestions") val suggestions: List<SuggestionApiItem>
)

data class SuggestionApiItem(
    @SerializedName("provider") val provider: String,
    @SerializedName("text") val text: String
)

data class GenericApiResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("error") val error: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("folder") val folder: String? = null
)

/**
 * Retrofit interface communicating with localhost Flask server (http://127.0.0.1:5000)
 */
interface DroidlateApiService {

    @GET("/api/project")
    suspend fun getProject(): Response<ProjectApiResponse>

    @GET("/api/strings")
    suspend fun getStrings(@Query("lang") langFolder: String): Response<StringsApiResponse>

    @POST("/api/translate")
    suspend fun saveTranslation(@Body request: TranslateApiRequest): Response<GenericApiResponse>

    @GET("/api/suggest")
    suspend fun getSuggestions(
        @Query("text") text: String,
        @Query("src") src: String = "values",
        @Query("tgt") tgt: String
    ): Response<SuggestionApiResponse>

    @POST("/api/languages")
    suspend fun addLanguage(@Body request: AddLanguageApiRequest): Response<GenericApiResponse>

    @POST("/api/prune")
    suspend fun pruneString(@Body request: PruneApiRequest): Response<GenericApiResponse>
}
