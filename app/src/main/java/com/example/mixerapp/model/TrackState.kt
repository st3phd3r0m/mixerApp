package com.example.mixerapp.model

import android.net.Uri

data class TrackState(
    val id: Int,
    val name: String = "Track ${id + 1}",
    val volume: Float = 0.8f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val audioMode: AudioMode = AudioMode.STEREO,
    val uri: Uri? = null,
    val isLoaded: Boolean = false,
    val playbackError: String? = null,
    val startOffsetMs: Long = 0L,   // Offset de démarrage en ms par rapport au début du segment
    val loop: Boolean = false       // Boucle l'audio (LOOP=1 dans Reaper)
)

