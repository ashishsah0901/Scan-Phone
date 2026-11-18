package com.ashish.scanphone.scan

import android.content.Context
import android.provider.ContactsContract
import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

class ContactsScanner(private val context: Context) : Scanner {

    override val source: ScanSource = ScanSource.CONTACTS

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        val cursor = try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                PROJECTION,
                null,
                null,
                null,
            )
        } catch (security: SecurityException) {
            emit(ScanEvent.Failed(source, security.message ?: "Contacts permission denied"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        if (cursor == null) {
            emit(ScanEvent.Failed(source, "Contacts provider unavailable"))
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        var examined = 0
        val reported = HashSet<String>()

        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val nameColumn = it.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
            val mimeColumn = it.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val valueColumn = it.getColumnIndexOrThrow(ContactsContract.Data.DATA1)

            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                examined++
                if (examined % PROGRESS_INTERVAL == 0) {
                    emit(ScanEvent.Progress(source, examined, null))
                }

                val contactId = it.getLong(idColumn)
                val name = it.getString(nameColumn).orEmpty()
                val mimeType = it.getString(mimeColumn).orEmpty()
                val value = it.getString(valueColumn).orEmpty()

                val hit = contactHit(contactId, name, mimeType, value, matcher) ?: continue
                if (reported.add(hit.id)) emit(ScanEvent.Found(hit))
            }
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private fun contactHit(
        contactId: Long,
        name: String,
        mimeType: String,
        value: String,
        matcher: TextMatcher,
    ): ScanHit? {
        val isPhone = mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        val isEmail = mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        val detailType = DETAIL_TYPES[mimeType]

        val kind = when {
            isPhone && (matcher.matches(value) || matcher.matchesNumber(value)) -> MatchKind.CONTACT_PHONE
            isEmail && matcher.matches(value) -> MatchKind.CONTACT_EMAIL
            detailType != null && matcher.matches(value) -> MatchKind.CONTACT_DETAIL
            matcher.matches(name) -> MatchKind.CONTACT_NAME
            else -> return null
        }

        val detail = when (kind) {
            MatchKind.CONTACT_NAME -> if (isPhone || isEmail) value else "contact"
            MatchKind.CONTACT_DETAIL -> "$detailType: $value"
            else -> value
        }

        return ScanHit(
            id = "contact:${kind.name}:$contactId:$detail",
            source = source,
            matchKind = kind,
            title = name.ifBlank { detail },
            subtitle = detail,
            snippet = null,
            openTarget = OpenTarget.Contact(contactId),
            verifyRef = contactId.toString(),
            fingerprint = "$name|$detail",
        )
    }

    private companion object {
        const val PROGRESS_INTERVAL = 200

        val DETAIL_TYPES: Map<String, String> = mapOf(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE to "address",
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE to "organisation",
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE to "note",
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE to "nickname",
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE to "website",
            ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE to "IM",
            ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE to "SIP",
        )

        val PROJECTION = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
        )
    }
}
