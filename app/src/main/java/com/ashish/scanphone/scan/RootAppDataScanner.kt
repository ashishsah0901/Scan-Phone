package com.ashish.scanphone.scan

import com.ashish.scanphone.model.MatchKind
import com.ashish.scanphone.model.ScanEvent
import com.ashish.scanphone.model.ScanHit
import com.ashish.scanphone.model.ScanRequest
import com.ashish.scanphone.model.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import java.io.BufferedReader
import java.io.File
import kotlin.coroutines.coroutineContext

class RootAppDataScanner(private val cacheDir: File) : Scanner {

    override val source: ScanSource = ScanSource.APP_DATA

    override fun scan(request: ScanRequest): Flow<ScanEvent> = flow {
        val matcher = TextMatcher(request.query, request.options.caseSensitive, request.options.queryMode)
        if (!matcher.isUsable) {
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        if (!RootShell.isAvailable()) {
            emit(
                ScanEvent.Failed(
                    source,
                    "Root access unavailable — other apps' private data is sandboxed on this device",
                ),
            )
            emit(ScanEvent.Finished(source, 0))
            return@flow
        }

        var examined = scanPaths(matcher, 0)
        if (request.options.searchFileContents) {
            examined += scanContents(matcher, request.options.caseSensitive, examined)
        }
        if (request.options.readAppData) {
            examined += scanSharedPrefs(matcher, examined)
            examined += scanDatabases(matcher, examined)
        }

        emit(ScanEvent.Progress(source, examined, null))
        emit(ScanEvent.Finished(source, examined))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ScanEvent>.scanSharedPrefs(matcher: TextMatcher, offset: Int): Int {
        val files = ArrayList<String>()
        consumeRootCommand("find $ROOTS_ARG -path '*/shared_prefs/*.xml' -type f 2>/dev/null") { path ->
            if (files.size < MAX_PREF_FILES) files.add(path)
        }

        var examined = 0
        var found = 0
        for (path in files) {
            coroutineContext.ensureActive()
            examined++
            emit(ScanEvent.Progress(source, offset + examined, "prefs: ${owningPackage(path)}"))
            if (found >= MAX_HITS_PER_PASS) continue

            val xml = RootShell.readOutput("cat ${RootShell.quote(path)} 2>/dev/null") ?: continue
            val entries = PREF_ENTRY.findAll(xml).map {
                it.groupValues[2] to it.groupValues[3].ifEmpty { it.groupValues[4] }
            }
            for ((key, value) in entries) {
                if (found >= MAX_HITS_PER_PASS) break
                val keyIndex = matcher.indexIn(key)
                val valueIndex = matcher.indexIn(value)
                if (keyIndex < 0 && valueIndex < 0) continue

                found++
                val matched = if (valueIndex >= 0) value else key
                val matchedIndex = if (valueIndex >= 0) valueIndex else keyIndex
                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "appref:$path:$key",
                            source = source,
                            matchKind = MatchKind.APP_PREFERENCE,
                            title = "$key = ${value.take(PREF_VALUE_PREVIEW)}",
                            subtitle = "${owningPackage(path)} · ${path.substringAfterLast('/')}",
                            snippet = matcher.snippet(sanitize(matched), matchedIndex.coerceAtMost(matched.length)),
                            openTarget = null,
                            verifyRef = path,
                        ),
                    ),
                )
            }
        }
        return examined
    }

    private suspend fun FlowCollector<ScanEvent>.scanPaths(matcher: TextMatcher, offset: Int): Int {
        var examined = 0
        var found = 0
        consumeRootCommand("find $ROOTS_ARG -type f 2>/dev/null") { path ->
            examined++
            if ((offset + examined) % PROGRESS_INTERVAL == 0) {
                emit(ScanEvent.Progress(source, offset + examined, owningPackage(path)))
            }
            if (found < MAX_HITS_PER_PASS && matcher.matches(path)) {
                found++
                emit(
                    ScanEvent.Found(
                        ScanHit(
                            id = "appdata:path:$path",
                            source = source,
                            matchKind = MatchKind.APP_DATA_PATH,
                            title = path.substringAfterLast('/'),
                            subtitle = "${owningPackage(path)} · $path",
                            snippet = null,
                            openTarget = null,
                            verifyRef = path,
                        ),
                    ),
                )
            }
        }
        return examined
    }

    private suspend fun FlowCollector<ScanEvent>.scanContents(
        matcher: TextMatcher,
        caseSensitive: Boolean,
        offset: Int,
    ): Int {
        val flags = if (caseSensitive) "-rFsna" else "-rFsnai"
        val command = "grep $flags -- ${RootShell.quote(matcher.rawQuery)} $ROOTS_ARG 2>/dev/null"

        var examined = 0
        var found = 0
        consumeRootCommand(command) { line ->
            examined++
            if ((offset + examined) % PROGRESS_INTERVAL == 0) {
                emit(ScanEvent.Progress(source, offset + examined, null))
            }
            if (found >= MAX_HITS_PER_PASS) return@consumeRootCommand

            val pathEnd = line.indexOf(':')
            if (pathEnd <= 0) return@consumeRootCommand
            val lineEnd = line.indexOf(':', pathEnd + 1)
            if (lineEnd <= pathEnd) return@consumeRootCommand

            val path = line.substring(0, pathEnd)
            val lineNumber = line.substring(pathEnd + 1, lineEnd)
            val content = sanitize(line.substring(lineEnd + 1))
            val index = matcher.indexIn(content)

            found++
            emit(
                ScanEvent.Found(
                    ScanHit(
                        id = "appdata:content:$path:$lineNumber",
                        source = source,
                        matchKind = MatchKind.APP_DATA_CONTENT,
                        title = path.substringAfterLast('/'),
                        subtitle = "${owningPackage(path)} · line $lineNumber · $path",
                        snippet = matcher.snippet(content, index),
                        openTarget = null,
                        verifyRef = path,
                    ),
                ),
            )
        }
        return examined
    }

    private suspend fun FlowCollector<ScanEvent>.scanDatabases(matcher: TextMatcher, offset: Int): Int {
        val reader = RootDatabaseReader(cacheDir)
        val databases = ArrayList<String>()
        consumeRootCommand("find $ROOTS_ARG -type f -name '*.db' 2>/dev/null") { path ->
            if (databases.size < MAX_DATABASES) databases.add(path)
        }

        var examined = 0
        for (path in databases) {
            coroutineContext.ensureActive()
            examined++
            emit(ScanEvent.Progress(source, offset + examined, "db: ${owningPackage(path)}"))
            reader.read(path, matcher) { hit -> emit(ScanEvent.Found(hit)) }
        }
        return examined
    }

    private suspend fun consumeRootCommand(command: String, onLine: suspend (String) -> Unit) {
        val process = RootShell.start(command) ?: return
        val disposable = coroutineContext.job.invokeOnCompletion { process.destroy() }
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = readBoundedLine(reader) ?: break
                    if (line.isNotEmpty()) onLine(line)
                }
            }
            process.waitFor()
        } finally {
            disposable.dispose()
            process.destroy()
        }
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val builder = StringBuilder()
        var read = reader.read()
        if (read == -1) return null
        while (read != -1 && read != NEW_LINE) {
            if (builder.length < MAX_LINE_CHARS) builder.append(read.toChar())
            read = reader.read()
        }
        return builder.toString()
    }

    private fun owningPackage(path: String): String {
        for (root in DATA_ROOTS) {
            if (path.startsWith("$root/")) return path.removePrefix("$root/").substringBefore('/')
        }
        return path.substringBeforeLast('/').substringAfterLast('/')
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

    private companion object {
        val DATA_ROOTS = listOf("/data/data", "/data/user/10", "/data/media/0")
        val ROOTS_ARG = DATA_ROOTS.joinToString(" ") { RootShell.quote(it) }
        const val PROGRESS_INTERVAL = 500
        const val MAX_HITS_PER_PASS = 1000
        const val MAX_LINE_CHARS = 600
        const val MAX_DATABASES = 200
        const val MAX_PREF_FILES = 500
        const val PREF_VALUE_PREVIEW = 40
        const val NEW_LINE = '\n'.code
        val PREF_ENTRY = Regex(
            "<(string|int|long|float|boolean|set)\\s+name=\"([^\"]*)\"(?:\\s+value=\"([^\"]*)\")?\\s*/?>" +
                "(?:([^<]*)</\\1>)?",
        )
    }
}
