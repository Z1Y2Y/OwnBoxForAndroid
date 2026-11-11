package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficUpdaterTest {

    @Test
    fun samplesProxyDirectAndBypassTagsForBothDirections() {
        val counters = mutableMapOf(
            "proxy/uplink" to 1000L,
            "proxy/downlink" to 2000L,
            "direct/uplink" to 300L,
            "direct/downlink" to 500L,
            "bypass/uplink" to 70L,
            "bypass/downlink" to 90L,
        )
        val calls = mutableMapOf<String, Int>()
        val lastUpdate = System.currentTimeMillis() - 1000L
        val items = listOf("proxy", "direct", "bypass").map { tag ->
            TrafficUpdater.TrafficLooperData(tag = tag, lastUpdate = lastUpdate)
        }
        val updater = TrafficUpdater(
            queryStats = { tag, direction ->
                val key = "$tag/$direction"
                calls[key] = (calls[key] ?: 0) + 1
                counters.remove(key) ?: 0L
            },
            items = items,
        )

        updater.updateAll()

        assertEquals(6, calls.size)
        assertTrue(calls.values.all { it == 1 })
        assertTrue(items.all { it.hasTrafficDelta })
        assertTrue(items.all { it.txRate > 0L && it.rxRate > 0L })
    }
}
