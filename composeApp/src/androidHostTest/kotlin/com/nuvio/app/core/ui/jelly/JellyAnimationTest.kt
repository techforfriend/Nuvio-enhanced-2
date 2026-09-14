package com.nuvio.app.core.ui.jelly

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyAnimationTest {
    @Test
    fun `frame loop preserves motion and sleeps between interactions`() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = JellyMotion(0, 4).apply { resize(320f, 64f, 4) }
        val reference = JellyMotion(0, 4).apply { resize(320f, 64f, 4) }
        val animation = launch(clock) { motion.animate() }
        var time = 0L

        suspend fun flush() {
            Snapshot.sendApplyNotifications()
            yield()
        }

        suspend fun frame(delta: Long = 16_666_667L) {
            time += delta
            clock.sendFrame(time)
            flush()
        }

        suspend fun settle(reference: JellyMotion? = null) {
            repeat(240) {
                if (reference?.running == true) reference.advance(16_666_667 / 1_000_000_000.0)
                frame()
                if (reference != null) assertEquals(reference.frame, motion.frame)
            }
            assertFalse(motion.running)
            assertFalse(clock.hasAwaiters)
        }

        try {
            flush()
            assertFalse(clock.hasAwaiters)

            for (index in listOf(3, 1, 2, 0)) {
                motion.select(index)
                reference.select(index)
                flush()
                assertTrue(clock.hasAwaiters)
                frame()
                for (delta in listOf(8_333_333L, 16_666_667L, 33_333_333L, 120_000_000L)) {
                    frame(delta)
                    reference.advance(delta / 1_000_000_000.0)
                    assertEquals(reference.frame, motion.frame)
                }
                for (target in listOf((index + 1) % 4, index)) {
                    motion.select(target)
                    reference.select(target)
                    frame()
                    reference.advance(16_666_667 / 1_000_000_000.0)
                    assertEquals(reference.frame, motion.frame)
                }
                settle(reference)
                assertEquals(index.toFloat(), motion.frame.position, 0.0001f)
                val restingFrame = motion.frame
                frame(5_000_000_000L)
                assertEquals(restingFrame, motion.frame)
            }

            motion.begin(277f, 32f)
            flush()
            frame()
            repeat(6) { frame() }
            assertTrue(motion.frame.contentScale > 1f)
            motion.drag(-140.4f, -80f)
            repeat(6) { frame() }
            assertEquals(1, motion.finish())
            settle()
            assertEquals(1f, motion.frame.position, 0.0001f)

            motion.begin(40f, 32f)
            flush()
            frame()
            frame()
            motion.cancel(1)
            settle()
            assertEquals(1f, motion.frame.position, 0.0001f)

            motion.begin(40f, 32f)
            flush()
            assertTrue(clock.hasAwaiters)
        } finally {
            animation.cancelAndJoin()
        }
        assertFalse(clock.hasAwaiters)
    }
}
