package com.example.mixerapp.data.reaper

import com.example.mixerapp.model.AudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaperProjectParserTest {

    @Test
    fun parse_extracts_supported_track_fields() {
        val content = """
            <REAPER_PROJECT 0.1 "7.71/linux-x86_64" 1778101042 0
              <TRACK {A}
                NAME "Percussions - stem"
                VOLPAN 1 0 -1 -1 1
                MUTESOLO 1 0 0
                <ITEM
                  NAME "item name ignored"
                  VOLPAN 0.5 1 1 -1
                  <SOURCE WAVE
                    FILE "hrp110_stems_Percussions.wav"
                  >
                >
              >
              <TRACK {B}
                NAME "Lead"
                VOLPAN 0.58746333409396 1 -1 -1 1
                MUTESOLO 0 1 0
                <ITEM
                  <SOURCE WAVE
                    FILE "lead.wav"
                  >
                >
              >
            >
        """.trimIndent()

        val tracks = ReaperProjectParser.parse(content)

        assertEquals(2, tracks.size)

        val first = tracks[0]
        assertEquals("Percussions - stem", first.name)
        assertEquals(1f, first.volume ?: -1f, 0.0001f)
        assertEquals(0f, first.pan ?: -99f, 0.0001f)
        assertTrue(first.isMuted == true)
        assertFalse(first.isSolo == true)
        assertEquals("hrp110_stems_Percussions.wav", first.sourceFile)

        val second = tracks[1]
        assertEquals("Lead", second.name)
        assertEquals(0.58746333f, second.volume ?: -1f, 0.0001f)
        assertEquals(1f, second.pan ?: -99f, 0.0001f)
        assertFalse(second.isMuted == true)
        assertTrue(second.isSolo == true)
        assertEquals("lead.wav", second.sourceFile)
    }

    @Test
    fun panToAudioMode_maps_extreme_pan_only() {
        assertEquals(AudioMode.LEFT, ReaperProjectParser.panToAudioMode(-1f))
        assertEquals(AudioMode.RIGHT, ReaperProjectParser.panToAudioMode(1f))
        assertEquals(AudioMode.STEREO, ReaperProjectParser.panToAudioMode(0f))
        assertEquals(AudioMode.STEREO, ReaperProjectParser.panToAudioMode(null))
    }

    @Test
    fun parse_supports_unquoted_name_and_file_values() {
        val content = """
            <REAPER_PROJECT 0.1
              <TRACK {A}
                NAME Percussions
                VOLPAN 1 0 -1 -1 1
                MUTESOLO 0 0 0
                <ITEM
                  <SOURCE WAVE
                    FILE Percussions.wav
                  >
                >
              >
            >
        """.trimIndent()

        val tracks = ReaperProjectParser.parse(content)

        assertEquals(1, tracks.size)
        assertEquals("Percussions", tracks.first().name)
        assertEquals("Percussions.wav", tracks.first().sourceFile)
    }

    @Test
    fun parseProject_extracts_markers_and_track_items() {
        val content = """
            <REAPER_PROJECT 0.1
              MARKER 1 3.2 tag1 0 0
              MARKER 2 28.8 tag2 0 0
              <TRACK {A}
                NAME Percussions
                VOLPAN 1 0 -1 -1 1
                MUTESOLO 0 0 0
                <ITEM
                  POSITION 3.2
                  LENGTH 10
                  <SOURCE WAVE
                    FILE Percussions-A.wav
                  >
                >
                <ITEM
                  POSITION 28.8
                  LENGTH 11
                  <SOURCE WAVE
                    FILE Percussions-B.wav
                  >
                >
              >
            >
        """.trimIndent()

        val project = ReaperProjectParser.parseProject(content)

        assertEquals(2, project.markers.size)
        assertEquals("tag1", project.markers[0].name)
        assertEquals(3.2, project.markers[0].positionSec, 0.0001)
        assertEquals(1, project.tracks.size)
        assertEquals(2, project.tracks[0].items.size)
        assertEquals("Percussions-A.wav", project.tracks[0].items[0].sourceFile)
        assertEquals("Percussions-B.wav", project.tracks[0].items[1].sourceFile)
    }

    @Test
    fun tracksForMarker_selects_items_in_marker_window() {
        val content = """
            <REAPER_PROJECT 0.1
              MARKER 1 3.2 tag1 0 0
              MARKER 2 28.8 tag2 0 0
              MARKER 3 58.3 tag3 0 0
              <TRACK {A}
                NAME guit01
                VOLPAN 1 0 -1 -1 1
                MUTESOLO 0 0 0
                <ITEM
                  POSITION 3.2
                  <SOURCE WAVE
                    FILE guit01-A.wav
                  >
                >
                <ITEM
                  POSITION 28.8
                  <SOURCE WAVE
                    FILE guit01-B.wav
                  >
                >
                <ITEM
                  POSITION 58.3
                  <SOURCE WAVE
                    FILE guit01-C.wav
                  >
                >
              >
            >
        """.trimIndent()

        val project = ReaperProjectParser.parseProject(content)
        val slices = ReaperProjectParser.markerSlices(project)

        val first = ReaperProjectParser.tracksForMarker(project, slices[0].startSec, slices[0].endSec)
        val second = ReaperProjectParser.tracksForMarker(project, slices[1].startSec, slices[1].endSec)
        val third = ReaperProjectParser.tracksForMarker(project, slices[2].startSec, slices[2].endSec)

        assertEquals("guit01-A.wav", first.first().sourceFile)
        assertEquals("guit01-B.wav", second.first().sourceFile)
        assertEquals("guit01-C.wav", third.first().sourceFile)
        assertNull(slices[2].endSec)
    }
}

