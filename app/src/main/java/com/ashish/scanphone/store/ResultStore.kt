package com.ashish.scanphone.store

import android.content.Context
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedResults(
    val query: String,
    val savedAt: Long,
    val hits: List<ScanHit>,
)

class ResultStore private constructor(private val file: File) {

    fun save(query: String, hits: List<ScanHit>) {
        runCatching {
            val root = JSONObject()
                .put(QUERY, query)
                .put(SAVED_AT, System.currentTimeMillis())
            val array = JSONArray()
            hits.take(MAX_PERSISTED).forEach { array.put(hitToJson(it)) }
            root.put(HITS, array)
            file.writeText(root.toString())
        }
    }

    fun load(): SavedResults? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val array = root.getJSONArray(HITS)
        val hits = ArrayList<ScanHit>(array.length())
        for (index in 0 until array.length()) {
            hitFromJson(array.getJSONObject(index))?.let(hits::add)
        }
        SavedResults(root.optString(QUERY), root.optLong(SAVED_AT), hits)
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    private fun hitToJson(hit: ScanHit): JSONObject = JSONObject()
        .put("id", hit.id)
        .put("source", hit.source.name)
        .put("matchKind", hit.matchKind.name)
        .put("title", hit.title)
        .put("subtitle", hit.subtitle)
        .put("snippet", hit.snippet)
        .put("exportPath", hit.exportPath)

    private fun hitFromJson(json: JSONObject): ScanHit? = runCatching {
        ScanHit(
            id = json.getString("id"),
            source = ScanSource.valueOf(json.getString("source")),
            matchKind = MatchKind.valueOf(json.getString("matchKind")),
            title = json.optString("title"),
            subtitle = json.optString("subtitle"),
            snippet = json.optString("snippet").ifBlank { null },
            openTarget = null,
            exportPath = json.optString("exportPath").ifBlank { null },
        )
    }.getOrNull()

    companion object {
        private const val QUERY = "query"
        private const val SAVED_AT = "savedAt"
        private const val HITS = "hits"
        private const val MAX_PERSISTED = 2000

        fun get(context: Context): ResultStore =
            ResultStore(File(context.applicationContext.filesDir, "last_results.json"))
    }
}
