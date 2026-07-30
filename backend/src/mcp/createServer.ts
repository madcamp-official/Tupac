import { McpServer } from "@modelcontextprotocol/server";
import { z } from "zod";

import type { AuthInfo } from "@modelcontextprotocol/server";

import { userIdFromAuthInfo } from "../auth/tokenVerifier.js";
import type { DeviceStore } from "../devices/DeviceStore.js";
import type { DeviceBrokerRegistry } from "../relay/DeviceBrokerRegistry.js";
import type { RelayResult, RemoteToolName } from "../relay/protocol.js";

export function createMobileGuiMcpServer(
  registry: DeviceBrokerRegistry,
  store: DeviceStore,
  authInfo: AuthInfo | undefined,
): McpServer {
  const userId = userIdFromAuthInfo(authInfo);
  const oauthClientId = authInfo?.clientId;
  const server = new McpServer(
    {
      name: "mobile-gui-agent",
      version: "0.2.0",
    },
    {
      capabilities: {
        tools: {},
      },
    },
  );

  server.registerTool(
    "device_status",
    {
      description:
        "Returns the selected Android device and AccessibilityService status.",
      inputSchema: z.object({}),
    },
    async () =>
      relay(registry, store, userId, oauthClientId, "device_status", {}),
  );

  server.registerTool(
    "device_observe",
    {
      description:
        "Reads the current Android accessibility UI tree after on-device privacy filtering.",
      inputSchema: z.object({
        max_nodes: z.number().int().min(1).max(200).default(80),
      }),
    },
    async ({ max_nodes }) =>
      relay(registry, store, userId, oauthClientId, "device_observe", {
        max_nodes,
      }),
  );

  server.registerTool(
    "device_back",
    {
      description: "Performs the reversible Android system Back action once.",
      inputSchema: z.object({}),
    },
    async () =>
      relay(registry, store, userId, oauthClientId, "device_back", {}),
  );

  server.registerTool(
    "device_launch_app",
    {
      description:
        "Launches an installed Android app by its visible Korean or English name.",
      inputSchema: z.object({
        name: z.string().min(1),
      }),
    },
    async ({ name }) =>
      relay(registry, store, userId, oauthClientId, "device_launch_app", {
        name,
      }),
  );

  server.registerTool(
    "device_open_uri",
    {
      description:
        "Opens an absolute HTTPS URL. Arbitrary URI schemes and generic Android intents are rejected on the phone.",
      inputSchema: z.object({
        uri: z.string().url().startsWith("https://"),
      }),
    },
    async ({ uri }) =>
      relay(registry, store, userId, oauthClientId, "device_open_uri", {
        uri,
      }),
  );

  server.registerTool(
    "device_find_node",
    {
      description:
        "Finds visible UI text, optionally scrolling a visible container up to a bounded number of times. Returns a fresh snapshot_id and node_id without clicking.",
      inputSchema: z.object({
        text: z.string().min(1).max(200),
        scroll: z.boolean().default(false),
        max_scrolls: z.number().int().min(0).max(5).default(3),
      }),
    },
    async ({ text, scroll, max_scrolls }) =>
      relay(registry, store, userId, oauthClientId, "device_find_node", {
        text,
        scroll,
        max_scrolls,
      }),
  );

  server.registerTool(
    "device_set_checked",
    {
      description:
        "Idempotently sets a checkbox, switch, or toggle from the newest device_observe snapshot to the requested state.",
      inputSchema: z.object({
        snapshot_id: z.string().min(1),
        node_id: z.string().min(1),
        checked: z.boolean(),
      }),
    },
    async ({ snapshot_id, node_id, checked }) =>
      relay(registry, store, userId, oauthClientId, "device_set_checked", {
        snapshot_id,
        node_id,
        checked,
      }),
  );

  server.registerTool(
    "device_select_option",
    {
      description:
        "Opens one selector from the newest device_observe snapshot and selects an exact visible option by label.",
      inputSchema: z.object({
        snapshot_id: z.string().min(1),
        node_id: z.string().min(1),
        option: z.string().min(1).max(200),
      }),
    },
    async ({ snapshot_id, node_id, option }) =>
      relay(registry, store, userId, oauthClientId, "device_select_option", {
        snapshot_id,
        node_id,
        option,
      }),
  );

  server.registerTool(
    "device_click_node",
    {
      description:
        "Clicks one node from the newest device_observe snapshot. The phone rejects stale, sensitive, authentication, and irreversible payment targets.",
      inputSchema: z.object({
        snapshot_id: z.string().min(1),
        node_id: z.string().min(1),
      }),
    },
    async ({ snapshot_id, node_id }) =>
      relay(registry, store, userId, oauthClientId, "device_click_node", {
        snapshot_id,
        node_id,
      }),
  );

  server.registerTool(
    "device_type_node",
    {
      description:
        "Replaces the text in one editable node from the newest device_observe snapshot. The entered value is not returned.",
      inputSchema: z.object({
        snapshot_id: z.string().min(1),
        node_id: z.string().min(1),
        text: z.string(),
      }),
    },
    async ({ snapshot_id, node_id, text }) =>
      relay(registry, store, userId, oauthClientId, "device_type_node", {
        snapshot_id,
        node_id,
        text,
      }),
  );

  server.registerTool(
    "device_fill_field",
    {
      description:
        "Fills one login input with an encrypted value stored only on the phone. The value is never sent to or returned by this MCP server. A short-lived approval on the phone is required.",
      inputSchema: z.object({
        snapshot_id: z.string().min(1),
        node_id: z.string().min(1),
        field: z.enum(["username", "password"]),
      }),
    },
    async ({ snapshot_id, node_id, field }) =>
      relay(registry, store, userId, oauthClientId, "device_fill_field", {
        snapshot_id,
        node_id,
        field,
      }),
  );

  server.registerTool(
    "device_scroll",
    {
      description:
        "Scrolls one exact container from a prior Android observation.",
      inputSchema: z.object({
        snapshot_id: z.string().min(1),
        node_id: z.string().min(1),
        direction: z.enum(["up", "down", "left", "right"]),
      }),
    },
    async ({ snapshot_id, node_id, direction }) =>
      relay(registry, store, userId, oauthClientId, "device_scroll", {
        snapshot_id,
        node_id,
        direction,
      }),
  );

  server.registerTool(
    "device_submit_text",
    {
      description:
        "Submits the current editable field using its Android Search, Go, or Done IME action.",
      inputSchema: z.object({}),
    },
    async () =>
      relay(registry, store, userId, oauthClientId, "device_submit_text", {}),
  );

  server.registerTool(
    "device_home",
    {
      description: "Navigates to the Android home screen.",
      inputSchema: z.object({}),
    },
    async () =>
      relay(registry, store, userId, oauthClientId, "device_home", {}),
  );

  return server;
}

async function relay(
  registry: DeviceBrokerRegistry,
  store: DeviceStore,
  userId: string,
  oauthClientId: string | undefined,
  toolName: RemoteToolName,
  args: Record<string, unknown>,
) {
  const { deviceId, result } = await registry.executeForUser(
    userId,
    toolName,
    args,
  );
  await store
    .audit({
      userId,
      ...(deviceId ? { deviceId } : {}),
      ...(oauthClientId ? { oauthClientId } : {}),
      eventType: "mcp_tool_call",
      toolName,
      outcome: result.status,
      ...(result.status === "succeeded"
        ? {}
        : { errorCode: result.error.code }),
    })
    .catch((error: unknown) => {
      process.stderr.write(
        JSON.stringify({
          level: "error",
          event: "audit_log_write_failed",
          toolName,
          message: error instanceof Error ? error.message : "unknown_error",
        }) + "\n",
      );
    });
  return mcpResult(result);
}

function mcpResult(result: RelayResult) {
  return {
    content: [
      {
        type: "text" as const,
        text: JSON.stringify(result),
      },
    ],
    isError: result.status !== "succeeded",
  };
}
