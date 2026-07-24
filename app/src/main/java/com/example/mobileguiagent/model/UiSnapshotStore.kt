package com.example.mobileguiagent.model

import android.content.Context
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Process-safe handoff between AccessibilityService observation and model
 * inference. This also survives an Activity/service recreation during a test.
 */
object UiSnapshotStore {
    private const val TAG = "UiSnapshotStore"
    private const val FILE_NAME = "latest_ui_snapshot.json"

    fun write(context: Context, snapshot: UiSnapshot) {
        val target = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        val nodes = JSONArray()
        snapshot.nodes.forEach { node ->
            nodes.put(
                JSONObject()
                    .put("id", node.id)
                    .put("text", node.text ?: JSONObject.NULL)
                    .put("content_description", node.contentDescription ?: JSONObject.NULL)
                    .put("class_name", node.className ?: JSONObject.NULL)
                    .put("view_id", node.viewId ?: JSONObject.NULL)
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("enabled", node.enabled)
                    .put("checked", node.checked ?: JSONObject.NULL)
                    .put("bounds", node.bounds.flattenToString())
                    .put("depth", node.depth),
            )
        }
        val json = JSONObject()
            .put("package_name", snapshot.packageName)
            .put("captured_at_millis", snapshot.capturedAtMillis)
            .put("nodes", nodes)

        temporary.writeText(json.toString())
        if (!temporary.renameTo(target)) {
            target.writeText(json.toString())
            temporary.delete()
        }
    }

    fun read(context: Context): UiSnapshot? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val items = json.getJSONArray("nodes")
            val nodes = buildList {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    add(
                        UiNode(
                            id = item.getString("id"),
                            text = item.optNullableString("text"),
                            contentDescription = item.optNullableString("content_description"),
                            className = item.optNullableString("class_name"),
                            viewId = item.optNullableString("view_id"),
                            clickable = item.getBoolean("clickable"),
                            editable = item.getBoolean("editable"),
                            scrollable = item.getBoolean("scrollable"),
                            enabled = item.getBoolean("enabled"),
                            checked = if (item.isNull("checked")) {
                                null
                            } else {
                                item.getBoolean("checked")
                            },
                            bounds = Rect.unflattenFromString(item.getString("bounds")) ?: Rect(),
                            depth = item.getInt("depth"),
                        ),
                    )
                }
            }
            UiSnapshot(
                packageName = json.getString("package_name"),
                nodes = nodes,
                capturedAtMillis = json.getLong("captured_at_millis"),
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to read persisted UI snapshot", error)
        }.getOrNull()
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}
