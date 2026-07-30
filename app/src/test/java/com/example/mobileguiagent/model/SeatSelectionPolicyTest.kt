package com.example.mobileguiagent.model

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatSelectionPolicyTest {
    @Test
    fun sixByElevenGridChoosesLaterCentralRowD6() {
        val resolution = SeatSelectionPolicy.resolve(
            snapshot = auditorium(),
            candidatePatterns = CANDIDATE_PATTERNS,
        )

        assertEquals(6 * 11, resolution.allSeats.size)
        assertEquals(
            listOf("D6", "C6"),
            resolution.geometricCenterSeats.map(ParsedSeat::stableLabel),
        )
        assertEquals("D6", resolution.centerRankedAvailableSeats.first().stableLabel)
        assertEquals("D6", resolution.recommendedNode?.text?.substringBefore(' '))
    }

    @Test
    fun soldAndAccessibleCenterSeatsRemainInGridButAreExcluded() {
        val resolution = SeatSelectionPolicy.resolve(
            snapshot = auditorium(
                labels = mapOf(
                    "D6" to "D6 판매완료 일반",
                    "C6" to "C6 판매가능 장애인",
                ),
            ),
            candidatePatterns = CANDIDATE_PATTERNS,
        )

        assertEquals(6 * 11, resolution.allSeats.size)
        assertFalse(
            resolution.centerRankedAvailableSeats.any { seat ->
                seat.stableLabel == "D6" || seat.stableLabel == "C6"
            },
        )
        assertEquals("D5", resolution.centerRankedAvailableSeats.first().stableLabel)
    }

    @Test
    fun selectedMarkerIsReturnedAsEvidenceEvenWhenSeatIsNotActionable() {
        val resolution = SeatSelectionPolicy.resolve(
            snapshot = auditorium(
                labels = mapOf("D6" to "D6 선택됨 일반"),
                nonActionable = setOf("D6"),
            ),
            candidatePatterns = CANDIDATE_PATTERNS,
        )

        assertEquals(listOf("D6"), resolution.selectedEvidence.map(ParsedSeat::stableLabel))
        assertFalse(
            resolution.centerRankedAvailableSeats.any { seat -> seat.stableLabel == "D6" },
        )
    }

    @Test
    fun missingNamedCaptureGroupsProducesNoCandidates() {
        val resolution = SeatSelectionPolicy.resolve(
            snapshot = auditorium(),
            candidatePatterns = setOf(Regex("""([A-Z])(\d+)""")),
        )

        assertTrue(resolution.allSeats.isEmpty())
        assertNull(resolution.recommendedNode)
    }

    private fun auditorium(
        labels: Map<String, String> = emptyMap(),
        nonActionable: Set<String> = emptySet(),
    ): UiSnapshot {
        val nodes = buildList {
            for (row in 'A'..'F') {
                for (number in 1..11) {
                    val seat = "$row$number"
                    add(
                        node(
                            id = "seat_$seat",
                            label = labels[seat] ?: "$seat 판매가능 일반",
                            rowIndex = row - 'A',
                            number = number,
                            actionable = seat !in nonActionable,
                        ),
                    )
                }
            }
        }
        return UiSnapshot(
            packageName = "com.megabox.mop",
            nodes = nodes,
        )
    }

    private fun node(
        id: String,
        label: String,
        rowIndex: Int,
        number: Int,
        actionable: Boolean,
    ): UiNode = UiNode(
        id = id,
        text = label,
        contentDescription = null,
        className = "android.widget.Button",
        viewId = null,
        clickable = actionable,
        editable = false,
        scrollable = false,
        enabled = actionable,
        checked = null,
        bounds = Rect(
            number * 50,
            300 + rowIndex * 50,
            number * 50 + 40,
            340 + rowIndex * 50,
        ),
        depth = 2,
        visibleToUser = true,
    )

    private companion object {
        val CANDIDATE_PATTERNS = setOf(
            Regex("""(?<row>[A-Z])\s*(?<number>\d+)""", RegexOption.IGNORE_CASE),
        )
    }
}
