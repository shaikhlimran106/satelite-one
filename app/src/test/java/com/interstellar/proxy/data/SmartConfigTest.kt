package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartConfigTest {

    @Test
    fun `sanitize clamps every numeric field and enforces real max below good`() {
        val safe = SmartConfig(
            patrolIntervalSec = 1,
            firstPatrolDelaySec = 999,
            goodMs = 50,
            pingMaxMs = 1,
            realMaxMs = 9_000,
            cooldownSec = 1,
            roundTimeoutSec = 1,
            minCandidatesWarn = 0,
            verifyAttempts = 0,
            testUrl = "http://insecure.example",
        ).sanitized()

        assertEquals(SmartConfig.PATROL_MIN_SEC, safe.patrolIntervalSec)
        assertEquals(SmartConfig.FIRST_DELAY_MAX_SEC, safe.firstPatrolDelaySec)
        assertEquals(SmartConfig.GOOD_MIN_MS, safe.goodMs)
        assertEquals(SmartConfig.PING_MIN_MS, safe.pingMaxMs)
        assertEquals(SmartConfig.GOOD_MIN_MS, safe.realMaxMs)
        assertEquals(SmartConfig.COOLDOWN_MIN_SEC, safe.cooldownSec)
        assertEquals(SmartConfig.ROUND_MIN_SEC, safe.roundTimeoutSec)
        assertEquals(SmartConfig.MIN_CANDIDATES_MIN, safe.minCandidatesWarn)
        assertEquals(SmartConfig.VERIFY_MIN, safe.verifyAttempts)
        assertEquals(SmartConfig.DEFAULT_TEST_URL, safe.testUrl)
    }

    @Test
    fun `real max is capped at the healthy threshold`() {
        val safe = SmartConfig(goodMs = 200, realMaxMs = 900).sanitized()
        assertEquals(200, safe.realMaxMs)
    }

    @Test
    fun `valid https url and defaults survive sanitize`() {
        val custom = SmartConfig(testUrl = "https://example.com/204").sanitized()
        assertEquals("https://example.com/204", custom.testUrl)
        assertTrue(SmartConfig().isDefault())
        assertFalse(custom.isDefault())
    }
}
