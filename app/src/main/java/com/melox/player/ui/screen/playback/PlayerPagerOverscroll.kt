package com.melox.player.ui.screen.playback

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/** Transfers one native effect node between retained pager pages. */
internal class PlayerPagerOverscroll(private val effect: OverscrollEffect) {
    private var owner: VisualNode? = null

    fun Modifier.visualModifier(selected: Boolean): Modifier =
        then(VisualElement(this@PlayerPagerOverscroll, selected))

    private fun claim(node: VisualNode) {
        if (owner === node) return
        owner?.detachEffect()
        owner = node
        node.attachEffect(effect)
    }

    private fun release(node: VisualNode) {
        if (owner !== node) return
        node.detachEffect()
        owner = null
    }

    private data class VisualElement(
        val overscroll: PlayerPagerOverscroll,
        val selected: Boolean,
    ) : ModifierNodeElement<VisualNode>() {
        override fun create() = VisualNode(overscroll, selected)
        override fun update(node: VisualNode) = node.update(overscroll, selected)
        override fun InspectorInfo.inspectableProperties() {
            name = "playerPagerOverscroll"
            properties["selected"] = selected
        }
    }

    private class VisualNode(
        private var overscroll: PlayerPagerOverscroll,
        private var selected: Boolean,
    ) : DelegatingNode() {
        private var effect: OverscrollEffect? = null

        fun attachEffect(value: OverscrollEffect) {
            delegate(value.node)
            effect = value
        }

        fun detachEffect() {
            effect?.let { undelegate(it.node) }
            effect = null
        }

        fun update(value: PlayerPagerOverscroll, isSelected: Boolean) {
            if (overscroll !== value || !isSelected) overscroll.release(this)
            overscroll = value
            selected = isSelected
            if (isAttached && selected) overscroll.claim(this)
        }

        override fun onAttach() {
            if (selected) overscroll.claim(this)
        }

        override fun onDetach() = overscroll.release(this)
    }
}
