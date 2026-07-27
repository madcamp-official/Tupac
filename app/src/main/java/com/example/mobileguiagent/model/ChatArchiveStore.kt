package com.example.mobileguiagent.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatArchive(
    val id: Long,
    val title: String,
    val savedAt: Long,
    val messages: List<ChatMessage>,
)

/**
 * Small, dependency-free persistence for local chat sessions.
 *
 * Tool traces are regular ChatMessages, so reopening an archive restores the
 * exact user/assistant/tool timeline that was visible when it was saved.
 */
object ChatArchiveStore {
    private const val PREFS = "local_chat_archives"
    private const val KEY_ARCHIVES = "archives"
    private const val MAX_ARCHIVES = 30

    fun load(context: Context): List<ChatArchive> =
        runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ARCHIVES, "[]")
                .orEmpty()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ChatArchive(
                            id = item.getLong("id"),
                            title = item.getString("title"),
                            savedAt = item.getLong("saved_at"),
                            messages = item.getJSONArray("messages").toMessages(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

    fun save(context: Context, archive: ChatArchive): List<ChatArchive> {
        val archives = (listOf(archive) + load(context).filterNot { it.id == archive.id })
            .take(MAX_ARCHIVES)
        write(context, archives)
        return archives
    }

    fun delete(context: Context, archiveId: Long): List<ChatArchive> {
        val archives = load(context).filterNot { it.id == archiveId }
        write(context, archives)
        return archives
    }

    private fun write(context: Context, archives: List<ChatArchive>) {
        val array = JSONArray(
            archives.map { archive ->
                JSONObject()
                    .put("id", archive.id)
                    .put("title", archive.title)
                    .put("saved_at", archive.savedAt)
                    .put(
                        "messages",
                        JSONArray(
                            archive.messages.map { message ->
                                JSONObject()
                                    .put("id", message.id)
                                    .put("role", message.role.name)
                                    .put("text", message.text)
                            },
                        ),
                    )
            },
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ARCHIVES, array.toString())
            .apply()
    }

    private fun JSONArray.toMessages(): List<ChatMessage> = buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(
                ChatMessage(
                    id = item.getLong("id"),
                    role = runCatching {
                        ChatRole.valueOf(item.getString("role"))
                    }.getOrDefault(ChatRole.ASSISTANT),
                    text = item.getString("text"),
                ),
            )
        }
    }
}
