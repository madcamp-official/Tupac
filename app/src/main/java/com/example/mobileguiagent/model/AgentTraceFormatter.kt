package com.example.mobileguiagent.model

import com.example.mobileguiagent.device.DeviceToolResult

/**
 * Converts internal tool results into short, user-readable timeline entries.
 * Keeping this out of LocalChatRepository leaves the repository responsible
 * for state transitions rather than presentation wording.
 */
internal object AgentTraceFormatter {
    fun format(result: DeviceToolResult): String = when (result) {
        is DeviceToolResult.Action ->
            "${if (result.success) "성공" else "실패"} · ${result.message}"

        is DeviceToolResult.Error ->
            "오류 ${result.code} · ${result.message}"

        is DeviceToolResult.Screenshot ->
            "스크린샷 ${result.width}×${result.height}"

        is DeviceToolResult.Success ->
            result.message ?: "성공"

        is DeviceToolResult.UiObservation ->
            "${result.snapshot.packageName} · 노드 ${result.snapshot.nodes.size}개"
    }
}
