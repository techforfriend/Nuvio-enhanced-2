package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PartySyncTest {
    private fun playback(
        seq: Long = 1,
        paused: Boolean = false,
        positionMs: Long = 10_000,
        rate: Float = 1f,
        at: Long = 1_000_000,
        memberId: String = "a",
        waiting: Boolean = false,
    ) = PartyPlayback(
        seq = seq,
        memberId = memberId,
        paused = paused,
        positionMs = positionMs,
        rate = rate,
        at = at,
        waiting = waiting,
    )

    @Test
    fun playingTimelineAdvancesWithRate() {
        val state = playback(rate = 1.5f)
        assertEquals(10_000 + 3_000, PartySync.expectedPositionMs(state, nowMs = 1_002_000))
    }

    @Test
    fun pausedTimelineStaysPut() {
        val state = playback(paused = true)
        assertEquals(10_000, PartySync.expectedPositionMs(state, nowMs = 1_500_000))
    }

    @Test
    fun expectedPositionIsClampedToDuration() {
        val state = playback(positionMs = 99_000)
        assertEquals(100_000, PartySync.expectedPositionMs(state, nowMs = 1_010_000, durationMs = 100_000))
    }

    @Test
    fun clockSkewBeforeAnchorDoesNotRewind() {
        val state = playback()
        assertEquals(10_000, PartySync.expectedPositionMs(state, nowMs = 999_000))
    }

    @Test
    fun higherSequenceWins() {
        assertTrue(PartySync.isNewer(playback(seq = 3), playback(seq = 2)))
        assertFalse(PartySync.isNewer(playback(seq = 2), playback(seq = 3)))
        assertTrue(PartySync.isNewer(playback(seq = 1), null))
    }

    @Test
    fun sameSequenceTieBreaksOnMemberSoEveryoneAgrees() {
        val first = playback(seq = 4, memberId = "aaa")
        val second = playback(seq = 4, memberId = "bbb")
        assertTrue(PartySync.isNewer(second, first))
        assertFalse(PartySync.isNewer(first, second))
        assertFalse(PartySync.isNewer(first, first))
    }

    @Test
    fun smallDriftIsLeftAlone() {
        val result = PartySync.correction(10_200, 10_000, playback(), nudging = false)
        assertEquals(PartyCorrection.InSync, result)
    }

    @Test
    fun aheadOfTheRoomSlowsDown() {
        val result = PartySync.correction(10_600, 10_000, playback(), nudging = false)
        val nudge = assertIs<PartyCorrection.Nudge>(result)
        assertTrue(nudge.rate < 1f)
    }

    @Test
    fun behindTheRoomSpeedsUp() {
        val result = PartySync.correction(9_400, 10_000, playback(), nudging = false)
        val nudge = assertIs<PartyCorrection.Nudge>(result)
        assertTrue(nudge.rate > 1f)
    }

    @Test
    fun nudgingKeepsGoingUntilCloseEnough() {
        assertIs<PartyCorrection.Nudge>(PartySync.correction(10_150, 10_000, playback(), nudging = true))
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_050, 10_000, playback(), nudging = true))
    }

    @Test
    fun largeDriftSeeks() {
        val result = PartySync.correction(4_000, 10_000, playback(), nudging = false)
        assertEquals(PartyCorrection.Seek(10_000), result)
    }

    @Test
    fun pausedRoomOnlySeeksWhenNoticeablyOff() {
        val paused = playback(paused = true)
        assertEquals(PartyCorrection.InSync, PartySync.correction(10_300, 10_000, paused, nudging = false))
        assertEquals(PartyCorrection.Seek(10_000), PartySync.correction(12_000, 10_000, paused, nudging = false))
    }

    @Test
    fun hostCoordinatesWhenOnline() {
        val party = party(memberId = "b", hostMemberId = "a", online = listOf("a", "b"))
        assertEquals("a", PartySync.coordinatorId(party))
    }

    @Test
    fun oldestOnlineMemberCoordinatesWhenHostIsAway() {
        val party = party(memberId = "c", hostMemberId = "a", online = listOf("b", "c"))
        assertEquals("b", PartySync.coordinatorId(party))
    }

    @Test
    fun waitingIgnoresOtherTitlesAndSkippedMembers() {
        val media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie")
        val party = party(memberId = "a", hostMemberId = "a", online = emptyList()).copy(
            media = media,
            presence = mapOf(
                "a" to PartyPresence("a", PartyMemberStatus.Ready, media.key),
                "b" to PartyPresence("b", PartyMemberStatus.Buffering, media.key),
                "c" to PartyPresence("c", PartyMemberStatus.Loading, "movie|tt2||"),
                "d" to PartyPresence("d", PartyMemberStatus.Loading, media.key),
            ),
        )
        assertEquals(listOf("b", "d"), PartySync.waitingMemberIds(party, ignored = emptySet()))
        assertEquals(listOf("b"), PartySync.waitingMemberIds(party, ignored = setOf("d")))
    }

    @Test
    fun clockUsesTheFastestSample() {
        val time = TestTimeSource()
        val clock = PartyClock(timeSource = time, wallClockMs = { 0L })
        val slow = clock.mark()
        time += 400.milliseconds
        clock.addSample(serverMs = 5_000_000, sentAt = slow)
        assertEquals(5_000_200, clock.nowMs())

        val fast = clock.mark()
        time += 40.milliseconds
        clock.addSample(serverMs = 5_000_500, sentAt = fast)
        assertEquals(5_000_520, clock.nowMs())

        val slower = clock.mark()
        time += 900.milliseconds
        clock.addSample(serverMs = 9_999_999, sentAt = slower)
        assertEquals(5_001_420, clock.nowMs())
    }

    @Test
    fun clockFallsBackToWallTimeUntilSynced() {
        val clock = PartyClock(timeSource = TestTimeSource(), wallClockMs = { 42L })
        assertFalse(clock.isSynced)
        assertEquals(42L, clock.nowMs())
    }

    @Test
    fun codesAreNormalizedAndFormatted() {
        assertEquals("ABC234", normalizePartyCode(" abc-234 "))
        assertTrue(isValidPartyCode("abc 234"))
        assertFalse(isValidPartyCode("ABC10"))
        assertEquals("ABC 234", formatPartyCode("abc234"))
    }

    @Test
    fun mediaKeyIgnoresSourceDetails() {
        val first = PartyMedia(type = "series", metaId = "tt9", metaType = "series", season = 1, episode = 2, source = PartySource(infoHash = "x"))
        val second = first.copy(source = PartySource(infoHash = "y"), title = "Other")
        assertEquals(first.key, second.key)
        assertFalse(first.key == first.copy(episode = 3).key)
    }

    private fun party(memberId: String, hostMemberId: String, online: List<String>) = ActiveParty(
        id = "p",
        code = "ABC234",
        memberId = memberId,
        hostMemberId = hostMemberId,
        members = listOf("a", "b", "c").map { PartyMember(id = it, displayName = it.uppercase()) },
        presence = online.associateWith { PartyPresence(it, PartyMemberStatus.Ready) },
        media = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie"),
        maxMembers = 3,
        connected = true,
    )
}
