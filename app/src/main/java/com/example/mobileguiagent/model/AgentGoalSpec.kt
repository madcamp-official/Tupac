package com.example.mobileguiagent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Model-authored, app-independent interpretation of a user goal.
 *
 * Subjects and entity names are intentionally open strings. A shopping app
 * can use product/variant/quantity while a booking app can use
 * movie/date/audience without changing the runtime schema.
 */
data class AgentGoalSpec(
    val objective: String,
    val entities: List<AgentGoalEntity> = emptyList(),
    val constraints: List<AgentGoalConstraint> = emptyList(),
    val preferences: List<AgentGoalPreference> = emptyList(),
    val successCriteria: List<String> = emptyList(),
    val forbiddenActions: Set<String> = emptySet(),
    val assumptions: List<String> = emptyList(),
) {
    fun promptSection(): String = buildString {
        appendLine("MODEL_AUTHORED_GOAL_SPEC:")
        appendLine(AgentGoalSpecJson.encode(this@AgentGoalSpec))
        append(
            "Treat hard constraints and forbidden actions as immutable. " +
                "Preferences may use their declared fallback only after current-screen evidence.",
        )
    }

    fun constraint(subject: String): AgentGoalConstraint? =
        constraints.firstOrNull { it.subject.equals(subject, ignoreCase = true) }

    fun entityValues(name: String): Set<String> =
        entities.asSequence()
            .filter { it.required && it.name.equals(name, ignoreCase = true) }
            .map(AgentGoalEntity::value)
            .filter(String::isNotBlank)
            .toSet()

    companion object {
        fun minimal(goal: String) = AgentGoalSpec(
            objective = goal.trim(),
            successCriteria = listOf("The requested outcome is visibly satisfied."),
            forbiddenActions = setOf("execute_payment"),
        )
    }
}

data class AgentGoalEntity(
    val name: String,
    val value: String,
    val required: Boolean = true,
)

data class AgentGoalConstraint(
    val subject: String,
    val operator: String,
    val value: String,
    val hard: Boolean = true,
)

data class AgentGoalPreference(
    val subject: String,
    val operator: String,
    val value: String,
    val fallback: String? = null,
)

/** Rejects malformed or oversized model output before it reaches the runner. */
object AgentGoalSpecValidator {
    fun validate(spec: AgentGoalSpec): AgentGoalSpec {
        require(spec.objective.isNotBlank()) { "Goal objective is blank." }
        require(spec.entities.size <= MAX_ENTITIES) { "Too many goal entities." }
        require(spec.constraints.size <= MAX_CONSTRAINTS) { "Too many goal constraints." }
        require(spec.preferences.size <= MAX_PREFERENCES) { "Too many goal preferences." }
        require(spec.successCriteria.size <= MAX_SUCCESS_CRITERIA) {
            "Too many success criteria."
        }
        require(spec.forbiddenActions.size <= MAX_FORBIDDEN_ACTIONS) {
            "Too many forbidden actions."
        }
        require(spec.assumptions.size <= MAX_ASSUMPTIONS) {
            "Too many assumptions."
        }
        spec.entities.forEach { entity ->
            require(entity.name.isSafeKey() && entity.value.isNotBlank()) {
                "Invalid goal entity."
            }
        }
        spec.constraints.forEach { constraint ->
            require(
                constraint.subject.isSafeKey() &&
                    constraint.operator.isSafeKey() &&
                    constraint.value.isNotBlank(),
            ) { "Invalid goal constraint." }
        }
        spec.preferences.forEach { preference ->
            require(
                preference.subject.isSafeKey() &&
                    preference.operator.isSafeKey() &&
                    preference.value.isNotBlank(),
            ) { "Invalid goal preference." }
        }
        return spec.copy(
            objective = spec.objective.trim().take(MAX_VALUE_LENGTH),
            entities = spec.entities
                .map { entity ->
                    entity.copy(
                        name = entity.name.lowercase(),
                        value = entity.value.trim().take(MAX_VALUE_LENGTH),
                    )
                }
                .distinctBy { "${it.name}|${it.value}" },
            constraints = spec.constraints
                .map { constraint ->
                    constraint.copy(
                        subject = constraint.subject.lowercase(),
                        operator = constraint.operator.lowercase(),
                        value = constraint.value.trim().take(MAX_VALUE_LENGTH),
                    )
                }
                .distinctBy { "${it.subject}|${it.operator}|${it.value}" },
            preferences = spec.preferences
                .map { preference ->
                    preference.copy(
                        subject = preference.subject.lowercase(),
                        operator = preference.operator.lowercase(),
                        value = preference.value.trim().take(MAX_VALUE_LENGTH),
                        fallback = preference.fallback
                            ?.trim()
                            ?.take(MAX_VALUE_LENGTH)
                            ?.takeIf(String::isNotBlank),
                    )
                }
                .distinctBy { "${it.subject}|${it.operator}|${it.value}" },
            successCriteria = spec.successCriteria
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { it.take(MAX_VALUE_LENGTH) }
                .distinct(),
            forbiddenActions = spec.forbiddenActions
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(String::lowercase)
                .toSet() + ALWAYS_FORBIDDEN,
            assumptions = spec.assumptions
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { it.take(MAX_VALUE_LENGTH) }
                .distinct(),
        )
    }

    private fun String.isSafeKey(): Boolean =
        isNotBlank() && length <= MAX_KEY_LENGTH && KEY.matches(this)

    private const val MAX_ENTITIES = 32
    private const val MAX_CONSTRAINTS = 48
    private const val MAX_PREFERENCES = 24
    private const val MAX_SUCCESS_CRITERIA = 24
    private const val MAX_FORBIDDEN_ACTIONS = 24
    private const val MAX_ASSUMPTIONS = 24
    private const val MAX_KEY_LENGTH = 80
    private const val MAX_VALUE_LENGTH = 500
    private val KEY = Regex("""^[\p{L}\p{N}_.-]+$""")
    private val ALWAYS_FORBIDDEN = setOf("execute_payment")
}

/** Single JSON representation shared by model adapters and durable storage. */
object AgentGoalSpecJson {
    fun encode(spec: AgentGoalSpec): JSONObject = JSONObject()
        .put("objective", spec.objective)
        .put(
            "entities",
            JSONArray(
                spec.entities.map { entity ->
                    JSONObject()
                        .put("name", entity.name)
                        .put("value", entity.value)
                        .put("required", entity.required)
                },
            ),
        )
        .put(
            "constraints",
            JSONArray(
                spec.constraints.map { constraint ->
                    JSONObject()
                        .put("subject", constraint.subject)
                        .put("operator", constraint.operator)
                        .put("value", constraint.value)
                        .put("hard", constraint.hard)
                },
            ),
        )
        .put(
            "preferences",
            JSONArray(
                spec.preferences.map { preference ->
                    JSONObject()
                        .put("subject", preference.subject)
                        .put("operator", preference.operator)
                        .put("value", preference.value)
                        .put("fallback", preference.fallback ?: JSONObject.NULL)
                },
            ),
        )
        .put("success_criteria", JSONArray(spec.successCriteria))
        .put("forbidden_actions", JSONArray(spec.forbiddenActions.toList()))
        .put("assumptions", JSONArray(spec.assumptions))

    fun decode(json: JSONObject): AgentGoalSpec =
        AgentGoalSpecValidator.validate(
            AgentGoalSpec(
                objective = json.getString("objective"),
                entities = json.optJSONArray("entities").objects().map { entity ->
                    AgentGoalEntity(
                        name = entity.getString("name"),
                        value = entity.getString("value"),
                        required = entity.optBoolean("required", true),
                    )
                },
                constraints = json.optJSONArray("constraints").objects().map { constraint ->
                    AgentGoalConstraint(
                        subject = constraint.getString("subject"),
                        operator = constraint.getString("operator"),
                        value = constraint.getString("value"),
                        hard = constraint.optBoolean("hard", true),
                    )
                },
                preferences = json.optJSONArray("preferences").objects().map { preference ->
                    AgentGoalPreference(
                        subject = preference.getString("subject"),
                        operator = preference.getString("operator"),
                        value = preference.getString("value"),
                        fallback = preference.optionalString("fallback"),
                    )
                },
                successCriteria = json.optJSONArray("success_criteria").strings(),
                forbiddenActions = json.optJSONArray("forbidden_actions").strings().toSet(),
                assumptions = json.optJSONArray("assumptions").strings(),
            ),
        )

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun JSONObject.optionalString(key: String): String? =
        takeIf { has(key) && !isNull(key) }
            ?.optString(key)
            ?.takeIf(String::isNotBlank)
}
