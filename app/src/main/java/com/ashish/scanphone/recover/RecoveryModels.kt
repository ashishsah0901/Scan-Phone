package com.ashish.scanphone.recover

import org.json.JSONArray
import org.json.JSONObject

enum class RecoveryType {
    FILE,
    QUARANTINE,
    APP_DATA_FILE,
    CONTACT,
    CALENDAR,
    CALL_LOG,
    MESSAGE,
    CARVED,
}

enum class RecoverySource {
    RECYCLE_BIN,
    DEEP_SCAN,
}

data class RecoveryEntry(
    val id: String,
    val type: RecoveryType,
    val title: String,
    val subtitle: String,
    val deletedAt: Long,
    val source: RecoverySource,
    val originalPath: String? = null,
    val trashPath: String? = null,
    val viaRoot: Boolean = false,
    val payload: String? = null,
) {
    fun payloadObject(): JSONObject? = payload?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun payloadArray(): JSONArray? = payload?.let { runCatching { JSONArray(it) }.getOrNull() }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("deletedAt", deletedAt)
        .put("source", source.name)
        .put("originalPath", originalPath)
        .put("trashPath", trashPath)
        .put("viaRoot", viaRoot)
        .put("payload", payload)

    companion object {
        fun fromJson(json: JSONObject): RecoveryEntry = RecoveryEntry(
            id = json.getString("id"),
            type = RecoveryType.valueOf(json.getString("type")),
            title = json.optString("title"),
            subtitle = json.optString("subtitle"),
            deletedAt = json.optLong("deletedAt"),
            source = runCatching { RecoverySource.valueOf(json.optString("source")) }
                .getOrDefault(RecoverySource.RECYCLE_BIN),
            originalPath = json.optString("originalPath").ifBlank { null },
            trashPath = json.optString("trashPath").ifBlank { null },
            viaRoot = json.optBoolean("viaRoot", false),
            payload = if (json.isNull("payload")) null else json.optString("payload").ifBlank { null },
        )
    }
}

sealed interface RestoreResult {
    data class Success(val detail: String) : RestoreResult

    data class Failure(val reason: String) : RestoreResult
}
