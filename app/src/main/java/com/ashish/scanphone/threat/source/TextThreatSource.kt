package com.ashish.scanphone.threat.source

import android.content.Context
import android.provider.Telephony
import com.ashish.scanphone.model.OpenTarget
import com.ashish.scanphone.notifications.NotificationStore
import com.ashish.scanphone.threat.ThreatArea
import com.ashish.scanphone.threat.ThreatCheck
import com.ashish.scanphone.threat.ThreatEvent
import com.ashish.scanphone.threat.ThreatFinding
import com.ashish.scanphone.threat.ThreatScanOptions
import com.ashish.scanphone.threat.rules.TextThreatRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

class TextThreatSource(private val context: Context) : ThreatSource {

    override val check: ThreatCheck = ThreatCheck.TEXT

    override fun scan(options: ThreatScanOptions): Flow<ThreatEvent> = flow {
        val messagesExamined = scanSms()
        emit(ThreatEvent.Finished(ThreatArea.MESSAGES, messagesExamined))

        val notificationsExamined = scanNotifications()
        emit(ThreatEvent.Finished(ThreatArea.NOTIFICATIONS, notificationsExamined))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ThreatEvent>.scanSms(): Int {
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.THREAD_ID),
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )
        } catch (security: SecurityException) {
            emit(ThreatEvent.Failed(ThreatArea.MESSAGES, security.message ?: "SMS permission denied"))
            return 0
        } ?: return 0

        var examined = 0
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val threadColumn = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

            while (it.moveToNext() && examined < MAX_MESSAGES) {
                coroutineContext.ensureActive()
                examined++
                if (examined % PROGRESS_INTERVAL == 0) {
                    emit(ThreatEvent.Progress(ThreatArea.MESSAGES, examined, null))
                }

                val body = it.getString(bodyColumn).orEmpty()
                val address = it.getString(addressColumn).orEmpty()
                val matches = TextThreatRules.analyze(body, address)
                val finding = ThreatFinding.from(
                    id = "threat:sms:${it.getLong(idColumn)}",
                    area = ThreatArea.MESSAGES,
                    title = "SMS from ${address.ifBlank { "unknown" }}",
                    subtitle = body.take(SNIPPET),
                    evidence = body.take(EVIDENCE),
                    openTarget = OpenTarget.MessageThread(it.getLong(threadColumn)),
                    matches = matches,
                    deleteRef = "sms:${it.getLong(idColumn)}",
                ) ?: continue
                emit(ThreatEvent.Found(finding))
            }
        }
        return examined
    }

    private suspend fun FlowCollector<ThreatEvent>.scanNotifications(): Int {
        val records = NotificationStore.get(context).snapshot()
        var examined = 0
        records.forEach { record ->
            coroutineContext.ensureActive()
            examined++
            val text = listOf(record.title, record.text).filter { it.isNotBlank() }.joinToString(" ")
            val matches = TextThreatRules.analyze(text, record.packageName)
            val finding = ThreatFinding.from(
                id = "threat:notification:${record.key}:${record.postedAt}",
                area = ThreatArea.NOTIFICATIONS,
                title = "${record.appLabel}: ${record.title.ifBlank { record.text }}".take(SNIPPET),
                subtitle = record.text.take(SNIPPET),
                evidence = text.take(EVIDENCE),
                openTarget = OpenTarget.NotificationSettings(record.packageName),
                matches = matches,
            ) ?: return@forEach
            emit(ThreatEvent.Found(finding))
        }
        return examined
    }

    private companion object {
        const val MAX_MESSAGES = 5000
        const val PROGRESS_INTERVAL = 200
        const val SNIPPET = 80
        const val EVIDENCE = 240
    }
}
