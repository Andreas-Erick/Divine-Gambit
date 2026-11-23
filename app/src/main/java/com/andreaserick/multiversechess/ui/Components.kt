package com.andreaserick.multiversechess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.Side

private val PillShape = RoundedCornerShape(50)
private val PillPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)

/** The outline of every secondary control: a faint line of gold, like the cards' edge. */
private val controlBorder: BorderStroke @Composable get() = BorderStroke(1.dp, Palette.cardEdge)

/**
 * Presses in slightly while held, like a key, for buttons that share [interaction]. Applied in
 * the draw phase, so it never recomposes; still when animations are off.
 */
@Composable
internal fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) 0.975f else 1f, tween(90), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** The main call to action: a filled gold pill. */
@Composable
fun PrimaryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressScale(interaction),
        enabled = enabled,
        shape = PillShape,
        contentPadding = PillPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.accent,
            disabledContainerColor = Palette.accent.copy(alpha = 0.22f),
            disabledContentColor = Palette.ink.copy(alpha = 0.45f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        interactionSource = interaction,
        content = content,
    )
}

/** A secondary action: a card-coloured pill with a light outline. */
@Composable
fun SecondaryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressScale(interaction),
        enabled = enabled,
        shape = PillShape,
        contentPadding = PillPadding,
        interactionSource = interaction,
        border = if (enabled) controlBorder else BorderStroke(1.dp, Palette.cardEdge.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Palette.card,
            contentColor = Palette.ink,
            disabledContainerColor = Palette.card.copy(alpha = 0.6f),
            disabledContentColor = Palette.ink.copy(alpha = 0.35f),
        ),
        content = content,
    )
}

/** A round button floating over the multiverse, for navigation and tools. [selected] fills it, for toggles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val tint = when {
        selected -> Palette.onAccent
        enabled -> Palette.ink
        else -> Palette.ink.copy(alpha = 0.3f)
    }
    // Held, it says what it does: the icon alone is seldom enough for someone new.
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp).shadow(3.dp, CircleShape),
            shape = CircleShape,
            color = if (selected) Palette.accent else Palette.card,
            contentColor = tint,
            border = if (selected) BorderStroke(1.dp, Palette.accent) else controlBorder,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription, tint = tint)
            }
        }
    }
}

/** Four corner brackets: "show everything". Drawn here since only the core icon set is bundled. */
val OverviewIcon: ImageVector = ImageVector.Builder("Overview", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        // Each corner is an L, 2 units thick and 6 long.
        for ((x, y, dx, dy) in listOf(listOf(3f, 3f, 1f, 1f), listOf(21f, 3f, -1f, 1f), listOf(21f, 21f, -1f, -1f), listOf(3f, 21f, 1f, -1f))) {
            moveTo(x, y)
            lineTo(x + 6 * dx, y)
            lineTo(x + 6 * dx, y + 2 * dy)
            lineTo(x + 2 * dx, y + 2 * dy)
            lineTo(x + 2 * dx, y + 6 * dy)
            lineTo(x, y + 6 * dy)
            close()
        }
        // A small board in the middle.
        moveTo(9f, 9f)
        lineTo(15f, 9f)
        lineTo(15f, 15f)
        lineTo(9f, 15f)
        close()
    }
}.build()

/** Three boards in a row: one timeline through time. */
val HistoryIcon: ImageVector = boardsIcon("History", horizontal = true)

/** Three boards in a column: one moment across timelines. */
val ParallelIcon: ImageVector = boardsIcon("Parallel", horizontal = false)

private fun boardsIcon(name: String, horizontal: Boolean): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            for (i in 0 until 3) {
                val (x, y) = if (horizontal) 1.5f + i * 7.5f to 8.5f else 8.5f to 1.5f + i * 7.5f
                moveTo(x, y)
                lineTo(x + 6f, y)
                lineTo(x + 6f, y + 6f)
                lineTo(x, y + 6f)
                close()
            }
        }
    }.build()

/** A small disc in a side's colour, used next to "White to move" and in move lists. */
@Composable
fun SideDisc(side: Side, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(
        modifier
            .size(size)
            .background(if (side == Side.WHITE) Color.White else Palette.pieceDark, CircleShape)
            .border(1.5.dp, Palette.inkSoft, CircleShape),
    )
}

/** A card with a flat offset shadow, echoing the boards' slabs. */
@Composable
fun SlabCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .padding(start = 5.dp, top = 6.dp)
                .background(Palette.slab, MaterialTheme.shapes.large),
        )
        Surface(
            modifier = Modifier.padding(end = 5.dp, bottom = 6.dp),
            shape = MaterialTheme.shapes.large,
            color = Palette.card,
            border = BorderStroke(1.dp, Palette.cardEdge),
            content = content,
        )
    }
}

/** A confirm button for something that can't be taken back: resigning, discarding, deleting. */
@Composable
fun DestructiveTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(text) }
}

/**
 * Shows [content] for [text] while there is one, expanding in and folding away. The last text
 * stays for the folding, rather than vanishing first and leaving an empty box to close.
 */
@Composable
fun LingeringText(text: String?, content: @Composable (String) -> Unit) {
    val last = remember { arrayOf(text.orEmpty()) }
    if (text != null) last[0] = text
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = text != null,
        enter = if (reduced) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(140)) + fadeOut(tween(110)),
    ) { content(last[0]) }
}
