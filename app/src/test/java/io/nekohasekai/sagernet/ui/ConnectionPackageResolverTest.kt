package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPackageResolverTest {
    @Test
    fun recognizesCurrentAppFromAnnotatedProcessPathWithoutUidPackageLookup() {
        assertTrue(
            ConnectionPackageResolver.matchesPackage(
                hints = listOf("/data/user/0/com.ownbox.fork (android.uid.system)"),
                packageName = "com.ownbox.fork",
            ),
        )
    }

    @Test
    fun matchesUidOnlyWhenItUniquelyBelongsToCurrentApp() {
        assertTrue(
            ConnectionPackageResolver.matchesUnambiguousUid(
                connectionUid = 10123,
                applicationUid = 10123,
                packageName = "com.ownbox.fork",
                packagesForUid = listOf("com.ownbox.fork"),
            ),
        )
        assertFalse(
            ConnectionPackageResolver.matchesUnambiguousUid(
                connectionUid = 10123,
                applicationUid = 10123,
                packageName = "com.ownbox.fork",
                packagesForUid = listOf("com.ownbox.fork", "com.example.shared"),
            ),
        )
        assertFalse(
            ConnectionPackageResolver.matchesUnambiguousUid(
                connectionUid = 10124,
                applicationUid = 10123,
                packageName = "com.ownbox.fork",
                packagesForUid = listOf("com.ownbox.fork"),
            ),
        )
    }

    @Test
    fun doesNotTreatPackageNamePrefixAsCurrentApp() {
        assertFalse(
            ConnectionPackageResolver.matchesPackage(
                hints = listOf("com.ownbox.forked"),
                packageName = "com.ownbox.fork",
            ),
        )
    }

    @Test
    fun matchesProcessPathBeforeSharedUidPackageOrder() {
        val packages = listOf(
            "com.google.android.gms.policy_sidecar_aps",
            "com.google.android.gms",
            "com.google.android.gms.location.history",
        )

        assertEquals(
            "com.google.android.gms",
            ConnectionPackageResolver.selectPackage(
                hints = listOf("com.google.android.gms (10123)", "com.google.android.gms.policy_sidecar_aps"),
                packagesForUid = packages,
            ),
        )
    }

    @Test
    fun mapsChildProcessToLongestOwningPackage() {
        assertEquals(
            "com.google.android.gms",
            ConnectionPackageResolver.selectPackage(
                hints = listOf("com.google.android.gms.unstable (10123)"),
                packagesForUid = listOf("com.google.android.gms", "com.google.android"),
            ),
        )
    }

    @Test
    fun stripsPathUidSuffixAndAndroidProcessSuffix() {
        assertEquals(
            "com.example.app",
            ConnectionPackageResolver.selectPackage(
                hints = listOf("/data/user/0/com.example.app:worker (10123)"),
                packagesForUid = listOf("com.example.app"),
            ),
        )
    }

    @Test
    fun doesNotGuessWhenSharedUidHasNoMatchingProcess() {
        assertNull(
            ConnectionPackageResolver.selectPackage(
                hints = listOf("unknown.process (10123)"),
                packagesForUid = listOf("com.example.first", "com.example.second"),
            ),
        )
    }

    @Test
    fun usesOnlyPackageWhenUidIsUnambiguous() {
        assertEquals(
            "com.example.app",
            ConnectionPackageResolver.selectPackage(
                hints = listOf("unknown.process (10123)"),
                packagesForUid = listOf("com.example.app"),
            ),
        )
    }

    @Test
    fun usesExplicitMetadataPackageWhenUidLookupIsUnavailable() {
        assertEquals(
            "com.example.app",
            ConnectionPackageResolver.selectPackage(
                hints = listOf("—"),
                packagesForUid = emptyList(),
                explicitPackageName = "com.example.app",
            ),
        )
    }

    @Test
    fun mapsAmbiguousCoreSystemUidToAndroidPackage() {
        assertEquals(
            "android",
            ConnectionPackageResolver.selectPackage(
                hints = listOf("unknown.system.process"),
                packagesForUid = listOf("android", "com.android.systemui"),
                connectionUid = 1000,
            ),
        )
    }
}
