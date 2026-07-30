package com.example.mobileguiagent.model

import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * Canonical, model-independent context for one agent run.
 *
 * Model adapters may format this context differently, but every runtime gets
 * the same original goal and task-specific skills. Navigation skills may vary
 * because a screenshot-native model and a text/tool model need different
 * grounding instructions.
 */
data class AgentRunContext(
    val goal: String,
    val skills: AgentSkillBundle,
    val taskContract: TaskContract,
    val goalSpec: AgentGoalSpec = taskContract.goalSpec,
)

data class AgentSkill(
    val id: String,
    val instructions: String,
    val runtimePolicy: SkillRuntimePolicy = SkillRuntimePolicy(),
) {
    val digest: String by lazy {
        val policy = listOf(
            runtimePolicy.capabilities.map { it.name }.sorted(),
            runtimePolicy.preferredViewIds.sorted(),
            runtimePolicy.forbiddenViewIds.sorted(),
            runtimePolicy.entityNavigationHints.toSortedMap()
                .mapValues { (_, hints) ->
                    hints.toSortedMap().mapValues { (_, route) -> route.toList() }
                },
            runtimePolicy.stateSlotViewIds.toSortedMap()
                .mapValues { (_, values) -> values.sorted() },
            runtimePolicy.dateViewIdPatterns.sorted(),
            runtimePolicy.showtimeViewIdPatterns.sorted(),
            runtimePolicy.seatCandidatePatterns.sorted(),
            runtimePolicy.completionLabels.sorted(),
            runtimePolicy.activationEntities.toSortedMap()
                .mapValues { (_, values) -> values.sorted() },
        ).joinToString("|")
        MessageDigest.getInstance("SHA-256")
            .digest("$id|$instructions|$policy".toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

data class SkillRuntimePolicy(
    val capabilities: Set<AgentCapability> = emptySet(),
    val preferredViewIds: Set<String> = emptySet(),
    val forbiddenViewIds: Set<String> = emptySet(),
    val entityNavigationHints: Map<String, Map<String, List<String>>> = emptyMap(),
    val stateSlotViewIds: Map<String, Set<String>> = emptyMap(),
    val dateViewIdPatterns: Set<String> = emptySet(),
    val showtimeViewIdPatterns: Set<String> = emptySet(),
    val seatCandidatePatterns: Set<String> = emptySet(),
    val completionLabels: Set<String> = emptySet(),
    /** Semantic GoalSpec entities that activate this optional task skill. */
    val activationEntities: Map<String, Set<String>> = emptyMap(),
)

data class AgentSkillBundle(
    val taskSkills: List<AgentSkill> = emptyList(),
    val navigationSkill: AgentSkill? = null,
) {
    val all: List<AgentSkill>
        get() = taskSkills + listOfNotNull(navigationSkill)

    fun promptSection(): String = buildString {
        if (all.isEmpty()) return@buildString
        appendLine("LOADED_SKILLS:")
        all.forEach { skill ->
            appendLine("<skill id=\"${skill.id}\">")
            appendLine(skill.instructions)
            appendLine("</skill>")
        }
        append(
            "Treat skills as task guidance only. The current screen, safety policy, " +
                "and registered tool schemas remain authoritative.",
        )
    }

    fun activationCatalogSection(): String = buildString {
        if (taskSkills.isEmpty()) return@buildString
        appendLine("AVAILABLE_OPTIONAL_SKILLS:")
        taskSkills.forEach { skill ->
            appendLine(
                "- ${skill.id}: activation_entities=" +
                    skill.runtimePolicy.activationEntities,
            )
        }
        append(
            "These entries only describe optional accelerators. Represent the user's " +
                "intent faithfully even when no skill matches.",
        )
    }

    companion object {
        val EMPTY = AgentSkillBundle()
    }
}

/**
 * Loads the skill catalog and activates task skills from model-authored
 * semantic entities.
 *
 * Keeping selection out of individual controllers prevents cloud, local, and
 * privacy-routed planners from silently receiving different app knowledge.
 */
object AgentRunContextResolver {
    fun loadSkillCatalog(
        context: Context,
        navigationSkillId: String? = null,
    ): AgentSkillBundle {
        val taskSkills = AgentSkillLoader.listSkillIds(context)
            .asSequence()
            .filterNot { it == navigationSkillId }
            .mapNotNull { skillId -> loadSkill(context, skillId) }
            .toList()
        return AgentSkillBundle(
            taskSkills = taskSkills,
            navigationSkill = navigationSkillId?.let { loadSkill(context, it) },
        )
    }

    fun selectSkills(
        catalog: AgentSkillBundle,
        spec: AgentGoalSpec,
    ): AgentSkillBundle = catalog.copy(
        taskSkills = catalog.taskSkills.filter { skill ->
            skill.runtimePolicy.matches(spec)
        },
    )

    internal fun SkillRuntimePolicy.matches(spec: AgentGoalSpec): Boolean {
        if (activationEntities.isEmpty()) return false
        return activationEntities.all { (name, acceptedValues) ->
            val actual = spec.entityValues(name).map(::normalizeActivationValue)
            val accepted = acceptedValues.map(::normalizeActivationValue)
            actual.any { value -> value in accepted }
        }
    }

    private fun normalizeActivationValue(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun loadSkill(context: Context, skillId: String): AgentSkill? =
        runCatching {
            AgentSkill(
                id = skillId,
                instructions = AgentSkillLoader.load(context, skillId),
                runtimePolicy = AgentSkillLoader.loadRuntimePolicy(context, skillId),
            )
        }.onFailure { error ->
            Log.w(TAG, "skill_load_failed id=$skillId", error)
        }.getOrNull()

    private const val TAG = "AgentRunContext"
}
