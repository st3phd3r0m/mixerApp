package com.example.mixerapp.model

data class LimiterConfiguration(
    val isEnabled: Boolean = false,
    val thresholdDb: Float = 0f,  // 0 to -60 dB
    val ceilingDb: Float = 0f,    // 0 to -20 dB
    val releaseMs: Float = 50f   // 1 to 1000 ms
)
