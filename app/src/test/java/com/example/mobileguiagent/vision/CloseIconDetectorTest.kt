package com.example.mobileguiagent.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class CloseIconDetectorTest {
    private val detector = CloseIconDetector()

    @Test
    fun detectsDarkCloseIconOnLightPopup() {
        val image = fixture(
            background = 238,
            foreground = 24,
            centerX = 430,
            centerY = 92,
            radius = 13,
            thickness = 3,
        )

        val candidate = detector.detect(image).firstOrNull()

        requireNotNull(candidate)
        assertNear(candidate.bounds.centerX, 430, tolerance = 6)
        assertNear(candidate.bounds.centerY, 92, tolerance = 6)
        assertTrue(candidate.confidence >= 0.68)
    }

    @Test
    fun detectsLightCloseIconOnDarkPopup() {
        val image = fixture(
            background = 32,
            foreground = 242,
            centerX = 44,
            centerY = 128,
            radius = 16,
            thickness = 4,
        )

        val candidate = detector.detect(image).firstOrNull()

        requireNotNull(candidate)
        assertNear(candidate.bounds.centerX, 44, tolerance = 7)
        assertNear(candidate.bounds.centerY, 128, tolerance = 7)
    }

    @Test
    fun ignoresPlusAndSingleDiagonal() {
        val canvas = LumaCanvas(WIDTH, HEIGHT, 236)
        canvas.drawPlus(430, 92, radius = 14, thickness = 3, color = 20)
        canvas.drawDiagonal(40, 160, radius = 14, thickness = 3, color = 20)

        val candidates = detector.detect(canvas.toImage())

        assertEquals(emptyList<CloseIconCandidate>(), candidates)
    }

    @Test
    fun detectsCloseIconAmongUiLikeClutter() {
        val canvas = LumaCanvas(WIDTH, HEIGHT, 246)
        for (row in 0 until 8) {
            val top = 180 + row * 74
            canvas.drawRectBorder(28, top, WIDTH - 28, top + 48, 2, 182)
            canvas.drawHorizontal(52, WIDTH - 90, top + 22, 2, 92)
        }
        canvas.drawPlus(86, 84, radius = 15, thickness = 3, color = 20)
        canvas.drawDiagonal(210, 116, radius = 18, thickness = 3, color = 20)
        canvas.drawX(428, 86, radius = 14, thickness = 3, color = 20)

        val candidates = detector.detect(canvas.toImage())

        require(candidates.isNotEmpty())
        assertNear(candidates.first().bounds.centerX, 428, tolerance = 7)
        assertNear(candidates.first().bounds.centerY, 86, tolerance = 7)
    }

    @Test
    fun benchmarkLaptopShapeDetection() {
        val images = listOf(
            fixture(238, 24, 430, 92, 13, 3),
            fixture(32, 242, 44, 128, 16, 4),
            fixture(250, 10, 400, 380, 20, 4),
        )
        repeat(WARMUP_RUNS) {
            images.forEach(detector::detect)
        }
        val samples = LongArray(BENCHMARK_RUNS)
        repeat(BENCHMARK_RUNS) { index ->
            val image = images[index % images.size]
            val started = System.nanoTime()
            val result = detector.detect(image)
            samples[index] = System.nanoTime() - started
            require(result.isNotEmpty())
        }
        samples.sort()
        val p50 = samples.percentile(0.50) / 1_000_000.0
        val p95 = samples.percentile(0.95) / 1_000_000.0
        val average = samples.average() / 1_000_000.0
        println(
            "CLOSE_ICON_BENCHMARK width=$WIDTH height=$HEIGHT " +
                "runs=$BENCHMARK_RUNS avg_ms=%.3f p50_ms=%.3f p95_ms=%.3f"
                    .format(average, p50, p95),
        )
        writePreview(images.first(), detector.detect(images.first()))
    }

    private fun fixture(
        background: Int,
        foreground: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
        thickness: Int,
    ): LumaImage {
        val canvas = LumaCanvas(WIDTH, HEIGHT, background)
        // Two large neutral panels make the fixture closer to a real screen
        // without adding an artificial cue specific to the target X.
        canvas.fillRect(24, 54, WIDTH - 24, HEIGHT - 72, background)
        canvas.drawX(centerX, centerY, radius, thickness, foreground)
        return canvas.toImage()
    }

    private fun assertNear(actual: Int, expected: Int, tolerance: Int) {
        assertTrue(
            "expected $actual to be within $tolerance px of $expected",
            abs(actual - expected) <= tolerance,
        )
    }

    private fun LongArray.percentile(fraction: Double): Long =
        get(((size - 1) * fraction).toInt().coerceIn(indices))

    private fun writePreview(
        image: LumaImage,
        candidates: List<CloseIconCandidate>,
    ) {
        val preview = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val value = image.valueAt(x, y)
                preview.setRGB(x, y, Color(value, value, value).rgb)
            }
        }
        val graphics = preview.createGraphics()
        try {
            graphics.color = Color(0, 190, 80)
            graphics.stroke = BasicStroke(3f)
            candidates.forEach { candidate ->
                graphics.drawRect(
                    candidate.bounds.left,
                    candidate.bounds.top,
                    candidate.bounds.width,
                    candidate.bounds.height,
                )
            }
        } finally {
            graphics.dispose()
        }
        val output = File("build/reports/close-icon-benchmark.png")
        output.parentFile.mkdirs()
        ImageIO.write(preview, "png", output)
        println("CLOSE_ICON_PREVIEW ${output.absolutePath}")
    }

    private class LumaCanvas(
        private val width: Int,
        private val height: Int,
        background: Int,
    ) {
        private val pixels = ByteArray(width * height) { background.toByte() }

        fun fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
                for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
                    pixels[y * width + x] = color.toByte()
                }
            }
        }

        fun drawX(
            centerX: Int,
            centerY: Int,
            radius: Int,
            thickness: Int,
            color: Int,
        ) {
            for (offset in -radius..radius) {
                drawPoint(centerX + offset, centerY + offset, thickness, color)
                drawPoint(centerX + offset, centerY - offset, thickness, color)
            }
        }

        fun drawPlus(
            centerX: Int,
            centerY: Int,
            radius: Int,
            thickness: Int,
            color: Int,
        ) {
            for (offset in -radius..radius) {
                drawPoint(centerX + offset, centerY, thickness, color)
                drawPoint(centerX, centerY + offset, thickness, color)
            }
        }

        fun drawDiagonal(
            centerX: Int,
            centerY: Int,
            radius: Int,
            thickness: Int,
            color: Int,
        ) {
            for (offset in -radius..radius) {
                drawPoint(centerX + offset, centerY + offset, thickness, color)
            }
        }

        fun drawHorizontal(
            startX: Int,
            endX: Int,
            y: Int,
            thickness: Int,
            color: Int,
        ) {
            for (x in startX..endX) {
                drawPoint(x, y, thickness, color)
            }
        }

        fun drawRectBorder(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            thickness: Int,
            color: Int,
        ) {
            for (x in left..right) {
                drawPoint(x, top, thickness, color)
                drawPoint(x, bottom, thickness, color)
            }
            for (y in top..bottom) {
                drawPoint(left, y, thickness, color)
                drawPoint(right, y, thickness, color)
            }
        }

        private fun drawPoint(x: Int, y: Int, thickness: Int, color: Int) {
            val radius = thickness / 2
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val targetX = x + dx
                    val targetY = y + dy
                    if (targetX in 0 until width && targetY in 0 until height) {
                        pixels[targetY * width + targetX] = color.toByte()
                    }
                }
            }
        }

        fun toImage(): LumaImage = LumaImage(width, height, pixels.copyOf())
    }

    private companion object {
        const val WIDTH = 473
        const val HEIGHT = 1_024
        const val WARMUP_RUNS = 8
        const val BENCHMARK_RUNS = 120
    }
}
