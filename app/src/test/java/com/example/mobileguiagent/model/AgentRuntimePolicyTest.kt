package com.example.mobileguiagent.model

import android.graphics.Rect
import com.example.mobileguiagent.credentials.CredentialFieldRole
import com.example.mobileguiagent.credentials.PublicCredentialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AgentRuntimePolicyTest {
    @Test
    fun parsesExplicitRequestedCalendarDate() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 29)
        }

        assertEquals(
            TaskDate(2026, 8, 2),
            TaskDateParser.extract("8월 2일 오디세이 예매", calendar),
        )
    }

    @Test
    fun rejectsPrefixTheaterAndWrongCalendarDate() {
        val contract = TaskContract(
            originalGoal = "8월 2일 대전",
            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
            requiredSelections = setOf("대전"),
            requiredEntities = mapOf("theater" to setOf("대전")),
            requiredDate = TaskDate(2026, 8, 2),
            stateSlotViewIds = mapOf("theater" to setOf("prevTheaterNm")),
            dateViewIdPatterns = setOf("""^playDate_(\d{8})$"""),
        )
        val wrong = snapshot(
            node(
                "theater",
                text = "대전신세계아트앤사이언스",
                viewId = "prevTheaterNm",
                depth = 1,
            ),
            node(
                "date",
                text = "8월 1일",
                viewId = "playDate_20260801",
                clickable = true,
                depth = 1,
            ),
        )

        assertEquals(
            "SUMMARY_THEATER_MISMATCH",
            TaskStateConstraintInterceptor.summaryMismatch(contract, wrong)?.code,
        )
        assertEquals(
            "DATE_OUTSIDE_REQUESTED_DAY",
            TaskStateConstraintInterceptor.correction(
                contract = contract,
                snapshot = wrong,
                proposedTarget = wrong.nodes.last(),
            )?.code,
        )
    }

    @Test
    fun completionRequiresVisibleRuntimeEvidenceDeclaredBySkill() {
        val contract = TaskContract(
            originalGoal = "결제 직전까지",
            capabilities = emptySet(),
            requiredSelections = emptySet(),
            completionLabels = setOf("결제하기"),
        )

        assertFalse(
            AgentCompletionEvaluator.evaluate(
                contract,
                snapshot(node("seat", text = "D7 선택됨", depth = 1)),
            ).satisfied,
        )
        assertTrue(
            AgentCompletionEvaluator.evaluate(
                contract,
                snapshot(node("payment", text = "결제하기", depth = 1)),
            ).satisfied,
        )
    }

    @Test
    fun genericGoalDoesNotAutoCompleteAndRequiresGroundedModelEvidence() {
        val contract = contract()
        val screen = snapshot(node("cart", text = "장바구니 2개", depth = 1))

        assertFalse(AgentCompletionEvaluator.evaluate(contract, screen).satisfied)
        assertFalse(
            AgentCompletionEvaluator.evaluate(
                contract,
                screen,
                claimedEvidenceLabels = setOf("작업 완료"),
            ).satisfied,
        )
        assertTrue(
            AgentCompletionEvaluator.evaluate(
                contract,
                screen,
                claimedEvidenceLabels = setOf("장바구니 2개"),
            ).satisfied,
        )
    }

    @Test
    fun paymentLabelAloneDoesNotCompleteGeometricSeatGoal() {
        val contract = TaskContract(
            originalGoal = "중앙 좌석을 선택하고 결제 직전까지",
            capabilities = emptySet(),
            requiredSelections = emptySet(),
            selectionPolicies = setOf(AgentSelectionPolicy.GEOMETRIC_CENTER),
            completionLabels = setOf("결제하기"),
        )
        val paymentScreen = snapshot(
            node("payment", text = "결제하기", clickable = true, depth = 1),
        )

        val withoutSeat = AgentCompletionEvaluator.evaluate(contract, paymentScreen)
        val withSeat = AgentCompletionEvaluator.evaluate(
            contract,
            paymentScreen,
            validatedSelections = setOf("seat"),
        )

        assertFalse(withoutSeat.satisfied)
        assertTrue(withoutSeat.missing.contains("validated:seat"))
        assertTrue(withSeat.satisfied)
    }

    @Test
    fun earliestPolicyRejectsFirstDisplayedButLaterShowtime() {
        val snapshot = snapshot(
            node(
                "showtime_1455",
                text = "14:55",
                viewId = "time_movie_01_14551730",
                clickable = true,
                depth = 1,
            ),
            node(
                "showtime_1415",
                text = "14:15",
                viewId = "time_movie_02_14151650",
                clickable = true,
                depth = 1,
            ),
        )
        val contract = TaskContract(
            originalGoal = "가장 빠른 상영시간",
            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
            requiredSelections = emptySet(),
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
            selectionPolicies = setOf(AgentSelectionPolicy.EARLIEST_AVAILABLE),
        )

        val interception = TaskStateConstraintInterceptor.correction(
            contract = contract,
            snapshot = snapshot,
            proposedTarget = snapshot.nodes.first(),
        )

        assertEquals("SHOWTIME_NOT_EARLIEST", interception?.code)
        assertTrue(interception?.message?.contains("14:15") == true)
    }

    @Test
    fun exactMovieShowtimeResolutionFailsClosedWhenMovieLabelIsAbsent() {
        val screen = snapshot(
            node("schedule", depth = 1),
            node(
                "wrong_showtime",
                parent = "schedule",
                text = "09:40",
                viewId = "time_spiderman_01_09401215",
                clickable = true,
                depth = 2,
            ),
        )
        val contract = TaskContract(
            originalGoal = "오디세이를 예매해",
            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
            requiredSelections = emptySet(),
            requiredEntities = mapOf("movie" to setOf("오디세이")),
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
        )

        assertTrue(
            TaskStateConstraintInterceptor.eligibleShowtimes(
                contract = contract,
                snapshot = screen,
                currentMinutes = 0,
            ).isEmpty(),
        )
        assertEquals(
            "SHOWTIME_WRONG_MOVIE",
            TaskStateConstraintInterceptor.correction(
                contract = contract,
                snapshot = screen,
                proposedTarget = screen.nodes.last(),
                currentMinutes = 0,
            )?.code,
        )
    }

    @Test
    fun exhaustedCenterlessShowtimeIsSkippedForNextEligibleCandidate() {
        val screen = snapshot(
            node(
                "showtime_1840",
                text = "18:40",
                viewId = "time_movie_05_18402115",
                clickable = true,
                depth = 1,
            ),
            node(
                "showtime_2125",
                text = "21:25",
                viewId = "time_movie_05_21252400",
                clickable = true,
                depth = 1,
            ),
        )
        val contract = TaskContract(
            originalGoal = "현재 시각 이후 가장 빠른 회차, 중앙 없으면 다음 회차",
            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
            requiredSelections = emptySet(),
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
            selectionPolicies = setOf(
                AgentSelectionPolicy.EARLIEST_AVAILABLE,
                AgentSelectionPolicy.FUTURE_ONLY,
                AgentSelectionPolicy.NEXT_CANDIDATE_FALLBACK,
            ),
        )

        val exhausted = TaskStateConstraintInterceptor.correction(
            contract = contract,
            snapshot = screen,
            proposedTarget = screen.nodes.first(),
            currentMinutes = 18 * 60,
            excludedShowtimeMinutes = setOf(18 * 60 + 40),
        )
        val next = TaskStateConstraintInterceptor.correction(
            contract = contract,
            snapshot = screen,
            proposedTarget = screen.nodes.last(),
            currentMinutes = 18 * 60,
            excludedShowtimeMinutes = setOf(18 * 60 + 40),
        )

        assertEquals("SHOWTIME_EXHAUSTED", exhausted?.code)
        assertNull(next)
    }

    @Test
    fun earliestShowtimeIsScopedToRequestedMovieSection() {
        val screen = snapshot(
            node("root", depth = 0),
            node("schedule", parent = "root", depth = 1),
            node("requested_section", parent = "schedule", depth = 2),
            node(
                "requested_movie",
                parent = "requested_section",
                text = "스파이더맨: 브랜드 뉴 데이",
                depth = 3,
            ),
            node(
                "requested_past",
                parent = "requested_section",
                text = "17:40",
                viewId = "time_spiderman_01_17402015",
                clickable = true,
                depth = 3,
            ),
            node(
                "requested_early",
                parent = "requested_section",
                text = "18:10",
                viewId = "time_spiderman_07_18102045",
                clickable = true,
                depth = 3,
            ),
            node(
                "requested_late",
                parent = "requested_section",
                text = "20:25",
                viewId = "time_spiderman_01_20252300",
                clickable = true,
                depth = 3,
            ),
            node("other_section", parent = "schedule", depth = 2),
            node(
                "other_movie",
                parent = "other_section",
                text = "호프",
                depth = 3,
            ),
            node(
                "other_earlier",
                parent = "other_section",
                text = "17:55",
                viewId = "time_hope_03_17552041",
                clickable = true,
                depth = 3,
            ),
        )
        val contract = TaskContract(
            originalGoal = "스파이더맨의 가장 빠른 상영시간",
            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
            requiredSelections = emptySet(),
            requiredEntities = mapOf("movie" to setOf("스파이더맨")),
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
            selectionPolicies = setOf(
                AgentSelectionPolicy.EARLIEST_AVAILABLE,
                AgentSelectionPolicy.FUTURE_ONLY,
            ),
        )

        assertNull(
            TaskStateConstraintInterceptor.correction(
                contract,
                screen,
                screen.nodes.first { it.id == "requested_early" },
                currentMinutes = 17 * 60 + 50,
            ),
        )
        assertEquals(
            "SHOWTIME_NOT_IN_FUTURE",
            TaskStateConstraintInterceptor.correction(
                contract,
                screen,
                screen.nodes.first { it.id == "requested_past" },
                currentMinutes = 17 * 60 + 50,
            )?.code,
        )
        assertEquals(
            "SHOWTIME_NOT_EARLIEST",
            TaskStateConstraintInterceptor.correction(
                contract,
                screen,
                screen.nodes.first { it.id == "requested_late" },
                currentMinutes = 17 * 60 + 50,
            )?.code,
        )
        assertEquals(
            "SHOWTIME_WRONG_MOVIE",
            TaskStateConstraintInterceptor.correction(
                contract,
                screen,
                screen.nodes.first { it.id == "other_earlier" },
                currentMinutes = 17 * 60 + 50,
            )?.code,
        )
    }

    @Test
    fun contractGrantsCapabilitiesFromTaskSkillAndCompilesModelSelections() {
        val contract = GoalSpecTaskContractCompiler.compile(
            goal = "메가박스 양산에서 오디세이를 예매해",
            spec = AgentGoalSpec(
                objective = "오디세이 예매",
                entities = listOf(
                    AgentGoalEntity("theater", "양산"),
                    AgentGoalEntity("movie", "오디세이"),
                ),
            ),
            skills = AgentSkillBundle(
                taskSkills = listOf(
                    AgentSkill(
                        "book-megabox-movie",
                        "instructions",
                        runtimePolicy = SkillRuntimePolicy(
                            capabilities = setOf(
                                AgentCapability.USE_STORED_CREDENTIALS,
                                AgentCapability.ACKNOWLEDGE_INFORMATION,
                                AgentCapability.ENFORCE_EXACT_SELECTIONS,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(contract.allows(AgentCapability.USE_STORED_CREDENTIALS))
        assertTrue(contract.allows(AgentCapability.ACKNOWLEDGE_INFORMATION))
        assertEquals(setOf("양산"), contract.requiredSelections)
        assertEquals(setOf("오디세이"), contract.requiredEntities["movie"])
    }

    @Test
    fun contractUsesModelEntityWithoutNaturalLanguageRegex() {
        val contract = GoalSpecTaskContractCompiler.compile(
            goal =
                "메가박스 앱을 실행해서 8월 5일 오디세이를 예매해. " +
                    "지역은 대전이고 영화관은 아무 곳이나 골라.",
            spec = AgentGoalSpec(
                objective = "오디세이 예매",
                entities = listOf(AgentGoalEntity("movie", "오디세이")),
            ),
            skills = AgentSkillBundle(
                taskSkills = listOf(
                    AgentSkill(
                        id = "book-megabox-movie",
                        instructions = "instructions",
                        runtimePolicy = SkillRuntimePolicy(
                            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("오디세이"), contract.requiredEntities["movie"])
    }

    @Test
    fun skillContractRejectsForbiddenAffordanceWithoutAppLogicInController() {
        val snapshot = snapshot(
            node(
                "node_0",
                viewId = "com.example:id/wrong_entry",
                clickable = true,
                depth = 0,
            ),
        )
        val contract = TaskContract(
            originalGoal = "complete the task",
            capabilities = emptySet(),
            requiredSelections = emptySet(),
            preferredViewIds = setOf("com.example:id/right_entry"),
            forbiddenViewIds = setOf("com.example:id/wrong_entry"),
        )

        val interception = GoalInvariantInterceptor.correction(
            contract = contract,
            snapshot = snapshot,
            proposedTarget = snapshot.nodes.single(),
        )

        assertEquals("FORBIDDEN_AFFORDANCE", interception?.code)
        assertTrue(interception?.message?.contains("right_entry") == true)
    }

    @Test
    fun exactPreferredAffordanceIsResolvedBeforeNeighboringUnlabeledControls() {
        val screen = snapshot(
            node(
                "menu",
                viewId = "com.example:id/rnb",
                clickable = true,
                depth = 0,
            ),
            node(
                "booking",
                viewId = "com.example:id/booking",
                clickable = true,
                depth = 0,
            ),
        )
        val interception = PreferredAffordanceInterceptor.intercept(
            contract = TaskContract(
                originalGoal = "book",
                capabilities = emptySet(),
                requiredSelections = emptySet(),
                preferredViewIds = setOf("com.example:id/booking"),
            ),
            snapshot = screen,
        )

        assertEquals("PREFERRED_AFFORDANCE_AVAILABLE", interception?.code)
        assertEquals(RuntimeAction.TapNode("booking"), interception?.automaticAction)
    }

    @Test
    fun informationalModalResolvesClickableActionInSameSubtree() {
        val snapshot = snapshot(
            node("node_0", depth = 0),
            node("node_1", parent = "node_0", depth = 1),
            node(
                "node_2",
                parent = "node_1",
                text = "보다 나아진 관람환경을 위해 리클라이너 좌석을 도입하여 " +
                    "리뉴얼 오픈하였습니다. 새롭게 변화된 지점에 많은 관심 부탁드립니다.",
                depth = 2,
            ),
            node(
                "node_3",
                parent = "node_1",
                description = "확인",
                clickable = true,
                depth = 2,
            ),
            node(
                "node_4",
                parent = "node_0",
                text = "14:15",
                clickable = true,
                depth = 1,
            ),
        )

        val interception = BlockingSurfaceInterceptor.intercept(
            contract = contract(AgentCapability.ACKNOWLEDGE_INFORMATION),
            snapshot = snapshot,
        )

        assertEquals("BLOCKING_SURFACE_SAFE_ACTION", interception?.code)
        assertEquals(RuntimeAction.TapNode("node_3"), interception?.automaticAction)
    }

    @Test
    fun bookingNoticeHeadingGroundsDeepConfirmButton() {
        val snapshot = snapshot(
            node("root", depth = 0),
            node("schedule", parent = "root", depth = 1),
            node("modal", parent = "schedule", viewId = "brchReservGuide", depth = 2),
            node("panel", parent = "modal", depth = 3),
            node("heading_wrap", parent = "panel", depth = 4),
            node(
                "heading",
                parent = "heading_wrap",
                text = "예매안내",
                depth = 5,
            ),
            node("body_wrap", parent = "panel", depth = 4),
            node("body", parent = "body_wrap", depth = 5),
            node(
                "body_text",
                parent = "body",
                text = "주차 등록은 모바일티켓 하단의 주차확인바코드를 이용해 주세요.",
                depth = 6,
            ),
            node(
                "button_wrap",
                parent = "panel",
                viewId = "reserveGuideBtn",
                depth = 4,
            ),
            node(
                "confirm",
                parent = "button_wrap",
                description = "확인",
                clickable = true,
                depth = 5,
            ),
        )

        val interception = BlockingSurfaceInterceptor.intercept(
            contract = contract(AgentCapability.ACKNOWLEDGE_INFORMATION),
            snapshot = snapshot,
        )

        assertEquals("BLOCKING_SURFACE_SAFE_ACTION", interception?.code)
        assertEquals(RuntimeAction.TapNode("confirm"), interception?.automaticAction)
    }

    @Test
    fun shortPromotionalOverlayClosesBeforeBackgroundBookingControl() {
        val snapshot = snapshot(
            node("root", depth = 0),
            node("promo", parent = "root", depth = 1),
            node(
                "suppress",
                parent = "promo",
                text = "오늘 그만보기",
                clickable = true,
                depth = 2,
            ),
            node(
                "close",
                parent = "promo",
                text = "닫기",
                clickable = true,
                depth = 2,
            ),
            node("content", parent = "root", depth = 1),
            node(
                "booking",
                parent = "content",
                viewId = "com.example:id/booking",
                clickable = true,
                depth = 2,
            ),
        )

        val interception = BlockingSurfaceInterceptor.intercept(
            contract = contract(AgentCapability.ACKNOWLEDGE_INFORMATION),
            snapshot = snapshot,
        )

        assertEquals("BLOCKING_SURFACE_SAFE_ACTION", interception?.code)
        assertEquals(RuntimeAction.TapNode("close"), interception?.automaticAction)
    }

    @Test
    fun functionalModalWithLongCopyIsNotClosedAsInformationalNotice() {
        val snapshot = snapshot(
            node("root", depth = 0),
            node("ticket_modal", parent = "root", depth = 1),
            node(
                "terms",
                parent = "ticket_modal",
                text = "관람 인원과 좌석을 선택하기 위한 상세 이용 안내입니다. ".repeat(4),
                depth = 2,
            ),
            node(
                "close",
                parent = "ticket_modal",
                text = "닫기",
                clickable = true,
                depth = 2,
            ),
            node(
                "adult_plus",
                parent = "ticket_modal",
                text = "+",
                clickable = true,
                depth = 2,
            ),
        )

        assertNull(
            BlockingSurfaceInterceptor.intercept(
                contract = contract(AgentCapability.ACKNOWLEDGE_INFORMATION),
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun backgroundConfirmOutsideNoticeSubtreeIsNotAutoClicked() {
        val snapshot = snapshot(
            node("node_0", depth = 0),
            node("node_1", parent = "node_0", depth = 1),
            node(
                "node_2",
                parent = "node_1",
                text = "충분히 긴 안내 본문입니다. ".repeat(8),
                depth = 2,
            ),
            node("node_3", parent = "node_0", depth = 1),
            node(
                "node_4",
                parent = "node_3",
                description = "확인",
                clickable = true,
                depth = 2,
            ),
        )

        assertNull(
            BlockingSurfaceInterceptor.intercept(
                contract = contract(AgentCapability.ACKNOWLEDGE_INFORMATION),
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun modalGuardBlocksBackgroundActionOutsideNoticeSubtree() {
        val screen = snapshot(
            node("root", depth = 0),
            node("schedule", parent = "root", depth = 1),
            node(
                "showtime",
                parent = "schedule",
                text = "14:55",
                clickable = true,
                depth = 2,
            ),
            node("modal", parent = "root", depth = 1),
            node(
                "notice",
                parent = "modal",
                text = "보다 나아진 관람환경을 위한 충분히 긴 안내문입니다. ".repeat(5),
                depth = 2,
            ),
            node(
                "confirm",
                parent = "modal",
                text = "확인",
                clickable = true,
                depth = 2,
            ),
        )

        val correction = ModalProgressGuard.correction(
            target = screen.nodes.first { it.id == "showtime" },
            snapshot = screen,
        )

        assertTrue(correction?.contains("long notice/modal") == true)
    }

    @Test
    fun modalGuardAllowsTicketCountControlInsideBookingModal() {
        val screen = snapshot(
            node("root", depth = 0),
            node("seat_preview", parent = "root", viewId = "seatPreviewLayer", depth = 1),
            node("wrap", parent = "seat_preview", depth = 2),
            node("content", parent = "wrap", depth = 3),
            node(
                "notice",
                parent = "content",
                text = "12세 미만 고객은 반드시 성인 보호자와 동반해야 한다는 관람등급 안내입니다. ".repeat(4),
                depth = 4,
            ),
            node("ticket_list", parent = "content", viewId = "ticketKindList", depth = 4),
            node(
                "adult_plus",
                parent = "ticket_list",
                text = "+",
                viewId = "TKA_plus",
                clickable = true,
                depth = 5,
            ),
            node(
                "close",
                parent = "wrap",
                description = "닫기",
                clickable = true,
                depth = 3,
            ),
        )

        val correction = ModalProgressGuard.correction(
            target = screen.nodes.first { it.id == "adult_plus" },
            snapshot = screen,
        )

        assertNull(correction)
    }

    @Test
    fun authenticationIsScreenDrivenRatherThanGoalWordDriven() {
        val resources = listOf(
            PublicCredentialDescriptor(
                id = "R_USER",
                scopeAlias = "account",
                role = CredentialFieldRole.USERNAME,
            ),
            PublicCredentialDescriptor(
                id = "R_PASSWORD",
                scopeAlias = "account",
                role = CredentialFieldRole.PASSWORD,
            ),
        )
        val login = snapshot(
            node("node_0", depth = 0),
            node(
                "node_1",
                parent = "node_0",
                editable = true,
                depth = 1,
            ),
            node(
                "node_2",
                parent = "node_0",
                editable = true,
                password = true,
                depth = 1,
            ),
            node(
                "node_3",
                parent = "node_0",
                text = "로그인",
                clickable = true,
                depth = 1,
            ),
        )

        val interception = AuthenticationInterceptor.intercept(
            contract = TaskContract(
                originalGoal = "영화를 예매해",
                capabilities = setOf(AgentCapability.USE_STORED_CREDENTIALS),
                requiredSelections = emptySet(),
            ),
            snapshot = login,
            resources = resources,
        )

        assertEquals(
            RuntimeAction.FillCredential(
                nodeId = "node_1",
                resourceId = "R_USER",
            ),
            interception?.automaticAction,
        )
    }

    @Test
    fun filledAuthenticationFormResolvesSubmitOnce() {
        val login = snapshot(
            node("node_0", depth = 0),
            node(
                "node_1",
                parent = "node_0",
                text = "[LOCAL_VALUE_REDACTED]",
                editable = true,
                depth = 1,
            ),
            node(
                "node_2",
                parent = "node_0",
                text = "••••",
                editable = true,
                password = true,
                depth = 1,
            ),
            node(
                "node_3",
                parent = "node_0",
                text = "로그인",
                viewId = "loginBtn",
                clickable = true,
                depth = 1,
            ),
        )

        val interception = AuthenticationInterceptor.intercept(
            contract = contract(AgentCapability.USE_STORED_CREDENTIALS),
            snapshot = login,
            resources = emptyList(),
        )

        assertEquals("AUTH_SUBMIT_READY", interception?.code)
        assertEquals(RuntimeAction.TapNode("node_3"), interception?.automaticAction)
    }

    @Test
    fun selectionMismatchBlocksProgressButAllowsAssociatedChangeControl() {
        val snapshot = snapshot(
            node("node_0", depth = 0),
            node("node_1", parent = "node_0", depth = 1),
            node(
                "node_2",
                parent = "node_1",
                description = "극장변경",
                clickable = true,
                depth = 2,
            ),
            node(
                "node_3",
                parent = "node_1",
                text = "울산",
                clickable = true,
                depth = 2,
            ),
            node(
                "node_4",
                parent = "node_0",
                text = "14:15",
                clickable = true,
                depth = 1,
            ),
        )
        val contract = TaskContract(
            originalGoal = "양산에서 예약해",
            capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
            requiredSelections = setOf("양산"),
        )

        val blocked = GoalInvariantInterceptor.correction(
            contract = contract,
            snapshot = snapshot,
            proposedTarget = snapshot.nodes.first { it.id == "node_4" },
        )
        val allowed = GoalInvariantInterceptor.correction(
            contract = contract,
            snapshot = snapshot,
            proposedTarget = snapshot.nodes.first { it.id == "node_2" },
        )

        assertEquals("GOAL_SELECTION_MISMATCH", blocked?.code)
        assertNull(allowed)
    }

    @Test
    fun timeWindowParserUnderstandsInheritedKoreanPmMarker() {
        val window = TaskTemporalParser.extractWindow(
            "개봉일 오후 2시~4시 사이 회차",
        )

        assertEquals(TaskTimeWindow(14 * 60, 16 * 60), window)
    }

    @Test
    fun showtimeOutsideWindowIsRejectedBeforeClick() {
        val screen = snapshot(
            node("root", depth = 0),
            node(
                "card",
                parent = "root",
                viewId = "time_movie_a_03_13101545",
                clickable = true,
                depth = 1,
            ),
            node(
                "label",
                parent = "card",
                text = "13:10",
                depth = 2,
            ),
        )
        val contract = constrainedContract(
            showtimeViewIdPatterns = setOf("""^time_.*_(\d{4})\d{4}$"""),
        )

        val interception = TaskStateConstraintInterceptor.correction(
            contract = contract,
            snapshot = screen,
            proposedTarget = screen.nodes.last(),
        )

        assertEquals("SHOWTIME_OUTSIDE_WINDOW", interception?.code)
        assertEquals(
            "view:time_movie_a_03_13101545",
            TaskStateConstraintInterceptor.stableTargetKey(
                contract,
                screen.nodes.last(),
                screen,
            ),
        )
    }

    @Test
    fun mismatchingStructuredMovieSummaryBlocksProgressButAllowsClose() {
        val screen = snapshot(
            node("root", depth = 0),
            node(
                "movie",
                parent = "root",
                text = "스파이더맨: 브랜드 뉴 데이",
                viewId = "prevMovieNm",
                depth = 1,
            ),
            node(
                "theater",
                parent = "root",
                text = "양산3관(리클라이너)",
                viewId = "prevTheaterNm",
                depth = 1,
            ),
            node(
                "date",
                parent = "root",
                text = "07/29(수)13:10",
                viewId = "prevDate",
                depth = 1,
            ),
            node(
                "next",
                parent = "root",
                text = "동의하고 좌석선택",
                clickable = true,
                depth = 1,
            ),
            node(
                "close",
                parent = "root",
                description = "닫기",
                clickable = true,
                depth = 1,
            ),
        )
        val contract = constrainedContract(
            requiredEntities = mapOf(
                "theater" to setOf("양산"),
                "movie" to setOf("오디세이"),
            ),
            stateSlotViewIds = mapOf(
                "theater" to setOf("prevTheaterNm"),
                "movie" to setOf("prevMovieNm"),
                "time" to setOf("prevDate"),
            ),
        )

        val blocked = TaskStateConstraintInterceptor.correction(
            contract,
            screen,
            screen.nodes.first { it.id == "next" },
        )
        val closeAllowed = TaskStateConstraintInterceptor.correction(
            contract,
            screen,
            screen.nodes.first { it.id == "close" },
        )

        assertEquals("SUMMARY_MOVIE_MISMATCH", blocked?.code)
        assertNull(closeAllowed)
    }

    @Test
    fun rejectedAttemptsReachLimitWithoutExecutedAction() {
        val history = AgentAttemptHistory(maxSameRejections = 2)

        assertFalse(history.recordRejection("screen-a", "BLOCKING_SURFACE_UNRESOLVED"))
        assertTrue(history.recordRejection("screen-a", "BLOCKING_SURFACE_UNRESOLVED"))
        history.onScreenChanged()
        assertFalse(history.recordRejection("screen-b", "BLOCKING_SURFACE_UNRESOLVED"))
    }

    private fun contract(vararg capabilities: AgentCapability) = TaskContract(
        originalGoal = "test",
        capabilities = capabilities.toSet(),
        requiredSelections = emptySet(),
    )

    private fun constrainedContract(
        requiredEntities: Map<String, Set<String>> = emptyMap(),
        stateSlotViewIds: Map<String, Set<String>> = emptyMap(),
        showtimeViewIdPatterns: Set<String> = emptySet(),
    ) = TaskContract(
        originalGoal = "오디세이를 오후 2시~4시에 예매",
        capabilities = setOf(AgentCapability.ENFORCE_EXACT_SELECTIONS),
        requiredSelections = emptySet(),
        requiredEntities = requiredEntities,
        timeWindow = TaskTimeWindow(14 * 60, 16 * 60),
        stateSlotViewIds = stateSlotViewIds,
        showtimeViewIdPatterns = showtimeViewIdPatterns,
    )

    private fun snapshot(vararg nodes: UiNode) = UiSnapshot(
        packageName = "com.example.target",
        nodes = nodes.toList(),
    )

    private fun node(
        id: String,
        parent: String? = null,
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        depth: Int,
    ) = UiNode(
        id = id,
        parentId = parent,
        text = text,
        contentDescription = description,
        className = "android.view.View",
        viewId = viewId,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        checked = null,
        selected = false,
        password = password,
        bounds = Rect(0, depth * 10, 100, depth * 10 + 10),
        depth = depth,
        visibleToUser = true,
    )
}
