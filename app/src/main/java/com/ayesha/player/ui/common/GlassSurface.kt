package com.ayesha.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ayesha.player.core.theme.PlayerControlsStyle

// glass_surface.dart's gradients: a rim-light sheen easing into a darker body.
private val TintGradient = Brush.verticalGradient(0f to Color(0x24FFFFFF), 0.4f to Color(0x4D000000), 1f to Color(0x6B000000))
private val TintGradientStrong = Brush.verticalGradient(0f to Color(0x1AFFFFFF), 0.4f to Color(0x99000000), 1f to Color(0xB3000000))
private val FrostGradient = Brush.verticalGradient(0f to Color(0x40FFFFFF), 0.45f to Color(0x1F000000), 1f to Color(0x42000000))
private val FrostGradientStrong = Brush.verticalGradient(0f to Color(0x4DFFFFFF), 0.45f to Color(0x3D000000), 1f to Color(0x5C000000))
private val BorderTint = Color(0x1FFFFFFF)
private val BorderFrost = Color(0x4DFFFFFF)

/**
 * The player's translucent control surface (glass_surface.dart). [shape] defaults to a circle.
 *
 * "Frosted" uses the lighter frosted gradient and border. The real backdrop blur of the
 * Flutter build isn't possible here: the video is a SurfaceView, composited by the system
 * outside the app's own drawing, so nothing drawn by the app can sample or blur it.
 */
@Composable
fun GlassSurface(
    style: PlayerControlsStyle,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    strong: Boolean = false,
    borderColor: Color? = null,
    withShadow: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val frosted = style == PlayerControlsStyle.Frosted
    val brush = when {
        frosted && strong -> FrostGradientStrong
        frosted -> FrostGradient
        strong -> TintGradientStrong
        else -> TintGradient
    }
    Box(
        modifier
            .then(if (withShadow && !frosted) Modifier.shadow(18.dp, shape, ambientColor = Color(0x59000000), spotColor = Color(0x59000000)) else Modifier)
            .clip(shape)
            .background(brush)
            .border(1.dp, borderColor ?: if (frosted) BorderFrost else BorderTint, shape),
        contentAlignment = contentAlignment,
        content = content,
    )
}
