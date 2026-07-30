package com.example.mobileguiagent.model

import android.content.Context
import com.example.mobileguiagent.cloud.AgentMeasuredGoalSpec
import com.example.mobileguiagent.cloud.GeminiModel
import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolResult
import com.example.mobileguiagent.agent.AgentBootstrapMode
import com.example.mobileguiagent.agent.AgentWorkspace
import com.example.mobileguiagent.agent.AgentDataSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Append-only, private JSONL audit log for one agent run.
 *
 * Files live under the app-private files directory and are never uploaded.
 * Screenshots and secret field values are deliberately not persisted.
 */
class AgentRunLogStore private constructor(
    val runId: String,
    val file: File,
    private val workspaceId: String,
    private val writer: BufferedWriter,
) : Closeable {
    @Synchronized
    internal fun recordStart(
        goal: String,
        model: GeminiModel,
        runContext: AgentRunContext,
        workspace: AgentWorkspace,
        bootstrapMode: AgentBootstrapMode,
        goalInterpretation: AgentMeasuredGoalSpec?,
    ) {
        append(
            JSONObject()
                .put("type", "run_start")
                .put("goal", AgentDataSanitizer.text(goal))
                .put("model", model.apiId)
                .put("bootstrap_mode", bootstrapMode.name)
                .put("workspace_id", workspace.id)
                .put(
                    "skills",
                    JSONArray(runContext.skills.all.map(AgentSkill::id)),
                )
                .put("goal_spec", workspace.goalSpec?.let(AgentGoalSpecJson::encode))
                .put("goal_interpretation_cache_hit", goalInterpretation == null)
                .put("goal_interpretation_latency_ms", goalInterpretation?.latencyMs)
                .put("goal_interpretation_request_bytes", goalInterpretation?.requestBytes)
                .put("goal_interpretation_prompt_tokens", goalInterpretation?.promptTokenCount)
                .put(
                    "goal_interpretation_candidate_tokens",
                    goalInterpretation?.candidatesTokenCount,
                )
                .put("goal_interpretation_total_tokens", goalInterpretation?.totalTokenCount),
        )
    }

    @Synchronized
    fun recordTrace(event: AgentTraceEvent) {
        append(
            when (event) {
                is AgentTraceEvent.PlannerDecision -> JSONObject()
                    .put("type", "planner_decision")
                    .put("step", event.step)
                    .put("model", event.model)
                    .put("action", event.action)
                    .put("reason_code", event.reasonCode)
                    .put("target", AgentDataSanitizer.text(event.target))
                    .put("expected_change", AgentDataSanitizer.text(event.expectedChange))
                    .put("message", AgentDataSanitizer.text(event.message))
                    .put("latency_ms", event.latencyMs)
                    .put("request_bytes", event.requestBytes)
                    .put("prompt_tokens", event.promptTokenCount)
                    .put("candidate_tokens", event.candidatesTokenCount)
                    .put("total_tokens", event.totalTokenCount)
                    .put(
                        "plan",
                        JSONArray(
                            event.plan.map { item ->
                                AgentDataSanitizer.text(item)
                            },
                        ),
                    )
                    .put(
                        "progress_summary",
                        AgentDataSanitizer.text(event.progressSummary),
                    )

                is AgentTraceEvent.ToolCall -> JSONObject()
                    .put("type", "tool_call")
                    .put("step", event.step)
                    .put("call_id", event.callId)
                    .put("idempotency_key", "$workspaceId:$runId:${event.callId}")
                    .put("tool", event.call.name)
                    .put("expected_change", AgentDataSanitizer.text(event.expectedChange))
                    .put("target_key", AgentDataSanitizer.text(event.targetKey))
                    .put("arguments", AgentDataSanitizer.toolArguments(event.call))

                is AgentTraceEvent.ActionVerification -> JSONObject()
                    .put("type", "action_verification")
                    .put("step", event.step)
                    .put("call_id", event.callId)
                    .put("tool", event.tool)
                    .put("target_key", AgentDataSanitizer.text(event.targetKey))
                    .put("verified", event.verified)
                    .put("resumed", event.resumed)
                    .put("evidence", JSONArray(event.evidence))
                    .put("message", AgentDataSanitizer.text(event.message))

                is AgentTraceEvent.RuntimeRoute -> JSONObject()
                    .put("type", "runtime_route")
                    .put("step", event.step)
                    .put("runtime", event.runtime)
                    .put("reason", AgentDataSanitizer.text(event.reason))

                is AgentTraceEvent.RuntimeFact -> JSONObject()
                    .put("type", "runtime_fact")
                    .put("step", event.step)
                    .put("key", AgentDataSanitizer.text(event.key))
                    .put("value", AgentDataSanitizer.text(event.value))

                is AgentTraceEvent.LatencySample -> JSONObject()
                    .put("type", "latency_sample")
                    .put("step", event.step)
                    .put("stage", event.stage)
                    .put("duration_ms", event.durationMs)
                    .put("expected_ms", event.expectedMs)
                    .put("operation", event.operation)
                    .put("attempt", event.attempt)

                is AgentTraceEvent.ToolResult -> JSONObject()
                    .put("type", "tool_result")
                    .put("step", event.step)
                    .put("call_id", event.callId)
                    .put("tool", event.call.name)
                    .put("automatic", event.automatic)
                    .put("result", resultJson(event.call, event.result))
            },
        )
    }

    @Synchronized
    fun recordEnd(outcome: AgentOutcome) {
        append(
            JSONObject()
                .put("type", "run_end")
                .put("status", AgentDataSanitizer.text(outcome.status))
                .put("disposition", outcome.disposition.name)
                .put("stop_reason", outcome.stopReason.name)
                .put("message", AgentDataSanitizer.text(outcome.message))
                .put("steps", outcome.steps),
        )
        close()
    }

    @Synchronized
    fun recordError(error: Throwable, cancelled: Boolean = false) {
        append(
            JSONObject()
                .put("type", if (cancelled) "run_cancelled" else "run_error")
                .put("error_type", error::class.java.simpleName)
                .put("message", AgentDataSanitizer.text(error.message)),
        )
        close()
    }

    private fun append(payload: JSONObject) {
        payload.put("run_id", runId)
        payload.put("workspace_id", workspaceId)
        payload.put("timestamp_ms", System.currentTimeMillis())
        writer.write(payload.toString())
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() {
        writer.close()
    }

    private fun resultJson(
        call: DeviceToolCall,
        result: DeviceToolResult,
    ): JSONObject = when (result) {
        is DeviceToolResult.UiObservation -> {
            val snapshot = AgentDataSanitizer.observation(result.snapshot)
            JSONObject()
                .put("kind", "ui_observation")
                .put("package", snapshot.packageName)
                .put("captured_at_ms", snapshot.capturedAtMillis)
                .put("fingerprint", snapshot.fingerprint.hash)
                .put(
                    "nodes",
                    JSONArray(
                        snapshot.nodes.map { node ->
                            JSONObject()
                                .put("id", node.id)
                                .put("parent_id", node.parentId)
                                .put(
                                    "text",
                                    if (node.password) JSONObject.NULL else node.text,
                                )
                                .put("content_description", node.contentDescription)
                                .put("hint", node.hint)
                                .put("class_name", node.className)
                                .put("role_description", node.roleDescription)
                                .put("view_id", node.viewId)
                                .put("clickable", node.clickable)
                                .put("editable", node.editable)
                                .put("scrollable", node.scrollable)
                                .put("enabled", node.enabled)
                                .put("checked", node.checked)
                                .put("selected", node.selected)
                                .put("password", node.password)
                                .put("focused", node.focused)
                                .put("bounds", node.bounds.flattenToString())
                                .put("depth", node.depth)
                                .put("visible_to_user", node.visibleToUser)
                        },
                    ),
                )
        }

        is DeviceToolResult.Action -> JSONObject()
            .put("kind", "action")
            .put("action", result.action)
            .put("success", result.success)
            .put("message", AgentDataSanitizer.toolResultMessage(call, result.message))

        is DeviceToolResult.Success -> JSONObject()
            .put("kind", "success")
            .put("message", AgentDataSanitizer.toolResultMessage(call, result.message))

        is DeviceToolResult.Error -> JSONObject()
            .put("kind", "error")
            .put("code", result.code)
            .put("message", AgentDataSanitizer.text(result.message))

        is DeviceToolResult.Screenshot -> JSONObject()
            .put("kind", "screenshot_metadata")
            .put("width", result.width)
            .put("height", result.height)
            .put("persisted", false)
    }

    companion object {
        private const val DIRECTORY = "agent-runs"

        fun create(
            context: Context,
            workspaceId: String,
        ): AgentRunLogStore {
            val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            val runId = UUID.randomUUID().toString()
            val file = File(directory, "run-$runId.jsonl")
            return AgentRunLogStore(
                runId = runId,
                file = file,
                workspaceId = workspaceId,
                writer = file.outputStream().bufferedWriter(Charsets.UTF_8),
            )
        }
    }
}
