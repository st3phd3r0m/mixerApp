package com.example.reapmixer.audio

/**
 * Build a linear timeline made of silence and clips from clip start offsets.
 */
object TrackTimelinePlanner {

    sealed interface Segment<out T> {
        data class Silence(val durationMs: Long) : Segment<Nothing>
        data class Clip<T>(val value: T) : Segment<T>
    }

    fun <T> plan(
        clips: List<T>,
        startOffsetMs: (T) -> Long,
        lengthMs: (T) -> Long?
    ): List<Segment<T>> {
        if (clips.isEmpty()) return emptyList()

        val sorted = clips.sortedBy { startOffsetMs(it).coerceAtLeast(0L) }
        val result = mutableListOf<Segment<T>>()
        var cursorMs = 0L

        sorted.forEach { clip ->
            val startMs = startOffsetMs(clip).coerceAtLeast(0L)
            val gapMs = (startMs - cursorMs).coerceAtLeast(0L)
            if (gapMs > 0L) {
                result += Segment.Silence(gapMs)
                cursorMs += gapMs
            }

            result += Segment.Clip(clip)

            val clipLengthMs = lengthMs(clip)?.coerceAtLeast(1L)
            cursorMs = if (clipLengthMs != null) {
                maxOf(cursorMs, startMs + clipLengthMs)
            } else {
                maxOf(cursorMs, startMs)
            }
        }

        return result
    }
}

