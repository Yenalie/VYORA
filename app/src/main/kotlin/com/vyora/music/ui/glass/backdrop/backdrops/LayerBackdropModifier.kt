package com.vyora.music.ui.glass.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.vyora.music.ui.glass.backdrop.internal.recordLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Modifier.layerBackdrop(
    backdrop: LayerBackdrop,
    frozen: () -> Boolean = { false }
): Modifier = this then LayerBackdropElement(backdrop, frozen)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val frozen: () -> Boolean
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, frozen)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.frozen = frozen
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (frozen != other.frozen) return false

        return true
    }

    override fun hashCode(): Int {
        return 31 * backdrop.hashCode() + frozen.hashCode()
    }
}

private const val RecordBudgetNs = 200_000_000L

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var frozen: () -> Boolean
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    private var lastRecordNs = 0L

    private var trailingRecordQueued = false

    private fun queueTrailingRecord(delayNs: Long) {
        if (trailingRecordQueued) return
        trailingRecordQueued = true
        coroutineScope.launch {
            delay(delayNs / 1_000_000L + 1L)
            trailingRecordQueued = false
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        backdrop.staticRegions.clear()

        if (frozen() && backdrop.hasRecording) {
            backdrop.onDraw(this@draw)
            return
        }

        if (backdrop.hasRecording) {
            val sinceLastRecord = System.nanoTime() - lastRecordNs
            if (sinceLastRecord < RecordBudgetNs) {
                backdrop.onDraw(this@draw)
                queueTrailingRecord(RecordBudgetNs - sinceLastRecord)
                return
            }
        }

        lastRecordNs = System.nanoTime()
        recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        drawLayer(backdrop.graphicsLayer)
        backdrop.contentRecorded()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
