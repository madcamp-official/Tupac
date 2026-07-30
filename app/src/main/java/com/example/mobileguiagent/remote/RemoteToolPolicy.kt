package com.example.mobileguiagent.remote

/**
 * Security metadata for every tool that may cross the device boundary.
 *
 * This catalog is deliberately independent from MCP. The cloud relay, the
 * Android command receiver, and the local development MCP server must all use
 * the same policy instead of trusting a model prompt or transport.
 */
enum class RemoteToolRisk {
    READ,
    OBSERVE,
    REVERSIBLE,
    WRITE,
    SENSITIVE,
}

object RemoteToolScope {
    const val STATUS = "device:status"
    const val OBSERVE = "device:observe"
    const val CONTROL = "device:control"
    const val SENSITIVE = "device:sensitive"

    val ALL: Set<String> = setOf(STATUS, OBSERVE, CONTROL, SENSITIVE)
}

data class RemoteToolSpec(
    val name: String,
    val risk: RemoteToolRisk,
    val requiredScope: String,
    val needsCurrentSnapshot: Boolean = false,
    val returnsScreenshot: Boolean = false,
    val returnsUiTree: Boolean = false,
    val targetsSnapshotNode: Boolean = false,
    val requiresLocalConfirmation: Boolean = false,
)

object RemoteToolCatalog {
    private val specs = listOf(
        RemoteToolSpec("device_status", RemoteToolRisk.READ, RemoteToolScope.STATUS),
        RemoteToolSpec(
            "device_list_apps",
            RemoteToolRisk.READ,
            RemoteToolScope.STATUS,
        ),
        RemoteToolSpec(
            "device_observe",
            RemoteToolRisk.OBSERVE,
            RemoteToolScope.OBSERVE,
            needsCurrentSnapshot = true,
            returnsUiTree = true,
        ),
        RemoteToolSpec(
            "device_screenshot",
            RemoteToolRisk.OBSERVE,
            RemoteToolScope.OBSERVE,
            needsCurrentSnapshot = true,
            returnsScreenshot = true,
        ),
        RemoteToolSpec(
            "device_home",
            RemoteToolRisk.REVERSIBLE,
            RemoteToolScope.CONTROL,
        ),
        RemoteToolSpec(
            "device_back",
            RemoteToolRisk.REVERSIBLE,
            RemoteToolScope.CONTROL,
        ),
        RemoteToolSpec(
            "device_scroll",
            RemoteToolRisk.REVERSIBLE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
        ),
        RemoteToolSpec(
            "device_swipe",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
        ),
        RemoteToolSpec(
            "device_click_node",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
        ),
        RemoteToolSpec(
            "device_tap",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
        ),
        RemoteToolSpec(
            "device_type_node",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
        ),
        RemoteToolSpec(
            "device_type_text",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
        ),
        RemoteToolSpec(
            "device_submit_text",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
        ),
        RemoteToolSpec(
            "device_set_progress",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
        ),
        RemoteToolSpec(
            "device_launch_app",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
        ),
        RemoteToolSpec(
            "device_open_uri",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
        ),
        RemoteToolSpec(
            "device_find_node",
            RemoteToolRisk.REVERSIBLE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
        ),
        RemoteToolSpec(
            "device_set_checked",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
        ),
        RemoteToolSpec(
            "device_select_option",
            RemoteToolRisk.WRITE,
            RemoteToolScope.CONTROL,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
        ),
        RemoteToolSpec(
            "device_open_screen",
            RemoteToolRisk.SENSITIVE,
            RemoteToolScope.SENSITIVE,
            requiresLocalConfirmation = true,
        ),
        RemoteToolSpec(
            "device_open_settings",
            RemoteToolRisk.SENSITIVE,
            RemoteToolScope.SENSITIVE,
            requiresLocalConfirmation = true,
        ),
        RemoteToolSpec(
            "device_start_task",
            RemoteToolRisk.SENSITIVE,
            RemoteToolScope.SENSITIVE,
            requiresLocalConfirmation = true,
        ),
        RemoteToolSpec(
            "device_system_action",
            RemoteToolRisk.SENSITIVE,
            RemoteToolScope.SENSITIVE,
            requiresLocalConfirmation = true,
        ),
        RemoteToolSpec(
            "device_list_fields",
            RemoteToolRisk.SENSITIVE,
            RemoteToolScope.SENSITIVE,
            requiresLocalConfirmation = true,
        ),
        RemoteToolSpec(
            "device_fill_field",
            RemoteToolRisk.SENSITIVE,
            RemoteToolScope.SENSITIVE,
            needsCurrentSnapshot = true,
            targetsSnapshotNode = true,
            requiresLocalConfirmation = true,
        ),
    ).associateBy(RemoteToolSpec::name)

    fun find(name: String): RemoteToolSpec? = specs[name]

    fun exposedNames(): Set<String> = specs.keys
}
