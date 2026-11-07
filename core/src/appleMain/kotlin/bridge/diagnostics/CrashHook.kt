package bridge.diagnostics

import kotlin.experimental.ExperimentalNativeApi

/**
 * Swift can't reach Kotlin/Native's unhandled-exception hook; the Mac app calls this once at launch so an exception
 * escaping core lands in its local crash log ([write] gets the stack trace) before the process terminates.
 */
@OptIn(ExperimentalNativeApi::class)
fun installCrashHook(write: (String) -> Unit) {
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { e ->
        runCatching { write(e.stackTraceToString()) }
        previous?.invoke(e)
    }
}
