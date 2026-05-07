package com.example.mixerapp.data.reaper

import com.example.mixerapp.model.AudioMode

/** Donnees minimales extraites d'une piste Reaper compatibles avec le mixer. */
data class ReaperTrackData(
    val name: String?,
    val volume: Float?,
    val pan: Float?,
    val isMuted: Boolean?,
    val isSolo: Boolean?,
    val sourceFile: String?
)

data class ReaperMarkerData(
    val index: Int,
    val positionSec: Double,
    val name: String
)

data class ReaperItemData(
    val positionSec: Double?,
    val lengthSec: Double?,
    val sourceFile: String?
)

data class ReaperTrackProjectData(
    val name: String?,
    val volume: Float?,
    val pan: Float?,
    val isMuted: Boolean?,
    val isSolo: Boolean?,
    val items: List<ReaperItemData>
)

data class ReaperProjectData(
    val markers: List<ReaperMarkerData>,
    val tracks: List<ReaperTrackProjectData>
)

data class ReaperMarkerSlice(
    val index: Int,
    val name: String,
    val startSec: Double,
    val endSec: Double?
)

object ReaperProjectParser {

    fun parse(content: String): List<ReaperTrackData> {
        val project = parseProject(content)
        return project.tracks.map { track ->
            ReaperTrackData(
                name = track.name,
                volume = track.volume,
                pan = track.pan,
                isMuted = track.isMuted,
                isSolo = track.isSolo,
                sourceFile = track.items.firstNotNullOfOrNull { it.sourceFile }
            )
        }
    }

    fun parseProject(content: String): ReaperProjectData {
        val result = mutableListOf<ReaperTrackProjectData>()
        val markers = mutableListOf<ReaperMarkerData>()
        var current: MutableTrack? = null
        var currentItem: MutableItem? = null
        var trackDepth = 0
        var itemDepth = 0
        var sourceWaveDepth = 0

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (current == null) {
                parseMarker(line)?.let { markers += it }
                if (line.startsWith("<TRACK ")) {
                    current = MutableTrack()
                    trackDepth = 1
                }
                return@forEach
            }

            if (line == ">") {
                if (sourceWaveDepth > 0) {
                    sourceWaveDepth--
                } else if (itemDepth > 0) {
                    itemDepth--
                    if (itemDepth == 0) {
                        val trackRef = current ?: return@forEach
                        currentItem?.let { trackRef.items += it.toImmutable() }
                        currentItem = null
                    }
                }
                trackDepth--
                if (trackDepth == 0) {
                    if (itemDepth > 0) {
                        val trackRef = current ?: return@forEach
                        currentItem?.let { trackRef.items += it.toImmutable() }
                        currentItem = null
                        itemDepth = 0
                    }
                    result += current.toProjectData()
                    current = null
                }
                return@forEach
            }

            if (line.startsWith("<")) {
                trackDepth++
                if (line.startsWith("<ITEM")) {
                    itemDepth++
                    currentItem = MutableItem()
                }
                if (line.startsWith("<SOURCE WAVE")) {
                    sourceWaveDepth = 1
                }
                return@forEach
            }

            val track = current
            if (track.name == null && line.startsWith("NAME ")) {
                track.name = parseFieldValue(line, "NAME")
                return@forEach
            }
            if (track.volume == null && line.startsWith("VOLPAN ")) {
                val parts = line.split(" ").filter { it.isNotBlank() }
                track.volume = parts.getOrNull(1)?.toFloatOrNull()
                track.pan = parts.getOrNull(2)?.toFloatOrNull()
                return@forEach
            }
            if (track.isMuted == null && line.startsWith("MUTESOLO ")) {
                val parts = line.split(" ").filter { it.isNotBlank() }
                track.isMuted = parts.getOrNull(1)?.toIntOrNull()?.let { it != 0 }
                track.isSolo = parts.getOrNull(2)?.toIntOrNull()?.let { it != 0 }
                return@forEach
            }
            if (itemDepth > 0 && line.startsWith("POSITION ")) {
                currentItem?.positionSec = line.removePrefix("POSITION ").trim().toDoubleOrNull()
                return@forEach
            }
            if (itemDepth > 0 && line.startsWith("LENGTH ")) {
                currentItem?.lengthSec = line.removePrefix("LENGTH ").trim().toDoubleOrNull()
                return@forEach
            }
            if (sourceWaveDepth > 0 && line.startsWith("FILE ")) {
                val file = parseFieldValue(line, "FILE")
                currentItem?.sourceFile = file
            }
        }

        return ReaperProjectData(
            markers = markers.sortedBy { it.positionSec },
            tracks = result
        )
    }

    fun markerSlices(project: ReaperProjectData): List<ReaperMarkerSlice> {
        val sorted = project.markers.sortedBy { it.positionSec }
        return sorted.mapIndexed { index, marker ->
            val next = sorted.getOrNull(index + 1)
            val cleanName = marker.name.ifBlank { "Marker ${marker.index}" }
            ReaperMarkerSlice(
                index = marker.index,
                name = cleanName,
                startSec = marker.positionSec,
                endSec = next?.positionSec
            )
        }
    }

    fun tracksForMarker(project: ReaperProjectData, startSec: Double, endSec: Double?): List<ReaperTrackData> {
        return project.tracks.map { track ->
            val item = track.items.firstOrNull { itemInMarkerRange(it, startSec, endSec) }
            ReaperTrackData(
                name = track.name,
                volume = track.volume,
                pan = track.pan,
                isMuted = track.isMuted,
                isSolo = track.isSolo,
                sourceFile = item?.sourceFile ?: track.items.firstNotNullOfOrNull { it.sourceFile }
            )
        }
    }

    fun panToAudioMode(pan: Float?): AudioMode = when {
        pan == null -> AudioMode.STEREO
        pan <= -0.95f -> AudioMode.LEFT
        pan >= 0.95f -> AudioMode.RIGHT
        else -> AudioMode.STEREO
    }

    private fun parseFieldValue(line: String, field: String): String? {
        val quoted = parseQuotedValue(line)
        if (!quoted.isNullOrBlank()) return quoted

        // Fallback pour les exports RPP sans guillemets: ex "NAME Percussions"
        val raw = line.removePrefix("$field ").trim()
        return raw.takeIf { it.isNotBlank() }
    }

    private fun parseQuotedValue(line: String): String? {
        val firstQuote = line.indexOf('"')
        if (firstQuote < 0) return null
        val lastQuote = line.lastIndexOf('"')
        if (lastQuote <= firstQuote) return null
        return line.substring(firstQuote + 1, lastQuote)
    }

    private fun parseMarker(line: String): ReaperMarkerData? {
        if (!line.startsWith("MARKER ")) return null
        val parts = line.split(" ", limit = 4).filter { it.isNotBlank() }
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val position = parts.getOrNull(2)?.toDoubleOrNull() ?: return null
        val remainder = line.removePrefix("MARKER ")
            .removePrefix("$index")
            .trim()
            .removePrefix(parts[2])
            .trim()
        val name = parseLeadingValue(remainder)?.takeIf { it.isNotBlank() } ?: "Marker $index"
        return ReaperMarkerData(index = index, positionSec = position, name = name)
    }

    private fun parseLeadingValue(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith('"')) {
            val end = trimmed.indexOf('"', startIndex = 1)
            if (end > 1) return trimmed.substring(1, end)
        }
        return trimmed.substringBefore(' ')
    }

    private fun itemInMarkerRange(item: ReaperItemData, startSec: Double, endSec: Double?): Boolean {
        val position = item.positionSec ?: return false
        val epsilon = 0.0005
        if (position + epsilon < startSec) return false
        if (endSec == null) return true
        return position < endSec - epsilon
    }

    private data class MutableTrack(
        var name: String? = null,
        var volume: Float? = null,
        var pan: Float? = null,
        var isMuted: Boolean? = null,
        var isSolo: Boolean? = null,
        val items: MutableList<ReaperItemData> = mutableListOf(),
    ) {
        fun toProjectData(): ReaperTrackProjectData = ReaperTrackProjectData(
            name = name,
            volume = volume,
            pan = pan,
            isMuted = isMuted,
            isSolo = isSolo,
            items = items.toList()
        )
    }

    private data class MutableItem(
        var positionSec: Double? = null,
        var lengthSec: Double? = null,
        var sourceFile: String? = null,
    ) {
        fun toImmutable(): ReaperItemData = ReaperItemData(
            positionSec = positionSec,
            lengthSec = lengthSec,
            sourceFile = sourceFile
        )
    }
}

