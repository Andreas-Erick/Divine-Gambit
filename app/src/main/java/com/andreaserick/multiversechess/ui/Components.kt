package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreaserick.multiversechess.engine.Side

private val PillShape = RoundedCornerShape(50)
private val PillPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)

/** The main call to action: a filled amethyst pill. */
@Composable
fun PrimaryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        contentPadding = PillPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.amethyst,
            disabledContainerColor = Palette.amethyst.copy(alpha = 0.22f),
            disabledContentColor = Palette.ink.copy(alpha = 0.45f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        content = content,
    )
}

/** A secondary action: a parchment pill with an ink outline. */
@Composable
fun SecondaryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        contentPadding = PillPadding,
        border = BorderStroke(1.5.dp, if (enabled) Palette.ink.copy(alpha = 0.7f) else Palette.ink.copy(alpha = 0.2f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Palette.card,
            contentColor = Palette.ink,
            disabledContainerColor = Palette.card.copy(alpha = 0.6f),
            disabledContentColor = Palette.ink.copy(alpha = 0.35f),
        ),
        content = content,
    )
}

/** A round button floating over the multiverse, for navigation and tools. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp).shadow(3.dp, CircleShape),
        shape = CircleShape,
        color = Palette.card,
        contentColor = Palette.ink,
        border = BorderStroke(1.dp, Palette.presentEdge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = if (enabled) Palette.ink else Palette.ink.copy(alpha = 0.3f))
        }
    }
}

/** A small disc in a side's colour, used next to "White to move" and in move lists. */
@Composable
fun SideDisc(side: Side, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(
        modifier
            .size(size)
            .background(if (side == Side.WHITE) Color.White else Palette.ink, CircleShape)
            .border(1.5.dp, Palette.ink, CircleShape),
    )
}

/** Parchment card with a flat offset shadow, echoing the boards' slabs. */
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
            border = BorderStroke(1.dp, Palette.presentEdge),
            content = content,
        )
    }
}
