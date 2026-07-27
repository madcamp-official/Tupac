package com.example.mobileguiagent.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mobileguiagent.model.LocalChatRepository

/**
 * Internal same-app entry point for voice, wake-word, and automated QA flows.
 *
 * It is deliberately not exported. The foreground screen remains untouched
 * while the cloud agent observes and executes the requested GUI task.
 */
class GeminiCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_GOAL) return
        val goal = intent.getStringExtra(EXTRA_GOAL)?.trim().orEmpty()
        if (goal.isEmpty()) return
        Log.i(TAG, "Background Gemini goal received")
        LocalChatRepository.sendWithGemini(
            context = context.applicationContext,
            input = goal,
            controllerVisible = false,
        )
    }

    companion object {
        const val ACTION_RUN_GOAL =
            "com.example.mobileguiagent.action.RUN_GEMINI_GOAL"
        const val EXTRA_GOAL = "goal"
        private const val TAG = "GeminiCommandReceiver"
    }
}
