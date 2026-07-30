package com.example.mobileguiagent.agent

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.FillSecretDeviceTool
import com.example.mobileguiagent.device.SetTextDeviceTool
import com.example.mobileguiagent.model.UiSnapshot
import com.example.mobileguiagent.remote.RemoteObservationRedactor
import org.json.JSONObject

/**
 * Single persistence-boundary sanitizer shared by logs, chat traces, and
 * durable workspace state.
 */
object AgentDataSanitizer {
    const val REDACTED = "[REDACTED]"

    fun observation(snapshot: UiSnapshot): UiSnapshot =
        RemoteObservationRedactor.redact(snapshot)

    fun toolArguments(call: DeviceToolCall): JSONObject {
        val copy = JSONObject(call.arguments.toString())
        if (call.name == SetTextDeviceTool.NAME || call.name == FillSecretDeviceTool.NAME) {
            SENSITIVE_ARGUMENT_KEYS.forEach { key ->
                if (copy.has(key)) copy.put(key, REDACTED)
            }
        }
        return copy
    }

    fun toolResultMessage(call: DeviceToolCall, message: String?): String? =
        if (call.name == SetTextDeviceTool.NAME || call.name == FillSecretDeviceTool.NAME) {
            message?.let { "${call.name} completed; value redacted" }
        } else {
            text(message)
        }

    fun text(value: String?): String? {
        var sanitized = value ?: return null
        PATTERNS.forEach { pattern ->
            sanitized = pattern.replace(sanitized, REDACTED)
        }
        return sanitized
    }

    private val SENSITIVE_ARGUMENT_KEYS = setOf("text", "value", "username", "password")
    private val PATTERNS = listOf(
        Regex("""(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)"""),
        Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE),
        Regex("""(?<!\d)(?:\d[ -]?){13,19}(?!\d)"""),
    )
}
