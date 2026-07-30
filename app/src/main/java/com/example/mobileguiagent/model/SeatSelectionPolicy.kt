package com.example.mobileguiagent.model

import java.util.Locale
import kotlin.math.abs

/**
 * One logical auditorium seat parsed from an accessibility node.
 *
 * [availableGeneral] is deliberately stricter than [selected]: only a visible,
 * enabled and clickable node whose current label says both "available" and
 * an ordinary bookable seat type may be recommended. Sold and accessible seats remain in [allSeats]
 * so they still contribute to the auditorium's geometric center.
 */
data class ParsedSeat(
    val node: UiNode,
    val row: String,
    val number: Int,
    val label: String,
    val availableGeneral: Boolean,
    val selected: Boolean,
) {
    val stableLabel: String = "$row$number"
    val stableKey: String = "seat:$stableLabel"
}

data class SeatSelectionResolution(
    val allSeats: List<ParsedSeat>,
    val geometricCenterSeats: List<ParsedSeat>,
    val centerRankedAvailableSeats: List<ParsedSeat>,
    val selectedEvidence: List<ParsedSeat>,
    val recommendedNode: UiNode?,
)

/**
 * Parses and ranks seat nodes without depending on model-visible node limits.
 *
 * Every candidate regex must expose named groups `row` and `number`, for
 * example `(?<row>[A-Z])\s*(?<number>\d+)`. Patterns identify seat-shaped
 * labels; availability and seat type are then read from the same matched node
 * label. The complete parsed grid determines the center, while only actionable
 * ordinary `판매가능` nodes are eligible for recommendation.
 */
object SeatSelectionPolicy {
    fun resolve(
        snapshot: UiSnapshot,
        candidatePatterns: Set<Regex>,
    ): SeatSelectionResolution {
        if (candidatePatterns.isEmpty()) return EMPTY_RESOLUTION

        val patterns = candidatePatterns.sortedBy(Regex::pattern)
        val parsed = snapshot.nodes.mapNotNull { node ->
            parse(node, patterns)
        }
        val seats = parsed
            .groupBy { seat -> SeatKey(seat.row, seat.number) }
            .map { (_, duplicates) -> duplicates.preferredRepresentation() }
            .sortedWith(SEAT_LABEL_ORDER)
        if (seats.isEmpty()) return EMPTY_RESOLUTION

        val rowPositions = seats
            .map(ParsedSeat::row)
            .distinct()
            .sortedWith(ROW_LABEL_ORDER)
            .mapIndexed { index, row -> row to index }
            .toMap()
        val rowCenter = (
            rowPositions.values.minOrNull()!! +
                rowPositions.values.maxOrNull()!!
            ) / 2.0
        val numberCenter = (
            seats.minOf(ParsedSeat::number) +
                seats.maxOf(ParsedSeat::number)
            ) / 2.0
        fun distance(seat: ParsedSeat): Double {
            val rowDistance = abs(rowPositions.getValue(seat.row) - rowCenter)
            val horizontalDistance = abs(seat.number - numberCenter)
            return rowDistance * rowDistance + horizontalDistance * horizontalDistance
        }
        val centerOrder = compareBy<ParsedSeat>(::distance)
            .thenBy { seat -> abs(seat.number - numberCenter) }
            .thenByDescending { seat -> rowPositions.getValue(seat.row) }
            .thenBy(ParsedSeat::stableLabel)
        val ranked = seats
            .filter(ParsedSeat::availableGeneral)
            .sortedWith(centerOrder)
        val minimumDistance = seats.minOf(::distance)
        val geometricCenterSeats = seats
            .filter { seat -> distance(seat) == minimumDistance }
            .sortedWith(centerOrder)
        val selected = seats
            .filter(ParsedSeat::selected)
            .sortedWith(SEAT_LABEL_ORDER)

        return SeatSelectionResolution(
            allSeats = seats,
            geometricCenterSeats = geometricCenterSeats,
            centerRankedAvailableSeats = ranked,
            selectedEvidence = selected,
            recommendedNode = ranked.firstOrNull()?.node,
        )
    }

    private fun parse(
        node: UiNode,
        patterns: List<Regex>,
    ): ParsedSeat? {
        val label = node.semanticLabel()
        if (label.isBlank()) return null
        val match = patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(label)?.takeIf { result ->
                result.namedValue(ROW_GROUP) != null &&
                    result.namedValue(NUMBER_GROUP) != null
            }
        } ?: return null
        val row = match.namedValue(ROW_GROUP)
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val number = match.namedValue(NUMBER_GROUP)
            ?.toIntOrNull()
            ?.takeIf { value -> value > 0 }
            ?: return null
        val availableGeneral =
            node.visibleToUser &&
                node.enabled &&
                node.clickable &&
                AVAILABLE_MARKER.containsMatchIn(label) &&
                GENERAL_MARKER.containsMatchIn(label) &&
                !ACCESSIBLE_MARKER.containsMatchIn(label)
        val selected =
            node.selected ||
                node.checked == true ||
                SELECTED_MARKER.containsMatchIn(label)
        return ParsedSeat(
            node = node,
            row = row,
            number = number,
            label = label,
            availableGeneral = availableGeneral,
            selected = selected,
        )
    }

    private fun List<ParsedSeat>.preferredRepresentation(): ParsedSeat =
        sortedWith(
            compareByDescending<ParsedSeat>(ParsedSeat::selected)
                .thenByDescending(ParsedSeat::availableGeneral)
                .thenByDescending { seat ->
                    seat.node.visibleToUser && seat.node.enabled && seat.node.clickable
                }
                .thenBy { seat -> seat.node.id },
        ).first()

    private fun UiNode.semanticLabel(): String =
        listOfNotNull(text, contentDescription, hint)
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun MatchResult.namedValue(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()

    private data class SeatKey(
        val row: String,
        val number: Int,
    )

    private val ROW_LABEL_ORDER = Comparator<String> { first, second ->
        val firstOrdinal = first.alphaOrdinal()
        val secondOrdinal = second.alphaOrdinal()
        when {
            firstOrdinal != null && secondOrdinal != null ->
                firstOrdinal.compareTo(secondOrdinal)
            firstOrdinal != null -> -1
            secondOrdinal != null -> 1
            else -> first.compareTo(second)
        }
    }
    private val SEAT_LABEL_ORDER = Comparator<ParsedSeat> { first, second ->
        val rowComparison = ROW_LABEL_ORDER.compare(first.row, second.row)
        if (rowComparison != 0) {
            rowComparison
        } else {
            compareValuesBy(first, second, ParsedSeat::number, ParsedSeat::stableLabel)
        }
    }

    private fun String.alphaOrdinal(): Long? {
        if (isEmpty() || any { character -> character !in 'A'..'Z' }) return null
        var value = 0L
        forEach { character ->
            val next = value * ALPHABET_SIZE + (character - 'A' + 1)
            if (next < value) return null
            value = next
        }
        return value
    }

    private val AVAILABLE_MARKER = Regex(
        """(?<!불)(?:판매\s*가능|available)""",
        RegexOption.IGNORE_CASE,
    )
    private val GENERAL_MARKER = Regex(
        """(?:일반|general|스탠다드|standard|스페셜\s*석|special\s*seat)""",
        RegexOption.IGNORE_CASE,
    )
    private val ACCESSIBLE_MARKER = Regex(
        """(?:장애인|휠체어|wheelchair|accessible)""",
        RegexOption.IGNORE_CASE,
    )
    private val SELECTED_MARKER = Regex(
        """(?:선택\s*(?:됨|완료|좌석)|selected)""",
        RegexOption.IGNORE_CASE,
    )

    private val EMPTY_RESOLUTION = SeatSelectionResolution(
        allSeats = emptyList(),
        geometricCenterSeats = emptyList(),
        centerRankedAvailableSeats = emptyList(),
        selectedEvidence = emptyList(),
        recommendedNode = null,
    )

    private const val ROW_GROUP = "row"
    private const val NUMBER_GROUP = "number"
    private const val ALPHABET_SIZE = 26L
}
