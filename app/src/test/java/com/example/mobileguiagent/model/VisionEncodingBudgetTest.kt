package com.example.mobileguiagent.model

import com.example.mobileguiagent.device.DeviceToolResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionEncodingBudgetTest {
    private val budget = VisionEncodingBudget()

    @Test
    fun unchangedRenderedScreenIsEncodedOnlyOnce() {
        val first = screenshot(byteArrayOf(1, 2, 3))
        val sameBytesInNewResult = screenshot(byteArrayOf(1, 2, 3))

        assertTrue(budget.shouldEncode(first))
        budget.markEncoded(first)

        assertFalse(budget.shouldEncode(sameBytesInNewResult))
    }

    @Test
    fun changedBytesOrDimensionsAreNewScreens() {
        val first = screenshot(byteArrayOf(1, 2, 3))
        budget.markEncoded(first)

        assertTrue(budget.shouldEncode(screenshot(byteArrayOf(1, 2, 4))))
        assertTrue(
            budget.shouldEncode(
                screenshot(
                    bytes = byteArrayOf(1, 2, 3),
                    width = 501,
                ),
            ),
        )
    }

    @Test
    fun stableAccessibilityIdentityIgnoresIncidentalPixelChanges() {
        val first = screenshot(byteArrayOf(1, 2, 3))
        val clockTick = screenshot(byteArrayOf(9, 8, 7))
        budget.markEncoded(first, stableScreenKey = "launcher:fingerprint-a")

        assertFalse(
            budget.shouldEncode(
                clockTick,
                stableScreenKey = "launcher:fingerprint-a",
            ),
        )
        assertTrue(
            budget.shouldEncode(
                clockTick,
                stableScreenKey = "launcher:fingerprint-b",
            ),
        )
    }

    @Test
    fun returningToScreenAfterDifferentScreenAllowsNewEncoding() {
        val screenA = screenshot(byteArrayOf(1))
        val screenB = screenshot(byteArrayOf(2))

        budget.markEncoded(screenA)
        assertTrue(budget.shouldEncode(screenB))
        budget.markEncoded(screenB)

        assertTrue(budget.shouldEncode(screenA))
    }

    @Test
    fun proposalIsAvailableOnlyForExactMostRecentScreen() {
        val screenA = screenshot(byteArrayOf(1))
        val screenB = screenshot(byteArrayOf(2))
        budget.markEncoded(screenA)
        budget.rememberProposal(screenA, """{"tool":"click"}""")

        assertTrue(budget.priorProposal(screenA)?.contains("click") == true)
        assertTrue(budget.priorProposal(screenB) == null)
    }

    private fun screenshot(
        bytes: ByteArray,
        width: Int = 500,
        height: Int = 1_000,
    ) = DeviceToolResult.Screenshot(
        jpegBytes = bytes,
        width = width,
        height = height,
    )
}
