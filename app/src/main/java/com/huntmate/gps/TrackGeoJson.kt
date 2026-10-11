package com.huntmate.gps

import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Builds safe GeoJSON for a saved GPS trail.
 * A line requires at least two coordinates; shorter trails intentionally
 * become an empty FeatureCollection, never a null Feature.
 */
internal fun trackGeoJson(points: List<TrackPoint>): FeatureCollection {
    if (points.size < 2) return FeatureCollection.fromFeatures(arrayOf())

    val coordinates = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    val line = Feature.fromGeometry(LineString.fromLngLats(coordinates))
    return FeatureCollection.fromFeatures(arrayOf(line))
}
