package com.example.mobileguiagent.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticBookingSettlePolicyTest {
    @Test
    fun waitsForSeatMapAfterAudienceConfirmation() {
        assertEquals(
            AutomaticBookingSettlePolicy.SEAT_MAP_TRANSITION_SETTLE_MS,
            AutomaticBookingSettlePolicy.delayMs("AUDIENCE_CONFIRMED_CONTINUE"),
        )
    }

    @Test
    fun waitsForScheduleAfterEntitySelectionConfirmation() {
        assertEquals(
            AutomaticBookingSettlePolicy.ENTITY_SELECTION_TRANSITION_SETTLE_MS,
            AutomaticBookingSettlePolicy.delayMs(
                "ENTITY_SELECTION_CONFIRMED_CONTINUE",
            ),
        )
    }

    @Test
    fun waitsForWebViewToReflectRequiredEntitySelection() {
        assertEquals(
            AutomaticBookingSettlePolicy.ENTITY_SELECTION_SETTLE_MS,
            AutomaticBookingSettlePolicy.delayMs("SELECT_REQUIRED_ENTITY"),
        )
    }

    @Test
    fun waitsForAsynchronousWebViewSeatPriceUpdate() {
        assertEquals(1_800L, AutomaticBookingSettlePolicy.SEAT_SELECTION_SETTLE_MS)
        assertEquals(
            AutomaticBookingSettlePolicy.SEAT_SELECTION_SETTLE_MS,
            AutomaticBookingSettlePolicy.delayMs("SELECT_GEOMETRIC_CENTER_SEAT"),
        )
    }

    @Test
    fun keepsDefaultSettleForOtherAutomaticBookingActions() {
        listOf(
            "AUDIENCE_INCREMENT_ADULT",
            "AUDIENCE_DECREMENT_ADULT",
            "CENTER_SEATS_EXHAUSTED_NEXT_SHOWTIME",
        ).forEach { code ->
            assertNull(code, AutomaticBookingSettlePolicy.delayMs(code))
        }
    }
}
