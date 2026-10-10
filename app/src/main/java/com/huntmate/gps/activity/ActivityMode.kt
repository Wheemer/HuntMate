package com.huntmate.gps.activity

/** Product-local workspaces. This app contains Hunting and Fishing only. */
enum class ActivityMode(val title: String, val emoji: String, val tools: List<String>) {
    HUNTING("Hunting", "🦌", listOf("GPS / Map", "Hunting Zones", "Government Maps", "Public Land", "Regulations", "Waypoints", "Saved Hunts")),
    FISHING("Fishing", "🎣", listOf("GPS / Map", "Fishing Spots", "Boat Launches", "Waypoints", "Routes", "Fishing Trips"));

    override fun toString(): String = "$emoji  $title"
}
