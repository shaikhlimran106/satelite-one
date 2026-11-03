package com.interstellar.proxy.data

/**
 * Decides whether a single bad patrol sample is enough to start a
 * reselection round. Slow-but-alive exits need two consecutive bad samples,
 * so a one-off spike does not trigger a switch. A missing or dead exit is
 * always actionable immediately.
 */
object SmartPatrolGate {

    const val REQUIRED_BAD_PATROLS = 2

    sealed interface Decision {
        data object Healthy : Decision
        data object Confirm : Decision
        data object Reselect : Decision
    }

    fun decide(
        hasCurrent: Boolean,
        currentDead: Boolean,
        delayMs: Int,
        goodMs: Int,
        previousBadPatrols: Int,
    ): Decision {
        if (!hasCurrent || currentDead) return Decision.Reselect
        if (delayMs in 1..goodMs) return Decision.Healthy
        return if (previousBadPatrols + 1 >= REQUIRED_BAD_PATROLS) {
            Decision.Reselect
        } else {
            Decision.Confirm
        }
    }
}
