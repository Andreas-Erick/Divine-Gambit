package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andreaserick.multiversechess.R

/**
 * Colours of the multiverse view and the app chrome, "midnight cosmos": the boards lie on a deep
 * indigo night, timelines run as teal ribbons, and gold marks what must be played.
 */
internal object Palette {
    // Floor of the multiverse and app background.
    val background = Color(0xFF11132A)
    val floor = Color(0xFF14172F)
    val floorBand = Color(0xFF191D3A)
    val card = Color(0xFF1F2346)
    val ink = Color(0xFFECE8F4)
    val inkSoft = Color(0xFFA4A3BF)

    /** Shadows under cards and boards. */
    val slab = Color(0xFF06071A)

    // The present: a lighter column across all timelines.
    val present = Color(0xFF1C2044)
    val presentEdge = Color(0xFF3B4176)

    // Boards: bone and slate squares; the frame says whose move it is.
    val lightSquare = Color(0xFFE9E4D6)
    val darkSquare = Color(0xFF6E7FA6)
    val whiteBoardFrame = Color(0xFFE6E1F0)
    val blackBoardFrame = Color(0xFF0A0B1C)

    /** Black's pieces, and the outline of White's. */
    val pieceDark = Color(0xFF22243A)

    // Timelines.
    val ribbon = Color(0xFF2FA79A)
    val ribbonInactive = Color(0xFF39475F)

    // Accents and highlights, and the text drawn on them.
    val accent = Color(0xFF3FC1B0)
    val onAccent = Color(0xFF0B1A24)
    val gold = Color(0xFFE8B04B)
    val onGold = Color(0xFF2A1B00)
    val lastMove = Color(0xA6E8D35C)
    val travel = Color(0xB3A99BF0)
    val selection = Color(0xCC3FC1B0)
    val target = Color(0x8C3FC1B0)
    val targetDot = Color(0xE00E5C55)
    val travelArrow = Color(0xFFB3A6FF)
    val threat = Color(0xFFFF6B6B)
    val onThreat = Color(0xFF2A0A10)
}

@OptIn(ExperimentalTextApi::class)
private val lora = FontFamily(
    Font(R.font.lora, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.lora, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.lora, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.lora, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Serif type for headings and body; the game is meant to feel like a well-made board game. */
internal val SerifFamily = lora

private fun Typography.serif(): Typography {
    fun TextStyle.s() = copy(fontFamily = lora)
    return copy(
        displayLarge = displayLarge.s(), displayMedium = displayMedium.s(), displaySmall = displaySmall.s(),
        headlineLarge = headlineLarge.s().copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.s().copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.s().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.s().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.s().copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = titleSmall.s().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s().copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = labelMedium.s(), labelSmall = labelSmall.s(),
    )
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    primaryContainer = Color(0xFF184A47),
    onPrimaryContainer = Color(0xFFB9F2E9),
    secondary = Palette.gold,
    onSecondary = Palette.onGold,
    secondaryContainer = Color(0xFF4A3812),
    onSecondaryContainer = Color(0xFFFBE2B0),
    background = Palette.background,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = Color(0xFF262B54),
    onSurfaceVariant = Palette.inkSoft,
    surfaceContainerHigh = Palette.card,
    surfaceContainerHighest = Color(0xFF282D57),
    surfaceContainer = Palette.card,
    outline = Palette.presentEdge,
    outlineVariant = Color(0xFF2C3160),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A0A),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MultiverseChessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography().serif(), shapes = shapes, content = content)
}
