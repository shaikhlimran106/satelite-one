package com.interstellar.proxy.data

/**
 * Runtime-tunable smart-switch parameters. Defaults mirror the values that
 * used to be compile-time constants in SmartSwitchEngine.
 *
 * Kept free of Android dependencies so the selection policy and validation
 * can be unit-tested on the JVM.
 */
data class SmartConfig(
    val patrolIntervalSec: Int = DEFAULT_PATROL_INTERVAL_SEC,
    val firstPatrolDelaySec: Int = DEFAULT_FIRST_PATROL_DELAY_SEC,
    val goodMs: Int = DEFAULT_GOOD_MS,
    val pingMaxMs: Int = DEFAULT_PING_MAX_MS,
    val realMaxMs: Int = DEFAULT_REAL_MAX_MS,
    val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
    val roundTimeoutSec: Int = DEFAULT_ROUND_TIMEOUT_SEC,
    val minCandidatesWarn: Int = DEFAULT_MIN_CANDIDATES_WARN,
    val verifyAttempts: Int = DEFAULT_VERIFY_ATTEMPTS,
    val testUrl: String = DEFAULT_TEST_URL,
    val allowUdp: Boolean = true,
    val backoffEnabled: Boolean = true,
) {
    /**
     * Clamps persisted values into the supported ranges. `REAL_MAX_MS` is
     * capped at `GOOD_MS`: accepting a candidate that the next patrol would
     * immediately classify as unhealthy causes switch flapping.
     */
    fun sanitized(): SmartConfig {
        val safeGood = goodMs.coerceIn(GOOD_MIN_MS, GOOD_MAX_MS)
        return copy(
            patrolIntervalSec = patrolIntervalSec.coerceIn(PATROL_MIN_SEC, PATROL_MAX_SEC),
            firstPatrolDelaySec = firstPatrolDelaySec.coerceIn(FIRST_DELAY_MIN_SEC, FIRST_DELAY_MAX_SEC),
            goodMs = safeGood,
            pingMaxMs = pingMaxMs.coerceIn(PING_MIN_MS, PING_MAX_MS_LIMIT),
            realMaxMs = realMaxMs.coerceIn(REAL_MIN_MS, safeGood),
            cooldownSec = cooldownSec.coerceIn(COOLDOWN_MIN_SEC, COOLDOWN_MAX_SEC),
            roundTimeoutSec = roundTimeoutSec.coerceIn(ROUND_MIN_SEC, ROUND_MAX_SEC),
            minCandidatesWarn = minCandidatesWarn.coerceIn(MIN_CANDIDATES_MIN, MIN_CANDIDATES_MAX),
            verifyAttempts = verifyAttempts.coerceIn(VERIFY_MIN, VERIFY_MAX),
            testUrl = testUrl.trim().takeIf { it.startsWith("https://") } ?: DEFAULT_TEST_URL,
        )
    }

    fun isDefault(): Boolean = sanitized() == SmartConfig()

    companion object {
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

        const val DEFAULT_PATROL_INTERVAL_SEC = 30
        const val DEFAULT_FIRST_PATROL_DELAY_SEC = 8
        const val DEFAULT_GOOD_MS = 300
        const val DEFAULT_PING_MAX_MS = 200
        const val DEFAULT_REAL_MAX_MS = 300
        const val DEFAULT_COOLDOWN_SEC = 20
        const val DEFAULT_ROUND_TIMEOUT_SEC = 40
        const val DEFAULT_MIN_CANDIDATES_WARN = 3
        const val DEFAULT_VERIFY_ATTEMPTS = 5

        const val PATROL_MIN_SEC = 10
        const val PATROL_MAX_SEC = 300
        const val FIRST_DELAY_MIN_SEC = 0
        const val FIRST_DELAY_MAX_SEC = 60
        const val GOOD_MIN_MS = 100
        const val GOOD_MAX_MS = 3_000
        const val PING_MIN_MS = 50
        const val PING_MAX_MS_LIMIT = 2_000
        const val REAL_MIN_MS = 100
        const val COOLDOWN_MIN_SEC = 5
        const val COOLDOWN_MAX_SEC = 300
        const val ROUND_MIN_SEC = 20
        const val ROUND_MAX_SEC = 300
        const val MIN_CANDIDATES_MIN = 1
        const val MIN_CANDIDATES_MAX = 20
        const val VERIFY_MIN = 1
        const val VERIFY_MAX = 10
    }
}
