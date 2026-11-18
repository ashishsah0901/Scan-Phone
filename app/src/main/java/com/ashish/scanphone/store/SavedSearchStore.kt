package com.ashish.scanphone.store

import android.content.Context
import com.ashish.scanphone.model.QueryMode
import com.ashish.scanphone.model.ScanOptions
import com.ashish.scanphone.model.ScanSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedSearch(
    val name: String,
    val query: String,
    val options: ScanOptions,
)

class SavedSearchStore private constructor(private val file: File) {

    fun list(): List<SavedSearch> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(search: SavedSearch) {
        val current = list().filterNot { it.name == search.name }.toMutableList()
        current.add(search)
        persist(current)
    }

    fun delete(name: String) {
        persist(list().filterNot { it.name == name })
    }

    private fun persist(searches: List<SavedSearch>) {
        runCatching {
            val array = JSONArray()
            searches.forEach { array.put(toJson(it)) }
            file.writeText(array.toString())
        }
    }

    private fun toJson(search: SavedSearch): JSONObject = JSONObject()
        .put("name", search.name)
        .put("query", search.query)
        .put("queryMode", search.options.queryMode.name)
        .put("caseSensitive", search.options.caseSensitive)
        .put("searchFileContents", search.options.searchFileContents)
        .put("runImageOcr", search.options.runImageOcr)
        .put("searchAppMetadata", search.options.searchAppMetadata)
        .put("searchApkContents", search.options.searchApkContents)
        .put("readAppData", search.options.readAppData)
        .put("mediaImageOcr", search.options.mediaImageOcr)
        .put("mediaVideoText", search.options.mediaVideoText)
        .put("sources", JSONArray(search.options.sources.map { it.name }))

    private fun fromJson(json: JSONObject): SavedSearch? = runCatching {
        val sourcesArray = json.getJSONArray("sources")
        val sources = (0 until sourcesArray.length())
            .mapNotNull { runCatching { ScanSource.valueOf(sourcesArray.getString(it)) }.getOrNull() }
            .toSet()
        SavedSearch(
            name = json.getString("name"),
            query = json.optString("query"),
            options = ScanOptions(
                sources = sources.ifEmpty { ScanOptions.DEFAULT_SOURCES },
                queryMode = runCatching { QueryMode.valueOf(json.optString("queryMode")) }
                    .getOrDefault(QueryMode.SUBSTRING),
                searchFileContents = json.optBoolean("searchFileContents", true),
                runImageOcr = json.optBoolean("runImageOcr", false),
                searchAppMetadata = json.optBoolean("searchAppMetadata", true),
                searchApkContents = json.optBoolean("searchApkContents", false),
                readAppData = json.optBoolean("readAppData", false),
                mediaImageOcr = json.optBoolean("mediaImageOcr", false),
                mediaVideoText = json.optBoolean("mediaVideoText", false),
                caseSensitive = json.optBoolean("caseSensitive", false),
            ),
        )
    }.getOrNull()

    companion object {
        fun get(context: Context): SavedSearchStore =
            SavedSearchStore(File(context.applicationContext.filesDir, "saved_searches.json"))
    }
}
