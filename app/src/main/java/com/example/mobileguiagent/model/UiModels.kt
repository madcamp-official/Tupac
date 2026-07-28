package com.example.mobileguiagent.model

import android.graphics.Rect
import java.security.MessageDigest

data class UiNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    // 빈 입력창의 안내 문구("받는사람", "비밀번호를 입력하세요"). 값이 들어가기
    // 전에는 이것만이 그 칸이 무엇인지 알려준다. 앱마다 text에 넣기도 하고
    // hintText에 넣기도 해서 둘 다 읽어야 한다(실측: 크롬의 웹 폼은 text와
    // contentDescription이 모두 비어 있고 hintText에만 라벨이 있었다).
    val hint: String? = null,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean?,
    val bounds: Rect,
    val depth: Int,
    // 트리에서 바로 위 노드. 글자를 그 글자를 감싼 단추에 붙이는 데 쓴다.
    // 거리로 짐작하지 않고 부모를 따라가면 되므로 틀릴 일이 없다.
    val parentId: String? = null,
    // 현재 화면에 실제로 보이는지(안드로이드 isVisibleToUser). 가려지거나 화면 밖이면 false.
    val visibleToUser: Boolean = true,
    // 비밀번호 입력창인지. 이 화면을 클라우드 모델에 보내면 안 된다고 판단하는
    // 가장 강한 신호다. 값 자체는 접근성 트리에도 안 나오지만, 이런 칸이 있는
    // 화면이면 주변에 아이디·주민번호 같은 것이 함께 있다고 봐야 한다.
    val password: Boolean = false,
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
                // 슬라이더를 옮기면 화면이 바뀐 것으로 쳐야 한다. 지문에 안 넣으면
                // 밝기를 성공적으로 내려도 "화면 그대로"로 기록되고, 그게 세 번
                // 이어지면 에이전트가 정체로 보고 멈춘다.
                append(node.range?.current)
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
