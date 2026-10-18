package com.nuvio.app.core.diagnostics

import kotlinx.coroutines.flow.StateFlow

data class LocalCrashReport(
    val id: String,
    val summary: String,
    val details: String,
    val contextSummary: String = "",
)

internal object RuntimeDiagnostics {
    fun snapshotText(): String = "Runtime state: OK"
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
