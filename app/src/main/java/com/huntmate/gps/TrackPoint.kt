package com.huntmate.gps

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val timestampMillis: Long
)

enum class TrackingState { IDLE, TRACKING, PAUSED }

data class TrackingSnapshot(
    val state: TrackingState = TrackingState.IDLE,
    val point: TrackPoint? = null,
    val points: List<TrackPoint> = emptyList(),
    val distanceMeters: Double = 0.0
)
