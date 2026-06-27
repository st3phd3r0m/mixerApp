package com.example.mixerapp.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTimelinePlannerTest {

    private data class Clip(
        val name: String,
        val startOffsetMs: Long,
        val lengthMs: Long?
    )

    @Test
    fun plan_builds_silence_and_clips_for_two_items_with_gap() {
        val clips = listOf(
            Clip(name = "Celebrate_KICK.wav", startOffsetMs = 7579L, lengthMs = 12_000L),
            Clip(name = "Celebrate_KICK_fill.wav", startOffsetMs = 64_200L, lengthMs = 8_500L)
        )

        val planned = TrackTimelinePlanner.plan(
            clips = clips,
            startOffsetMs = { it.startOffsetMs },
            lengthMs = { it.lengthMs }
        )

        assertEquals(4, planned.size)

        val seg0 = planned[0] as TrackTimelinePlanner.Segment.Silence
        val seg1 = planned[1] as TrackTimelinePlanner.Segment.Clip
        val seg2 = planned[2] as TrackTimelinePlanner.Segment.Silence
        val seg3 = planned[3] as TrackTimelinePlanner.Segment.Clip

        assertEquals(7579L, seg0.durationMs)
        assertEquals("Celebrate_KICK.wav", seg1.value.name)
        assertEquals(44_621L, seg2.durationMs)
        assertEquals("Celebrate_KICK_fill.wav", seg3.value.name)
    }

    @Test
    fun plan_does_not_insert_silence_for_overlapping_items() {
        val clips = listOf(
            Clip(name = "A.wav", startOffsetMs = 1_000L, lengthMs = 10_000L),
            Clip(name = "B.wav", startOffsetMs = 5_000L, lengthMs = 2_000L)
        )

        val planned = TrackTimelinePlanner.plan(
            clips = clips,
            startOffsetMs = { it.startOffsetMs },
            lengthMs = { it.lengthMs }
        )

        assertEquals(3, planned.size)
        assertEquals(TrackTimelinePlanner.Segment.Silence(1_000L), planned[0])
        assertEquals("A.wav", (planned[1] as TrackTimelinePlanner.Segment.Clip).value.name)
        assertEquals("B.wav", (planned[2] as TrackTimelinePlanner.Segment.Clip).value.name)
    }
}

