package com.interstellar.proxy.data

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSelectionPolicyTest {

    private val config = SmartConfig(goodMs = 300, realMaxMs = 300)

    private fun candidate(tag: String, delay: Int) =
        SmartSelectionPolicy.Candidate(tag, delay)

    @Test
    fun `picks the lowest qualified candidate`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("a", 280), candidate("b", 120), candidate("c", 250)),
            currentTag = "a",
            currentDelayMs = 600,
            currentDead = false,
            config = config,
        )
        val pick = decision as SmartSelectionPolicy.Decision.Pick
        assertEquals("b", pick.candidate.tag)
        assertFalse(pick.fallback)
    }

    @Test
    fun `slow pool fallback accepts a meaningfully faster candidate under the cap`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("a", 420), candidate("b", 350)),
            currentTag = "current",
            currentDelayMs = 600,
            currentDead = false,
            config = config,
        )
        val pick = decision as SmartSelectionPolicy.Decision.Pick
        assertEquals("b", pick.candidate.tag)
        assertTrue(pick.fallback)
    }

    @Test
    fun `slow pool keeps current when improvement is too small`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("a", 320)),
            currentTag = "current",
            currentDelayMs = 340,
            currentDead = false,
            config = config,
        )
        assertTrue(decision is SmartSelectionPolicy.Decision.KeepCurrent)
    }

    @Test
    fun `slow pool keeps current when the best candidate is above the cap`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("a", 700)),
            currentTag = "current",
            currentDelayMs = 2_000,
            currentDead = false,
            config = config,
        )
        assertTrue(decision is SmartSelectionPolicy.Decision.KeepCurrent)
    }

    @Test
    fun `dead current exit picks the best measured node even above the cap`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("a", 700), candidate("b", 900)),
            currentTag = "current",
            currentDelayMs = 0,
            currentDead = true,
            config = config,
        )
        val pick = decision as SmartSelectionPolicy.Decision.Pick
        assertEquals("a", pick.candidate.tag)
        assertTrue(pick.fallback)
    }

    @Test
    fun `keeps current when it is within the 50ms switch tolerance`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("current", 240), candidate("best", 200)),
            currentTag = "current",
            currentDelayMs = 600,
            currentDead = false,
            config = config,
        )
        val pick = decision as SmartSelectionPolicy.Decision.Pick
        assertEquals("current", pick.candidate.tag)
        assertFalse(pick.fallback)
    }

    @Test
    fun `switches when current is more than the tolerance behind`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("current", 300), candidate("best", 200)),
            currentTag = "current",
            currentDelayMs = 600,
            currentDead = false,
            config = config,
        )
        val pick = decision as SmartSelectionPolicy.Decision.Pick
        assertEquals("best", pick.candidate.tag)
    }

    @Test
    fun `dead current is excluded even if its stale kernel delay looks fast`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("current", 100), candidate("best", 200)),
            currentTag = "current",
            currentDelayMs = 0,
            currentDead = true,
            config = config,
        )
        val pick = decision as SmartSelectionPolicy.Decision.Pick
        assertEquals("best", pick.candidate.tag)
    }

    @Test
    fun `no valid candidates means no choice`() {
        val decision = SmartSelectionPolicy.choose(
            candidates = listOf(candidate("a", 0), candidate("b", 65_000)),
            currentTag = "current",
            currentDelayMs = 100,
            currentDead = false,
            config = config,
        )
        assertEquals(SmartSelectionPolicy.Decision.NoCandidate, decision)
    }

    @Test
    fun `udp-only detection covers request protocols and quic transport`() {
        assertTrue(node(NodeType.HYSTERIA2).isUdpOnlyProtocol())
        assertTrue(node(NodeType.TUIC).isUdpOnlyProtocol())
        assertTrue(node(NodeType.WIREGUARD).isUdpOnlyProtocol())
        assertTrue(node(NodeType.VLESS, network = "quic").isUdpOnlyProtocol())
        assertFalse(node(NodeType.VLESS).isUdpOnlyProtocol())
        assertFalse(node(NodeType.SHADOWSOCKS).isUdpOnlyProtocol())
    }

    private fun node(type: NodeType, network: String = "tcp") = ProxyNode(
        id = type.wire,
        name = type.wire,
        type = type,
        server = "example.com",
        port = 443,
        network = network,
    )
}
