package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext
import platform.Foundation.NSCondition
import platform.Foundation.NSQualityOfServiceUserInitiated
import platform.Foundation.NSThread
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext

private const val PLUGIN_THREAD_STACK_SIZE_BYTES = 8L * 1024L * 1024L

private const val PLUGIN_JS_MAX_STACK_SIZE_BYTES = 6L * 1024L * 1024L

private val pluginThreadCounter = AtomicInt(0)

@OptIn(ExperimentalCoroutinesApi::class)
internal val pluginDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PLUGINS)

internal suspend fun <T> withPluginThread(block: suspend () -> T): T {
    val dispatcher = DedicatedLargeStackThreadDispatcher(
        stackSizeBytes = PLUGIN_THREAD_STACK_SIZE_BYTES,
        name = "nuvio-plugin-${pluginThreadCounter.addAndGet(1)}",
    )
    try {
        return withContext(dispatcher) { block() }
    } finally {
        dispatcher.close()
    }
}

internal fun QuickJs.configurePluginRuntime() {
    maxStackSize = PLUGIN_JS_MAX_STACK_SIZE_BYTES
}

private class DedicatedLargeStackThreadDispatcher(
    stackSizeBytes: Long,
    name: String,
) : CoroutineDispatcher() {
    private val condition = NSCondition()
    private val queue = ArrayDeque<Runnable>()
    private var closed = false

    init {
        NSThread(block = { runLoop() }).apply {
            stackSize = stackSizeBytes.toULong()
            this.name = name
            qualityOfService = NSQualityOfServiceUserInitiated
            start()
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        condition.lock()
        val accepted = try {
            if (!closed) {
                queue.addLast(block)
                condition.signal()
                true
            } else {
                false
            }
        } finally {
            condition.unlock()
        }
        if (!accepted) Dispatchers.Default.dispatch(context, block)
    }

    fun close() {
        condition.lock()
        try {
            closed = true
            condition.signal()
        } finally {
            condition.unlock()
        }
    }

    private fun runLoop() {
        while (true) {
            condition.lock()
            val task = try {
                while (queue.isEmpty() && !closed) condition.wait()
                queue.removeFirstOrNull()
            } finally {
                condition.unlock()
            }
            if (task == null) return
            autoreleasepool { task.run() }
        }
    }
}
