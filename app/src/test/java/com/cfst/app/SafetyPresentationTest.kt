package com.cfst.app

import com.cfst.app.speedtest.NetworkIdentityGuard
import com.cfst.app.model.SpeedTestResult
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SafetyPresentationTest {
    @Test fun bothDisplaySortsPreserveLossPriority() {
        val lossy = SpeedTestResult("lossy", 3, 1, 66.0, 1.0, 999.0, "N/A")
        val clean = SpeedTestResult("clean", 3, 3, 0.0, 100.0, 1.0, "N/A")
        assertEquals("clean", SpeedTestResult.rank(listOf(lossy, clean), false).first().ipAddress)
        assertEquals("clean", SpeedTestResult.rank(listOf(lossy, clean), true).first().ipAddress)
    }
    @Test fun lostCapturedNetworkStopsEvenBeforeSystemSnapshotUpdates() {
        val stops = mutableListOf<String>()
        val guard = NetworkIdentityGuard { stops.add(it) }
        guard.update("wifi:1"); guard.begin(); guard.lost("wifi:1")
        assertEquals(1, stops.size)
    }
    @Test fun networkChangeStopsCapturedRunOnce() {
        val stops = mutableListOf<String>()
        val guard = NetworkIdentityGuard { stops.add(it) }
        guard.update("wifi:1"); guard.begin(); guard.update("cell:2"); guard.update(null)
        assertEquals(1, stops.size)
        assertTrue(stops.first().contains("network"))
    }
    @Test fun sameIdentityDoesNotStopRunAndIdleChangeIsSafe() {
        val stops = mutableListOf<String>()
        val guard = NetworkIdentityGuard { stops.add(it) }
        guard.update("wifi:1"); guard.begin(); guard.update("wifi:1"); guard.end(); guard.update("cell:2")
        assertTrue(stops.isEmpty())
    }
    @Test fun offlineCannotBegin() { assertFalse(NetworkIdentityGuard {}.begin()) }
    @Test fun csvIsLocaleIndependentAndEscapesUntrustedFields() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val result = SpeedTestResult("192.0.2.1", 3, 2, 33.33, 1.25, 2.50, "a,b\"c")
            val csv = result.toCsvString()
            assertTrue(csv.contains("33.33,1.25,2.62")) // legacy 2.50 MiB/s expressed as decimal MB/s
            assertTrue(csv.contains("\"a,b\"\"c\""))
        } finally { Locale.setDefault(old) }
    }
}
