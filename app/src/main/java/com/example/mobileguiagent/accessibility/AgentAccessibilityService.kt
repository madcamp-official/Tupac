package com.example.mobileguiagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import com.example.mobileguiagent.model.NodeActionResult
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiRange
import com.example.mobileguiagent.model.UiSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentAccessibilityService : AccessibilityService() {
    fun openAndroidSettings(): Boolean = runCatching {
        startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        true
    }.getOrElse {
        Log.e(TAG, "Unable to open Android settings", it)
        false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        activeService = this
        mutableConnectionState.value = true
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        Log.d(
            TAG,
            "package=$packageName, class=$className, type=${event.eventType}",
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        val wasActiveService = activeService === this
        if (wasActiveService) {
            activeService = null
            mutableConnectionState.value = false
        }
        super.onDestroy()
    }

    fun captureSnapshot(): UiSnapshot? {
        val root = rootInActiveWindow ?: return null
        val output = mutableListOf<UiNode>()
        collectNodes(root, output = output)
        return UiSnapshot(
            packageName = root.packageName?.toString().orEmpty(),
            nodes = output,
        )
    }

    fun captureScreen(
        maxDimension: Int,
        onResult: (ScreenCaptureResult) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(
                ScreenCaptureResult.Error(
                    code = "UNSUPPORTED_ANDROID_VERSION",
                    message = "접근성 화면 캡처는 Android 11(API 30) 이상이 필요합니다.",
                ),
            )
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(
                            hardwareBuffer,
                            screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
                        )
                        if (wrapped == null) {
                            onResult(
                                ScreenCaptureResult.Error(
                                    code = "BITMAP_CONVERSION_FAILED",
                                    message = "캡처 버퍼를 이미지로 변환하지 못했습니다.",
                                ),
                            )
                            return
                        }
                        val source = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        val longest = maxOf(source.width, source.height)
                        val scale = if (longest > maxDimension) {
                            maxDimension.toFloat() / longest
                        } else {
                            1f
                        }
                        val outputWidth = (source.width * scale).toInt().coerceAtLeast(1)
                        val outputHeight = (source.height * scale).toInt().coerceAtLeast(1)
                        val outputBitmap = if (
                            outputWidth != source.width || outputHeight != source.height
                        ) {
                            Bitmap.createScaledBitmap(source, outputWidth, outputHeight, true)
                        } else {
                            source
                        }
                        val encoded = ByteArrayOutputStream().use { stream ->
                            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                            stream.toByteArray()
                        }
                        if (outputBitmap !== source) outputBitmap.recycle()
                        source.recycle()
                        onResult(
                            ScreenCaptureResult.Success(
                                jpegBytes = encoded,
                                width = outputWidth,
                                height = outputHeight,
                            ),
                        )
                    } catch (error: Throwable) {
                        onResult(
                            ScreenCaptureResult.Error(
                                code = "SCREENSHOT_PROCESSING_FAILED",
                                message = error.message ?: error::class.java.simpleName,
                            ),
                        )
                    } finally {
                        hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    onResult(
                        ScreenCaptureResult.Error(
                            code = "SCREENSHOT_FAILED_$errorCode",
                            message = "Android 접근성 화면 캡처가 실패했습니다. errorCode=$errorCode",
                        ),
                    )
                }
                },
            )
        } catch (error: SecurityException) {
            onResult(
                ScreenCaptureResult.Error(
                    code = "SCREENSHOT_CAPABILITY_DENIED",
                    message = error.message ?: "화면 캡처 권한이 허용되지 않았습니다.",
                ),
            )
        }
    }

    fun collectNodes(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        output: MutableList<UiNode> = mutableListOf(),
    ): List<UiNode> {
        if (node == null || output.size >= MAX_NODES || depth > MAX_DEPTH) return output

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        output += UiNode(
            id = "node_${output.size}",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            checked = if (node.isCheckable) node.isChecked else null,
            password = node.isPassword,
            focused = node.isFocused,
            inputType = node.inputType,
            bounds = bounds,
            depth = depth,
            visibleToUser = node.isVisibleToUser,
            range = node.rangeInfo?.let { info ->
                UiRange(min = info.min, max = info.max, current = info.current)
            },
        )

        for (index in 0 until node.childCount) {
            collectNodes(node.getChild(index), depth + 1, output)
        }
        return output
    }

    /**
     * Resolves a model-selected snapshot node against the current native tree.
     *
     * Traversal IDs can change when Settings refreshes, so an exact visible
     * label is required and the original view ID/class/bounds are used only
     * to rank otherwise identical matches.
     */
    /**
     * 스냅샷에서 고른 그 입력창에 글자를 넣는다.
     *
     * setTextOnFirstEditable로는 안 된다. 그건 포커스를 보고, 포커스가 없으면
     * 화면의 첫 입력창으로 물러난다. 크롬의 웹 폼은 칸을 눌러도 접근성 포커스가
     * 잡히지 않아서, 세 번 채운 값이 모두 첫 칸에 덮어써졌다(실측: 받는사람 칸에
     * 우편번호가 들어가고 나머지는 비어 있었다).
     *
     * 라벨로 찾을 수도 없다. 빈 입력창은 라벨이 hint에만 있거나 아예 없다.
     * 남는 단서는 위치다. 관찰 직후에 부르므로 화면이 그대로라는 건 이미
     * 확인돼 있고, 그러면 bounds가 그 칸을 가리키는 가장 확실한 표시다.
     */
    fun setTextOnSnapshotNode(
        target: UiNode,
        expectedPackage: String,
        text: String,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != expectedPackage) return false

        val nodes = mutableListOf<IndexedNativeNode>()
        collectIndexedNativeNodes(root, nodes)
        val match = nodes
            .asSequence()
            .filter { item -> item.node.isEditable && item.node.isEnabled }
            .maxByOrNull { item -> boundsSimilarityScore(item.node, target.bounds) }
            ?: return false

        val bounds = Rect()
        match.node.getBoundsInScreen(bounds)
        if (bounds != target.bounds) return false      // 같은 자리가 아니면 넣지 않는다

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return match.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * 스냅샷에서 고른 슬라이더를 그 값으로 옮긴다.
     *
     * 밝기나 음량은 tap으로는 맞출 수 없다. 슬라이더는 누른 좌표가 곧 값이라
     * "절반으로 줄여줘"를 좌표로 환산해야 하는데, 트랙의 시작과 끝이 노드
     * bounds와 일치한다는 보장이 없다(패딩과 손잡이 반지름만큼 어긋난다).
     * ACTION_SET_PROGRESS는 값을 직접 준다.
     *
     * 칸을 찾는 방식은 setTextOnSnapshotNode와 같다. 관찰 직후에 부르므로 화면은
     * 그대로이고, 그러면 bounds가 그 슬라이더를 가리키는 가장 확실한 표시다.
     *
     * 다만 true를 돌려줬다고 값이 반영됐다는 뜻은 아니다. 위젯이 손가락으로
     * 놓는 순간에만 값을 적용하면, 손잡이는 옮겨지고 실제 설정은 그대로다.
     * 실측(삼성 설정 앱 > 디스플레이 > 밝기): 노드의 current는 191 -> 63으로
     * 바뀌었는데 screen_brightness는 191에 머물렀다. 같은 폰의 빠른 설정 패널
     * 밝기 슬라이더는 정상이었다(191 -> 63이 그대로 반영됐다).
     *
     * 구별하는 표시가 하나 있다. 정상인 쪽은 눈금이 0~255로 화면 값과 같았고,
     * 안 먹는 쪽은 0~267386880(255의 2^20배)이라는 제 나름의 눈금을 썼다.
     */
    fun setProgressOnSnapshotNode(
        target: UiNode,
        expectedPackage: String,
        value: Float,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != expectedPackage) return false

        val nodes = mutableListOf<IndexedNativeNode>()
        collectIndexedNativeNodes(root, nodes)
        val match = nodes
            .asSequence()
            .filter { item -> item.node.rangeInfo != null && item.node.isEnabled }
            .maxByOrNull { item -> boundsSimilarityScore(item.node, target.bounds) }
            ?: return false

        val bounds = Rect()
        match.node.getBoundsInScreen(bounds)
        if (bounds != target.bounds) return false      // 같은 자리가 아니면 건드리지 않는다

        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }
        return match.node.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            arguments,
        )
    }

    fun clickSnapshotNode(
        target: UiNode,
        expectedPackage: String,
    ): NodeActionResult {
        val root = rootInActiveWindow ?: return NodeActionResult(success = false)
        if (root.packageName?.toString() != expectedPackage) {
            return NodeActionResult(success = false)
        }

        val targetLabels = listOfNotNull(target.text, target.contentDescription)
            .map(::normalize)
            .filter(String::isNotBlank)
            .toSet()
        if (targetLabels.isEmpty()) return NodeActionResult(success = false)

        val nodes = mutableListOf<IndexedNativeNode>()
        collectIndexedNativeNodes(root, nodes)
        val match = nodes
            .asSequence()
            .filter { item -> item.node.isVisibleToUser && item.node.isEnabled }
            .mapNotNull { item ->
                val labels = nodeLabels(item.node)
                val normalizedLabels = labels.map(::normalize).toSet()
                if (targetLabels.intersect(normalizedLabels).isEmpty()) {
                    null
                } else {
                    val score =
                        (if (item.id == target.id) 1_000 else 0) +
                            (if (
                                !target.viewId.isNullOrBlank() &&
                                item.node.viewIdResourceName == target.viewId
                            ) {
                                100
                            } else {
                                0
                            }) +
                            (if (
                                !target.className.isNullOrBlank() &&
                                item.node.className?.toString() == target.className
                            ) {
                                20
                            } else {
                                0
                            }) +
                            boundsSimilarityScore(item.node, target.bounds)
                    RankedNativeNode(
                        item = item,
                        label = labels.firstOrNull().orEmpty(),
                        score = score,
                    )
                }
            }
            .maxByOrNull(RankedNativeNode::score)
            ?: return NodeActionResult(success = false)

        val clickable = findClickableNode(match.item.node)
            ?: return NodeActionResult(
                success = false,
                matchedText = match.label,
                matchedNodeId = match.item.id,
            )
        val clicked = clickable.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return NodeActionResult(
            success = clicked,
            matchedText = match.label,
            matchedNodeId = match.item.id,
            usedClickableAncestor = clickable.usedAncestor,
        )
    }

    /**
     * 지금 포커스된 입력창에 글자를 넣는다. 포커스가 없으면 첫 번째 입력창.
     *
     * "첫 번째 입력창"만 보면 칸이 여럿인 화면에서 엉뚱한 데로 들어간다. 로그인
     * 화면이 대표적이다 — 비밀번호를 넣으려는데 아이디 칸이 첫 번째라 거기에
     * 들어가고, 아이디가 화면에 그대로 노출된다. 개인정보를 다루려면 어느 칸에
     * 넣는지가 분명해야 하므로 포커스를 먼저 본다.
     */
    fun setTextOnFirstEditable(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { node -> node.isEditable && node.isEnabled }
            ?: findFirstNode(root) { node -> node.isEditable && node.isEnabled }
            ?: return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Sets text only on the exact editable node selected from the latest
     * snapshot. Unlike setTextOnFirstEditable, this has no fallback to another
     * field, which prevents a credential from landing in the wrong input.
     */
    fun setTextOnSnapshotNode(
        target: UiNode,
        expectedPackage: String,
        text: CharArray,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != expectedPackage) return false
        val nodes = mutableListOf<IndexedNativeNode>()
        collectIndexedNativeNodes(root, nodes)
        val exact = nodes.firstOrNull { item ->
            item.id == target.id &&
                item.node.isVisibleToUser &&
                item.node.isEnabled &&
                item.node.isEditable &&
                item.node.isPassword == target.password &&
                (
                    target.viewId.isNullOrBlank() ||
                        item.node.viewIdResourceName == target.viewId
                    ) &&
                (
                    target.className.isNullOrBlank() ||
                        item.node.className?.toString() == target.className
                    )
        }?.node ?: return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                String(text),
            )
        }
        return exact.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Invokes the focused field's IME action (Search/Done/Go) without guessing
     * at an unlabeled icon beside the text field.
     */
    fun submitFirstEditable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val root = rootInActiveWindow ?: return false
        val editable =
            findFirstNode(root) { node ->
                node.isEditable && node.isEnabled && node.isFocused
            } ?: findFirstNode(root) { node -> node.isEditable && node.isEnabled }
            ?: return false
        return editable.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
        )
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun tap(
        x: Float,
        y: Float,
        onComplete: (Boolean) -> Unit,
    ) {
        val path = Path().apply {
            moveTo(x, y)
            // Some Samsung/WebView combinations acknowledge a zero-length
            // gesture but never dispatch a touch event. A sub-pixel segment
            // remains a tap while ensuring the gesture has a real contour.
            lineTo(
                if (x >= TAP_PATH_EPSILON_PX) {
                    x - TAP_PATH_EPSILON_PX
                } else {
                    x + TAP_PATH_EPSILON_PX
                },
                y,
            )
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_GESTURE_DURATION_MS,
                ),
            )
            .build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete(false)
                }
            },
            null,
        )
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 400L,
        onComplete: (Boolean) -> Unit,
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs,
                ),
            )
            .build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete(false)
                }
            },
            null,
        )
    }

    /**
     * 브라우저 주소창에 떠 있는 호스트. 브라우저 화면이 아니거나 못 읽으면 null.
     *
     * 로그인을 웹으로 넘기는 앱이 많다. 쿠팡은 앱에서 로그인을 누르면 크롬
     * 커스텀탭으로 login.coupang.com을 연다. 그때 눈앞의 패키지는
     * com.android.chrome이라, 앱 이름만 봐서는 어느 계정을 넣어야 하는지 알 수
     * 없다. 주소창이 그 답을 들고 있다.
     */
    fun browserHost(): String? {
        val root = rootInActiveWindow ?: return null
        val appPackage = root.packageName?.toString()?.lowercase() ?: return null
        if (BROWSER_MARKERS.none { marker -> marker in appPackage }) return null

        val bar = findFirstNode(root) { node ->
            val viewId = node.viewIdResourceName ?: return@findFirstNode false
            URL_BAR_SUFFIXES.any { suffix -> viewId.endsWith(suffix) }
        } ?: return null
        return hostOf(bar.text?.toString().orEmpty())
    }

    /** 주소창 글자에서 호스트만 남긴다. 주소창은 전체 URL을 보일 때도 있다. */
    private fun hostOf(shown: String): String? {
        val host = shown.trim()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
        return host.takeIf { it.contains('.') && it.none(Char::isWhitespace) }
    }

    fun dumpTreeToLog(): UiSnapshot? {
        val snapshot = captureSnapshot() ?: return null
        snapshot.nodes.forEach { node ->
            Log.d(
                TREE_TAG,
                "${"  ".repeat(node.depth)}${node.id} " +
                    "text=${node.text} desc=${node.contentDescription} " +
                    "class=${node.className} viewId=${node.viewId} " +
                    "clickable=${node.clickable} editable=${node.editable} " +
                    "scrollable=${node.scrollable} enabled=${node.enabled} " +
                    "checked=${node.checked} bounds=${node.bounds.flattenToString()}",
            )
        }
        return snapshot
    }

    private fun findBestMatch(
        root: AccessibilityNodeInfo,
        candidates: List<String>,
        exactOnly: Boolean,
    ): TextMatch? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNativeNodes(root, nodes)
        val visibleNodes = nodes.filter { node -> node.isVisibleToUser }
        val normalizedCandidates = candidates.map { candidate ->
            candidate to normalize(candidate)
        }

        normalizedCandidates.forEach { (candidate, normalizedCandidate) ->
            visibleNodes.firstOrNull { node ->
                nodeLabels(node).any { label -> normalize(label) == normalizedCandidate }
            }?.let { return TextMatch(it, candidate) }
        }

        if (exactOnly) return null

        normalizedCandidates.forEach { (candidate, normalizedCandidate) ->
            visibleNodes.firstOrNull { node ->
                nodeLabels(node).any { label -> normalize(label).contains(normalizedCandidate) }
            }?.let { return TextMatch(it, candidate) }
        }
        return null
    }

    private fun collectNativeNodes(
        node: AccessibilityNodeInfo?,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
    ) {
        if (node == null || output.size >= MAX_NODES || depth > MAX_DEPTH) return
        output += node
        for (index in 0 until node.childCount) {
            collectNativeNodes(node.getChild(index), output, depth + 1)
        }
    }
    private fun collectIndexedNativeNodes(
        node: AccessibilityNodeInfo?,
        output: MutableList<IndexedNativeNode>,
        depth: Int = 0,
    ) {
        if (node == null || output.size >= MAX_NODES || depth > MAX_DEPTH) return
        output += IndexedNativeNode(
            id = "node_${output.size}",
            node = node,
        )
        for (index in 0 until node.childCount) {
            collectIndexedNativeNodes(node.getChild(index), output, depth + 1)
        }
    }

    private fun boundsSimilarityScore(
        node: AccessibilityNodeInfo,
        targetBounds: Rect,
    ): Int {
        val currentBounds = Rect()
        node.getBoundsInScreen(currentBounds)
        if (currentBounds == targetBounds) return 10
        val distance =
            kotlin.math.abs(currentBounds.centerX() - targetBounds.centerX()) +
                kotlin.math.abs(currentBounds.centerY() - targetBounds.centerY())
        return if (distance <= 48) 5 else 0
    }

    private fun findClickableNode(node: AccessibilityNodeInfo): ClickableMatch? {
        var current: AccessibilityNodeInfo? = node
        var usedAncestor = false
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                return ClickableMatch(current, usedAncestor)
            }
            current = current.parent
            usedAncestor = true
        }
        return null
    }

    private fun findFirstNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findFirstNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun nodeLabels(node: AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(::add)
        node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(::add)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('‑', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim()

    private data class TextMatch(
        val node: AccessibilityNodeInfo,
        val label: String,
    )

    private data class ClickableMatch(
        val node: AccessibilityNodeInfo,
        val usedAncestor: Boolean,
    )

    private data class IndexedNativeNode(
        val id: String,
        val node: AccessibilityNodeInfo,
    )

    private data class RankedNativeNode(
        val item: IndexedNativeNode,
        val label: String,
        val score: Int,
    )

    companion object {
        private const val TAG = "AgentAccessibility"
        private const val TREE_TAG = "AgentUiTree"
        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 80
        private const val TAP_PATH_EPSILON_PX = 2f
        private const val TAP_GESTURE_DURATION_MS = 120L

        private val mutableConnectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = mutableConnectionState.asStateFlow()
        private const val SETTINGS_PACKAGE = "com.android.settings"

        /** 주소창을 가진 앱으로 볼 패키지 조각. */
        private val BROWSER_MARKERS = listOf(
            "chrome", "browser", "firefox", "sbrowser", "whale", "opera", "edge", "duckduckgo",
        )

        /** 브라우저마다 주소창의 id가 다르다. 끝부분으로 알아본다. */
        private val URL_BAR_SUFFIXES = listOf(
            ":id/url_bar", ":id/urlbar", ":id/url_view", ":id/toolbar_url",
            ":id/location_bar_edit_text", ":id/mozac_browser_toolbar_url_view",
        )
        private const val SNAPSHOT_SETTLE_DELAY_MS = 180L

        @Volatile
        var activeService: AgentAccessibilityService? = null
            private set
    }
}

sealed interface ScreenCaptureResult {
    data class Success(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
    ) : ScreenCaptureResult

    data class Error(
        val code: String,
        val message: String,
    ) : ScreenCaptureResult
}
