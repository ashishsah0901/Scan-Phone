package com.ashish.scanphone.notifications

import org.json.JSONObject

data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY, key)
        .put(PACKAGE, packageName)
        .put(LABEL, appLabel)
        .put(TITLE, title)
        .put(TEXT, text)
        .put(POSTED_AT, postedAt)

    companion object {
        private const val KEY = "key"
        private const val PACKAGE = "package"
        private const val LABEL = "label"
        private const val TITLE = "title"
        private const val TEXT = "text"
        private const val POSTED_AT = "postedAt"

        fun fromJson(json: JSONObject): NotificationRecord = NotificationRecord(
            key = json.optString(KEY),
            packageName = json.optString(PACKAGE),
            appLabel = json.optString(LABEL),
            title = json.optString(TITLE),
            text = json.optString(TEXT),
            postedAt = json.optLong(POSTED_AT),
        )
    }
}
