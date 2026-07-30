package com.example.mobileguiagent.model

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads agent behavior from Markdown assets so workflows remain editable and
 * independent from the tool executor.
 */
object AgentSkillLoader {
    private val cache = ConcurrentHashMap<String, String>()

    fun listSkillIds(context: Context): List<String> =
        context.assets.list("skills")
            .orEmpty()
            .filter { skillId ->
                runCatching {
                    context.assets.open("skills/$skillId/SKILL.md").close()
                }.isSuccess
            }
            .sorted()

    fun load(context: Context, skillName: String): String =
        cache.getOrPut(skillName) {
            context.assets.open("skills/$skillName/SKILL.md")
                .bufferedReader()
                .use { it.readText() }
                .substringAfterSecond("---")
                .trim()
        }

    fun loadRuntimePolicy(
        context: Context,
        skillName: String,
    ): SkillRuntimePolicy = runCatching {
        val json = context.assets.open("skills/$skillName/runtime-contract.json")
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        SkillRuntimePolicy(
            capabilities = json.optJSONArray("capabilities")
                ?.toStringSet()
                ?.mapNotNull { name ->
                    runCatching { AgentCapability.valueOf(name) }.getOrNull()
                }
                ?.toSet()
                .orEmpty(),
            preferredViewIds = json.optJSONArray("preferred_view_ids")
                ?.toStringSet()
                .orEmpty(),
            forbiddenViewIds = json.optJSONArray("forbidden_view_ids")
                ?.toStringSet()
                .orEmpty(),
            entityNavigationHints = json.optJSONObject("entity_navigation_hints")
                ?.toNestedStringListMap()
                .orEmpty(),
            stateSlotViewIds = json.optJSONObject("state_slot_view_ids")
                ?.toStringSetMap()
                .orEmpty(),
            dateViewIdPatterns = json.optJSONArray("date_view_id_patterns")
                ?.toStringSet()
                .orEmpty(),
            showtimeViewIdPatterns = json.optJSONArray("showtime_view_id_patterns")
                ?.toStringSet()
                .orEmpty(),
            seatCandidatePatterns = json.optJSONArray("seat_candidate_patterns")
                ?.toStringSet()
                .orEmpty(),
            completionLabels = json.optJSONArray("completion_labels")
                ?.toStringSet()
                .orEmpty(),
            activationEntities = json.optJSONObject("activation_entities")
                ?.toStringSetMap()
                .orEmpty(),
        )
    }.getOrDefault(SkillRuntimePolicy())

    private fun org.json.JSONArray.toStringSet(): Set<String> = buildSet {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.toStringSetMap(): Map<String, Set<String>> = buildMap {
        keys().forEach { key ->
            optJSONArray(key)
                ?.toStringSet()
                ?.takeIf(Set<String>::isNotEmpty)
                ?.let { values -> put(key.trim().lowercase(), values) }
        }
    }

    private fun JSONObject.toNestedStringListMap():
        Map<String, Map<String, List<String>>> = buildMap {
        keys().forEach { kind ->
            val routes = optJSONObject(kind) ?: return@forEach
            val parsed = buildMap {
                routes.keys().forEach { entity ->
                    routes.optJSONArray(entity)
                        ?.toStringSet()
                        ?.toList()
                        ?.takeIf(List<String>::isNotEmpty)
                        ?.let { route -> put(entity.trim(), route) }
                }
            }
            if (parsed.isNotEmpty()) put(kind.trim().lowercase(), parsed)
        }
    }

    private fun String.substringAfterSecond(delimiter: String): String {
        val first = indexOf(delimiter)
        if (first < 0) return this
        val second = indexOf(delimiter, first + delimiter.length)
        return if (second < 0) this else substring(second + delimiter.length)
    }

    const val GUI_APP_NAVIGATION = "gui-app-navigation"
}
