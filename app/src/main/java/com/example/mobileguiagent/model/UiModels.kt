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
    val bounds: Rect,
    val depth: Int,
    // 현재 화면에 실제로 보이는지(안드로이드 isVisibleToUser). 가려지거나 화면 밖이면 false.
    val visibleToUser: Boolean = true,
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

data class PocMetric(
    val task: String,
    val success: Boolean,
    val steps: Int,
    val latencyMs: Long,
    val nodeActionUsed: Boolean,
    val coordinateActionUsed: Boolean,
    val screenChanged: Boolean,
    val failureCode: String?,
)
