package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionChainFormatterTest {
    @Test
    fun appendsRuntimeSelectedMemberAfterStrategyGroup() {
        assertEquals(
            "AI相关 → 美国02",
            ConnectionChainFormatter.format(listOf("AI相关"), "美国02"),
        )
    }

    @Test
    fun doesNotDuplicateRuntimeMemberAlreadyInChain() {
        assertEquals(
            "美国02 → AI相关",
            ConnectionChainFormatter.format(listOf("美国02", "AI相关"), "美国02"),
        )
    }

    @Test
    fun preservesLegacyChainWhenRuntimeMemberIsUnavailable() {
        assertEquals("bypass", ConnectionChainFormatter.format(listOf("bypass"), ""))
        assertEquals("美国02", ConnectionChainFormatter.format(listOf("美国02"), "美国02"))
    }
}
