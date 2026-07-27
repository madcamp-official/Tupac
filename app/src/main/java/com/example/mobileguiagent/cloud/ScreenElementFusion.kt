package com.example.mobileguiagent.cloud

import android.graphics.Rect
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.model.isMeaningfulForAgent
import com.example.mobileguiagent.ocr.OcrScreenObservation

enum class ScreenElementSource {
    ACCESSIBILITY,
    OCR,
    ICON,
}

/**
 * One model-facing screen element after accessibility and OCR observations
 * have been spatially and semantically deduplicated.
 */
data class ScreenElement(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val nodeId: String?,
    val sources: Set<ScreenElementSource>,
    /** Confidence is present only for locally inferred visual candidates. */
    val confidence: Double? = null,
)

/**
 * Accessibility remains authoritative for actions and state. OCR only enriches
 * matching nodes or adds text that the accessibility tree did not expose.
 */
object ScreenElementFusion {
    fun shouldRunOcr(snapshot: UiSnapshot): Boolean {
        val meaningful = snapshot.nodes.filter { node ->
            node.enabled && node.isMeaningfulForAgent()
        }
        val readableLabels = meaningful.count { node ->
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        }
        val webViewPresent = meaningful.any { node ->
            node.className?.contains("WebView", ignoreCase = true) == true
        }
        return webViewPresent || readableLabels < MIN_READABLE_LABELS
    }

    fun fromAccessibility(snapshot: UiSnapshot): List<ScreenElement> =
        snapshot.nodes
            .asSequence()
            .filter { node -> node.enabled && node.isMeaningfulForAgent() }
            .map { node -> node.toScreenElement() }
            .toList()

    fun fuse(
        snapshot: UiSnapshot,
        ocr: OcrScreenObservation,
        deviceWidth: Int,
        deviceHeight: Int,
    ): List<ScreenElement> {
        val elements = fromAccessibility(snapshot).toMutableList()
        var nextOcrId = 0
        ocr.lines.forEach { line ->
            val ocrText = line.text.trim().take(MAX_TEXT_LENGTH)
            if (ocrText.isBlank()) return@forEach
            val ocrBounds = ocr.boundsInDevicePixels(
                line = line,
                deviceWidth = deviceWidth,
                deviceHeight = deviceHeight,
            )
            val matchIndex = elements.indexOfFirst { element ->
                labelsMatch(element, ocrText) &&
                    spatiallyMatches(element.bounds, ocrBounds)
            }
            if (matchIndex >= 0) {
                val match = elements[matchIndex]
                elements[matchIndex] = match.copy(
                    text = match.text?.takeIf(String::isNotBlank) ?: ocrText,
                    sources = match.sources + ScreenElementSource.OCR,
                )
            } else if (
                elements.none { element ->
                    ScreenElementSource.OCR in element.sources &&
                        normalized(element.text.orEmpty()) == normalized(ocrText) &&
                        spatiallyMatches(element.bounds, ocrBounds)
                }
            ) {
                elements += ScreenElement(
                    id = "ocr_${nextOcrId++}",
                    text = ocrText,
                    contentDescription = null,
                    viewId = null,
                    bounds = ocrBounds,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    nodeId = null,
                    sources = setOf(ScreenElementSource.OCR),
                )
            }
        }
        ocr.closeIcons.forEachIndexed { index, icon ->
            val iconBounds = ocr.boundsInDevicePixels(
                candidate = icon,
                deviceWidth = deviceWidth,
                deviceHeight = deviceHeight,
            )
            val closeLabelMatch = elements.indexOfFirst { element ->
                spatiallyMatches(element.bounds, iconBounds) &&
                    listOfNotNull(
                        element.text,
                        element.contentDescription,
                        element.viewId,
                    ).any { label -> CLOSE_LABELS.any(label.lowercase()::contains) }
            }
            if (closeLabelMatch >= 0) {
                val match = elements[closeLabelMatch]
                elements[closeLabelMatch] = match.copy(
                    sources = match.sources + ScreenElementSource.ICON,
                    confidence = maxOf(match.confidence ?: 0.0, icon.confidence),
                )
            } else {
                elements += ScreenElement(
                    id = "icon_close_$index",
                    text = null,
                    contentDescription = "닫기 아이콘 후보",
                    viewId = null,
                    bounds = iconBounds,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    nodeId = null,
                    sources = setOf(ScreenElementSource.ICON),
                    confidence = icon.confidence,
                )
            }
        }
        return elements
    }

    /**
     * OCR-only text must pass the same on-device privacy router before it can
     * be serialized into a cloud request.
     */
    fun privacySnapshot(
        original: UiSnapshot,
        elements: List<ScreenElement>,
    ): UiSnapshot = UiSnapshot(
        packageName = original.packageName,
        nodes = elements.mapIndexed { index, element ->
            UiNode(
                id = element.nodeId ?: "privacy_ocr_$index",
                text = element.text,
                contentDescription = element.contentDescription,
                className = null,
                viewId = element.viewId,
                clickable = element.clickable,
                editable = element.editable,
                scrollable = element.scrollable,
                enabled = true,
                checked = null,
                bounds = Rect(element.bounds),
                depth = 0,
                visibleToUser = true,
            )
        },
    )

    private fun UiNode.toScreenElement(): ScreenElement = ScreenElement(
        id = id,
        text = text,
        contentDescription = contentDescription,
        viewId = viewId,
        bounds = Rect(bounds),
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        nodeId = id,
        sources = setOf(ScreenElementSource.ACCESSIBILITY),
    )

    private fun labelsMatch(
        element: ScreenElement,
        ocrText: String,
    ): Boolean {
        val ocrLabel = normalized(ocrText)
        if (ocrLabel.isBlank()) return false
        return listOfNotNull(
            element.text,
            element.contentDescription,
        ).any { candidate ->
            val nodeLabel = normalized(candidate)
            nodeLabel == ocrLabel ||
                (
                    minOf(nodeLabel.length, ocrLabel.length) >= MIN_CONTAINMENT_LENGTH &&
                        (nodeLabel.contains(ocrLabel) || ocrLabel.contains(nodeLabel))
                    )
        }
    }

    private fun spatiallyMatches(
        first: Rect,
        second: Rect,
    ): Boolean {
        if (first.isEmpty || second.isEmpty) return false
        if (first.contains(second.centerX(), second.centerY())) return true
        if (second.contains(first.centerX(), first.centerY())) return true
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return false
        val intersectionArea = intersection.width().toLong() * intersection.height()
        val smallerArea = minOf(
            first.width().toLong() * first.height(),
            second.width().toLong() * second.height(),
        ).coerceAtLeast(1)
        return intersectionArea.toDouble() / smallerArea >= MIN_OVERLAP_OF_SMALLER
    }

    private fun normalized(value: String): String =
        value.lowercase().replace(NON_SEMANTIC, "")

    private const val MIN_READABLE_LABELS = 6
    private const val MIN_CONTAINMENT_LENGTH = 3
    private const val MIN_OVERLAP_OF_SMALLER = 0.5
    private const val MAX_TEXT_LENGTH = 160
    private val NON_SEMANTIC = Regex("""[\s\p{P}\p{S}]+""")
    private val CLOSE_LABELS = listOf(
        "닫기",
        "close",
        "dismiss",
        "cancel",
    )
}
