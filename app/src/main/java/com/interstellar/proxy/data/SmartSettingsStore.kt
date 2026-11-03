package com.interstellar.proxy.data

/** Maps [SmartConfig] to the flat settings.properties keys. */
object SmartSettingsStore {

    fun load(): SmartConfig = SmartConfig(
        patrolIntervalSec = Settings.smartPatrolIntervalSec,
        firstPatrolDelaySec = Settings.smartFirstPatrolDelaySec,
        goodMs = Settings.smartGoodMs,
        pingMaxMs = Settings.smartPingMaxMs,
        realMaxMs = Settings.smartRealMaxMs,
        cooldownSec = Settings.smartCooldownSec,
        roundTimeoutSec = Settings.smartRoundTimeoutSec,
        minCandidatesWarn = Settings.smartMinCandidatesWarn,
        verifyAttempts = Settings.smartVerifyAttempts,
        testUrl = Settings.smartTestUrl,
        allowUdp = Settings.smartAllowUdp,
        backoffEnabled = Settings.smartBackoffEnabled,
    ).sanitized()

    fun save(config: SmartConfig) {
        val safe = config.sanitized()
        Settings.smartPatrolIntervalSec = safe.patrolIntervalSec
        Settings.smartFirstPatrolDelaySec = safe.firstPatrolDelaySec
        Settings.smartGoodMs = safe.goodMs
        Settings.smartPingMaxMs = safe.pingMaxMs
        Settings.smartRealMaxMs = safe.realMaxMs
        Settings.smartCooldownSec = safe.cooldownSec
        Settings.smartRoundTimeoutSec = safe.roundTimeoutSec
        Settings.smartMinCandidatesWarn = safe.minCandidatesWarn
        Settings.smartVerifyAttempts = safe.verifyAttempts
        Settings.smartTestUrl = safe.testUrl
        Settings.smartAllowUdp = safe.allowUdp
        Settings.smartBackoffEnabled = safe.backoffEnabled
    }
}
