package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.agent.AgentBootstrapIssueCode
import com.example.mobileguiagent.agent.AgentBootstrapMode
import com.example.mobileguiagent.agent.AgentBootstrapResult
import com.example.mobileguiagent.agent.AgentBootstrapper
import com.example.mobileguiagent.agent.AgentRunCoordinator
import com.example.mobileguiagent.agent.AgentWorkspace
import com.example.mobileguiagent.agent.AgentWorkspaceStore
import com.example.mobileguiagent.agent.AgentWorkspaceStatus
import com.example.mobileguiagent.agent.FileAgentWorkspaceStore
import com.example.mobileguiagent.cloud.AgentPlanner
import com.example.mobileguiagent.cloud.AgentGoalInterpretationRequest
import com.example.mobileguiagent.cloud.AgentGoalInterpreter
import com.example.mobileguiagent.cloud.AgentMeasuredGoalSpec
import com.example.mobileguiagent.cloud.GeminiAgentRunner
import com.example.mobileguiagent.cloud.GeminiMeasuredDecision
import com.example.mobileguiagent.cloud.GeminiModel
import com.example.mobileguiagent.cloud.GeminiPlannerAction
import com.example.mobileguiagent.cloud.GeminiPlannerRequest
import com.example.mobileguiagent.device.DeviceToolExecutor
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.device.ObserveUiDeviceTool
import com.example.mobileguiagent.model.AgentRunDisposition
import com.example.mobileguiagent.model.AgentGoalSpec
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentBootstrapperInstrumentedTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun newAndResumedRunsShareWorkspaceButUseDistinctRunIds() = runBlocking {
        val goal = "bootstrap-resume-${UUID.randomUUID()}"
        val interpreter = FakeGoalInterpreter()
        val bootstrapper = AgentBootstrapper(goalInterpreter = interpreter)
        val store = FileAgentWorkspaceStore.create(context)
        var first: AgentBootstrapResult.Ready? = null
        var second: AgentBootstrapResult.Ready? = null
        var workspaceId: String? = null

        try {
            first = requireReady(
                bootstrapper.bootstrap(
                    context = context,
                    apiKey = "instrumented-test-key",
                    model = GeminiModel.FLASH_LITE_3_1,
                    goal = goal,
                ),
            )
            workspaceId = first.session.workspace.id

            assertEquals(AgentBootstrapMode.NEW, first.mode)
            assertEquals(first.runLog.runId, first.session.workspace.currentRunId)
            assertEquals(listOf(first.runLog.runId), first.session.workspace.runIds)
            assertRunStart(first, AgentBootstrapMode.NEW)
            assertEquals(
                listOf("workspace_created", "run_started"),
                workspaceEvents(workspaceId).take(2),
            )

            pauseAndClose(first)

            second = requireReady(
                bootstrapper.bootstrap(
                    context = context,
                    apiKey = "instrumented-test-key",
                    model = GeminiModel.FLASH_LITE_3_1,
                    goal = goal,
                ),
            )
            assertEquals(AgentBootstrapMode.RESUMED, second.mode)
            assertEquals(1, interpreter.calls)
            assertEquals(workspaceId, second.session.workspace.id)
            assertNotEquals(first.runLog.runId, second.runLog.runId)
            assertEquals(second.runLog.runId, second.session.workspace.currentRunId)
            assertEquals(
                listOf(first.runLog.runId, second.runLog.runId),
                second.session.workspace.runIds,
            )
            assertRunStart(second, AgentBootstrapMode.RESUMED)
        } finally {
            second?.let(::pauseAndClose)
            first?.let(::closeArtifacts)
            workspaceId?.let(store::delete)
        }
    }

    @Test
    fun missingConfigurationReturnsTypedSetupResultWithoutCreatingRun() = runBlocking {
        val result = AgentBootstrapper().bootstrap(
            context = context,
            apiKey = " ",
            model = GeminiModel.FLASH_LITE_3_1,
            goal = "설정 검증 ${UUID.randomUUID()}",
        )

        assertTrue(result is AgentBootstrapResult.SetupRequired)
        result as AgentBootstrapResult.SetupRequired
        assertEquals(AgentBootstrapIssueCode.API_KEY_MISSING, result.code)
    }

    @Test
    fun failureAfterLogCreationClosesAndDeletesPartialRunLog() = runBlocking {
        val runDirectory = File(context.filesDir, "agent-runs")
        val filesBefore = runDirectory.listFiles().orEmpty().map(File::getName).toSet()
        val failingStore = FailOnBeginStore()
        val result = AgentBootstrapper(
            goalInterpreter = FakeGoalInterpreter(),
            workspaceStoreFactory = { failingStore },
        ).bootstrap(
            context = context,
            apiKey = "instrumented-test-key",
            model = GeminiModel.FLASH_LITE_3_1,
            goal = "bootstrap-failure-${UUID.randomUUID()}",
        )

        assertTrue(result is AgentBootstrapResult.Failed)
        result as AgentBootstrapResult.Failed
        assertEquals(AgentBootstrapIssueCode.INITIALIZATION_FAILED, result.code)
        assertEquals("synthetic run_started failure", result.cause.message)
        assertTrue(failingStore.deleted)
        assertEquals(
            filesBefore,
            runDirectory.listFiles().orEmpty().map(File::getName).toSet(),
        )
    }

    @Test
    fun coordinatorConsumesBootstrapAndOwnsSuccessfulTerminalization() = runBlocking {
        val goal = "bootstrap-coordinator-${UUID.randomUUID()}"
        val snapshot = UiSnapshot(
            packageName = "com.example.bootstrap",
            nodes = List(12) { index ->
                UiNode(
                    id = "node_$index",
                    text = "항목 $index",
                    contentDescription = null,
                    className = "android.widget.TextView",
                    viewId = "com.example.bootstrap:id/item_$index",
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    checked = null,
                    bounds = Rect(0, index * 10, 100, index * 10 + 10),
                    depth = 1,
                )
            },
        )
        val executor = DeviceToolExecutor { call ->
            if (call.name == ObserveUiDeviceTool.NAME) {
                DeviceToolResult.UiObservation(snapshot)
            } else {
                DeviceToolResult.Error(
                    code = "UNEXPECTED_TOOL",
                    message = call.name,
                )
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                apiKey: String,
                model: GeminiModel,
                request: GeminiPlannerRequest,
            ): GeminiMeasuredDecision = GeminiMeasuredDecision(
                action = GeminiPlannerAction(
                    action = "finish_success",
                    reasonCode = "TEST_COMPLETE",
                    target = "항목 0",
                    expectedChange = "none",
                    message = "bootstrap lifecycle complete",
                ),
                requestBytes = 1,
                promptTokenCount = 1,
                candidatesTokenCount = 1,
                totalTokenCount = 2,
            )
        }
        val result = AgentRunCoordinator(
            deviceTools = executor,
            bootstrapper = AgentBootstrapper(goalInterpreter = FakeGoalInterpreter()),
            runner = GeminiAgentRunner(executor, planner),
        ).execute(
            context = context,
            apiKey = "instrumented-test-key",
            model = GeminiModel.FLASH_LITE_3_1,
            goal = goal,
            onProgress = {},
            onTrace = {},
        )
        val store = FileAgentWorkspaceStore.create(context)

        try {
            assertEquals(AgentRunDisposition.SUCCEEDED, result.outcome.disposition)
            assertEquals(
                AgentWorkspaceStatus.COMPLETED,
                store.load(result.workspaceId)?.status,
            )
            val logEvents = File(result.logPath)
                .readLines()
                .filter(String::isNotBlank)
                .map(::JSONObject)
            assertEquals("run_start", logEvents.first().getString("type"))
            assertEquals(
                AgentBootstrapMode.NEW.name,
                logEvents.first().getString("bootstrap_mode"),
            )
            assertEquals("run_end", logEvents.last().getString("type"))
            assertEquals("SUCCEEDED", logEvents.last().getString("disposition"))
        } finally {
            store.delete(result.workspaceId)
            File(result.logPath).delete()
        }
    }

    private fun requireReady(result: AgentBootstrapResult): AgentBootstrapResult.Ready {
        assertTrue("Expected Ready but got $result", result is AgentBootstrapResult.Ready)
        return result as AgentBootstrapResult.Ready
    }

    private fun assertRunStart(
        ready: AgentBootstrapResult.Ready,
        expectedMode: AgentBootstrapMode,
    ) {
        val start = JSONObject(ready.runLog.file.readLines().first())
        assertEquals("run_start", start.getString("type"))
        assertEquals(expectedMode.name, start.getString("bootstrap_mode"))
        assertEquals(ready.runLog.runId, start.getString("run_id"))
        assertEquals(ready.session.workspace.id, start.getString("workspace_id"))
    }

    private fun pauseAndClose(ready: AgentBootstrapResult.Ready) {
        if (ready.session.workspace.currentRunId != null) {
            ready.session.cancel("instrumented bootstrap test pause")
            ready.runLog.recordError(
                CancellationException("instrumented bootstrap test pause"),
                cancelled = true,
            )
        }
        closeArtifacts(ready)
    }

    private fun closeArtifacts(ready: AgentBootstrapResult.Ready) {
        runCatching { ready.runLog.close() }
        runCatching { ready.runLog.file.delete() }
    }

    private fun workspaceEvents(workspaceId: String): List<String> =
        File(
            context.filesDir,
            "agent-workspaces/$workspaceId/events.jsonl",
        ).readLines()
            .filter(String::isNotBlank)
            .map { JSONObject(it).getString("event") }

    private class FakeGoalInterpreter : AgentGoalInterpreter {
        var calls: Int = 0
            private set

        override suspend fun interpret(
            apiKey: String,
            model: GeminiModel,
            request: AgentGoalInterpretationRequest,
        ): AgentMeasuredGoalSpec {
            calls += 1
            return AgentMeasuredGoalSpec(
                spec = AgentGoalSpec.minimal(request.goal),
                latencyMs = 1,
                requestBytes = 1,
                promptTokenCount = 1,
                candidatesTokenCount = 1,
                totalTokenCount = 2,
            )
        }
    }

    private class FailOnBeginStore : AgentWorkspaceStore {
        private var workspace: AgentWorkspace? = null
        var deleted: Boolean = false
            private set

        override fun openOrCreate(
            goal: String,
            skillDigests: Map<String, String>,
            factory: () -> AgentWorkspace,
        ): AgentWorkspace = factory().also { workspace = it }

        override fun load(workspaceId: String): AgentWorkspace? = workspace

        override fun save(
            workspace: AgentWorkspace,
            event: String,
        ): AgentWorkspace {
            if (event == "run_started") {
                error("synthetic run_started failure")
            }
            this.workspace = workspace
            return workspace
        }

        override fun listResumable(): List<AgentWorkspace> =
            listOfNotNull(workspace)

        override fun delete(workspaceId: String): Boolean {
            workspace = null
            deleted = true
            return true
        }
    }
}
