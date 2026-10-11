package dev.bezh.zamer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.R
import dev.bezh.zamer.domain.Draft
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.MeasureResult
import dev.bezh.zamer.domain.Quantity
import dev.bezh.zamer.domain.Side
import dev.bezh.zamer.domain.Workspace

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MeasureScreen(
    vm: MeasureViewModel,
    contentPadding: PaddingValues,
    onPair: () -> Unit,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val formatter = remember(ui.settings.units, ui.settings.decimals) {
        Formatter(ui.settings.units, ui.settings.decimals)
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(ui.readingCount) {
        if (ui.readingCount > 0 && ui.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = ScreenPadding)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionPill(
            link = ui.link,
            onRequestPermission = onRequestPermission,
            onEnableBluetooth = onEnableBluetooth,
            onPair = onPair,
        )
        val ws = ui.workspace ?: return@Column
        ModeSelector(ws.mode, vm::setMode)
        ModePage(
            mode = ws.mode,
            ws = ws,
            lastMm = ui.lastMm,
            readingCount = ui.readingCount,
            formatter = formatter,
            onSelectSlot = vm::selectSlot,
            modifier = Modifier.weight(1f),
        )
        if (ui.recent.isNotEmpty()) {
            RecentReadings(ui.recent, formatter) { vm.put(it) }
        }
        ActionRow(canUndo = ws.draft().values.any { it != null }, onUndo = vm::undo, onReset = vm::reset)
    }
}

/** Everything that belongs to one mode: its readout, its slots and how to measure it. */
@Composable
private fun ModePage(
    mode: MeasureMode,
    ws: Workspace,
    lastMm: Int?,
    readingCount: Int,
    formatter: Formatter,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = ws.draft(mode)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Readout(mode, draft, ws.result(mode), lastMm, readingCount, formatter, Modifier.weight(1f).fillMaxWidth())
        if (mode.sides.size > 1) {
            SlotRow(mode, draft, formatter, onSelectSlot)
        }
        if (mode == MeasureMode.HEIGHT) {
            Text(
                stringResource(R.string.height_howto),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

// ----------------------------------------------------------------- mode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeSelector(selected: MeasureMode, onSelect: (MeasureMode) -> Unit) {
    val modes = MeasureMode.entries
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { i, mode ->
            ToggleButton(
                checked = mode == selected,
                onCheckedChange = { onSelect(mode) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .semantics { role = Role.RadioButton },
                // Four labels share 360dp; the default padding would truncate them.
                contentPadding = PaddingValues(horizontal = 4.dp),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(
                    text = stringResource(mode.label),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun prompt(mode: MeasureMode, side: Side) = when (side) {
    Side.LENGTH -> if (mode == MeasureMode.DISTANCE) R.string.slot_next_distance else R.string.slot_next_length
    Side.WIDTH -> R.string.slot_next_width
    Side.HEIGHT -> R.string.slot_next_height
    Side.BASE -> R.string.slot_next_base
    Side.HYPOTENUSE -> R.string.slot_next_hypotenuse
}

// ---------------------------------------------------------------- readout

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Readout(
    mode: MeasureMode,
    draft: Draft,
    result: MeasureResult?,
    lastMm: Int?,
    readingCount: Int,
    formatter: Formatter,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val impossible = draft.isComplete && result == null
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(44.dp),
        color = when {
            result != null -> scheme.primaryContainer
            impossible -> scheme.errorContainer
            else -> scheme.surfaceContainerHigh
        },
        contentColor = when {
            result != null -> scheme.onPrimaryContainer
            impossible -> scheme.onErrorContainer
            else -> scheme.onSurface
        },
    ) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.CenterStart) {
            val title: String
            val quantity: Quantity?
            val extra: String?
            if (result != null) {
                title = stringResource(
                    when (result.mode) {
                        MeasureMode.DISTANCE -> R.string.result_distance
                        MeasureMode.AREA -> R.string.result_area
                        MeasureMode.VOLUME -> R.string.result_volume
                        MeasureMode.HEIGHT -> R.string.result_height
                    },
                )
                quantity = formatter.primary(result)
                extra = formatter.secondary(result)?.let {
                    val name = stringResource(if (result.mode == MeasureMode.AREA) R.string.extra_perimeter else R.string.extra_floor)
                    "$name · $it"
                }
            } else if (impossible) {
                title = stringResource(R.string.height_impossible)
                quantity = null
                extra = null
            } else if (draft.next != null && (lastMm != null || draft.values.any { it != null })) {
                title = stringResource(prompt(mode, mode.sides[draft.next]))
                quantity = lastMm?.let(formatter::length)
                extra = lastMm?.let { stringResource(R.string.last_reading) }
            } else {
                title = stringResource(R.string.hero_hint_start)
                quantity = null
                extra = null
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (quantity == null) {
                    Box(
                        Modifier
                            .size(112.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (impossible) Icons.Rounded.ErrorOutline else Icons.Rounded.Straighten,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = LocalContentColorAlpha(0.85f),
                )
                if (quantity != null) {
                    AnimatedContent(
                        targetState = quantity to readingCount,
                        transitionSpec = {
                            (slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(280))) togetherWith
                                (slideOutVertically(tween(200)) { -it / 3 } + fadeOut(tween(160)))
                        },
                        label = "reading",
                    ) { (q, _) ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = q.number,
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 92.sp),
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = q.unit,
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                    }
                }
                if (extra != null) {
                    Text(text = extra, style = MaterialTheme.typography.titleMedium, color = LocalContentColorAlpha(0.75f))
                }
            }
        }
    }
}

@Composable
private fun LocalContentColorAlpha(alpha: Float) =
    androidx.compose.material3.LocalContentColor.current.copy(alpha = alpha)

// ------------------------------------------------------------------ slots

@Composable
private fun SlotRow(mode: MeasureMode, draft: Draft, formatter: Formatter, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = TileSpacing) {
        draft.values.forEachIndexed { i, mm ->
            val waiting = i == draft.next
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = if (waiting) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(mode.sides[i].label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val q = mm?.let(formatter::length)
                    Text(
                        text = q?.toString() ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (q == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- recent

/** Earlier readings, so a value measured once can be reused without measuring again. */
@Composable
private fun RecentReadings(values: List<Int>, formatter: Formatter, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.History,
            contentDescription = stringResource(R.string.recent_readings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        values.forEach { mm ->
            SuggestionChip(onClick = { onPick(mm) }, label = { Text(formatter.length(mm).toString()) })
        }
    }
}

// ---------------------------------------------------------------- actions

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionRow(canUndo: Boolean, onUndo: () -> Unit, onReset: () -> Unit) {
    val padding = PaddingValues(horizontal = 16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.weight(1f).height(72.dp),
            contentPadding = padding,
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_undo), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Button(
            onClick = onReset,
            modifier = Modifier.weight(1f).height(72.dp),
            contentPadding = padding,
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_reset), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}
