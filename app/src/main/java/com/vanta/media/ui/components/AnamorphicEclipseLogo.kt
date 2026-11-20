package com.vanta.media.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vanta.media.ui.theme.AmberCorona
import com.vanta.media.ui.theme.CrimsonFlare
import com.vanta.media.ui.theme.OledObsidian

@Composable
fun AnamorphicEclipseLogo(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    Canvas(modifier = modifier.size(width = size * 2.33f, height = size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.height * 0.36f

        // 1. Amber-Crimson Corona Halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    AmberCorona.copy(alpha = 0.95f),
                    CrimsonFlare.copy(alpha = 0.70f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 1.55f
            ),
            radius = radius * 1.55f,
            center = center
        )

        // 2. 21:9 Horizontal Anamorphic Projector Flare
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    CrimsonFlare.copy(alpha = 0.55f),
                    AmberCorona.copy(alpha = 0.95f),
                    Color.White.copy(alpha = 0.85f),
                    AmberCorona.copy(alpha = 0.95f),
                    CrimsonFlare.copy(alpha = 0.55f),
                    Color.Transparent
                )
            ),
            topLeft = Offset(0f, center.y - 1.5.dp.toPx()),
            size = Size(this.size.width, 3.dp.toPx())
        )

        // 3. Matte Obsidian Eclipse Lens Core
        drawCircle(
            color = OledObsidian,
            radius = radius,
            center = center
        )
    }
}
