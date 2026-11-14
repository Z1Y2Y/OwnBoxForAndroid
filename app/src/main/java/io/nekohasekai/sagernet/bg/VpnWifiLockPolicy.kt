package io.nekohasekai.sagernet.bg

internal enum class VpnWifiLockMode {
    LOW_LATENCY,
    HIGH_PERFORMANCE,
}

/** On API 29+, low-latency locks stop applying in the background/screen-off, unlike legacy high-performance locks. */
internal fun vpnWifiLockModeForSdk(sdkInt: Int): VpnWifiLockMode =
    if (sdkInt >= 29) VpnWifiLockMode.LOW_LATENCY else VpnWifiLockMode.HIGH_PERFORMANCE
