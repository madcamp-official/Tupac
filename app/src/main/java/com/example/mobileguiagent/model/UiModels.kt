package com.example.mobileguiagent.model

import android.graphics.Rect
import java.security.MessageDigest

data class UiNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    /** Strong local-only signal supplied directly by AccessibilityNodeInfo. */
    val password: Boolean = false,
    /** Helps local input handling without exposing the field value. */
    val focused: Boolean = false,
    /** Android text input flags when this is an editable node. */
    val inputType: Int = 0,
    val bounds: Rect,
    val depth: Int,
    /**
     * Android's current visibility judgement. The full snapshot keeps hidden
     * nodes for fingerprint/click consistency, but model-facing adapters must
     * exclude them so the planner cannot choose an occluded or off-screen view.
     */
    val visibleToUser: Boolean = true,
)

/**
 * Keeps actionable controls and human-readable labels while dropping hidden
 * layout/decorative nodes. This is deliberately a serialization-time filter:
 * traversal ids and the snapshot fingerprint continue to use the full tree.
 */
fun UiNode.isMeaningfulForAgent(): Boolean =
    visibleToUser &&
        (
            clickable ||
                editable ||
                scrollable ||
                !text.isNullOrBlank() ||
                !contentDescription.isNullOrBlank()
            )

data class UiSnapshot(
    val packageName: String,
    val nodes: List<UiNode>,
    val capturedAtMillis: Long = System.currentTimeMillis(),
) {
    val fingerprint: ScreenFingerprint by lazy {
        val canonical = buildString {
            append(packageName)
            nodes.forEach { node ->
                append('|')
                append(node.text.orEmpty())
                append('|')
                append(node.contentDescription.orEmpty())
                append('|')
                append(node.viewId.orEmpty())
                append('|')
                append(node.checked)
                append('|')
                append(node.bounds.flattenToString())
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

        ScreenFingerprint(
            packageName = packageName,
            nodeTexts = nodes.mapNotNull { it.text?.takeIf(String::isNotBlank) },
            nodeCount = nodes.size,
            hash = digest,
        )
    }
}

data class ScreenFingerprint(
    val packageName: String,
    val nodeTexts: List<String>,
    val nodeCount: Int,
    val hash: String,
)

data class NodeActionResult(
    val success: Boolean,
    val matchedText: String? = null,
    val matchedNodeId: String? = null,
    val usedClickableAncestor: Boolean = false,
)
