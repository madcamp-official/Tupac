package com.example.mobileguiagent.model

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads agent behavior from Markdown assets so workflows remain editable and
 * independent from the tool executor.
 */
object AgentSkillLoader {
    private val cache = ConcurrentHashMap<String, String>()

    fun load(context: Context, skillName: String): String =
        cache.getOrPut(skillName) {
            context.assets.open("skills/$skillName/SKILL.md")
                .bufferedReader()
                .use { it.readText() }
                .substringAfterSecond("---")
                .trim()
        }

    private fun String.substringAfterSecond(delimiter: String): String {
        val first = indexOf(delimiter)
        if (first < 0) return this
        val second = indexOf(delimiter, first + delimiter.length)
        return if (second < 0) this else substring(second + delimiter.length)
    }

    const val GUI_APP_NAVIGATION = "gui-app-navigation"
    const val GUI_OWL_NAVIGATION = "gui-owl-navigation"
}
