package com.ashish.scanphone.model

enum class ScanSource(val label: String) {
    FILES("Files"),
    MEDIA("Media"),
    CONTACTS("Contacts"),
    MESSAGES("Messages"),
    CALL_LOG("Calls"),
    CALENDAR("Calendar"),
    APPS("Apps"),
    NOTIFICATIONS("Notifications"),
    APP_USAGE("App usage"),
    APP_DATA("App data (root)"),
}

enum class MatchKind(val label: String) {
    FILE_NAME("file name"),
    FILE_PATH("folder path"),
    FILE_CONTENT("file content"),
    IMAGE_TEXT("text in image"),
    MEDIA_TAG("media tag"),
    MEDIA_EXIF("photo metadata"),
    MEDIA_IMAGE_TEXT("text in image"),
    VIDEO_TEXT("text in video frame"),
    VIDEO_CAPTION("video caption"),
    CONTACT_NAME("contact name"),
    CONTACT_PHONE("phone number"),
    CONTACT_EMAIL("email"),
    CONTACT_DETAIL("contact detail"),
    MESSAGE_BODY("message body"),
    MESSAGE_ADDRESS("sender"),
    CALL_NUMBER("call number"),
    CALL_NAME("caller name"),
    CALENDAR_TITLE("event title"),
    CALENDAR_DETAIL("event detail"),
    APP_LABEL("app name"),
    APP_PACKAGE("package name"),
    APP_VERSION("app version"),
    APP_PERMISSION("declared permission"),
    APP_COMPONENT("app component"),
    APP_PATH("app install path"),
    APP_INSTALLER("installing app"),
    APK_ENTRY("file inside the apk"),
    APK_RESOURCE("data inside the apk"),
    APP_DATA_PATH("private data path"),
    APP_DATA_CONTENT("private data content"),
    APP_DATABASE_ROW("app database row"),
    APP_PREFERENCE("app setting"),
    NOTIFICATION_TITLE("notification title"),
    NOTIFICATION_TEXT("notification text"),
    NOTIFICATION_APP("notifying app"),
    NOTIFICATION_HISTORY("past notification"),
    USAGE_APP("app usage"),
}

enum class QueryMode(val label: String) {
    SUBSTRING("Contains"),
    ALL_TERMS("All words"),
    ANY_TERMS("Any word"),
    REGEX("Regex"),
}

sealed interface OpenTarget {
    data class LocalFile(val path: String) : OpenTarget

    data class Contact(val contactId: Long) : OpenTarget

    data class MessageThread(val threadId: Long) : OpenTarget

    data class Dialer(val number: String) : OpenTarget

    data class CalendarEvent(val eventId: Long) : OpenTarget

    data class App(val packageName: String) : OpenTarget

    data class NotificationSettings(val packageName: String) : OpenTarget
}

data class ScanHit(
    val id: String,
    val source: ScanSource,
    val matchKind: MatchKind,
    val title: String,
    val subtitle: String,
    val snippet: String?,
    val openTarget: OpenTarget?,
    val verifyRef: String? = null,
    val fingerprint: String? = null,
    val exportPath: String? = null,
    val exportViaRoot: Boolean = false,
)

enum class HitStatus(val label: String) {
    UNCHANGED("unchanged"),
    CHANGED("changed"),
    GONE("gone"),
    UNVERIFIABLE("not checkable"),
}

data class HitVerification(
    val status: HitStatus,
    val detail: String?,
)

data class ScanOptions(
    val sources: Set<ScanSource> = DEFAULT_SOURCES,
    val queryMode: QueryMode = QueryMode.SUBSTRING,
    val searchFileContents: Boolean = true,
    val runImageOcr: Boolean = false,
    val searchAppMetadata: Boolean = true,
    val searchApkContents: Boolean = false,
    val readAppData: Boolean = false,
    val mediaImageOcr: Boolean = false,
    val mediaVideoText: Boolean = false,
    val caseSensitive: Boolean = false,
) {
    companion object {
        val DEFAULT_SOURCES: Set<ScanSource> = ScanSource.entries.toSet() - ScanSource.APP_DATA
    }
}

data class ScanRequest(
    val query: String,
    val options: ScanOptions,
)

sealed interface ScanEvent {
    data class Found(val hit: ScanHit) : ScanEvent

    data class Progress(val source: ScanSource, val examined: Int, val current: String?) : ScanEvent

    data class Failed(val source: ScanSource, val reason: String) : ScanEvent

    data class Finished(val source: ScanSource, val examined: Int) : ScanEvent
}
