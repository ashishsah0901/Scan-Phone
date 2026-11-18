package com.ashish.scanphone.threat.source

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.threat.ThreatArea
import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatEvent
import com.ashish.scanphone.threat.ThreatFinding
import com.ashish.scanphone.threat.ThreatScanOptions
import com.ashish.scanphone.threat.rules.NumberThreatRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

class NumberThreatSource(private val context: Context) : ThreatSource {

    override val check: ThreatCheck = ThreatCheck.NUMBERS

    override fun scan(options: ThreatScanOptions): Flow<ThreatEvent> = flow {
        val callsExamined = scanCalls()
        emit(ThreatEvent.Finished(ThreatArea.CALLS, callsExamined))

        val contactsExamined = scanContacts()
        emit(ThreatEvent.Finished(ThreatArea.CONTACTS, contactsExamined))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ThreatEvent>.scanCalls(): Int {
        val cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )
        } catch (security: SecurityException) {
            emit(ThreatEvent.Failed(ThreatArea.CALLS, security.message ?: "Call log permission denied"))
            return 0
        } ?: return 0

        var examined = 0
        val seen = HashSet<String>()
        cursor.use {
            val numberColumn = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameColumn = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            while (it.moveToNext() && examined < MAX_ROWS) {
                coroutineContext.ensureActive()
                val number = it.getString(numberColumn).orEmpty()
                if (number.isBlank() || !seen.add(number)) continue
                examined++

                val matches = NumberThreatRules.analyze(number)
                val finding = ThreatFinding.from(
                    id = "threat:call:$number",
                    area = ThreatArea.CALLS,
                    title = it.getString(nameColumn).orEmpty().ifBlank { number },
                    subtitle = number,
                    evidence = number,
                    openTarget = OpenTarget.Dialer(number),
                    matches = matches,
                ) ?: continue
                emit(ThreatEvent.Found(finding))
            }
        }
        return examined
    }

    private suspend fun FlowCollector<ThreatEvent>.scanContacts(): Int {
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )
        } catch (security: SecurityException) {
            emit(ThreatEvent.Failed(ThreatArea.CONTACTS, security.message ?: "Contacts permission denied"))
            return 0
        } ?: return 0

        var examined = 0
        val seen = HashSet<String>()
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext() && examined < MAX_ROWS) {
                coroutineContext.ensureActive()
                val number = it.getString(numberColumn).orEmpty()
                if (number.isBlank() || !seen.add(number)) continue
                examined++

                val matches = NumberThreatRules.analyze(number)
                val finding = ThreatFinding.from(
                    id = "threat:contact:$number",
                    area = ThreatArea.CONTACTS,
                    title = it.getString(nameColumn).orEmpty().ifBlank { number },
                    subtitle = number,
                    evidence = number,
                    openTarget = OpenTarget.Contact(it.getLong(idColumn)),
                    matches = matches,
                    deleteRef = it.getLong(idColumn).toString(),
                ) ?: continue
                emit(ThreatEvent.Found(finding))
            }
        }
        return examined
    }

    private companion object {
        const val MAX_ROWS = 5000
    }
}
