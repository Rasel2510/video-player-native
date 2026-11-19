package com.ayesha.player.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement

import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.LibraryCardStyle

/** Curves.easeOutQuad. */
private val EaseOutQuad = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

// ── Liquid Glass ─────────────────────────────────────────────────────────────
// Only the card is glass (no colored backdrop behind the list). On a plain background the look
// comes from three layers: a frosted body that is brighter at the top and fades toward the
// background at the bottom, a fine edge that defines the shape, and a specular highlight along
// the top rim that fades out by mid-height, like light catching the edge of Apple's Liquid Glass.

// Light: a near-background body with a diagonal sheen (lighter top-left, a touch darker
// bottom-right), so it reads as glass rather than a flat white card. The shape is drawn by a fine
// dark outline plus a bright bevel that catches light at both ends (top-left and bottom-right),
// with a soft shine across the top and a faint shade inside the bottom edge for thickness.
private val LightBody = Brush.linearGradient(0f to Color(0x8CFFFFFF), 0.55f to Color(0x2EFFFFFF), 1f to Color(0x0D000000))
private val LightShine = Brush.verticalGradient(0f to Color(0xA6FFFFFF), 0.38f to Color(0x00FFFFFF))
private val LightShade = Brush.verticalGradient(0.72f to Color(0x00000000), 1f to Color(0x12000000))
private val LightOutline = Color(0x24000000)
private val LightBevel = Brush.linearGradient(0f to Color(0xFFFFFFFF), 0.5f to Color(0x4DFFFFFF), 1f to Color(0xD9FFFFFF))
private val GlassFillDark = Brush.verticalGradient(0f to Color(0x2EFFFFFF), 0.45f to Color(0x17FFFFFF), 1f to Color(0x0DFFFFFF))
private val GlassEdgeDark = Color(0x21FFFFFF)
private val GlassHighlightDark = Brush.verticalGradient(0f to Color(0x8CFFFFFF), 0.4f to Color(0x00FFFFFF))

/**
 * The glass card: body, edge and top highlight; three cheap draws with brushes built once per
 * size. No drop shadow: under a see-through card it shows through and dirties the glass, and on a
 * budget GPU it was the layer that pushed scrolling past the 16 ms frame budget.
 */
private fun Modifier.liquidGlass(dark: Boolean): Modifier = drawWithCache {
    val radius = 14.dp.toPx() // AppRadius.md
    val corner = CornerRadius(radius, radius)
    val hairline = Stroke(1.dp.toPx())
    val half = hairline.width / 2
    val edgeSize = Size(size.width - hairline.width, size.height - hairline.width)
    // The highlight sits just inside the edge, so the two lines read as a bevel.
    val inner = hairline.width * 1.5f
    val innerSize = Size(size.width - inner * 2, size.height - inner * 2)
    val innerCorner = CornerRadius(radius - inner, radius - inner)
    val bevel = Stroke(1.2.dp.toPx())
    onDrawBehind {
        if (dark) {
            drawRoundRect(GlassFillDark, cornerRadius = corner)
            drawRoundRect(GlassEdgeDark, topLeft = Offset(half, half), size = edgeSize, cornerRadius = corner, style = hairline)
            drawRoundRect(GlassHighlightDark, topLeft = Offset(inner, inner), size = innerSize, cornerRadius = innerCorner, style = hairline)
        } else {
            drawRoundRect(LightBody, cornerRadius = corner)
            drawRoundRect(LightShine, cornerRadius = corner)
            drawRoundRect(LightShade, cornerRadius = corner)
            drawRoundRect(LightOutline, topLeft = Offset(half, half), size = edgeSize, cornerRadius = corner, style = hairline)
            drawRoundRect(LightBevel, topLeft = Offset(inner, inner), size = innerSize, cornerRadius = innerCorner, style = bevel)
        }
    }
}

/**
 * animated_squish_card.dart: shrinks to 97 % while pressed (150 ms in, 200 ms out). One node per
 * card that listens to the card's own press events: no state, no effect and no animation object
 * is created until the card is actually pressed, and the scale is applied in the card's layer,
 * so pressing never recomposes it. (collectIsPressedAsState + animateFloatAsState cost two
 * effects, a channel and an animation per row created while scrolling.)
 */
private fun Modifier.squishOnPress(interaction: InteractionSource): Modifier = this then SquishElement(interaction)

private data class SquishElement(val interaction: InteractionSource) : ModifierNodeElement<SquishNode>() {
    override fun create() = SquishNode(interaction)
    override fun update(node: SquishNode) = node.update(interaction)
}

private class SquishNode(private var interaction: InteractionSource) : Modifier.Node(), LayoutModifierNode {
    private var scale by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)
    private var listener: Job? = null

    override fun onAttach() = listen()

    fun update(source: InteractionSource) {
        if (source == interaction) return
        interaction = source
        listener?.cancel()
        listen()
    }

    private fun listen() {
        listener = coroutineScope.launch {
            var presses = 0
            interaction.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                // Created on the first press; the layer block reads it through state, so it
                // starts following the animation from here on.
                val a = scale ?: Animatable(1f).also { scale = it }
                val down = presses > 0
                launch { a.animateTo(if (down) 0.97f else 1f, tween(if (down) 150 else 200, easing = EaseOutQuad)) }
            }
        }
    }

    // One instance for the node's lifetime: a new lambda per placement (every scroll frame)
    // would make Compose update and re-run each card's layer block on every frame.
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val sc = scale?.value ?: 1f
        scaleX = sc
        scaleY = sc
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) {
            // Its own layer either way, so the card's drawing is cached and only moved while scrolling.
            p.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}

/**
 * tintable_card.dart + animated_squish_card.dart: the library's folder/video card.
 * Standard = opaque surface; Tinted = Liquid Glass (see above). Shrinks to 97 % while pressed
 * (150 ms in, 200 ms out), with a ripple.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TintableCard(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = AppTheme.colors
    val tinted = LocalAppearance.current.cardStyle == LibraryCardStyle.Tinted
    val interaction = remember { MutableInteractionSource() }
    val shape = AppRadius.md

    Box(
        modifier
            .fillMaxWidth()
            .squishOnPress(interaction)
            .then(if (tinted) Modifier.liquidGlass(c.isDark) else Modifier.background(c.surface, shape))
            .clip(shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = c.accent),
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            ),
    ) {
        content()
    }
}
