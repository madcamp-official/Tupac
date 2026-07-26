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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import com.example.mobileguiagent.model.NodeActionResult
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.UiSnapshotStore
import com.example.mobileguiagent.repository.AgentRepository

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

    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val persistSnapshot = Runnable {
        captureSnapshot()
            ?.takeIf { snapshot -> snapshot.packageName == SETTINGS_PACKAGE }
            ?.let { snapshot ->
                runCatching { UiSnapshotStore.write(this, snapshot) }
                    .onFailure { Log.e(TAG, "Unable to persist UI snapshot", it) }
            }
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
        AgentRepository.onServiceConnectionChanged(connected = true)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        AgentRepository.onServiceConnectionChanged(connected = true)

        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        Log.d(
            TAG,
            "package=$packageName, class=$className, type=${event.eventType}",
        )
        AgentRepository.onAccessibilityEvent(
            packageName = packageName,
            className = className,
            eventType = event.eventType,
        )
        if (
            packageName == SETTINGS_PACKAGE &&
            (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                )
        ) {
            snapshotHandler.removeCallbacks(persistSnapshot)
            snapshotHandler.postDelayed(persistSnapshot, SNAPSHOT_SETTLE_DELAY_MS)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        snapshotHandler.removeCallbacks(persistSnapshot)
        val wasActiveService = activeService === this
        if (wasActiveService) {
            activeService = null
            AgentRepository.onServiceConnectionChanged(connected = false)
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
            bounds = bounds,
            depth = depth,
            visibleToUser = node.isVisibleToUser,
            password = node.isPassword,
        )

        for (index in 0 until node.childCount) {
            collectNodes(node.getChild(index), depth + 1, output)
        }
        return output
    }

    fun clickText(
        candidates: List<String>,
        exactOnly: Boolean = false,
    ): NodeActionResult {
        val root = rootInActiveWindow ?: return NodeActionResult(success = false)
        val match = findBestMatch(root, candidates, exactOnly)
            ?: return NodeActionResult(success = false)
        val clickable = findClickableNode(match.node)
            ?: return NodeActionResult(
                success = false,
                matchedText = match.label,
                matchedNodeId = match.node.viewIdResourceName,
            )
        val clicked = clickable.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return NodeActionResult(
            success = clicked,
            matchedText = match.label,
            matchedNodeId = match.node.viewIdResourceName,
            usedClickableAncestor = clickable.usedAncestor,
        )
    }

    /**
     * Resolves a model-selected snapshot node against the current native tree.
     *
     * Traversal IDs can change when Settings refreshes, so an exact visible
     * label is required and the original view ID/class/bounds are used only
     * to rank otherwise identical matches.
     */
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

    fun tap(
        x: Float,
        y: Float,
        onComplete: (Boolean) -> Unit,
    ) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
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
        private const val SETTINGS_PACKAGE = "com.android.settings"
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
