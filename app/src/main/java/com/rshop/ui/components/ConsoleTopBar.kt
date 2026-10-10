package com.rshop.ui.components

import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.rshop.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.rshop.navigation.TopLevelDestination
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import com.rshop.ui.theme.ActiveTheme
import com.rshop.ui.theme.BackdropStyle
import com.rshop.ui.theme.liquidGlass
import com.rshop.ui.theme.Dimens
import com.rshop.ui.theme.RShopColors

/** The yellow mark on the Settings button. */
enum class SettingsBadge { None, Syncing, Update }

private val BadgeYellow = Color(0xFFFFD23F)

@Composable
fun ConsoleTopBar(
    selected: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** A mark on the Settings button: a sync running, or a new version to install. */
    settingsBadge: SettingsBadge = SettingsBadge.None,
) {
    // Switching tabs with L1/R1: the ring moves to the selected tab instead of staying on the
    // previous one. A new screen with its own focus target takes it right after (one frame later).
    val requesters = remember { TopLevelDestination.entries.associateWith { FocusRequester() } }
    val updateLabel = stringResource(R.string.badge_update)
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(selected) {
        if (inputMode == InputMode.Keyboard && selected != null) runCatching { requesters.getValue(selected).requestFocus() }
    }
    val look = ActiveTheme.look
    val onBar = look.accentBars
    val hairline = look.hairlines
    val ruleColor = RShopColors.Outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The eShop's red band with a soft shadow under it.
            .then(if (onBar) Modifier.shadow(6.dp).background(RShopColors.Accent) else Modifier)
            .height(Dimens.TopBarHeight)
            .then(if (hairline) Modifier.drawBehind { drawLine(ruleColor, Offset(24.dp.toPx(), size.height), Offset(size.width - 24.dp.toPx(), size.height), 1.dp.toPx()) } else Modifier)
            .padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(onBar)
        Spacer(Modifier.width(28.dp))
        // The shoulder hints stay put on both sides: only the tabs between them scroll, so R1 is
        // never pushed off screen when there are many tabs.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ShoulderHint("L1", onBar)
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    // No focusRestorer here: it would redirect the selected-tab request above to the
                    // previously focused tab.
                    .horizontalScroll(rememberScrollState())
                    // Liquid glass: the tabs sit in one floating capsule.
                    .then(if (look.glossy) Modifier.liquidGlass(CircleShape) else Modifier)
                    .padding(horizontal = if (look.glossy) 8.dp else 0.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TopLevelDestination.entries
                    .filter { it != TopLevelDestination.Settings }
                    .forEach { tab ->
                        TextTab(
                            label = stringResource(tab.labelRes),
                            selected = tab == selected,
                            onBar = onBar,
                            onClick = { onSelect(tab) },
                            modifier = Modifier
                                .focusRequester(requesters.getValue(tab))
                                .testTag("tab_${tab.name}"),
                        )
                    }
            }
            Spacer(Modifier.width(6.dp))
            ShoulderHint("R1", onBar)
        }
        Spacer(Modifier.width(12.dp))
        val settingsSelected = selected == TopLevelDestination.Settings
        FocusableSurface(
            onClick = { onSelect(TopLevelDestination.Settings) },
            shape = CircleShape,
            containerColor = if (settingsSelected) (if (onBar) Color.White.copy(alpha = 0.22f) else RShopColors.SurfaceHighest) else Color.Transparent,
            focusedContainerColor = if (onBar) Color.White.copy(alpha = 0.30f) else RShopColors.SurfaceHighest,
            glow = false,
            modifier = Modifier
                .size(44.dp)
                .focusRequester(requesters.getValue(TopLevelDestination.Settings))
                .testTag("tab_${TopLevelDestination.Settings.name}"),
            contentAlignment = Alignment.Center,
        ) { focused ->
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(TopLevelDestination.Settings.labelRes),
                tint = when {
                    onBar -> RShopColors.OnAccent
                    focused || settingsSelected -> RShopColors.TextPrimary
                    else -> RShopColors.TextSecondary
                },
            )
            if (settingsBadge != SettingsBadge.None) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 3.dp)
                        .size(17.dp)
                        .background(BadgeYellow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when (settingsBadge) {
                        // Still, not spinning: a turning icon would redraw the bar at every frame.
                        SettingsBadge.Syncing -> Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.badge_syncing),
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp),
                        )
                        else -> Text(
                            "!",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.semantics { contentDescription = updateLabel },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onBar: Boolean = false) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = Dimens.PillShape,
        focusedScale = 1.05f,
        containerColor = Color.Transparent,
        focusedContainerColor = if (onBar) Color.White.copy(alpha = 0.26f) else RShopColors.SurfaceHighest,
        glow = false,
    ) { focused ->
        val textColor by animateColorAsState(
            when {
                onBar -> if (selected || focused) RShopColors.OnAccent else RShopColors.OnAccent.copy(alpha = 0.82f)
                selected || focused -> RShopColors.TextPrimary
                else -> RShopColors.TextSecondary
            },
            label = "tabText",
        )
        val indicatorWidth by animateDpAsState(if (selected) 22.dp else 0.dp, label = "tabIndicator")
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = textColor, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .width(indicatorWidth)
                    .height(3.dp)
                    .background(if (onBar) RShopColors.OnAccent else RShopColors.Accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun ShoulderHint(label: String, onBar: Boolean = false) {
    val color = if (onBar) RShopColors.OnAccent.copy(alpha = 0.85f) else RShopColors.TextTertiary
    Text(
        text = label,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun Logo(onBar: Boolean = false) {
    // On the PlayStation blue the accent would be lost: the brighter shade is used there.
    val onBlue = ActiveTheme.look.backdrop == BackdropStyle.Ps2 || ActiveTheme.look.glossy
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = if (onBar) RShopColors.OnAccent else if (onBlue) RShopColors.AccentBright else RShopColors.Accent)) { append("R") }
            withStyle(SpanStyle(color = if (onBar) RShopColors.OnAccent else RShopColors.TextPrimary)) { append("Shop") }
        },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Black,
    )
}
