package com.example.mobileguiagent.remote

import com.example.mobileguiagent.agent.PrivacyReason
import com.example.mobileguiagent.agent.ScreenPrivacyRouter
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot

object RemoteObservationRedactor {
    private const val REDACTED = "[SENSITIVE_VALUE_REDACTED]"

    fun redact(snapshot: UiSnapshot): UiSnapshot {
        val decision = ScreenPrivacyRouter.route(snapshot)
        val redactAllText = PrivacyReason.SENSITIVE_PACKAGE in decision.reasons
        return snapshot.copy(
            nodes = snapshot.nodes.map { node ->
                if (
                    redactAllText ||
                    // Editable text is user-provided data, even when Android
                    // does not mark the field as a password and the value does
                    // not match one of our PII regexes. On any screen routed
                    // through this redactor it must never cross the device
                    // boundary.
                    node.editable ||
                    node.password ||
                    node.id in decision.sensitiveNodeIds
                ) {
                    node.redacted()
                } else {
                    node
                }
            },
        )
    }

    private fun UiNode.redacted(): UiNode = copy(
        text = text?.let { REDACTED },
        contentDescription = contentDescription?.let { REDACTED },
        hint = hint?.let { REDACTED },
        viewId = null,
        inputType = 0,
    )
}
