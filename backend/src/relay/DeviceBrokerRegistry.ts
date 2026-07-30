import type { DeviceStore } from "../devices/DeviceStore.js";
import { DeviceBroker } from "./DeviceBroker.js";
import type {
  DeviceConnection,
  RelayResult,
  RemoteToolName,
} from "./protocol.js";

interface BrokerEntry {
  userId: string;
  broker: DeviceBroker;
}

export class DeviceBrokerRegistry {
  private readonly brokers = new Map<string, BrokerEntry>();

  constructor(
    private readonly commandTimeoutMs: number,
    private readonly store: DeviceStore,
  ) {}

  attach(
    userId: string,
    deviceId: string,
    connection: DeviceConnection,
  ): DeviceBroker {
    const existing = this.brokers.get(deviceId);
    if (existing && existing.userId !== userId) {
      existing.broker.close();
      this.brokers.delete(deviceId);
    }
    const entry =
      this.brokers.get(deviceId) ??
      {
        userId,
        broker: new DeviceBroker(deviceId, this.commandTimeoutMs),
      };
    this.brokers.set(deviceId, entry);
    entry.broker.attach(connection);
    return entry.broker;
  }

  detach(deviceId: string, connection: DeviceConnection): void {
    this.brokers.get(deviceId)?.broker.detach(connection);
  }

  receive(deviceId: string, payload: string): void {
    this.brokers.get(deviceId)?.broker.receive(payload);
    void this.store.touch(deviceId).catch(() => undefined);
  }

  async executeForUser(
    userId: string,
    toolName: RemoteToolName,
    args: Record<string, unknown>,
  ): Promise<{ deviceId?: string; result: RelayResult }> {
    const online = [...this.brokers.entries()]
      .filter(([, entry]) => entry.userId === userId && entry.broker.online)
      .sort(
        ([, left], [, right]) =>
          (right.broker.lastSeen?.getTime() ?? 0) -
          (left.broker.lastSeen?.getTime() ?? 0),
      )[0];
    if (!online) {
      return {
        result: {
          status: "device_offline",
          error: {
            code: "DEVICE_OFFLINE",
            message: "이 사용자에게 연결된 Android 기기가 오프라인입니다.",
            retryable: true,
          },
        },
      };
    }
    const [deviceId, entry] = online;
    return {
      deviceId,
      result: await entry.broker.execute(toolName, args),
    };
  }

  status() {
    const entries = [...this.brokers.entries()];
    return {
      connectedDevices: entries.filter(([, entry]) => entry.broker.online).length,
      knownDevices: entries.length,
    };
  }

  revoke(deviceId: string): void {
    const entry = this.brokers.get(deviceId);
    if (!entry) return;
    entry.broker.close(
      4003,
      "device_revoked",
      "DEVICE_REVOKED",
      "이 기기의 연결이 해제되었습니다.",
    );
    this.brokers.delete(deviceId);
  }

  close(): void {
    for (const { broker } of this.brokers.values()) broker.close();
    this.brokers.clear();
  }
}
