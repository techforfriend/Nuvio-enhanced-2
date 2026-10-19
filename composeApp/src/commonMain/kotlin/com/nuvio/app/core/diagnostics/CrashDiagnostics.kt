package com.nuvio.app.core.diagnostics

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.StateFlow

data class LocalCrashReport(
    val id: String,
    val summary: String,
    val details: String,
    val contextSummary: String = "",
)

internal object RuntimeDiagnostics : SynchronizedObject() {
    private val recentLogs = ArrayDeque<String>()
    private var cloudStreamNativeRepositories: Int = 0

    fun updateCloudStreamNativeRepositories(count: Int) = synchronized(this) {
        cloudStreamNativeRepositories = count.coerceAtLeast(0)
    }

    fun recordLog(message: String) = synchronized(this) {
        val line = message.trim().takeIf { it.isNotEmpty() } ?: return@synchronized
        if (recentLogs.size >= 100) recentLogs.removeFirst()
        recentLogs.addLast(line)
    }

    fun snapshotText(): String = synchronized(this) {
        buildString {
            appendLine("Runtime state: OK")
            appendLine("CloudStream native repositories: $cloudStreamNativeRepositories")
            if (recentLogs.isNotEmpty()) {
                appendLine("Recent logs:")
                append(recentLogs.joinToString("\n"))
            }
        }
    }
}

internal fun String.sanitizeDiagnosticText(): String = this

internal fun localCrashReport(
    id: String,
    summary: String,
    details: String,
    contextSummary: String = "",
): LocalCrashReport = LocalCrashReport(
    id = id,
    summary = summary,
    details = details,
    contextSummary = contextSummary,
)

expect object CrashDiagnostics {
    val reportsSupported: Boolean
    val pendingReport: StateFlow<LocalCrashReport?>
    val lastReport: StateFlow<LocalCrashReport?>

    fun initialize(context: Any?)
    fun dismiss(reportId: String)
    fun currentReport(): String
}
