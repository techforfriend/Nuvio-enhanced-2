package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import platform.Foundation.NSCondition
import platform.Foundation.NSQualityOfServiceUtility
import platform.Foundation.NSThread
import kotlin.coroutines.CoroutineContext

private const val PLUGIN_THREAD_STACK_SIZE_BYTES = 8L * 1024L * 1024L

private const val PLUGIN_JS_MAX_STACK_SIZE_BYTES = 6L * 1024L * 1024L

internal val pluginDispatcher: CoroutineDispatcher = LargeStackThreadDispatcher(
    threadCount = MAX_CONCURRENT_PLUGINS,
    stackSizeBytes = PLUGIN_THREAD_STACK_SIZE_BYTES,
    namePrefix = "nuvio-plugin-worker",
)

internal suspend fun <T> withPluginThread(block: suspend () -> T): T =
    withContext(pluginDispatcher) { block() }

internal fun QuickJs.configurePluginRuntime() {
    maxStackSize = PLUGIN_JS_MAX_STACK_SIZE_BYTES
}

private class LargeStackThreadDispatcher(
    threadCount: Int,
    stackSizeBytes: Long,
    namePrefix: String,
) : CoroutineDispatcher() {
    private val condition = NSCondition()
    private val queue = ArrayDeque<Runnable>()

    init {
        repeat(threadCount) { index ->
            NSThread(block = { runLoop() }).apply {
                stackSize = stackSizeBytes.toULong()
                name = "$namePrefix-$index"
                qualityOfService = NSQualityOfServiceUtility
                start()
            }
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        condition.lock()
        try {
            queue.addLast(block)
            condition.signal()
        } finally {
            condition.unlock()
        }
    }

    private fun runLoop() {
        while (true) {
            condition.lock()
            val task = try {
                while (queue.isEmpty()) condition.wait()
                queue.removeFirst()
            } finally {
                condition.unlock()
            }
            autoreleasepool { task.run() }
        }
    }
}
