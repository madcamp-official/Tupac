package com.example.mobileguiagent.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dependency-free luminance image used by the close-icon detector.
 *
 * Keeping the detector independent from Android Bitmap makes the same
 * algorithm directly benchmarkable on the JVM. Android only needs to convert
 * the captured bitmap to this compact byte representation.
 */
data class LumaImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    fun valueAt(x: Int, y: Int): Int =
        pixels[y * width + x].toInt() and 0xff
}

data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(other: PixelBounds): Boolean =
        left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
}

data class CloseIconCandidate(
    val bounds: PixelBounds,
    val confidence: Double,
    val contrast: Double,
)

/**
 * Finds small X-shaped components without OCR or a learned vision model.
 *
 * The detector first creates a local-contrast mask, then evaluates compact
 * connected components for support along both diagonals. It intentionally
 * returns candidates rather than clicking: screen context and a subsequent
 * observation still decide whether a candidate is a real close control.
 */
class CloseIconDetector(
    private val maximumCandidates: Int = DEFAULT_MAXIMUM_CANDIDATES,
    private val minimumConfidence: Double = DEFAULT_MINIMUM_CONFIDENCE,
) {
    fun detect(image: LumaImage): List<CloseIconCandidate> {
        if (min(image.width, image.height) < MIN_IMAGE_DIMENSION) return emptyList()

        val contrastMasks = localContrastMasks(image)
        val queue = IntArray(image.pixels.size)
        val candidates = mutableListOf<CloseIconCandidate>()
        val minComponentSize = max(
            MIN_COMPONENT_SIZE_PX,
            min(image.width, image.height) / MIN_COMPONENT_SCALE_DIVISOR,
        )
        val maxComponentSize = min(
            MAX_COMPONENT_SIZE_PX,
            min(image.width, image.height) / MAX_COMPONENT_SCALE_DIVISOR,
        ).coerceAtLeast(minComponentSize)

        contrastMasks.forEach { foreground ->
            val visited = BooleanArray(foreground.size)
            for (seed in foreground.indices) {
                if (!foreground[seed] || visited[seed]) continue
                val component = collectComponent(
                    seed = seed,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    foreground = foreground,
                    visited = visited,
                    queue = queue,
                )
                val width = component.right - component.left
                val height = component.bottom - component.top
                if (
                    width !in minComponentSize..maxComponentSize ||
                    height !in minComponentSize..maxComponentSize
                ) {
                    continue
                }
                val aspect = width.toDouble() / height.coerceAtLeast(1)
                if (aspect !in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO) continue

                scoreComponent(
                    component = component,
                    image = image,
                    foreground = foreground,
                )?.let(candidates::add)
            }
        }

        return nonMaximumSuppression(
            candidates
                .filter { it.confidence >= minimumConfidence }
                .sortedByDescending(CloseIconCandidate::confidence),
        ).take(maximumCandidates)
    }

    private fun localContrastMasks(image: LumaImage): List<BooleanArray> {
        val width = image.width
        val height = image.height
        val integralWidth = width + 1
        val integral = LongArray(integralWidth * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += image.valueAt(x, y)
                integral[(y + 1) * integralWidth + x + 1] =
                    integral[y * integralWidth + x + 1] + rowSum
            }
        }

        val localRadius = max(
            MIN_LOCAL_RADIUS,
            min(width, height) / LOCAL_RADIUS_SCALE_DIVISOR,
        )
        val darkMask = BooleanArray(width * height)
        val lightMask = BooleanArray(width * height)
        for (y in 1 until height - 1) {
            val top = max(0, y - localRadius)
            val bottom = min(height, y + localRadius + 1)
            for (x in 1 until width - 1) {
                val left = max(0, x - localRadius)
                val right = min(width, x + localRadius + 1)
                val sum = integral[bottom * integralWidth + right] -
                    integral[top * integralWidth + right] -
                    integral[bottom * integralWidth + left] +
                    integral[top * integralWidth + left]
                val area = (right - left) * (bottom - top)
                val mean = sum.toDouble() / area.coerceAtLeast(1)
                val value = image.valueAt(x, y)
                val index = y * width + x
                when {
                    mean - value >= MIN_LOCAL_CONTRAST -> darkMask[index] = true
                    value - mean >= MIN_LOCAL_CONTRAST -> lightMask[index] = true
                }
            }
        }
        return listOf(darkMask, lightMask)
    }

    private fun collectComponent(
        seed: Int,
        imageWidth: Int,
        imageHeight: Int,
        foreground: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
    ): Component {
        var read = 0
        var write = 0
        queue[write++] = seed
        visited[seed] = true
        var left = imageWidth
        var top = imageHeight
        var right = 0
        var bottom = 0
        var count = 0

        while (read < write) {
            val index = queue[read++]
            val x = index % imageWidth
            val y = index / imageWidth
            left = min(left, x)
            top = min(top, y)
            right = max(right, x + 1)
            bottom = max(bottom, y + 1)
            count += 1

            for (dy in -1..1) {
                val nextY = y + dy
                if (nextY !in 0 until imageHeight) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = x + dx
                    if (nextX !in 0 until imageWidth) continue
                    val next = nextY * imageWidth + nextX
                    if (foreground[next] && !visited[next]) {
                        visited[next] = true
                        queue[write++] = next
                    }
                }
            }
        }
        return Component(left, top, right, bottom, count)
    }

    private fun scoreComponent(
        component: Component,
        image: LumaImage,
        foreground: BooleanArray,
    ): CloseIconCandidate? {
        val imageWidth = image.width
        val imageHeight = image.height
        val width = component.right - component.left
        val height = component.bottom - component.top
        val area = width * height
        val density = component.pixelCount.toDouble() / area.coerceAtLeast(1)
        if (density !in MIN_COMPONENT_DENSITY..MAX_COMPONENT_DENSITY) return null

        val diagonalTolerance = max(
            MIN_DIAGONAL_TOLERANCE,
            (min(width, height) * DIAGONAL_TOLERANCE_RATIO).toInt(),
        )
        val mainBins = BooleanArray(DIAGONAL_BINS)
        val antiBins = BooleanArray(DIAGONAL_BINS)
        var diagonalPixels = 0
        var centerPixels = 0

        for (y in component.top until component.bottom) {
            for (x in component.left until component.right) {
                if (!foreground[y * imageWidth + x]) continue
                val localX = x - component.left
                val localY = y - component.top
                val expectedMainY =
                    localX.toDouble() * (height - 1) / (width - 1).coerceAtLeast(1)
                val expectedAntiY = (height - 1) - expectedMainY
                val nearMain = abs(localY - expectedMainY) <= diagonalTolerance
                val nearAnti = abs(localY - expectedAntiY) <= diagonalTolerance
                if (nearMain || nearAnti) diagonalPixels += 1
                val bin = (
                    localX.toDouble() / width.coerceAtLeast(1) * DIAGONAL_BINS
                    ).toInt().coerceIn(0, DIAGONAL_BINS - 1)
                if (nearMain) mainBins[bin] = true
                if (nearAnti) antiBins[bin] = true
                if (
                    abs(localX - width / 2) <= diagonalTolerance &&
                    abs(localY - height / 2) <= diagonalTolerance
                ) {
                    centerPixels += 1
                }
            }
        }

        val mainCoverage = mainBins.count(Boolean::not).let {
            1.0 - it.toDouble() / DIAGONAL_BINS
        }
        val antiCoverage = antiBins.count(Boolean::not).let {
            1.0 - it.toDouble() / DIAGONAL_BINS
        }
        val minimumCoverage = min(mainCoverage, antiCoverage)
        val diagonalPurity =
            diagonalPixels.toDouble() / component.pixelCount.coerceAtLeast(1)
        if (
            minimumCoverage < MIN_DIAGONAL_COVERAGE ||
            diagonalPurity < MIN_DIAGONAL_PURITY ||
            centerPixels == 0
        ) {
            return null
        }
        val templateContrast = xTemplateContrast(image, component) ?: return null

        val aspect = width.toDouble() / height.coerceAtLeast(1)
        val aspectScore = 1.0 -
            (abs(aspect - 1.0) / (MAX_ASPECT_RATIO - 1.0)).coerceIn(0.0, 1.0)
        val centerX = (component.left + component.right) / 2.0
        val centerY = (component.top + component.bottom) / 2.0
        val horizontalEdgeScore =
            abs(centerX - imageWidth / 2.0) / (imageWidth / 2.0).coerceAtLeast(1.0)
        val upperScreenScore =
            (1.0 - centerY / imageHeight.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val locationScore = (horizontalEdgeScore + upperScreenScore) / 2.0
        val centerScore =
            (centerPixels.toDouble() / max(1, diagonalTolerance * diagonalTolerance))
                .coerceIn(0.0, 1.0)
        val confidence = (
            minimumCoverage * COVERAGE_WEIGHT +
                diagonalPurity * PURITY_WEIGHT +
                templateContrast * TEMPLATE_WEIGHT +
                aspectScore * ASPECT_WEIGHT +
                centerScore * CENTER_WEIGHT +
                locationScore * LOCATION_WEIGHT
            ).coerceIn(0.0, 1.0)

        val bounds = PixelBounds(
            left = max(0, component.left - BOUNDS_PADDING),
            top = max(0, component.top - BOUNDS_PADDING),
            right = min(imageWidth, component.right + BOUNDS_PADDING),
            bottom = min(imageHeight, component.bottom + BOUNDS_PADDING),
        )
        return CloseIconCandidate(
            bounds = bounds,
            confidence = confidence,
            contrast = templateContrast,
        )
    }

    /**
     * Connected-component edges alone can make a plus sign resemble a square
     * X. Verify against the original luminance: both diagonals must differ
     * from the surrounding patch in the same direction and by a useful amount.
     */
    private fun xTemplateContrast(
        image: LumaImage,
        component: Component,
    ): Double? {
        val width = component.right - component.left
        val height = component.bottom - component.top
        val tolerance = max(
            MIN_TEMPLATE_TOLERANCE,
            (min(width, height) * TEMPLATE_TOLERANCE_RATIO).toInt(),
        )
        var mainSum = 0L
        var mainCount = 0
        var antiSum = 0L
        var antiCount = 0
        var backgroundSum = 0L
        var backgroundCount = 0

        for (y in component.top until component.bottom) {
            for (x in component.left until component.right) {
                val localX = x - component.left
                val localY = y - component.top
                val expectedMainY =
                    localX.toDouble() * (height - 1) / (width - 1).coerceAtLeast(1)
                val expectedAntiY = (height - 1) - expectedMainY
                val mainDistance = abs(localY - expectedMainY)
                val antiDistance = abs(localY - expectedAntiY)
                val value = image.valueAt(x, y)
                when {
                    mainDistance <= tolerance && antiDistance > tolerance -> {
                        mainSum += value
                        mainCount += 1
                    }

                    antiDistance <= tolerance && mainDistance > tolerance -> {
                        antiSum += value
                        antiCount += 1
                    }

                    mainDistance > tolerance * BACKGROUND_DISTANCE_MULTIPLIER &&
                        antiDistance > tolerance * BACKGROUND_DISTANCE_MULTIPLIER -> {
                        backgroundSum += value
                        backgroundCount += 1
                    }
                }
            }
        }
        if (mainCount == 0 || antiCount == 0 || backgroundCount == 0) return null
        val mainMean = mainSum.toDouble() / mainCount
        val antiMean = antiSum.toDouble() / antiCount
        val backgroundMean = backgroundSum.toDouble() / backgroundCount
        val mainDelta = mainMean - backgroundMean
        val antiDelta = antiMean - backgroundMean
        if (mainDelta * antiDelta <= 0.0) return null
        val minimumContrast = min(abs(mainDelta), abs(antiDelta)) / 255.0
        return minimumContrast.takeIf { it >= MIN_TEMPLATE_CONTRAST }
    }

    private fun nonMaximumSuppression(
        sorted: List<CloseIconCandidate>,
    ): List<CloseIconCandidate> {
        val kept = mutableListOf<CloseIconCandidate>()
        sorted.forEach { candidate ->
            if (kept.none { existing -> overlapRatio(candidate.bounds, existing.bounds) >= 0.4 }) {
                kept += candidate
            }
        }
        return kept
    }

    private fun overlapRatio(first: PixelBounds, second: PixelBounds): Double {
        if (!first.intersects(second)) return 0.0
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = max(0, right - left) * max(0, bottom - top)
        val smaller = min(
            first.width * first.height,
            second.width * second.height,
        ).coerceAtLeast(1)
        return intersection.toDouble() / smaller
    }

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixelCount: Int,
    )

    private companion object {
        const val DEFAULT_MAXIMUM_CANDIDATES = 3
        const val DEFAULT_MINIMUM_CONFIDENCE = 0.68
        const val MIN_IMAGE_DIMENSION = 64
        const val MIN_COMPONENT_SIZE_PX = 8
        const val MAX_COMPONENT_SIZE_PX = 112
        const val MIN_COMPONENT_SCALE_DIVISOR = 100
        const val MAX_COMPONENT_SCALE_DIVISOR = 4
        const val MIN_LOCAL_RADIUS = 5
        const val LOCAL_RADIUS_SCALE_DIVISOR = 50
        const val MIN_LOCAL_CONTRAST = 24.0
        const val MIN_ASPECT_RATIO = 0.58
        const val MAX_ASPECT_RATIO = 1.72
        const val MIN_COMPONENT_DENSITY = 0.06
        const val MAX_COMPONENT_DENSITY = 0.90
        const val MIN_DIAGONAL_TOLERANCE = 1
        const val DIAGONAL_TOLERANCE_RATIO = 0.16
        const val DIAGONAL_BINS = 8
        const val MIN_DIAGONAL_COVERAGE = 0.625
        const val MIN_DIAGONAL_PURITY = 0.52
        const val COVERAGE_WEIGHT = 0.42
        const val PURITY_WEIGHT = 0.18
        const val TEMPLATE_WEIGHT = 0.20
        const val ASPECT_WEIGHT = 0.08
        const val CENTER_WEIGHT = 0.05
        const val LOCATION_WEIGHT = 0.07
        const val MIN_TEMPLATE_TOLERANCE = 1
        const val TEMPLATE_TOLERANCE_RATIO = 0.10
        const val BACKGROUND_DISTANCE_MULTIPLIER = 2
        const val MIN_TEMPLATE_CONTRAST = 0.10
        const val BOUNDS_PADDING = 3
    }
}
