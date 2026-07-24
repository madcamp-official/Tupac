package com.example.mobileguiagent.model

import com.example.mobileguiagent.device.DeviceToolCall
import com.example.mobileguiagent.device.DeviceToolDefinition
import com.example.mobileguiagent.device.DeviceToolRegistry
import com.example.mobileguiagent.device.DeviceToolResult
import org.json.JSONArray
import org.json.JSONObject

class LocalDeviceToolAdapter(
    private val registry: DeviceToolRegistry = DeviceToolRegistry(),
) {
    fun promptSection(): String = buildString {
        appendLine("AVAILABLE_DEVICE_TOOLS:")
        appendLine(
            JSONArray(
                registry.definitions.map { definition -> definition.toPromptJson() },
            ).toString(),
        )
        appendLine("To call a tool, return only this JSON object:")
        append("""{"tool":"tool_name","arguments":{}}""")
    }

    fun parseToolCall(modelOutput: String): DeviceToolCall? {
        val jsonText = JSON_OBJECT_PATTERN.find(modelOutput)?.value ?: return null
        val parsed = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val name = parsed.optString("tool").trim()
        if (name.isEmpty() || registry.definitions.none { it.name == name }) return null
        val arguments = parsed.optJSONObject("arguments") ?: JSONObject()
        return DeviceToolCall(name = name, arguments = arguments)
    }

    fun execute(call: DeviceToolCall): DeviceToolResult = registry.execute(call)

    private fun DeviceToolDefinition.toPromptJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", JSONObject(inputSchema.toString()))

    companion object {
        private val JSON_OBJECT_PATTERN = Regex("""\{[\s\S]*}""")
    }
}
