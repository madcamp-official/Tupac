package com.example.mobileguiagent.model

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookingProgressPolicyTest {
    @Test
    fun explicitDateIsSelectedOnceAndThenSuppressedAfterVerification() {
        val requiredDate = TaskDate(2026, 8, 2)
        val contract = bookingContract().copy(
            requiredDate = requiredDate,
            dateViewIdPatterns = setOf("""^playDate_(\d{8})$"""),
        )
        val schedule = snapshot(
            node(
                id = "date",
                description = "2 일",
                viewId = "playDate_20260802",
                clickable = true,
            ),
        )

        val select = RequestedDateInterceptor.intercept(contract, schedule)
        assertEquals("SELECT_REQUIRED_DATE", select?.code)
        assertEquals(RuntimeAction.TapNode("date"), select?.automaticAction)
        assertEquals(
            null,
            RequestedDateInterceptor.intercept(
                contract = contract,
                snapshot = schedule,
                validatedSelectionKeys = setOf(requiredDate.targetKey),
            ),
        )
    }

    @Test
    fun dateWrapperTargetsItsClickableDayInsteadOfTheWholeDateRow() {
        val contract = bookingContract().copy(
            requiredDate = TaskDate(2026, 8, 5),
            dateViewIdPatterns = setOf("""^playDate_(\d{8})$"""),
        )
        val schedule = snapshot(
            node("date_row", clickable = true),
            node(
                id = "date_wrapper",
                parent = "date_row",
                viewId = "playDate_20260805",
            ),
            node(
                id = "date_link",
                parent = "date_wrapper",
                description = "5 수",
                clickable = true,
            ),
        )

        val selection = RequestedDateInterceptor.intercept(contract, schedule)

        assertEquals(RuntimeAction.TapNode("date_link"), selection?.automaticAction)
    }

    @Test
    fun dateVerificationRejectsNeighboringFocusedDay() {
        val contract = bookingContract().copy(
            requiredDate = TaskDate(2026, 8, 5),
            dateViewIdPatterns = setOf("""^playDate_(\d{8})$"""),
        )
        val wrongDayActive = snapshot(
            node("requested", viewId = "playDate_20260805"),
            node("requested_link", parent = "requested", description = "5 수", clickable = true),
            node("wrong", viewId = "playDate_20260801"),
            node(
                "wrong_link",
                parent = "wrong",
                description = "8.1 토",
                clickable = true,
                focused = true,
            ),
        )
        val requestedDayActive = wrongDayActive.copy(
            nodes = wrongDayActive.nodes.map { node ->
                when (node.id) {
                    "requested_link" -> node.copy(focused = true)
                    "wrong_link" -> node.copy(focused = false)
                    else -> node
                }
            },
        )

        assertFalse(
            DateSelectionVerificationPolicy.enforce(
                generic = genericVerification(),
                contract = contract,
                after = wrongDayActive,
            ).verified,
        )
        assertTrue(
            DateSelectionVerificationPolicy.enforce(
                generic = genericVerification(),
                contract = contract,
                after = requestedDayActive,
            ).verified,
        )
    }

    @Test
    fun adultCountIsIncrementedBeforePlannerAndThenContinues() {
        val contract = bookingContract()
        val zeroAdults = snapshot(
            node("count", text = "0", viewId = "TKA"),
            node("plus", text = "+", viewId = "TKA_plus", clickable = true),
            node(
                "continue",
                description = "동의하고 좌석선택",
                viewId = "nextSelBtn",
                clickable = true,
            ),
        )

        val increment = RequestedAudienceInterceptor.intercept(contract, zeroAdults)
        assertEquals("AUDIENCE_INCREMENT_ADULT", increment?.code)
        assertEquals(RuntimeAction.TapNode("plus"), increment?.automaticAction)

        val oneAdult = zeroAdults.copy(
            nodes = zeroAdults.nodes.map { node ->
                if (node.id == "count") node.copy(text = "1") else node
            },
        )
        val next = RequestedAudienceInterceptor.intercept(contract, oneAdult)
        assertEquals("AUDIENCE_CONFIRMED_CONTINUE", next?.code)
        assertEquals(RuntimeAction.TapNode("continue"), next?.automaticAction)
    }

    @Test
    fun requiredTheaterRouteIsSelectedRevealedAndCompletedDeterministically() {
        val contract = bookingContract().copy(
            requiredEntities = mapOf("theater" to setOf("울산")),
            entityNavigationHints = mapOf(
                "theater" to mapOf(
                    "울산" to listOf("지역별", "부산/대구/경상"),
                ),
            ),
        )
        val categoryScreen = snapshot(
            node(
                id = "complete",
                description = "선택 완료",
                viewId = "choiceBtn",
                clickable = true,
            ),
            node("category", text = "지역별", clickable = true),
        )
        val category =
            RequiredEntityNavigationInterceptor.intercept(contract, categoryScreen)
        assertEquals("SELECT_ENTITY_ROUTE_HINT", category?.code)
        assertEquals(RuntimeAction.TapNode("category"), category?.automaticAction)

        val regionScreen = snapshot(
            node(
                id = "complete",
                description = "선택 완료",
                viewId = "choiceBtn",
                clickable = true,
            ),
            node("category", text = "지역별", clickable = true),
            node("region_parent", clickable = true),
            node(
                id = "region",
                parent = "region_parent",
                text = "부산/대구/경상",
            ),
        )
        val route = RequiredEntityNavigationInterceptor.intercept(contract, regionScreen)
        assertEquals("SELECT_ENTITY_ROUTE_HINT", route?.code)
        assertEquals(RuntimeAction.TapNode("region_parent"), route?.automaticAction)

        val offscreenTheater = snapshot(
            node(
                id = "complete",
                description = "선택 완료",
                viewId = "choiceBtn",
                clickable = true,
            ),
            node(
                id = "theater_list",
                scrollable = true,
                bounds = Rect(0, 0, 100, 300),
            ),
            node(
                id = "ulsan",
                parent = "theater_list",
                text = "울산",
                clickable = true,
                visible = false,
                bounds = Rect(0, 300, 100, 300),
            ),
        )
        val reveal = RequiredEntityNavigationInterceptor.intercept(contract, offscreenTheater)
        assertEquals("REVEAL_REQUIRED_ENTITY", reveal?.code)
        assertEquals(RuntimeAction.Scroll("theater_list", "down"), reveal?.automaticAction)

        val visibleTheater = offscreenTheater.copy(
            nodes = offscreenTheater.nodes.map { node ->
                if (node.id == "ulsan") {
                    node.copy(visibleToUser = true, bounds = Rect(0, 100, 100, 200))
                } else {
                    node
                }
            },
        )
        val select = RequiredEntityNavigationInterceptor.intercept(contract, visibleTheater)
        assertEquals("SELECT_REQUIRED_ENTITY", select?.code)
        assertEquals(RuntimeAction.TapNode("ulsan"), select?.automaticAction)

        val selected = snapshot(
            node(
                id = "complete",
                description = "선택 완료 (1/5)",
                viewId = "choiceBtn",
                clickable = true,
            ),
            node(id = "ulsan", text = "울산", clickable = true),
        )
        val complete = RequiredEntityNavigationInterceptor.intercept(contract, selected)
        assertEquals("ENTITY_SELECTION_CONFIRMED_CONTINUE", complete?.code)
        assertEquals(RuntimeAction.TapNode("complete"), complete?.automaticAction)
    }

    @Test
    fun exactCenterSeatsAreAttemptedBeforeShowtimeIsExhausted() {
        val contract = bookingContract()
        val seatMap = auditorium()

        val first = GeometricSeatInterceptor.evaluate(
            contract = contract,
            snapshot = seatMap,
            attemptedSeatKeys = emptySet(),
            validatedSeat = false,
        ) as GeometricSeatDecision.Select
        assertEquals("seat:D6", first.seatKey)
        assertEquals(
            RuntimeAction.TapNode("seat_D6", coordinateFallback = true),
            first.interception.automaticAction,
        )

        val second = GeometricSeatInterceptor.evaluate(
            contract = contract,
            snapshot = seatMap,
            attemptedSeatKeys = setOf("seat:D6"),
            validatedSeat = false,
        ) as GeometricSeatDecision.Select
        assertEquals("seat:C6", second.seatKey)

        val exhausted = GeometricSeatInterceptor.evaluate(
            contract = contract,
            snapshot = seatMap,
            attemptedSeatKeys = setOf("seat:D6", "seat:C6"),
            validatedSeat = false,
        ) as GeometricSeatDecision.Exhausted
        assertEquals(18 * 60 + 40, exhausted.showtimeMinutes)
        assertEquals(setOf("seat:D6", "seat:C6"), exhausted.centerSeatKeys)
    }

    @Test
    fun validatedSeatSuppressesFurtherAutomaticSelection() {
        assertTrue(
            GeometricSeatInterceptor.evaluate(
                contract = bookingContract(),
                snapshot = auditorium(),
                attemptedSeatKeys = emptySet(),
                validatedSeat = true,
            ) == null,
        )
    }

    @Test
    fun focusAndGenericTreeChangeDoNotValidateASeatAtZeroPrice() {
        val enforced = SeatSelectionVerificationPolicy.enforce(
            generic = AgentActionVerification(
                verified = true,
                evidence = setOf(AgentActionEvidence.SEMANTIC_UI_CHANGED),
                message = "generic change",
            ),
            contract = bookingContract(),
            before = seatVerificationSnapshot(price = "0"),
            after = seatVerificationSnapshot(price = "0", focused = true),
            targetKey = "seat:C4",
        )

        assertFalse(enforced.verified)
        assertTrue(enforced.message.contains("price did not transition"))
    }

    @Test
    fun positivePriceTransitionValidatesTheExactSeatAction() {
        val enforced = SeatSelectionVerificationPolicy.enforce(
            generic = AgentActionVerification(
                verified = true,
                evidence = setOf(AgentActionEvidence.SEMANTIC_UI_CHANGED),
                message = "generic change",
            ),
            contract = bookingContract(),
            before = seatVerificationSnapshot(price = "0"),
            after = seatVerificationSnapshot(price = "15,000", focused = true),
            targetKey = "seat:C4",
        )

        assertTrue(enforced.verified)
        assertTrue(AgentActionEvidence.SEAT_SELECTION_CONFIRMED in enforced.evidence)
    }

    @Test
    fun unchangedShowtimeIsExcludedAfterBoundedRetries() {
        val tracker = ShowtimeAttemptTracker(maxUnchangedAttempts = 2)

        assertFalse(tracker.recordUnchanged(19 * 60 + 45))
        assertTrue(tracker.recordUnchanged(19 * 60 + 45))
        tracker.clear(19 * 60 + 45)
        assertFalse(tracker.recordUnchanged(19 * 60 + 45))
    }

    @Test
    fun sameScheduleRefreshDoesNotValidateShowtimeSelection() {
        val before = scheduleSnapshot()
        val after = snapshot(
            *before.nodes.map { node ->
                when (node.id) {
                    "showtime" ->
                        node.copy(
                            text = "19:10 잔여 20석",
                            selected = true,
                            focused = true,
                            bounds = Rect(20, 20, 180, 120),
                        )
                    else -> node.copy(bounds = Rect(10, 10, 210, 210))
                }
            }.toTypedArray(),
            node(
                id = "remaining_count",
                parent = "schedule",
                text = "잔여 좌석 20",
                clickable = true,
            ),
        )
        val generic = AgentActionVerifier.verify(
            before = before,
            after = after,
            expectedChange = "Select 19:10",
            taskContract = bookingContract(),
        )

        assertTrue(generic.verified)
        val enforced = ShowtimeSelectionVerificationPolicy.enforce(
            generic = generic,
            contract = bookingContract(),
            before = before,
            after = after,
        )

        assertFalse(enforced.verified)
        assertTrue(enforced.message.contains("no new"))
    }

    @Test
    fun newlyOpenedInformationalPopupValidatesShowtimeSelection() {
        val before = scheduleSnapshot()
        val after = snapshot(
            *before.nodes.toTypedArray(),
            node("notice_modal"),
            node(
                id = "notice_message",
                parent = "notice_modal",
                text =
                    "상영 시작 시간이 임박하여 온라인 예매가 제한될 수 있습니다. " +
                        "선택한 회차와 관람 정보를 다시 확인한 후 계속 진행해 주세요. " +
                        "현장 상황에 따라 입장이 제한될 수 있으므로 안내 내용을 충분히 " +
                        "확인하고 동의하는 경우에만 다음 단계로 이동해 주세요.",
            ),
            node(
                id = "notice_confirm",
                parent = "notice_modal",
                text = "확인",
                clickable = true,
            ),
        )

        val enforced = ShowtimeSelectionVerificationPolicy.enforce(
            generic = genericVerification(),
            contract = bookingContract(),
            before = before,
            after = after,
        )

        assertTrue(enforced.verified)
        assertTrue(AgentActionEvidence.BOOKING_FLOW_ADVANCED in enforced.evidence)
        assertTrue(enforced.message.contains("informational_popup"))
    }

    @Test
    fun newlyOpenedAudienceControlsValidateShowtimeSelection() {
        val before = scheduleSnapshot()
        val after = snapshot(
            *before.nodes.toTypedArray(),
            node("audience_count", text = "0", viewId = "TKA"),
            node("audience_plus", text = "+", viewId = "TKA_plus", clickable = true),
            node(
                id = "audience_continue",
                text = "동의하고 좌석선택",
                viewId = "nextSelBtn",
                clickable = true,
            ),
        )

        val enforced = ShowtimeSelectionVerificationPolicy.enforce(
            generic = genericVerification(),
            contract = bookingContract(),
            before = before,
            after = after,
        )

        assertTrue(enforced.verified)
        assertTrue(enforced.message.contains("audience_selection"))
    }

    @Test
    fun newlyOpenedSeatMapValidatesShowtimeSelection() {
        val before = scheduleSnapshot()
        val after = snapshot(
            node(
                id = "seat_C4",
                text = "C4 스탠다드 판매가능 스페셜석",
                clickable = true,
            ),
        )

        val enforced = ShowtimeSelectionVerificationPolicy.enforce(
            generic = genericVerification(),
            contract = bookingContract(),
            before = before,
            after = after,
        )

        assertTrue(enforced.verified)
        assertTrue(enforced.message.contains("seat_selection"))
    }

    @Test
    fun showtimeVerificationAppliesToRuntimeAndContractViewTargets() {
        val contract = bookingContract()

        assertTrue(
            ShowtimeSelectionVerificationPolicy.applies(
                contract = contract,
                targetKey = null,
                runtimeCode = "SELECT_EARLIEST_SHOWTIME",
            ),
        )
        assertTrue(
            ShowtimeSelectionVerificationPolicy.applies(
                contract = contract,
                targetKey = "view:time_movie_03_19102145",
                runtimeCode = null,
            ),
        )
        assertFalse(
            ShowtimeSelectionVerificationPolicy.applies(
                contract = contract,
                targetKey = "view:nextSelBtn",
                runtimeCode = null,
            ),
        )
    }

    @Test
    fun earliestShowtimeIsSelectedByClockInsteadOfDisplayOrder() {
        val schedule = snapshot(
            node(
                "schedule",
                clickable = true,
                scrollable = true,
                bounds = Rect(0, 0, 100, 300),
            ),
            node(
                id = "later",
                parent = "schedule",
                text = "20:25",
                viewId = "time_movie_01_20252300",
                clickable = true,
                bounds = Rect(0, 0, 100, 100),
            ),
            node(
                id = "earliest",
                parent = "schedule",
                text = "20:05",
                viewId = "time_movie_02_20052240",
                clickable = true,
                bounds = Rect(0, 100, 100, 200),
            ),
        )

        val interception = EarliestShowtimeInterceptor.intercept(
            contract = bookingContract(),
            snapshot = schedule,
            currentMinutes = 20 * 60,
        )

        assertEquals("SELECT_EARLIEST_SHOWTIME", interception?.code)
        assertEquals(RuntimeAction.TapNode("earliest"), interception?.automaticAction)
    }

    @Test
    fun exhaustedShowtimesAreSkippedAndOffscreenEarliestIsRevealed() {
        val schedule = snapshot(
            node(
                "schedule",
                clickable = true,
                scrollable = true,
                bounds = Rect(0, 0, 100, 300),
            ),
            node(
                id = "exhausted_1910",
                parent = "schedule",
                text = "19:10",
                viewId = "time_movie_01_19102145",
                clickable = true,
                bounds = Rect(0, 0, 100, 100),
            ),
            node(
                id = "exhausted_1945",
                parent = "schedule",
                text = "19:45",
                viewId = "time_movie_02_19452220",
                clickable = true,
                bounds = Rect(0, 100, 100, 200),
            ),
            node(
                id = "earliest_offscreen",
                parent = "schedule",
                text = "20:05",
                viewId = "time_movie_03_20052240",
                clickable = true,
                visible = false,
                bounds = Rect(0, 300, 100, 300),
            ),
            node(
                id = "later",
                parent = "schedule",
                text = "20:25",
                viewId = "time_movie_04_20252300",
                clickable = true,
                visible = false,
                bounds = Rect(0, 300, 100, 300),
            ),
        )

        val interception = EarliestShowtimeInterceptor.intercept(
            contract = bookingContract(),
            snapshot = schedule,
            currentMinutes = 19 * 60,
            excludedShowtimeMinutes = setOf(19 * 60 + 10, 19 * 60 + 45),
        )

        assertEquals("REVEAL_EARLIEST_SHOWTIME", interception?.code)
        assertEquals(RuntimeAction.Scroll("schedule", "down"), interception?.automaticAction)
    }

    @Test
    fun allRequestedMovieShowtimesExhaustedStopsBeforePlannerFallback() {
        val schedule = snapshot(
            node(
                id = "first",
                text = "20:25",
                viewId = "time_movie_01_20252300",
                clickable = true,
            ),
            node(
                id = "second",
                text = "21:25",
                viewId = "time_movie_02_21250000",
                clickable = true,
            ),
        )

        assertTrue(
            ExhaustedShowtimeFallbackPolicy.allFutureCandidatesExhausted(
                contract = bookingContract(),
                snapshot = schedule,
                excludedShowtimeMinutes = setOf(20 * 60 + 25, 21 * 60 + 25),
                currentMinutes = 20 * 60,
            ),
        )
        assertFalse(
            ExhaustedShowtimeFallbackPolicy.allFutureCandidatesExhausted(
                contract = bookingContract(),
                snapshot = schedule,
                excludedShowtimeMinutes = setOf(20 * 60 + 25),
                currentMinutes = 20 * 60,
            ),
        )
    }

    @Test
    fun tomorrowOnlyScheduleStopsSameDayGoalBeforePlanner() {
        val schedule = requestedMovieSchedule(
            dateLabel = "7.30 내일",
            showtimes = listOf("09:30", "20:30"),
        )

        assertTrue(
            RequestedDayAvailabilityPolicy.noFutureShowtimeOnRequestedDay(
                contract = bookingContract().copy(
                    requiredEntities = mapOf("movie" to setOf("스파이더맨브랜드뉴데이")),
                    selectionPolicies =
                        bookingContract().selectionPolicies +
                            AgentSelectionPolicy.TODAY_ONLY,
                ),
                snapshot = schedule,
                currentMinutes = 22 * 60,
            ),
        )
    }

    @Test
    fun futureShowtimeTodayDoesNotTriggerSameDayTerminal() {
        val schedule = requestedMovieSchedule(
            dateLabel = "7.29 오늘",
            showtimes = listOf("20:30", "22:30"),
        )

        assertFalse(
            RequestedDayAvailabilityPolicy.noFutureShowtimeOnRequestedDay(
                contract = bookingContract().copy(
                    requiredEntities = mapOf("movie" to setOf("스파이더맨브랜드뉴데이")),
                    selectionPolicies =
                        bookingContract().selectionPolicies +
                            AgentSelectionPolicy.TODAY_ONLY,
                ),
                snapshot = schedule,
                currentMinutes = 22 * 60,
            ),
        )
    }

    private fun bookingContract() = TaskContract(
        originalGoal = "성인 1명이고 반드시 중앙 좌석",
        capabilities = setOf(
            AgentCapability.ACKNOWLEDGE_INFORMATION,
            AgentCapability.ENFORCE_EXACT_SELECTIONS,
        ),
        requiredSelections = emptySet(),
        goalSpec = AgentGoalSpec(
            objective = "성인 한 명의 중앙 좌석 선택",
            constraints = listOf(
                AgentGoalConstraint("audience.adult", "equals", "1"),
            ),
        ),
        stateSlotViewIds = mapOf(
            "adult_count" to setOf("TKA"),
            "adult_increment" to setOf("TKA_plus"),
            "adult_decrement" to setOf("TKA_minus"),
            "seat_selection_continue" to setOf("nextSelBtn"),
            "entity_selection_complete" to setOf("choiceBtn"),
            "seat_price" to setOf("ticketPriceInfo"),
            "time" to setOf("com.megabox.mop:id/headerTitle"),
        ),
        seatCandidatePatterns = setOf(
            """^(?<row>[A-Z]+)\s*(?<number>\d+)\b.*판매\s*가능.*$""",
        ),
        showtimeViewIdPatterns = setOf(
            """^time_.*_(\d{4})\d{4}$""",
        ),
        selectionPolicies = setOf(
            AgentSelectionPolicy.EARLIEST_AVAILABLE,
            AgentSelectionPolicy.FUTURE_ONLY,
            AgentSelectionPolicy.GEOMETRIC_CENTER,
            AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK,
        ),
    )

    private fun requestedMovieSchedule(
        dateLabel: String,
        showtimes: List<String>,
    ): UiSnapshot = snapshot(
        node("date", text = dateLabel, clickable = true),
        node("movie_card"),
        node(
            id = "movie_title",
            parent = "movie_card",
            text = "스파이더맨: 브랜드 뉴 데이",
        ),
        *showtimes.mapIndexed { index, time ->
            val compact = time.replace(":", "")
            node(
                id = "showtime_$index",
                parent = "movie_card",
                text = time,
                viewId = "time_movie_0${index}_$compact" + "0000",
                clickable = true,
            )
        }.toTypedArray(),
    )

    private fun auditorium(): UiSnapshot {
        val nodes = buildList {
            add(
                node(
                    id = "header",
                    text = "07.29(수) 18:40",
                    viewId = "com.megabox.mop:id/headerTitle",
                ),
            )
            for (row in 'A'..'F') {
                for (number in 1..11) {
                    add(
                        node(
                            id = "seat_$row$number",
                            text = "$row$number 스탠다드 판매가능 스페셜석",
                            clickable = true,
                        ),
                    )
                }
            }
        }
        return snapshot(*nodes.toTypedArray())
    }

    private fun seatVerificationSnapshot(
        price: String,
        focused: Boolean = false,
    ): UiSnapshot = snapshot(
        node("price", viewId = "ticketPriceInfo"),
        node("price_value", parent = "price", text = price),
        node(
            id = "seat_C4",
            text = "C4 스탠다드 판매가능 스페셜석",
            clickable = true,
            focused = focused,
        ),
    )

    private fun scheduleSnapshot(): UiSnapshot = snapshot(
        node(
            id = "schedule",
            text = "스파이더맨 상영시간",
            scrollable = true,
            bounds = Rect(0, 0, 200, 300),
        ),
        node(
            id = "showtime",
            parent = "schedule",
            text = "19:10",
            viewId = "time_movie_03_19102145",
            clickable = true,
            bounds = Rect(0, 0, 100, 100),
        ),
        node(
            id = "hidden_seat_template",
            text = "A1 스탠다드 판매가능 스페셜석",
            clickable = true,
            visible = false,
        ),
    )

    private fun genericVerification() = AgentActionVerification(
        verified = true,
        evidence = setOf(AgentActionEvidence.SEMANTIC_UI_CHANGED),
        message = "generic change",
    )

    private fun snapshot(vararg nodes: UiNode) = UiSnapshot(
        packageName = "com.megabox.mop",
        nodes = nodes.toList(),
    )

    private fun node(
        id: String,
        parent: String? = null,
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        visible: Boolean = true,
        focused: Boolean = false,
        bounds: Rect = Rect(0, 0, 100, 100),
    ) = UiNode(
        id = id,
        parentId = parent,
        text = text,
        contentDescription = description,
        className = if (clickable) "android.widget.Button" else "android.view.View",
        viewId = viewId,
        clickable = clickable,
        editable = false,
        scrollable = scrollable,
        enabled = true,
        checked = null,
        focused = focused,
        bounds = bounds,
        depth = 1,
        visibleToUser = visible,
    )
}
