package com.huntmate.gps

/**
 * Contract for authoritative government hunting-map data.
 *
 * This module intentionally ships with no invented boundaries, WMUs, seasons,
 * or regulations. A future adapter must supply data from an identified
 * government source and include source metadata for each dataset.
 */
data class GovernmentDatasetMetadata(
    val agency: String,
    val datasetName: String,
    val sourceUrl: String,
    val retrievedAtEpochMillis: Long,
    val jurisdiction: String
)

interface GovernmentHuntingLayer {
    val metadata: GovernmentDatasetMetadata
    suspend fun loadGeoJson(): String
}
