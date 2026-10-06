package com.nuvio.app.features.whatsnew

import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlin.time.Instant

internal enum class LastCheckedUnit {
    JUST_NOW,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

internal data class LastCheckedAge(
    val amount: Long,
    val unit: LastCheckedUnit,
)

internal fun calculateLastCheckedAge(
    fetchedAtMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): LastCheckedAge {
    val effectiveNowMillis = nowMillis.coerceAtLeast(fetchedAtMillis)
    val elapsedMillis = effectiveNowMillis - fetchedAtMillis

    val minuteMillis = 60_000L
    val hourMillis = 60L * minuteMillis
    val dayMillis = 24L * hourMillis

    if (elapsedMillis < minuteMillis) {
        return LastCheckedAge(amount = 0L, unit = LastCheckedUnit.JUST_NOW)
    }

    if (elapsedMillis < hourMillis) {
        return LastCheckedAge(
            amount = elapsedMillis / minuteMillis,
            unit = LastCheckedUnit.MINUTE,
        )
    }

    val start = Instant.fromEpochMilliseconds(fetchedAtMillis)
    val end = Instant.fromEpochMilliseconds(effectiveNowMillis)
    val calendarPeriod = start.periodUntil(end, timeZone)

    return when {
        calendarPeriod.years >= 1 -> LastCheckedAge(
            amount = calendarPeriod.years.toLong(),
            unit = LastCheckedUnit.YEAR,
        )
        calendarPeriod.months >= 1 -> LastCheckedAge(
            amount = calendarPeriod.months.toLong(),
            unit = LastCheckedUnit.MONTH,
        )
        calendarPeriod.days >= 7 -> LastCheckedAge(
            amount = (calendarPeriod.days / 7L),
            unit = LastCheckedUnit.WEEK,
        )
        calendarPeriod.days >= 1 -> LastCheckedAge(
            amount = calendarPeriod.days.toLong(),
            unit = LastCheckedUnit.DAY,
        )
        elapsedMillis >= dayMillis -> LastCheckedAge(
            amount = elapsedMillis / dayMillis,
            unit = LastCheckedUnit.DAY,
        )
        else -> LastCheckedAge(
            amount = elapsedMillis / hourMillis,
            unit = LastCheckedUnit.HOUR,
        )
    }
}