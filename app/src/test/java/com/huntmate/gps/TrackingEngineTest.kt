package com.huntmate.gps

import com.huntmate.gps.activity.ActivityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackingEngineTest {
    private class MemoryStore : TrackStore {
        private val data = mutableMapOf<ActivityMode, List<TrackPoint>>()
        private var session: Pair<ActivityMode, TrackingState>? = null
        override fun save(mode: ActivityMode, points: List<TrackPoint>) { data[mode] = points.toList() }
        override fun load(mode: ActivityMode): List<TrackPoint> = data[mode].orEmpty()
        override fun clear(mode: ActivityMode) { data.remove(mode) }
        override fun saveSession(mode: ActivityMode, state: TrackingState) { session = mode to state }
        override fun loadSession(): Pair<ActivityMode, TrackingState>? = session
        override fun clearSession() { session = null }
    }

    @Test fun activityModes_are_separate() {
        val store = MemoryStore()
        val engine = TrackingEngine(store)
        engine.selectMode(ActivityMode.HIKING)
        assertEquals(ActivityMode.HIKING, engine.currentMode())
        assertEquals(0, engine.snapshot.value.points.size)
        engine.selectMode(ActivityMode.FISHING)
        assertEquals(ActivityMode.FISHING, engine.currentMode())
        assertEquals(0, engine.snapshot.value.points.size)
    }

    @Test fun cannot_switch_while_tracking() {
        val engine = TrackingEngine(MemoryStore())
        engine.start()
        assertThrows(IllegalArgumentException::class.java) { engine.selectMode(ActivityMode.FISHING) }
    }

    @Test fun paused_track_can_resume_without_being_cleared() {
        val store = MemoryStore()
        val engine = TrackingEngine(store)
        engine.start()
        engine.pause()
        assertEquals(TrackingState.PAUSED, engine.snapshot.value.state)
        engine.start()
        assertEquals(TrackingState.TRACKING, engine.snapshot.value.state)
    }

    @Test fun session_state_remembers_activity_without_sharing_tracks() {
        val store = MemoryStore()
        val engine = TrackingEngine(store)
        engine.selectMode(ActivityMode.HIKING)
        engine.start()
        engine.pause()

        val restored = TrackingEngine(store)
        assertEquals(ActivityMode.HIKING, restored.currentMode())
        assertEquals(TrackingState.PAUSED, restored.snapshot.value.state)
    }
}
