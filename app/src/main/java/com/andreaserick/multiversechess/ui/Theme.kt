package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
 * Colours of the multiverse view and the app chrome, "the heavens": the boards lie on a deep
 * indigo night, timelines run as teal ribbons, gold marks what must be played, and the app's
 * controls are gold too, like the light of Dante's Paradise.
 *
 * In the Inferno everything burns instead: boards of ash and brick on a smouldering floor, rivers
 * of lava for timelines, and flame for the accents. [heat] blends from one to the other, so the
 * colours change as the player passes between them.
 */
internal object Palette {
    /** 0 in the multiverse, 1 in the Inferno, and in between on the way. */
    var heat by mutableFloatStateOf(0f)

    /** A heat to use instead of [heat] for a moment, without anyone reading it seeing a change. */
    private var fixedHeat: Float? = null

    /** [block]'s result with the colours as they are at [heat]. */
    fun <T> at(heat: Float, block: () -> T): T {
        fixedHeat = heat
        try {
            return block()
        } finally {
            fixedHeat = null
        }
    }

    private fun mix(cool: Long, hot: Long): Color = (fixedHeat ?: heat).let { h ->
        when (h) {
            0f -> Color(cool)
            1f -> Color(hot)
            else -> lerp(Color(cool), Color(hot), h)
        }
    }

    // Floor of the multiverse and app background.
    val background get() = mix(0xFF11132A, 0xFF170A0B)
    val floor get() = mix(0xFF14172F, 0xFF1B0C0C)
    val floorBand get() = mix(0xFF191D3A, 0x47300F0C)
    val card get() = mix(0xFF1F2346, 0xFF2E1513)
    val ink get() = mix(0xFFECE8F4, 0xFFF7E9DD)
    val inkSoft get() = mix(0xFFA4A3BF, 0xFFC99E8C)

    /** Shadows under cards and boards. */
    val slab get() = mix(0xFF06071A, 0xFF3A0905)

    // The present: a lighter column across all timelines.
    val present get() = mix(0xFF1C2044, 0x33461A10)
    val presentEdge get() = mix(0xFF3B4176, 0xFF6E2C1E)

    // Boards: bone and slate squares (ash and brick in the Inferno); the frame says whose move it is.
    val lightSquare get() = mix(0xFFE9E4D6, 0xFFEBD8C3)
    val darkSquare get() = mix(0xFF6E7FA6, 0xFFA04B30)
    val whiteBoardFrame get() = mix(0xFFE6E1F0, 0xFFF3E1CF)
    val blackBoardFrame get() = mix(0xFF0A0B1C, 0xFF140505)

    /** Black's pieces, and the outline of White's. */
    val pieceDark get() = mix(0xFF22243A, 0xFF2B1410)

    // Timelines.
    val ribbon get() = mix(0xFF2FA79A, 0xFFE0612B)
    val ribbonInactive get() = mix(0xFF39475F, 0xFF5C3027)

    // Accents and highlights, and the text drawn on them.
    val accent get() = mix(0xFFE8C77A, 0xFFFF7A3D)
    val onAccent get() = mix(0xFF2A1B00, 0xFF2A0B02)

    /** The edge of cards and round buttons: a faint line of gold. */
    val cardEdge get() = mix(0x66E8C77A, 0xFF6E2C1E)
    val gold get() = mix(0xFFE8B04B, 0xFFFFC94A)
    val onGold get() = mix(0xFF2A1B00, 0xFF2A1700)
    val lastMove get() = mix(0xA6E8D35C, 0xA6FFD15C)
    val travel get() = mix(0xB3A99BF0, 0xB3E58FD6)
    val selection get() = mix(0xCC3FC1B0, 0xCCFF7A3D)
    val target get() = mix(0x8C3FC1B0, 0x8CFF7A3D)
    val targetDot get() = mix(0xE00E5C55, 0xE07A2108)
    val travelArrow get() = mix(0xFFB3A6FF, 0xFFF0A3E4)
    val threat get() = mix(0xFFFF6B6B, 0xFFFF4D7A)
    val onThreat get() = mix(0xFF2A0A10, 0xFF2A0614)

    /** A piece just back from the Inferno. */
    val returned get() = mix(0xB3FF8A3D, 0xB3FFB347)

    /** Fire, for the way into the Inferno and back: the bright rim and the deep glow behind it. */
    val flame = Color(0xFFFFB347)
    val ember = Color(0xFFE0442B)
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

/** Material's colours, from the [Palette] at heat [h]. */
private fun colors(h: Float) = Palette.at(h) { scheme(h) }

private fun scheme(h: Float) = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    primaryContainer = lerp(Color(0xFF4A3812), Color(0xFF5A2410), h),
    onPrimaryContainer = lerp(Color(0xFFFBE2B0), Color(0xFFFFD9C2), h),
    secondary = Palette.gold,
    onSecondary = Palette.onGold,
    secondaryContainer = Color(0xFF4A3812),
    onSecondaryContainer = Color(0xFFFBE2B0),
    background = Palette.background,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = lerp(Color(0xFF262B54), Color(0xFF3A1A15), h),
    onSurfaceVariant = Palette.inkSoft,
    surfaceContainerHigh = Palette.card,
    surfaceContainerHighest = lerp(Color(0xFF282D57), Color(0xFF3D1B16), h),
    surfaceContainer = Palette.card,
    outline = Palette.presentEdge,
    outlineVariant = lerp(Color(0xFF2C3160), Color(0xFF4A1F18), h),
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
    // Material's colours change once, halfway into the Inferno or out, rather than on every frame
    // of the passage; the board and the Inferno follow the Palette as it blends.
    val hot by remember { derivedStateOf { Palette.heat >= 0.5f } }
    val scheme = remember(hot) { colors(if (hot) 1f else 0f) }
    MaterialTheme(colorScheme = scheme, typography = Typography().serif(), shapes = shapes, content = content)
}
