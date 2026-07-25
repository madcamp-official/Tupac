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
    // 비밀번호 입력창인지. 이 화면을 클라우드 모델에 보내면 안 된다고 판단하는
    // 가장 강한 신호다. 값 자체는 접근성 트리에도 안 나오지만, 이런 칸이 있는
    // 화면이면 주변에 아이디·주민번호 같은 것이 함께 있다고 봐야 한다.
    val password: Boolean = false,
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
