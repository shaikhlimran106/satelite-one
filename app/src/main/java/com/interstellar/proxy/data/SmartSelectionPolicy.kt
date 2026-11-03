package com.interstellar.proxy.data

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode

/**
 * Pure candidate-selection rules for smart mode.
 *
 * Primary rule: the lowest measured delay below [SmartConfig.realMaxMs].
 * Fallback: when the whole pool is slow, only leave the current node for a
 * measured candidate that is both meaningfully faster and not absurdly slow.
 * A dead current exit skips both guards — any measured node is better.
 */
object SmartSelectionPolicy {

    private const val MAX_DELAY_MS = 65_000
    private const val MIN_IMPROVEMENT_MS = 50
    private const val IMPROVEMENT_PERCENT = 20

    data class Candidate(val tag: String, val delayMs: Int)

    sealed interface Decision {
        data class Pick(val candidate: Candidate, val fallback: Boolean) : Decision
        data class KeepCurrent(val best: Candidate?) : Decision
        data object NoCandidate : Decision
    }

    fun choose(
        candidates: List<Candidate>,
        currentTag: String?,
        currentDelayMs: Int,
        currentDead: Boolean,
        config: SmartConfig,
    ): Decision {
        val valid = candidates.filter { it.delayMs in 1 until MAX_DELAY_MS }
        if (valid.isEmpty()) return Decision.NoCandidate

        val best = valid.minByOrNull { it.delayMs }!!
        val qualified = valid
            .filter { it.delayMs < config.realMaxMs }
            .minByOrNull { it.delayMs }
        if (qualified != null) return Decision.Pick(qualified, fallback = false)

        // No absolute winner. A dead/absent exit cannot get worse, so take
        // the best measured node even if it is still above the fallback cap.
        if (currentDead || currentTag == null || currentDelayMs <= 0) {
            return Decision.Pick(best, fallback = true)
        }

        val requiredImprovement = maxOf(
            MIN_IMPROVEMENT_MS,
            currentDelayMs * IMPROVEMENT_PERCENT / 100,
        )
        val improvement = currentDelayMs - best.delayMs
        val fallbackCeiling = config.realMaxMs * 2
        return if (best.delayMs <= fallbackCeiling && improvement >= requiredImprovement) {
            Decision.Pick(best, fallback = true)
        } else {
            Decision.KeepCurrent(best)
        }
    }
}

/** UDP-only protocols cannot answer a TCP connect; smart mode bypasses Ping for them. */
fun ProxyNode.isUdpOnlyProtocol(): Boolean =
    type in UDP_ONLY_TYPES || network.equals("quic", ignoreCase = true)

val UDP_ONLY_TYPES: Set<NodeType> = setOf(
    NodeType.HYSTERIA2,
    NodeType.TUIC,
    NodeType.WIREGUARD,
)
