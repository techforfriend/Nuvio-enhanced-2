package com.nuvio.app.features.player

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual object PlayerWallClock {
    actual fun snapshotForRemaining(remainingMs: Long): PlayerWallClockSnapshot {
        val now = System.currentTimeMillis()
        val end = now + remainingMs.coerceAtLeast(0L)
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        return PlayerWallClockSnapshot(
            currentTime = format.format(Date(now)),
            endTime = format.format(Date(end)),
        )
    }
}
