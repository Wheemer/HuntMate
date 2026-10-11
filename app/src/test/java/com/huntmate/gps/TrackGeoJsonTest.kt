package com.huntmate.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString

class TrackGeoJsonTest {
    private fun point(lat: Double, lon: Double) = TrackPoint(
        latitude = lat,
        longitude = lon,
        accuracyMeters = 5f,
        speedMetersPerSecond = 0f,
        bearingDegrees = 0f,
        timestampMillis = 1L
    )

    @Test fun emptyTrackProducesEmptyFeatureCollection() {
        val collection = trackGeoJson(emptyList())
        assertEquals(0, collection.features()!!.size)
    }

    @Test fun singlePointTrackProducesEmptyFeatureCollection() {
        val collection = trackGeoJson(listOf(point(47.56, -52.71)))
        assertEquals(0, collection.features()!!.size)
    }

    @Test fun twoPointTrackProducesLineFeature() {
        val collection = trackGeoJson(listOf(point(47.56, -52.71), point(47.57, -52.72)))
        assertEquals(1, collection.features()!!.size)
        assertTrue(collection.features()!![0].geometry() is LineString)
    }

    @Test fun longerTrackProducesOneLineFeature() {
        val collection = trackGeoJson(listOf(
            point(47.56, -52.71),
            point(47.57, -52.72),
            point(47.58, -52.73)
        ))
        assertEquals(1, collection.features()!!.size)
        assertTrue(collection.features()!![0].geometry() is LineString)
    }
}
