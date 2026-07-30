export const REMOTE_TOOL_NAMES = [
  "device_status",
  "device_observe",
  "device_launch_app",
  "device_open_uri",
  "device_find_node",
  "device_set_checked",
  "device_select_option",
  "device_click_node",
  "device_type_node",
  "device_fill_field",
  "device_scroll",
  "device_submit_text",
  "device_home",
  "device_back",
] as const;

export type RemoteToolName = (typeof REMOTE_TOOL_NAMES)[number];

export interface DeviceCommand {
  id: string;
  deviceId: string;
  toolName: RemoteToolName;
  arguments: Record<string, unknown>;
  grantedScopes: string[];
  createdAt: string;
  expiresAt: string;
}

export interface CommandEnvelope {
  type: "command";
  command: DeviceCommand;
}

export interface DeviceResultEnvelope {
  type: "result";
  commandId: string;
  status: "succeeded" | "failed" | "expired" | "denied";
  result?: unknown;
  error?: {
    code: string;
    message: string;
    retryable?: boolean;
  };
}

export interface HeartbeatEnvelope {
  type: "heartbeat";
}

export type DeviceInboundEnvelope = DeviceResultEnvelope | HeartbeatEnvelope;

export type RelayResult =
  | {
      status: "succeeded";
      commandId: string;
      result: unknown;
    }
  | {
      status: "failed" | "expired" | "denied" | "device_offline";
      commandId?: string;
      error: {
        code: string;
        message: string;
        retryable: boolean;
      };
    };

export interface DeviceConnection {
  send(payload: string): void;
  close(code?: number, reason?: string): void;
}
