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
    val playbackError: String? = null
)

