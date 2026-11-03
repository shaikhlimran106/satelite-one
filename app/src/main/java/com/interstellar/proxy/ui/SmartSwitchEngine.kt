package com.interstellar.proxy.ui

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.DirectPing
import com.interstellar.proxy.data.SmartConfig
import com.interstellar.proxy.data.SmartSelectionPolicy
import com.interstellar.proxy.data.SmartSelectionPolicy.Candidate
import com.interstellar.proxy.data.isUdpOnlyProtocol
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smart switch engine — automatic node selection driven by exit quality.
 *
 * Loop (self-gated to Started + smart mode):
 *  1. patrol: real-latency probe of the CURRENT exit (204 through the local
 *     inbound). Healthy ≤ config.goodMs → just refresh the cache entry.
 *  2. unhealthy → reselection round:
 *     a. kernels with a control API use a group url-test snapshot for every
 *        eligible node (UDP-only protocols included, no TCP Ping gate);
 *        API-less cores fall back to a bounded TCP Ping pre-filter.
 *     b. pick the lowest real latency < config.realMaxMs. If the whole pool
 *        is slower, only leave the current exit for a measured node that is
 *        meaningfully faster and below 2×realMax (dead current skips both).
 *     c. Xray (no API) verifies candidates by switching + probing, reserving
 *        a couple of slots for UDP nodes and reverting when nothing wins.
 *  3. guard rails: switch cooldown, round-duration alert, thin-candidate
 *     alert, no-candidate alert, optional exponential backoff — surfaced via
 *     [state] and AppLog ("smart").
 *
 * The delay cache lives in filesDir/smart_cache.json and doubles as the
 * "上次延迟记录" for both ordering and the node-page display.
 */
class SmartSwitchEngine(
    private val isActive: () -> Boolean,
    private val currentTag: () -> String?,
    private val socksPort: Int,
    private val useSocksProxy: () -> Boolean,
    /** Request kernel group url-test + wait for settle; null if unsupported. */
    private val requestKernelDelays: (suspend () -> Map<String, Int>?)?,
    /** Hot-switch the core onto [tag] (API or restart). */
    private val applySwitch: suspend (tag: String) -> Boolean,
    private val pool: () -> List<ProxyNode>,
    /** Core protocol matrix — Xray rejects TUIC/anytls/ssh/shadow-tls/quic. */
    private val isNodeEligible: (ProxyNode) -> Boolean = { true },
    private val config: () -> SmartConfig,
    private val onStateChanged: (SmartState) -> Unit,
) {
    data class SmartState(
        val active: Boolean = false,
        val phase: SmartPhase = SmartPhase.Idle,
        val currentDelayMs: Int = 0,
        /** The node smart mode currently rides on (what the card should show). */
        val currentTag: String? = null,
        val candidateCount: Int = 0,
        val lastSwitchTo: String? = null,
        val lastSwitchAt: Long = 0,
        val alert: SmartAlert? = null,
    )

    /** Engine phase — UI-localized via LocalizedNames.smartPhaseText. */
    sealed interface SmartPhase {
        data object Idle : SmartPhase
        data object Patrolling : SmartPhase
        data class PatrolError(val brief: String) : SmartPhase
        data class Healthy(val delayMs: Int) : SmartPhase
        data object PingScan : SmartPhase
        data object RealTest : SmartPhase
        data object Cooldown : SmartPhase
        data class Switched(val delayMs: Int) : SmartPhase
        data class Keep(val delayMs: Int) : SmartPhase
        data class SlowPoolKept(val delayMs: Int) : SmartPhase
        data object Failed : SmartPhase
    }

    /** Guard-rail outcome surfaced on the dashboard status line. */
    sealed interface SmartAlert {
        data object NoPool : SmartAlert
        data object NoCandidates : SmartAlert
        data object NoQualified : SmartAlert
        data class RoundTooLong(val seconds: Int) : SmartAlert
    }

    private enum class PatrolOutcome { Idle, Healthy, Switched, Kept, Cooldown, Failed }

    companion object {
        private const val PING_TIMEOUT_MS = 3_000
        private const val REAL_PROBE_TIMEOUT_S = 5L
        private const val MAX_DELAY_MS = 65_000
        private const val UDP_VERIFY_SLOTS = 2
        private const val MAX_BACKOFF_MS = 300_000L
        private const val CACHE_FILE = "smart_cache.json"
    }

    @Volatile
    private var started = false

    @Volatile
    private var generation = 0

    private val loopMutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var consecutiveBadRounds = 0

    /** tag → (delayMs, epochSec); persisted, sorted ascending by delay. */
    private val cache = linkedMapOf<String, Pair<Int, Long>>()

    private var state = SmartState()
        set(value) {
            field = value
            onStateChanged(value)
        }

    /** Per-probe client: the proxy TYPE can change when the user switches cores. */
    private fun probeClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(REAL_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(REAL_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .proxy(
            Proxy(
                if (useSocksProxy()) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", socksPort),
            ),
        )
        .build()

    fun start(immediate: Boolean = false) {
        if (started) return
        started = true
        generation++
        val gen = generation
        loadCache()
        consecutiveBadRounds = 0
        val cfg = config().sanitized()
        state = state.copy(active = false, phase = SmartPhase.Idle, alert = null, currentTag = currentTag())
        AppLog.log(
            "smart",
            "引擎已启动: 巡检 ${cfg.patrolIntervalSec}s · 正常阈值 ${cfg.goodMs}ms · " +
                "候选 ping<${cfg.pingMaxMs}ms 实测<${cfg.realMaxMs}ms · UDP ${if (cfg.allowUdp) "参与" else "排除"}",
        )
        val initialDelay = if (immediate) 0L else cfg.firstPatrolDelaySec * 1000L
        GlobalScope.launch(Dispatchers.IO) {
            loopMutex.withLock {
                if (gen != generation || !started) return@withLock
                // The previous loop has released the mutex (or never ran), so
                // any wake left by stop() is stale. Drain it only now — doing
                // this in start() could steal the wake a sleeping old loop
                // needs to notice the generation change.
                drainWake()
                if (initialDelay > 0) {
                    withTimeoutOrNull(initialDelay) { wake.receive() }
                }
                while (started && gen == generation) {
                    val outcome = try {
                        patrolOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.log(
                            "smart",
                            "巡检循环异常: ${e.message?.take(48) ?: e.javaClass.simpleName}",
                        )
                        state = state.copy(
                            phase = SmartPhase.PatrolError(
                                e.message?.take(24) ?: e.javaClass.simpleName,
                            ),
                        )
                        PatrolOutcome.Failed
                    }
                    if (!started || gen != generation) break
                    val cfgNow = config().sanitized()
                    val waitMs = nextDelayMs(cfgNow, outcome)
                    withTimeoutOrNull(waitMs) { wake.receive() }
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        generation++
        wake.trySend(Unit)
        AppLog.log("smart", "引擎已停止")
        state = state.copy(active = false, phase = SmartPhase.Idle, alert = null)
    }

    /** Save-triggered reconfiguration: no core restart, no network drop. */
    fun restart() {
        stop()
        start(immediate = true)
    }

    private fun drainWake() {
        while (wake.tryReceive().isSuccess) Unit
    }

    private fun nextDelayMs(cfg: SmartConfig, outcome: PatrolOutcome): Long {
        val base = cfg.patrolIntervalSec * 1000L
        when (outcome) {
            PatrolOutcome.Idle -> {
                consecutiveBadRounds = 0
                return base
            }

            PatrolOutcome.Healthy, PatrolOutcome.Switched -> consecutiveBadRounds = 0
            else -> consecutiveBadRounds = (consecutiveBadRounds + 1).coerceAtMost(8)
        }
        if (!cfg.backoffEnabled || consecutiveBadRounds == 0) return base
        val factor = 1L shl consecutiveBadRounds.coerceAtMost(4)
        return (base * factor).coerceAtMost(MAX_BACKOFF_MS)
    }

    // ---- patrol ----

    private suspend fun patrolOnce(): PatrolOutcome {
        if (!isActive()) {
            if (state.active) state = state.copy(active = false, phase = SmartPhase.Idle)
            return PatrolOutcome.Idle
        }
        val cfg = config().sanitized()
        state = state.copy(active = true, phase = SmartPhase.Patrolling, currentTag = currentTag(), alert = null)
        val tag = currentTag()
        val real = probeExit(cfg)
        state = state.copy(currentDelayMs = real)
        if (tag != null && real in 1..cfg.goodMs) {
            record(tag, real)
            state = state.copy(phase = SmartPhase.Healthy(real))
            AppLog.log("smart", "巡检正常: $tag · ${real}ms")
            return PatrolOutcome.Healthy
        }
        val currentDead = tag == null || real <= 0
        when {
            tag == null -> AppLog.log("smart", "巡检异常: 无当前节点 → 开始筛选备选")
            real <= 0 -> AppLog.log(
                "smart",
                "巡检异常: $tag · 出口不可用 → 开始筛选备选",
            )

            else -> AppLog.log(
                "smart",
                "巡检异常: $tag · 延迟 ${real}ms 超过阈值 ${cfg.goodMs}ms → 开始筛选备选",
            )
        }
        return reselect(real, currentDead, cfg)
    }

    // ---- reselection round ----

    private suspend fun reselect(
        currentDelay: Int,
        currentDead: Boolean,
        cfg: SmartConfig,
    ): PatrolOutcome {
        val roundStart = System.currentTimeMillis()
        val nodes = pool()
        if (nodes.isEmpty()) {
            state = state.copy(alert = SmartAlert.NoPool)
            AppLog.log("smart", "筛选中止: 无可用节点池")
            return PatrolOutcome.Failed
        }
        val tags = com.interstellar.proxy.data.config.ConfigBuilder.tagsFor(nodes)
        val nodeByTag = tags.zip(nodes).toMap()
        val eligibleTags = tags.filter { tag ->
            nodeByTag[tag]?.let { isNodeEligible(it) } == true
        }
        if (eligibleTags.isEmpty()) {
            state = state.copy(alert = SmartAlert.NoPool)
            AppLog.log("smart", "筛选中止: 当前内核没有可用协议节点")
            return PatrolOutcome.Failed
        }
        val udpEligibleTags = eligibleTags.filter { tag ->
            nodeByTag[tag]?.isUdpOnlyProtocol() == true
        }
        val udpTags = if (cfg.allowUdp) udpEligibleTags else emptyList()
        val nonUdpTags = eligibleTags.filterNot { it in udpEligibleTags }

        val kernelDelays = requestKernelDelays?.invoke()
        if (kernelDelays != null) {
            val measured = eligibleTags.mapNotNull { tag ->
                val delay = kernelDelays[tag]?.takeIf { it in 1 until MAX_DELAY_MS }
                    ?: return@mapNotNull null
                if (!cfg.allowUdp && tag in udpEligibleTags) return@mapNotNull null
                Candidate(tag, delay)
            }
            if (measured.isNotEmpty()) {
                state = state.copy(phase = SmartPhase.RealTest, candidateCount = measured.size)
                AppLog.log(
                    "smart",
                    "内核实测: ${measured.size} 个节点有延迟, " +
                        "达标 ${measured.count { it.delayMs < cfg.realMaxMs }} 个 (<${cfg.realMaxMs}ms)" +
                        udpSuffix(udpEligibleTags.size, cfg),
                )
                val outcome = decideAndApply(
                    measured = measured,
                    currentDelay = currentDelay,
                    currentDead = currentDead,
                    cfg = cfg,
                    verified = false,
                    originalTag = currentTag(),
                )
                return finishRound(roundStart, cfg, outcome)
            }
            AppLog.log("smart", "内核实测无有效延迟, 回退 Ping 扫描")
        }

        state = state.copy(phase = SmartPhase.PingScan)
        val targets = nodes.zip(tags).filter { (node, tag) ->
            tag in nonUdpTags && isNodeEligible(node)
        }
        val pings = pingPool(targets)
        val pingCandidates = nonUdpTags.filter { tag ->
            (pings[tag] ?: PING_TIMEOUT_MS) in 1 until cfg.pingMaxMs
        }
        val candidateTags = (pingCandidates + udpTags).distinct()
        state = state.copy(candidateCount = candidateTags.size)
        val fastestPing = pings.values.filter { it in 1 until PING_TIMEOUT_MS }.minOrNull()
        AppLog.log(
            "smart",
            "Ping 扫描完成: ${targets.size} 个 TCP 节点 → ${pingCandidates.size} 个候选 " +
                "(<${cfg.pingMaxMs}ms)" +
                (fastestPing?.let { ", 最快 ${it}ms" } ?: "") +
                udpSuffix(udpTags.size, cfg),
        )
        if (candidateTags.isEmpty()) {
            state = state.copy(alert = SmartAlert.NoCandidates)
            AppLog.log("smart", "筛选中止: 没有可实测的候选节点 (链路质量差或 UDP 已关闭?)")
            return PatrolOutcome.Failed
        }
        if (candidateTags.size < cfg.minCandidatesWarn) {
            AppLog.log("smart", "备选节点过少: 仅 ${candidateTags.size} 个")
        }

        state = state.copy(phase = SmartPhase.RealTest)
        val ordered = orderVerifyCandidates(pingCandidates, udpTags, cfg)
        val verified = mutableListOf<Candidate>()
        val original = currentTag()
        for (tag in ordered) {
            if (!started || !isActive()) {
                revert(original)
                return finishRound(roundStart, cfg, PatrolOutcome.Failed)
            }
            if (!applySwitch(tag)) continue
            state = state.copy(currentTag = tag)
            val real = probeExit(cfg)
            record(tag, real)
            AppLog.log("smart", "切换验证: $tag · ${if (real > 0) "${real}ms" else "不可用"}")
            if (real > 0) verified += Candidate(tag, real)
            if (real in 1 until cfg.realMaxMs) break
        }

        val outcome = decideAndApply(
            measured = verified,
            currentDelay = currentDelay,
            currentDead = currentDead,
            cfg = cfg,
            verified = true,
            originalTag = original,
        )
        return finishRound(roundStart, cfg, outcome)
    }

    private suspend fun decideAndApply(
        measured: List<Candidate>,
        currentDelay: Int,
        currentDead: Boolean,
        cfg: SmartConfig,
        verified: Boolean,
        originalTag: String?,
    ): PatrolOutcome {
        val original = originalTag
        val decision = SmartSelectionPolicy.choose(
            candidates = measured,
            currentTag = original,
            currentDelayMs = currentDelay,
            currentDead = currentDead,
            config = cfg,
        )
        return when (decision) {
            SmartSelectionPolicy.Decision.NoCandidate -> {
                if (verified) revert(original)
                if (currentDead) {
                    state = state.copy(alert = SmartAlert.NoQualified, phase = SmartPhase.Failed)
                    AppLog.log("smart", "本轮筛选结束: 当前节点不可用且没有可测量的备选")
                } else {
                    state = state.copy(alert = SmartAlert.NoCandidates, phase = SmartPhase.Failed)
                    AppLog.log("smart", "本轮筛选结束: 没有可测量的备选节点")
                }
                PatrolOutcome.Failed
            }

            is SmartSelectionPolicy.Decision.KeepCurrent -> {
                if (verified) revert(original)
                state = state.copy(phase = SmartPhase.SlowPoolKept(currentDelay.coerceAtLeast(0)))
                val best = decision.best?.let { ", 最优 ${it.delayMs}ms 未达改善要求" } ?: ""
                AppLog.log("smart", "池整体慢: 当前 ${currentDelay}ms$best → 保持")
                PatrolOutcome.Kept
            }

            is SmartSelectionPolicy.Decision.Pick -> {
                if (!started || !isActive()) {
                    if (verified) revert(original)
                    return PatrolOutcome.Failed
                }
                val candidate = decision.candidate
                val now = System.currentTimeMillis()
                if (!currentDead &&
                    state.lastSwitchAt > 0 &&
                    now - state.lastSwitchAt < cfg.cooldownSec * 1000L
                ) {
                    if (verified) revert(original)
                    state = state.copy(phase = SmartPhase.Cooldown)
                    AppLog.log(
                        "smart",
                        "冷却中 (距上次切换不足 ${cfg.cooldownSec}s), 暂不切换 → " +
                            "${candidate.tag} (${candidate.delayMs}ms)",
                    )
                    return PatrolOutcome.Cooldown
                }
                if (candidate.tag == currentTag()) {
                    record(candidate.tag, candidate.delayMs)
                    val switchedDuringVerify = verified && original != candidate.tag
                    state = state.copy(
                        phase = if (switchedDuringVerify) {
                            SmartPhase.Switched(candidate.delayMs)
                        } else {
                            SmartPhase.Keep(candidate.delayMs)
                        },
                        currentTag = candidate.tag,
                        currentDelayMs = candidate.delayMs,
                        lastSwitchTo = if (switchedDuringVerify) candidate.tag else state.lastSwitchTo,
                        lastSwitchAt = if (switchedDuringVerify) now else state.lastSwitchAt,
                    )
                    AppLog.log(
                        "smart",
                        if (switchedDuringVerify) {
                            "已切换: ${original ?: "(无)"} → ${candidate.tag} (${candidate.delayMs}ms)"
                        } else {
                            "保持当前节点: ${candidate.tag} (${candidate.delayMs}ms)"
                        },
                    )
                    if (switchedDuringVerify) PatrolOutcome.Switched else PatrolOutcome.Kept
                } else if (applySwitch(candidate.tag)) {
                    record(candidate.tag, candidate.delayMs)
                    state = state.copy(
                        phase = SmartPhase.Switched(candidate.delayMs),
                        currentTag = candidate.tag,
                        lastSwitchTo = candidate.tag,
                        lastSwitchAt = now,
                        currentDelayMs = candidate.delayMs,
                    )
                    val fallbackNote = if (decision.fallback) ", 兜底" else ""
                    AppLog.log(
                        "smart",
                        "已切换: ${original ?: "(无)"} → ${candidate.tag} " +
                            "(${candidate.delayMs}ms$fallbackNote)",
                    )
                    PatrolOutcome.Switched
                } else {
                    if (verified) revert(original)
                    AppLog.log("smart", "切换失败: ${candidate.tag} (内核 API 不可达或重启失败)")
                    PatrolOutcome.Failed
                }
            }
        }
    }

    private fun finishRound(
        roundStart: Long,
        cfg: SmartConfig,
        outcome: PatrolOutcome,
    ): PatrolOutcome {
        val elapsed = System.currentTimeMillis() - roundStart
        if (elapsed > cfg.roundTimeoutSec * 1000L && state.alert == null) {
            state = state.copy(alert = SmartAlert.RoundTooLong((elapsed / 1000).toInt()))
            AppLog.log("smart", "本轮筛选耗时过长 (${elapsed / 1000}s)")
        }
        saveCache()
        return outcome
    }

    private suspend fun revert(original: String?) {
        if (original == null || currentTag() == original) return
        if (applySwitch(original)) {
            state = state.copy(currentTag = original)
            AppLog.log("smart", "验证未通过, 回退: → $original")
        } else {
            AppLog.log("smart", "回退失败: $original")
        }
    }

    private fun udpSuffix(udpCount: Int, cfg: SmartConfig): String {
        if (udpCount == 0) return ""
        return if (cfg.allowUdp) ", UDP ${udpCount} 个绕过 Ping" else ", UDP ${udpCount} 个已排除"
    }

    // ---- probes ----

    /** Real end-to-end exit latency via the local inbound; -1 = failed. */
    private fun probeExit(cfg: SmartConfig): Int {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            probeClient().newCall(Request.Builder().url(cfg.testUrl).head().build()).execute().use {
                if (it.isSuccessful) (System.currentTimeMillis() - startedAt).toInt() else -1
            }
        }.getOrDefault(-1)
    }

    /** Direct TCP ping of every eligible TCP node (bounded concurrency). */
    private suspend fun pingPool(targets: List<Pair<ProxyNode, String>>): Map<String, Int> =
        coroutineScope {
            if (targets.isEmpty()) return@coroutineScope emptyMap<String, Int>()
            // the engine only runs while connected — a plain connect would
            // handshake with our own tun stack (~3-4ms) for every node
            DirectPing.warmup(1_500)
            val results = ConcurrentHashMap<String, Int>()
            val semaphore = Semaphore(16)
            val done = AtomicInteger()
            targets.forEach { (node, tag) ->
                launch {
                    semaphore.withPermit {
                        results[tag] = runCatching {
                            DirectPing.tcpConnect(node.server, node.port, PING_TIMEOUT_MS)
                                .coerceIn(1, PING_TIMEOUT_MS - 1)
                        }.getOrDefault(PING_TIMEOUT_MS)
                        done.incrementAndGet()
                    }
                }
            }
            // wait for all probes
            while (done.get() < targets.size) delay(50)
            results.toMap()
        }

    private fun orderVerifyCandidates(
        pingCandidates: List<String>,
        udpTags: List<String>,
        cfg: SmartConfig,
    ): List<String> {
        val tcpOrdered = pingCandidates.sortedBy { cache[it]?.first ?: Int.MAX_VALUE }
        val udpOrdered = udpTags.sortedBy { cache[it]?.first ?: Int.MAX_VALUE }
        val reserved = minOf(UDP_VERIFY_SLOTS, cfg.verifyAttempts, udpOrdered.size)
        val tcpSlots = (cfg.verifyAttempts - reserved).coerceAtLeast(0)
        val ordered = linkedSetOf<String>()
        ordered += tcpOrdered.take(tcpSlots)
        ordered += udpOrdered.take(reserved)
        for (tag in tcpOrdered + udpOrdered) {
            if (ordered.size >= cfg.verifyAttempts) break
            ordered += tag
        }
        return ordered.toList()
    }

    // ---- cache ----

    private fun record(tag: String, delay: Int) {
        if (delay <= 0) return
        cache[tag] = delay to System.currentTimeMillis() / 1000
    }

    private fun cacheFile(): File =
        File(InterstellarApplication.application.filesDir, CACHE_FILE)

    private fun loadCache() {
        runCatching {
            val obj = JSONObject(cacheFile().readText())
            obj.keys().forEach { tag ->
                val entry = obj.optJSONObject(tag) ?: return@forEach
                val delay = entry.optInt("delay", 0)
                if (delay > 0) cache[tag] = delay to entry.optLong("ts", 0)
            }
            sortCache()
        }
    }

    private fun saveCache() {
        runCatching {
            sortCache()
            val obj = JSONObject()
            cache.forEach { (tag, v) ->
                obj.put(tag, JSONObject().put("delay", v.first).put("ts", v.second))
            }
            cacheFile().writeText(obj.toString())
        }
    }

    private fun sortCache() {
        val sorted = cache.entries.sortedBy { it.value.first }
        cache.clear()
        sorted.forEach { (k, v) -> cache[k] = v }
    }
}
