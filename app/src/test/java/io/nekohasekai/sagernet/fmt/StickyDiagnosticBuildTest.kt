package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyDiagnosticBuildTest {
    @Test
    fun enablesStickyDiagnosticsOnlyForTheSeparateDiagnosticPackage() {
        assertTrue(isStickyDiagnosticBuild("com.ownbox.fork.diag"))
        assertFalse(isStickyDiagnosticBuild("com.ownbox.fork"))
        assertFalse(isStickyDiagnosticBuild("com.ownbox.fork.diag.debug"))
    }
}
