package com.example.mobileguiagent.agent

/** Planner selected for the next user request. */
enum class AgentRuntimeMode {
    /** On-device VLM; no cloud inference request is made. */
    LOCAL,

    /** Gemini vision planner; Android actions still execute on the phone. */
    GEMINI,
}
