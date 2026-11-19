package com.ayesha.player.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.unit.toSize

/** A thumbnail on screen: where it is (window pixels) and the picture it shows. */
class HeroSource(val bounds: Rect, val image: ImageBitmap?)

/**
 * Where each video thumbnail on screen is, so the player can grow out of the tapped one and
 * shrink back into it (Flutter's Hero 'video_thumb_<path>').
 *
 * Each thumbnail registers a small node while it's attached. Nothing runs per frame or per
 * scroll step: a position is only read when a video opens or closes. (The Compose shared-element
 * system this replaces tracked the hero through every layout pass, and needed the player to be
 * a separate screen.)
 */
object HeroSources {
    private val nodes = HashMap<String, HeroSourceNode>()

    internal fun put(path: String, node: HeroSourceNode) {
        nodes[path] = node
    }

    internal fun remove(path: String, node: HeroSourceNode) {
        if (nodes[path] === node) nodes.remove(path)
    }

    /** [path]'s thumbnail if it's at least partly on screen right now, else null. */
    fun find(path: String): HeroSource? {
        val node = nodes[path] ?: return null
        if (!node.isAttached) return null
        val c = node.requireLayoutCoordinates()
        if (!c.isAttached) return null
        // Rows composed ahead of the viewport (the list's cache window) aren't visible: clipped to nothing.
        val visible = c.boundsInWindow()
        if (visible.width < 1f || visible.height < 1f) return null
        return HeroSource(Rect(c.positionInWindow(), c.size.toSize()), node.image)
    }
}

/** Marks this thumbnail as the hero for [path], showing [image]. */
fun Modifier.heroSource(path: String, image: ImageBitmap?): Modifier = this then HeroSourceElement(path, image)

private data class HeroSourceElement(val path: String, val image: ImageBitmap?) : ModifierNodeElement<HeroSourceNode>() {
    override fun create() = HeroSourceNode(path, image)
    override fun update(node: HeroSourceNode) = node.update(path, image)
}

internal class HeroSourceNode(private var path: String, var image: ImageBitmap?) : Modifier.Node() {
    override fun onAttach() = HeroSources.put(path, this)
    override fun onDetach() = HeroSources.remove(path, this)

    fun update(newPath: String, newImage: ImageBitmap?) {
        image = newImage
        if (newPath == path) return
        HeroSources.remove(path, this)
        path = newPath
        if (isAttached) HeroSources.put(path, this)
    }
}
