package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import com.interstellar.proxy.R
import com.interstellar.proxy.data.SmartConfig
import com.interstellar.proxy.data.SmartSettingsStore
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

/**
 * Manual tuning for the smart switch engine. Values are persisted on commit;
 * [onApply] restarts only the engine loop (never the proxy core).
 */
@Composable
fun SmartSettingsPage(onApply: () -> Unit) {
    val colors = LocalInterstellarColors.current
    var config by remember { mutableStateOf(SmartSettingsStore.load()) }
    var advanced by remember { mutableStateOf(false) }

    fun save(updated: SmartConfig) {
        val safe = updated.sanitized()
        config = safe
        SmartSettingsStore.save(safe)
        onApply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // ---- 巡检 ----
        PrefSectionLabel(stringResource(R.string.smart_settings_section_patrol))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            SmartNumberRow(
                title = stringResource(R.string.smart_settings_interval_title),
                desc = stringResource(R.string.smart_settings_interval_desc),
                value = config.patrolIntervalSec,
                unit = stringResource(R.string.smart_settings_unit_seconds),
                range = SmartConfig.PATROL_MIN_SEC..SmartConfig.PATROL_MAX_SEC,
                onCommit = { save(config.copy(patrolIntervalSec = it)) },
            )
            SmartNumberRow(
                title = stringResource(R.string.smart_settings_good_title),
                desc = stringResource(R.string.smart_settings_good_desc),
                value = config.goodMs,
                unit = stringResource(R.string.smart_settings_unit_ms),
                range = SmartConfig.GOOD_MIN_MS..SmartConfig.GOOD_MAX_MS,
                onCommit = { save(config.copy(goodMs = it)) },
            )
        }
        IosSectionFooter(stringResource(R.string.smart_settings_patrol_footer))

        Spacer(Modifier.height(22.dp))

        // ---- 阈值 ----
        PrefSectionLabel(stringResource(R.string.smart_settings_section_thresholds))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            SmartNumberRow(
                title = stringResource(R.string.smart_settings_ping_title),
                desc = stringResource(R.string.smart_settings_ping_desc),
                value = config.pingMaxMs,
                unit = stringResource(R.string.smart_settings_unit_ms),
                range = SmartConfig.PING_MIN_MS..SmartConfig.PING_MAX_MS_LIMIT,
                onCommit = { save(config.copy(pingMaxMs = it)) },
            )
            SmartNumberRow(
                title = stringResource(R.string.smart_settings_real_title),
                desc = stringResource(R.string.smart_settings_real_desc),
                value = config.realMaxMs,
                unit = stringResource(R.string.smart_settings_unit_ms),
                range = SmartConfig.REAL_MIN_MS..config.goodMs,
                onCommit = { save(config.copy(realMaxMs = it)) },
            )
        }
        IosSectionFooter(stringResource(R.string.smart_settings_thresholds_footer))

        Spacer(Modifier.height(22.dp))

        // ---- 切换 ----
        PrefSectionLabel(stringResource(R.string.smart_settings_section_switch))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            SmartNumberRow(
                title = stringResource(R.string.smart_settings_cooldown_title),
                desc = stringResource(R.string.smart_settings_cooldown_desc),
                value = config.cooldownSec,
                unit = stringResource(R.string.smart_settings_unit_seconds),
                range = SmartConfig.COOLDOWN_MIN_SEC..SmartConfig.COOLDOWN_MAX_SEC,
                onCommit = { save(config.copy(cooldownSec = it)) },
            )
            SmartNumberRow(
                title = stringResource(R.string.smart_settings_round_timeout_title),
                desc = stringResource(R.string.smart_settings_round_timeout_desc),
                value = config.roundTimeoutSec,
                unit = stringResource(R.string.smart_settings_unit_seconds),
                range = SmartConfig.ROUND_MIN_SEC..SmartConfig.ROUND_MAX_SEC,
                onCommit = { save(config.copy(roundTimeoutSec = it)) },
            )
        }
        IosSectionFooter(stringResource(R.string.smart_settings_switch_footer))

        Spacer(Modifier.height(22.dp))

        // ---- 高级 ----
        PrefSectionLabel(stringResource(R.string.smart_settings_section_advanced))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefRowShell(
                title = stringResource(R.string.smart_settings_advanced_title),
                desc = stringResource(R.string.smart_settings_advanced_desc),
                onClick = { advanced = !advanced },
            ) {
                Text(
                    if (advanced) "⌃" else "⌄",
                    color = colors.textTertiary,
                    fontSize = 16.sp,
                )
            }
            if (advanced) {
                SmartNumberRow(
                    title = stringResource(R.string.smart_settings_first_delay_title),
                    desc = stringResource(R.string.smart_settings_first_delay_desc),
                    value = config.firstPatrolDelaySec,
                    unit = stringResource(R.string.smart_settings_unit_seconds),
                    range = SmartConfig.FIRST_DELAY_MIN_SEC..SmartConfig.FIRST_DELAY_MAX_SEC,
                    onCommit = { save(config.copy(firstPatrolDelaySec = it)) },
                )
                SmartNumberRow(
                    title = stringResource(R.string.smart_settings_min_candidates_title),
                    desc = stringResource(R.string.smart_settings_min_candidates_desc),
                    value = config.minCandidatesWarn,
                    unit = stringResource(R.string.smart_settings_unit_nodes),
                    range = SmartConfig.MIN_CANDIDATES_MIN..SmartConfig.MIN_CANDIDATES_MAX,
                    onCommit = { save(config.copy(minCandidatesWarn = it)) },
                )
                SmartNumberRow(
                    title = stringResource(R.string.smart_settings_verify_attempts_title),
                    desc = stringResource(R.string.smart_settings_verify_attempts_desc),
                    value = config.verifyAttempts,
                    unit = stringResource(R.string.smart_settings_unit_times),
                    range = SmartConfig.VERIFY_MIN..SmartConfig.VERIFY_MAX,
                    onCommit = { save(config.copy(verifyAttempts = it)) },
                )
                SmartTextRow(
                    title = stringResource(R.string.smart_settings_test_url_title),
                    desc = stringResource(R.string.smart_settings_test_url_desc),
                    value = config.testUrl,
                    onCommit = { save(config.copy(testUrl = it)) },
                )
                PrefToggleRow(
                    title = stringResource(R.string.smart_settings_allow_udp_title),
                    desc = stringResource(R.string.smart_settings_allow_udp_desc),
                    checked = config.allowUdp,
                    onChange = { save(config.copy(allowUdp = it)) },
                )
                PrefToggleRow(
                    title = stringResource(R.string.smart_settings_backoff_title),
                    desc = stringResource(R.string.smart_settings_backoff_desc),
                    checked = config.backoffEnabled,
                    onChange = { save(config.copy(backoffEnabled = it)) },
                )
            }
        }
        IosSectionFooter(stringResource(R.string.smart_settings_advanced_footer))

        Spacer(Modifier.height(18.dp))

        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefRowShell(
                title = stringResource(R.string.smart_settings_reset_title),
                desc = stringResource(R.string.smart_settings_reset_desc),
            ) {
                CapsuleAction(
                    text = stringResource(R.string.smart_settings_reset_action),
                    accent = colors.danger,
                    onClick = {
                        val defaults = SmartConfig()
                        config = defaults
                        SmartSettingsStore.save(defaults)
                        onApply()
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SmartNumberRow(
    title: String,
    desc: String,
    value: Int,
    unit: String,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    fun commit() {
        val parsed = text.toIntOrNull()
        if (parsed == null) {
            text = value.toString()
            error = false
            return
        }
        val clamped = parsed.coerceIn(range.first, range.last)
        error = clamped != parsed
        text = clamped.toString()
        if (clamped != value) onCommit(clamped)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (error) {
                    stringResource(
                        R.string.smart_settings_range_hint,
                        range.first,
                        range.last,
                    )
                } else {
                    desc
                },
                color = if (error) colors.danger else colors.textTertiary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                if (next.length <= 5 && next.all { it.isDigit() }) {
                    text = next
                    error = next.toIntOrNull()?.let { it !in range } ?: false
                }
            },
            modifier = Modifier
                .width(94.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            singleLine = true,
            isError = error,
            textStyle = TextStyle(
                fontSize = 15.sp,
                textAlign = TextAlign.End,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focusManager.clearFocus()
                },
            ),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedBorderColor = if (error) colors.danger else colors.primaryBorder,
                unfocusedBorderColor = if (error) colors.danger else colors.border,
                cursorColor = colors.primary,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(unit, color = colors.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun SmartTextRow(
    title: String,
    desc: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    fun commit() {
        val candidate = text.trim()
        if (candidate.startsWith("https://")) {
            error = false
            text = candidate
            if (candidate != value) onCommit(candidate)
        } else {
            error = true
            text = value
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (error) stringResource(R.string.smart_settings_url_invalid) else desc,
                color = if (error) colors.danger else colors.textTertiary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            singleLine = true,
            isError = error,
            textStyle = TextStyle(fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focusManager.clearFocus()
                },
            ),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedBorderColor = if (error) colors.danger else colors.primaryBorder,
                unfocusedBorderColor = if (error) colors.danger else colors.border,
                cursorColor = colors.primary,
            ),
        )
    }
}
