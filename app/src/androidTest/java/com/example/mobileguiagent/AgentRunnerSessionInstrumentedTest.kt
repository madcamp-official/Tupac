package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.agent.AgentWorkspace
import com.example.mobileguiagent.agent.AgentWorkspaceSession
import com.example.mobileguiagent.agent.AgentWorkspaceStatus
import com.example.mobileguiagent.agent.FileAgentWorkspaceStore
import com.example.mobileguiagent.cloud.AgentPlanner
import com.example.mobileguiagent.cloud.GeminiAgentRunner
import com.example.mobileguiagent.cloud.GeminiMeasuredDecision
import com.example.mobileguiagent.cloud.GeminiModel
import com.example.mobileguiagent.cloud.GeminiPlannerAction
import com.example.mobileguiagent.cloud.GeminiPlannerRequest
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import com.example.mobileguiagent.device.TapNodeDeviceTool
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentRunDisposition
import com.example.mobileguiagent.model.AgentSkillBundle
import com.example.mobileguiagent.model.AgentStopReason
import com.example.mobileguiagent.model.AgentTraceEvent
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentRunnerSessionInstrumentedTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scriptedRunPersistsIntentCorrelatesResultVerifiesAndFinishes() = runBlocking {
        val goal = "scripted-agent-e2e-${UUID.randomUUID()}"
        val before = bookingSnapshot(actionLabel = "좌석 선택")
        val after = bookingSnapshot(actionLabel = "결제하기")
        val planner = ScriptedPlanner(
            GeminiPlannerAction(
                action = "tap_node",
                reasonCode = "SELECT_SEAT",
                target = "좌석 선택",
                expectedChange = "결제하기 버튼 표시",
                nodeId = ACTION_NODE_ID,
                plan = listOf("좌석 선택", "결제 경계 검증"),
                progressSummary = "좌석 선택 직전",
            ),
            GeminiPlannerAction(
                action = "finish_success",
                reasonCode = "PAYMENT_BOUNDARY_VISIBLE",
                target = "결제하기",
                expectedChange = "작업 완료",
                message = "결제 직전 경계를 확인했습니다.",
                plan = listOf("좌석 선택", "결제 경계 검증"),
                progressSummary = "결제하기가 보여 완료",
            ),
        )
        val executor = ScriptedDeviceToolExecutor(before, after)
        val runContext = AgentRunContext(
            goal = goal,
            skills = AgentSkillBundle.EMPTY,
            taskContract = TaskContract(
                originalGoal = goal,
                capabilities = emptySet(),
                requiredSelections = emptySet(),
                completionLabels = setOf("결제하기"),
            ),
        )
        val store = FileAgentWorkspaceStore.create(context)
        val initial = store.openOrCreate(
            goal = goal,
            skillDigests = emptyMap(),
        ) {
            AgentWorkspace.create(goal, runContext)
        }
        val session = AgentWorkspaceSession(
            store = store,
            initial = initial,
            runContext = runContext,
        )
        val workspaceId = initial.id
        val traces = mutableListOf<RecordedTrace>()

        try {
            session.begin("run-${UUID.randomUUID()}")
            val outcome = GeminiAgentRunner(
                deviceTools = executor,
                planner = planner,
            ).run(
                context = context,
                apiKey = "scripted-test-key",
                model = GeminiModel.FLASH_LITE_3_1,
                goal = goal,
                runContext = runContext,
                workspaceProvider = { session.workspace },
                onProgress = {},
                onTrace = { event ->
                    session.consume(event)
                    traces += RecordedTrace(
                        event = event,
                        pendingCallId = session.workspace.pendingAction?.callId,
                        workspaceRevision = session.workspace.revision,
                    )
                },
            )
            session.finish(outcome)

            assertEquals(AgentRunDisposition.SUCCEEDED, outcome.disposition)
            assertEquals(AgentStopReason.GOAL_COMPLETED, outcome.stopReason)
            assertEquals(1, outcome.steps)

            val firstObserveIndex = traces.indexOfFirst { record ->
                record.event is AgentTraceEvent.ToolResult &&
                    record.event.call.name == ObserveUiDeviceTool.NAME
            }
            val actionDecisionIndex = traces.indexOfFirst { record ->
                record.event is AgentTraceEvent.PlannerDecision &&
                    record.event.action == "tap_node"
            }
            val toolCallIndex = traces.indexOfFirst { record ->
                record.event is AgentTraceEvent.ToolCall &&
                    record.event.call.name == TapNodeDeviceTool.NAME
            }
            val toolResultIndex = traces.indexOfFirst { record ->
                record.event is AgentTraceEvent.ToolResult &&
                    record.event.call.name == TapNodeDeviceTool.NAME
            }
            val verificationIndex = traces.indexOfFirst { record ->
                record.event is AgentTraceEvent.ActionVerification
            }
            val finishDecisionIndex = traces.indexOfFirst { record ->
                record.event is AgentTraceEvent.PlannerDecision &&
                    record.event.action == "finish_success"
            }

            assertTrue(firstObserveIndex in 0 until actionDecisionIndex)
            assertTrue(actionDecisionIndex < toolCallIndex)
            assertTrue(toolCallIndex < toolResultIndex)
            assertTrue(toolResultIndex < verificationIndex)
            assertTrue(verificationIndex < finishDecisionIndex)

            val toolCallRecord = traces[toolCallIndex]
            val toolCall = toolCallRecord.event as AgentTraceEvent.ToolCall
            val toolResultRecord = traces[toolResultIndex]
            val toolResult = toolResultRecord.event as AgentTraceEvent.ToolResult
            val verificationRecord = traces[verificationIndex]
            val verification =
                verificationRecord.event as AgentTraceEvent.ActionVerification

            assertTrue(toolCall.callId.isNotBlank())
            assertEquals(toolCall.callId, toolResult.callId)
            assertEquals(toolCall.callId, verification.callId)
            assertEquals(toolCall.callId, toolCallRecord.pendingCallId)
            assertEquals(toolCall.callId, toolResultRecord.pendingCallId)
            assertNull(verificationRecord.pendingCallId)
            assertTrue(toolCallRecord.workspaceRevision < toolResultRecord.workspaceRevision)
            assertTrue(toolResultRecord.workspaceRevision < verificationRecord.workspaceRevision)
            assertTrue(verification.verified)
            assertTrue("SEMANTIC_UI_CHANGED" in verification.evidence)
            assertEquals(
                before.fingerprint.hash,
                toolCall.call.arguments.getString("snapshot_id"),
            )

            assertEquals(2, planner.requests.size)
            assertNotNull(planner.requests.first().workspace)
            val finishRequestWorkspace =
                requireNotNull(planner.requests.last().workspace)
            assertNull(finishRequestWorkspace.pendingAction)
            assertTrue(
                finishRequestWorkspace.facts["tool.last_verification"]
                    ?.value
                    ?.startsWith("verified:") == true,
            )

            assertEquals(
                listOf(
                    ObserveUiDeviceTool.NAME,
                    TapNodeDeviceTool.NAME,
                    ObserveUiDeviceTool.NAME,
                ),
                executor.calls.map(DeviceToolCall::name),
            )
            assertEquals(
                ACTION_NODE_ID,
                executor.calls
                    .first { it.name == TapNodeDeviceTool.NAME }
                    .arguments
                    .getString("node_id"),
            )

            val persisted = requireNotNull(store.load(workspaceId))
            assertEquals(AgentWorkspaceStatus.COMPLETED, persisted.status)
            assertNull(persisted.pendingAction)
            assertEquals(1, persisted.completedActions)

            val workspaceEvents = workspaceEvents(workspaceId)
            assertSubsequence(
                actual = workspaceEvents.map(WorkspaceEvent::name),
                expected = listOf(
                    "workspace_created",
                    "run_started",
                    "tool_result",
                    "planner_decision",
                    "tool_call",
                    "tool_result",
                    "tool_result",
                    "action_verification",
                    "planner_decision",
                    "run_finished",
                ),
            )
            assertTrue(
                workspaceEvents.zipWithNext().all { (first, second) ->
                    first.revision < second.revision
                },
            )
        } finally {
            assertTrue(store.delete(workspaceId))
            assertFalse(store.delete(workspaceId))
        }
    }

    private fun workspaceEvents(workspaceId: String): List<WorkspaceEvent> =
        File(
            context.filesDir,
            "agent-workspaces/$workspaceId/events.jsonl",
        ).readLines()
            .filter(String::isNotBlank)
            .map { line ->
                val event = JSONObject(line)
                WorkspaceEvent(
                    name = event.getString("event"),
                    revision = event.getLong("revision"),
                )
            }

    private fun assertSubsequence(
        actual: List<String>,
        expected: List<String>,
    ) {
        var cursor = 0
        actual.forEach { value ->
            if (cursor < expected.size && value == expected[cursor]) {
                cursor += 1
            }
        }
        assertEquals(
            "Expected ordered subsequence $expected in $actual",
            expected.size,
            cursor,
        )
    }

    private fun bookingSnapshot(actionLabel: String): UiSnapshot = UiSnapshot(
        packageName = PACKAGE_NAME,
        nodes = listOf(
            node("title", "빠른 예매", "title"),
            node("theater", "울산", "theater"),
            node("movie", "스파이더맨", "movie"),
            node("time", "14:15", "time"),
            node("people", "성인 1", "people"),
            node("seat", "D7", "seat"),
            node(
                id = ACTION_NODE_ID,
                text = actionLabel,
                viewId = "continue",
                clickable = true,
                className = "android.widget.Button",
            ),
        ),
        capturedAtMillis = 1L,
    )

    private fun node(
        id: String,
        text: String,
        viewId: String,
        clickable: Boolean = false,
        className: String = "android.widget.TextView",
    ): UiNode = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        className = className,
        viewId = "$PACKAGE_NAME:id/$viewId",
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(0, 0, 100, 100),
        depth = 1,
    )

    private data class RecordedTrace(
        val event: AgentTraceEvent,
        val pendingCallId: String?,
        val workspaceRevision: Long,
    )

    private data class WorkspaceEvent(
        val name: String,
        val revision: Long,
    )

    private class ScriptedPlanner(
        vararg actions: GeminiPlannerAction,
    ) : AgentPlanner {
        private val remaining = ArrayDeque(actions.toList())
        val requests = mutableListOf<GeminiPlannerRequest>()

        override suspend fun decide(
            apiKey: String,
            model: GeminiModel,
            request: GeminiPlannerRequest,
        ): GeminiMeasuredDecision {
            requests += request
            check(remaining.isNotEmpty()) {
                "The runner requested more planner turns than the script supplied."
            }
            return GeminiMeasuredDecision(
                action = remaining.removeFirst(),
                requestBytes = 1,
                promptTokenCount = 1,
                candidatesTokenCount = 1,
                totalTokenCount = 2,
            )
        }
    }

    private class ScriptedDeviceToolExecutor(
        before: UiSnapshot,
        after: UiSnapshot,
    ) : DeviceToolExecutor {
        private val observations = ArrayDeque(listOf(before, after))
        val calls = mutableListOf<DeviceToolCall>()

        override fun execute(call: DeviceToolCall): DeviceToolResult = synchronized(this) {
            calls += DeviceToolCall(
                name = call.name,
                arguments = JSONObject(call.arguments.toString()),
            )
            when (call.name) {
                ObserveUiDeviceTool.NAME -> {
                    check(observations.isNotEmpty()) {
                        "The runner requested more observations than the script supplied."
                    }
                    DeviceToolResult.UiObservation(observations.removeFirst())
                }

                TapNodeDeviceTool.NAME -> {
                    if (call.arguments.optString("node_id") != ACTION_NODE_ID) {
                        DeviceToolResult.Error(
                            code = "UNEXPECTED_NODE",
                            message = "The scripted action targeted the wrong node.",
                        )
                    } else {
                        DeviceToolResult.Action(
                            action = TapNodeDeviceTool.NAME,
                            success = true,
                            message = "The scripted control was activated.",
                        )
                    }
                }

                else -> DeviceToolResult.Error(
                    code = "UNEXPECTED_TOOL",
                    message = "Unexpected scripted tool: ${call.name}",
                )
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.scripted"
        const val ACTION_NODE_ID = "node_action"
    }
}
