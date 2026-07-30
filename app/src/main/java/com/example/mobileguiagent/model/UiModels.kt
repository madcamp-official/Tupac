package com.example.mobileguiagent.model

import android.graphics.Rect
import java.security.MessageDigest

data class UiNode(
    val id: String,
    /**
     * Stable only inside this snapshot. It lets runtime policies reason about
     * dialog ownership and clickable ancestors without app-specific node IDs.
     */
    val parentId: String? = null,
    val text: String?,
    val contentDescription: String?,
    // 빈 입력창의 안내 문구("받는사람", "비밀번호를 입력하세요"). 값이 들어가기
    // 전에는 이것만이 그 칸이 무엇인지 알려준다. 앱마다 text에 넣기도 하고
    // hintText에 넣기도 해서 둘 다 읽어야 한다(실측: 크롬의 웹 폼은 text와
    // contentDescription이 모두 비어 있고 hintText에만 라벨이 있었다).
    val hint: String? = null,
    val className: String?,
    /** Semantic widget role reported by Android/WebView, e.g. "drop-down list". */
    val roleDescription: String? = null,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    /** Selection state exposed by AccessibilityNodeInfo for tabs/list choices. */
    val selected: Boolean = false,
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
    // 슬라이더·진행바라면 그 값의 범위. 밝기와 음량이 대표적이다.
    val range: UiRange? = null,
)

/**
 * 슬라이더가 가질 수 있는 값의 범위와 지금 값.
 *
 * 이게 없으면 슬라이더는 조작할 방법이 아예 없다. tap은 좌표 한 점을 누르는
 * 것이라 "절반으로" 같은 걸 맞출 수가 없고, 접근성 트리의 라벨에도 값이 안
 * 나오는 경우가 많다. 범위를 알면 ACTION_SET_PROGRESS로 정확한 값을 넣는다.
 */
data class UiRange(
    val min: Float,
    val max: Float,
    val current: Float,
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
                range != null ||
                !text.isNullOrBlank() ||
                !contentDescription.isNullOrBlank() ||
                !hint.isNullOrBlank()
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
                append(node.parentId.orEmpty())
                append('|')
                append(node.text.orEmpty())
                append('|')
                append(node.contentDescription.orEmpty())
                append('|')
                append(node.viewId.orEmpty())
                append('|')
                append(node.checked)
                append('|')
                append(node.selected)
                append('|')
                // 슬라이더를 옮기면 화면이 바뀐 것으로 쳐야 한다. 지문에 안 넣으면
                // 밝기를 성공적으로 내려도 "화면 그대로"로 기록되고, 그게 세 번
                // 이어지면 에이전트가 정체로 보고 멈춘다.
                append(node.range?.current)
                append('|')
                append(node.bounds.left)
                append(',')
                append(node.bounds.top)
                append(',')
                append(node.bounds.right)
                append(',')
                append(node.bounds.bottom)
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
