import { randomUUID } from "node:crypto";

import type {
  CommandEnvelope,
  DeviceConnection,
  DeviceInboundEnvelope,
  DeviceResultEnvelope,
  RelayResult,
  RemoteToolName,
} from "./protocol.js";

interface PendingCommand {
  resolve: (result: RelayResult) => void;
  timeout: NodeJS.Timeout;
}

export class DeviceBroker {
  private connection: DeviceConnection | undefined;
  private readonly pending = new Map<string, PendingCommand>();
  private lastSeenAt: Date | undefined;

  constructor(
    readonly deviceId: string,
    private readonly commandTimeoutMs: number,
  ) {}

  get online(): boolean {
    return this.connection !== undefined;
  }

  get lastSeen(): Date | undefined {
    return this.lastSeenAt;
  }

  attach(connection: DeviceConnection): void {
    this.connection?.close(4001, "replaced_by_new_connection");
    this.connection = connection;
    this.lastSeenAt = new Date();
  }

  detach(connection: DeviceConnection): void {
    if (this.connection !== connection) return;
    this.connection = undefined;
    this.failAllPending("DEVICE_DISCONNECTED", "휴대폰 연결이 끊겼습니다.", true);
  }

  receive(rawPayload: string): void {
    let envelope: DeviceInboundEnvelope;
    try {
      envelope = JSON.parse(rawPayload) as DeviceInboundEnvelope;
    } catch {
      return;
    }
    this.lastSeenAt = new Date();
    if (envelope.type === "heartbeat") return;
    if (envelope.type === "result") this.complete(envelope);
  }

  execute(
    toolName: RemoteToolName,
    argumentsValue: Record<string, unknown>,
  ): Promise<RelayResult> {
    const connection = this.connection;
    if (!connection) {
      return Promise.resolve({
        status: "device_offline",
        error: {
          code: "DEVICE_OFFLINE",
          message: "연결된 Android 기기가 오프라인입니다.",
          retryable: true,
        },
      });
    }
    if (this.pending.size > 0) {
      return Promise.resolve({
        status: "failed",
        error: {
          code: "DEVICE_BUSY",
          message: "이 기기에서 다른 MCP 명령을 실행 중입니다.",
          retryable: true,
        },
      });
    }

    const commandId = randomUUID();
    const createdAt = new Date();
    const expiresAt = new Date(createdAt.getTime() + this.commandTimeoutMs);
    const envelope: CommandEnvelope = {
      type: "command",
      command: {
        id: commandId,
        deviceId: this.deviceId,
        toolName,
        arguments: argumentsValue,
        grantedScopes: scopesFor(toolName),
        createdAt: createdAt.toISOString(),
        expiresAt: expiresAt.toISOString(),
      },
    };

    return new Promise<RelayResult>((resolve) => {
      const timeout = setTimeout(() => {
        this.pending.delete(commandId);
        resolve({
          status: "expired",
          commandId,
          error: {
            code: "COMMAND_TIMEOUT",
            message: "휴대폰이 제한 시간 안에 명령 결과를 반환하지 않았습니다.",
            retryable: true,
          },
        });
      }, this.commandTimeoutMs);
      timeout.unref();
      this.pending.set(commandId, { resolve, timeout });

      try {
        connection.send(JSON.stringify(envelope));
      } catch {
        clearTimeout(timeout);
        this.pending.delete(commandId);
        resolve({
          status: "failed",
          commandId,
          error: {
            code: "DEVICE_SEND_FAILED",
            message: "휴대폰으로 명령을 전달하지 못했습니다.",
            retryable: true,
          },
        });
      }
    });
  }

  close(
    code = 1001,
    reason = "gateway_shutdown",
    pendingCode = "GATEWAY_SHUTDOWN",
    pendingMessage = "서버가 종료되고 있습니다.",
  ): void {
    this.connection?.close(code, reason);
    this.connection = undefined;
    this.failAllPending(pendingCode, pendingMessage, true);
  }

  private complete(envelope: DeviceResultEnvelope): void {
    const pending = this.pending.get(envelope.commandId);
    if (!pending) return;
    clearTimeout(pending.timeout);
    this.pending.delete(envelope.commandId);

    if (envelope.status === "succeeded") {
      pending.resolve({
        status: "succeeded",
        commandId: envelope.commandId,
        result: envelope.result ?? null,
      });
      return;
    }
    pending.resolve({
      status: envelope.status,
      commandId: envelope.commandId,
      error: {
        code: envelope.error?.code ?? "DEVICE_COMMAND_FAILED",
        message: envelope.error?.message ?? "휴대폰에서 명령 실행에 실패했습니다.",
        retryable: envelope.error?.retryable ?? false,
      },
    });
  }

  private failAllPending(code: string, message: string, retryable: boolean): void {
    for (const [commandId, pending] of this.pending) {
      clearTimeout(pending.timeout);
      pending.resolve({
        status: "failed",
        commandId,
        error: { code, message, retryable },
      });
    }
    this.pending.clear();
  }
}

function scopesFor(toolName: RemoteToolName): string[] {
  switch (toolName) {
    case "device_status":
      return ["device:status"];
    case "device_observe":
      return ["device:observe"];
    case "device_fill_field":
      return ["device:sensitive"];
    case "device_launch_app":
    case "device_open_uri":
    case "device_find_node":
    case "device_set_checked":
    case "device_select_option":
    case "device_click_node":
    case "device_type_node":
    case "device_scroll":
    case "device_submit_text":
    case "device_home":
    case "device_back":
      return ["device:control"];
  }
}
