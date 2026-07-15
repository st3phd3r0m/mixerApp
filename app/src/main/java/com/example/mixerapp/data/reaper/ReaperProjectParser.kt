package com.example.mixerapp.data.reaper

import com.example.mixerapp.model.AudioMode
import com.example.mixerapp.model.LimiterConfiguration
import java.util.Locale

/** Donnees minimales extraites d'une piste Reaper compatibles avec le mixer. */
data class ReaperTrackData(
    val name: String?,
    val volume: Float?,
    val pan: Float?,
    val isMuted: Boolean?,
    val isSolo: Boolean?,
    val sourceFile: String?,
    val startOffsetSec: Double = 0.0  // Offset de démarrage par rapport au début du marker
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
    val tracks: List<ReaperTrackProjectData>,
    val masterVolume: Float? = null,
    val limiterConfig: LimiterConfiguration? = null
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
        var masterVolume: Float? = null
        var limiterConfig: LimiterConfiguration? = null
        var current: MutableTrack? = null
        var currentItem: MutableItem? = null
        var trackDepth = 0
        var itemDepth = 0
        var sourceWaveDepth = 0
        var limiterDepth = 0
        var tempLimiter = MutableLimiter()

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (current == null && limiterDepth == 0) {
                parseMarker(line)?.let { markers += it }
                if (line.startsWith("MASTER_VOLUME ")) {
                    masterVolume = parseMasterVolume(line)
                }
                if (line.startsWith("<TRACK ")) {
                    current = MutableTrack()
                    trackDepth = 1
                }
                if (line.startsWith("<MASTERLIMITER")) {
                    limiterDepth = 1
                    tempLimiter = MutableLimiter()
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
                } else if (limiterDepth > 0) {
                    limiterDepth--
                    if (limiterDepth == 0) {
                        limiterConfig = tempLimiter.toImmutable()
                    }
                }
                
                    if (trackDepth > 0) {
                        trackDepth--
                        if (trackDepth == 0) {
                            if (itemDepth > 0) {
                                val trackRef = current ?: return@forEach
                                currentItem?.let { trackRef.items += it.toImmutable() }
                                currentItem = null
                                itemDepth = 0
                            }
                            current?.let { result += it.toProjectData() }
                            current = null
                        }
                    }
                return@forEach
            }

            if (line.startsWith("<")) {
                if (limiterDepth > 0) {
                    limiterDepth++
                } else if (trackDepth > 0) {
                    trackDepth++
                    if (line.startsWith("<ITEM")) {
                        itemDepth++
                        currentItem = MutableItem()
                    }
                    if (line.startsWith("<SOURCE WAVE")) {
                        sourceWaveDepth = 1
                    }
                }
                return@forEach
            }

            if (limiterDepth == 1) {
                if (line.startsWith("ENABLED ")) tempLimiter.isEnabled = line.substringAfter(" ").toIntOrNull() == 1
                if (line.startsWith("THRESHOLD ")) tempLimiter.thresholdDb = line.substringAfter(" ").toFloatOrNull() ?: 0f
                if (line.startsWith("CEILING ")) tempLimiter.ceilingDb = line.substringAfter(" ").toFloatOrNull() ?: 0f
                if (line.startsWith("RELEASE ")) tempLimiter.releaseMs = line.substringAfter(" ").toFloatOrNull() ?: 50f
                return@forEach
            }

            val track = current
            if (track != null && trackDepth == 1) {
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
            tracks = result,
            masterVolume = masterVolume,
            limiterConfig = limiterConfig
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
            val startOffsetSec = if (item != null) {
                ((item.positionSec ?: startSec) - startSec).coerceAtLeast(0.0)
            } else {
                0.0
            }
            ReaperTrackData(
                name = track.name,
                volume = track.volume,
                pan = track.pan,
                isMuted = track.isMuted,
                isSolo = track.isSolo,
                sourceFile = item?.sourceFile ?: track.items.firstNotNullOfOrNull { it.sourceFile },
                startOffsetSec = startOffsetSec
            )
        }
    }

    fun panToAudioMode(pan: Float?): AudioMode = when {
        pan == null -> AudioMode.STEREO
        pan <= -0.95f -> AudioMode.LEFT
        pan >= 0.95f -> AudioMode.RIGHT
        else -> AudioMode.STEREO
    }

    fun audioModeToPan(mode: AudioMode): Float = when (mode) {
        AudioMode.LEFT -> -1.0f
        AudioMode.RIGHT -> 1.0f
        AudioMode.STEREO -> 0.0f
    }

    data class TrackUpdate(
        val volume: Float,
        val pan: Float,
        val isMuted: Boolean,
        val isSolo: Boolean
    )

    fun updateProjectSettings(
        content: String,
        updates: List<TrackUpdate>,
        masterVolume: Float? = null,
        limiterConfig: LimiterConfiguration? = null
    ): String {
        val lines = content.lines().toMutableList()
        var trackIndex = -1
        var depthInsideTrack = 0
        var limiterFound = false

        // Suppression de l'ancien bloc MASTERLIMITER s'il existe pour le réécrire proprement
        val iterator = lines.iterator()
        var inLimiter = false
        val newLines = mutableListOf<String>()
        while (iterator.hasNext()) {
            val line = iterator.next()
            val trimmed = line.trim()
            if (trimmed.startsWith("<MASTERLIMITER")) inLimiter = true
            if (!inLimiter) newLines.add(line)
            if (inLimiter && trimmed == ">") inLimiter = false
        }
        lines.clear()
        lines.addAll(newLines)

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()

            // Détection de début de bloc
            if (trimmed.startsWith("<")) {
                if (trimmed.startsWith("<TRACK")) {
                    trackIndex++
                    depthInsideTrack = 1
                } else if (depthInsideTrack > 0) {
                    depthInsideTrack++
                }
                continue
            }

            // Détection de fin de bloc
            if (trimmed == ">") {
                if (depthInsideTrack > 0) {
                    depthInsideTrack--
                }
                continue
            }

            // Mise à jour du MASTER_VOLUME au niveau global et insertion du MASTERLIMITER juste après
            if (trimmed.startsWith("MASTER_VOLUME ")) {
                val parts = trimmed.split(" ").filter { it.isNotBlank() }
                val v2 = parts.getOrNull(2) ?: "0"
                val v3 = parts.getOrNull(3) ?: "-1"
                val v4 = parts.getOrNull(4) ?: "-1"
                val v5 = parts.getOrNull(5) ?: "1"
                val indent = line.takeWhile { it.isWhitespace() }
                lines[i] = "${indent}MASTER_VOLUME %.14f $v2 $v3 $v4 $v5".format(Locale.US, masterVolume ?: parseMasterVolume(trimmed) ?: 1.0f)
                
                if (limiterConfig != null && !limiterFound) {
                    val limiterBlock = StringBuilder()
                    limiterBlock.append("${indent}<MASTERLIMITER\n")
                    limiterBlock.append("${indent}  ENABLED ${if (limiterConfig.isEnabled) 1 else 0}\n")
                    limiterBlock.append("${indent}  THRESHOLD %.2f\n".format(Locale.US, limiterConfig.thresholdDb))
                    limiterBlock.append("${indent}  CEILING %.2f\n".format(Locale.US, limiterConfig.ceilingDb))
                    limiterBlock.append("${indent}  RELEASE %.1f\n".format(Locale.US, limiterConfig.releaseMs))
                    limiterBlock.append("${indent}>")
                    lines[i] = lines[i] + "\n" + limiterBlock.toString()
                    limiterFound = true
                }
                continue
            }

            // Modification uniquement si on est au premier niveau d'une piste
            if (depthInsideTrack == 1) {
                val update = updates.getOrNull(trackIndex) ?: continue
                val indent = line.takeWhile { it.isWhitespace() }

                if (trimmed.startsWith("VOLPAN ")) {
                    val parts = trimmed.split(" ").filter { it.isNotBlank() }
                    val v3 = parts.getOrNull(3) ?: "-1"
                    val v4 = parts.getOrNull(4) ?: "-1"
                    val v5 = parts.getOrNull(5) ?: "1"
                    // Utilisation d'une haute précision pour Reaper
                    lines[i] = "${indent}VOLPAN %.14f %.14f $v3 $v4 $v5".format(Locale.US, update.volume, update.pan)
                } else if (trimmed.startsWith("MUTESOLO ")) {
                    val mute = if (update.isMuted) 1 else 0
                    val solo = if (update.isSolo) 1 else 0
                    val parts = trimmed.split(" ").filter { it.isNotBlank() }
                    val extra = parts.getOrNull(3) ?: "0"
                    lines[i] = "${indent}MUTESOLO $mute $solo $extra"
                }
            }
        }

        // Si MASTER_VOLUME n'a pas été trouvé, on ajoute le bloc à la fin (peu probable dans un RPP valide)
        if (limiterConfig != null && !limiterFound) {
            lines.add("<MASTERLIMITER")
            lines.add("  ENABLED ${if (limiterConfig.isEnabled) 1 else 0}")
            lines.add("  THRESHOLD %.2f".format(Locale.US, limiterConfig.thresholdDb))
            lines.add("  CEILING %.2f".format(Locale.US, limiterConfig.ceilingDb))
            lines.add("  RELEASE %.1f".format(Locale.US, limiterConfig.releaseMs))
            lines.add(">")
        }

        return lines.joinToString("\n")
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

    private fun parseMasterVolume(line: String): Float? {
        if (!line.startsWith("MASTER_VOLUME ")) return null
        val parts = line.split(" ").filter { it.isNotBlank() }
        return parts.getOrNull(1)?.toFloatOrNull()
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
            sourceFile = sourceFile,
        )
    }

    private data class MutableLimiter(
        var isEnabled: Boolean = false,
        var thresholdDb: Float = 0f,
        var ceilingDb: Float = 0f,
        var releaseMs: Float = 50f
    ) {
        fun toImmutable(): LimiterConfiguration = LimiterConfiguration(
            isEnabled = isEnabled,
            thresholdDb = thresholdDb,
            ceilingDb = ceilingDb,
            releaseMs = releaseMs
        )
    }
}
