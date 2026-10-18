package com.nuvio.app.features.player

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateByAddingTimeInterval

internal actual object PlayerWallClock {
    actual fun snapshotForRemaining(remainingMs: Long): PlayerWallClockSnapshot {
        val now = NSDate()
        val end = now.dateByAddingTimeInterval(remainingMs.coerceAtLeast(0L) / 1000.0)
        val formatter = NSDateFormatter().apply {
            dateFormat = "h:mm a"
        }
        return PlayerWallClockSnapshot(
            currentTime = formatter.stringFromDate(now),
            endTime = formatter.stringFromDate(end),
        )
    }
}
