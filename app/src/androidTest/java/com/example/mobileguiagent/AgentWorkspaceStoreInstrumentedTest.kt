package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mobileguiagent.agent.AgentWorkspace
import com.example.mobileguiagent.agent.AgentWorkspaceJson
import com.example.mobileguiagent.agent.AgentWorkspaceReducer
import com.example.mobileguiagent.agent.AgentWorkspaceRevisionConflict
import com.example.mobileguiagent.agent.AgentWorkspaceStatus
import com.example.mobileguiagent.agent.FileAgentWorkspaceStore
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.model.AgentRunContext
import com.example.mobileguiagent.model.AgentRunContextResolver
import com.example.mobileguiagent.model.AgentSkillBundle
import com.example.mobileguiagent.model.TaskContract
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentWorkspaceStoreInstrumentedTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun modelGoalSpecActivatesOnlyMatchingTaskSkill() {
        val catalog = AgentRunContextResolver.loadSkillCatalog(
            context = context,
            navigationSkillId = "gui-app-navigation",
        )
        val booking = AgentRunContextResolver.selectSkills(
            catalog,
            com.example.mobileguiagent.model.AgentGoalSpec(
                objective = "영화 예매",
                entities = listOf(
                    com.example.mobileguiagent.model.AgentGoalEntity("app", "메가박스"),
                ),
            ),
        )
        val settings = AgentRunContextResolver.selectSkills(
            catalog,
            com.example.mobileguiagent.model.AgentGoalSpec(
                objective = "설정 열기",
                entities = listOf(
                    com.example.mobileguiagent.model.AgentGoalEntity("app", "설정"),
                ),
            ),
        )

        assertTrue(booking.taskSkills.any { it.id == "book-megabox-movie" })
        assertTrue(settings.taskSkills.isEmpty())
        assertEquals("gui-app-navigation", booking.navigationSkill?.id)
    }

    @Test
    fun codecRoundTripPreservesPendingIntentAndMigratesSchemaOne() {
        val staged = stagedWorkspace()
        val encoded = AgentWorkspaceJson.encode(staged)
        val decoded = AgentWorkspaceJson.decode(encoded.toString())

        assertEquals(AgentWorkspace.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(staged.goalSpec, decoded.goalSpec)
        assertEquals(staged.pendingAction, decoded.pendingAction)
        assertEquals(
            staged.resumePoint?.semanticSignature,
            decoded.resumePoint?.semanticSignature,
        )

        encoded.put("schema_version", 1)
        encoded.getJSONObject("resume_point").remove("semantic_signature")
        encoded.getJSONObject("pending_action").remove("before_semantic_signature")
        val migrated = AgentWorkspaceJson.decode(encoded.toString())

        assertEquals(AgentWorkspace.SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(
            migrated.resumePoint?.screenFingerprint,
            migrated.resumePoint?.semanticSignature,
        )
    }

    @Test
    fun atomicStoreRecoversLatestCheckpointAndRejectsStaleRevision() {
        val store = FileAgentWorkspaceStore.create(context)
        val original = workspace()
        try {
            store.save(original, "workspace_created")
            val running = AgentWorkspaceReducer.beginRun(original, "run-1", now = 2)
            store.save(running, "run_started")

            assertThrows(AgentWorkspaceRevisionConflict::class.java) {
                store.save(running, "duplicate_revision")
            }

            val snapshot = File(
                context.filesDir,
                "agent-workspaces/${original.id}/workspace.json",
            )
            snapshot.writeText("{corrupt")
            val recovered = store.load(original.id)

            assertNotNull(recovered)
            assertEquals(running.revision, recovered?.revision)
            assertEquals("run-1", recovered?.currentRunId)
        } finally {
            store.delete(original.id)
        }
    }

    @Test
    fun legacyRedactedGoalKeyIsNeverAutoResumed() {
        val store = FileAgentWorkspaceStore.create(context)
        val legacy = workspace().copy(goalKey = "테스트 앱 열기")
        var replacementId: String? = null
        try {
            store.save(legacy, "workspace_created")
            val replacement = store.openOrCreate(
                goal = "테스트 앱 열기",
                skillDigests = emptyMap(),
            ) {
                workspace()
            }
            replacementId = replacement.id

            assertNotEquals(legacy.id, replacement.id)
            assertTrue(replacement.goalKey.startsWith("sha256:"))
            assertEquals(
                AgentWorkspaceStatus.STALE,
                store.load(legacy.id)?.status,
            )
        } finally {
            store.delete(legacy.id)
            replacementId?.let(store::delete)
        }
    }

    private fun stagedWorkspace(): AgentWorkspace {
        val observed = AgentWorkspaceReducer.observe(
            workspace = workspace(),
            snapshot = UiSnapshot(
                packageName = "example.app",
                nodes = listOf(
                    UiNode(
                        id = "node_1",
                        text = "다음",
                        contentDescription = null,
                        className = "android.widget.Button",
                        viewId = "example.app:id/next",
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                        checked = null,
                        bounds = Rect(0, 0, 100, 100),
                        depth = 1,
                    ),
                ),
            ),
            contract = contract(),
            now = 10,
        )
        return AgentWorkspaceReducer.stageToolIntent(
            workspace = observed,
            call = DeviceToolCall("tap_node"),
            step = 2,
            callId = "call-2",
            expectedChange = "다음 화면",
            targetKey = "next",
            now = 11,
        )
    }

    private fun workspace(): AgentWorkspace = AgentWorkspace.create(
        goal = "테스트 앱 열기",
        runContext = AgentRunContext(
            goal = "테스트 앱 열기",
            skills = AgentSkillBundle.EMPTY,
            taskContract = contract(),
        ),
        now = 1,
    )

    private fun contract() = TaskContract(
        originalGoal = "테스트 앱 열기",
        capabilities = emptySet(),
        requiredSelections = emptySet(),
    )
}
