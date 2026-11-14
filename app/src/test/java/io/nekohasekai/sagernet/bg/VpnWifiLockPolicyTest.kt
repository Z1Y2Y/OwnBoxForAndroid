package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnWifiLockPolicyTest {
    @Test
    fun preAndroid10KeepsLegacyHighPerformanceMode() {
        assertEquals(VpnWifiLockMode.HIGH_PERFORMANCE, vpnWifiLockModeForSdk(28))
    }

    @Test
    fun android10AndNewerUseForegroundLimitedLowLatencyMode() {
        assertEquals(VpnWifiLockMode.LOW_LATENCY, vpnWifiLockModeForSdk(29))
        assertEquals(VpnWifiLockMode.LOW_LATENCY, vpnWifiLockModeForSdk(35))
    }
}
