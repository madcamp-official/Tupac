import assert from "node:assert/strict";
import test from "node:test";

import type {
  AuditEvent,
  DeviceRecord,
  DeviceRegistration,
  DeviceStore,
} from "../devices/DeviceStore.js";
import { DeviceBrokerRegistry } from "./DeviceBrokerRegistry.js";
import type { DeviceConnection } from "./protocol.js";

class FakeConnection implements DeviceConnection {
  sent: string[] = [];
  closedCode: number | undefined;

  send(payload: string): void {
    this.sent.push(payload);
  }

  close(code?: number): void {
    this.closedCode = code;
  }
}

class FakeStore implements DeviceStore {
  async register(): Promise<DeviceRegistration> {
    throw new Error("not used");
  }
  async listForUser(): Promise<DeviceRecord[]> {
    return [];
  }
  async revoke(): Promise<boolean> {
    return false;
  }
  async authenticate(): Promise<DeviceRecord | undefined> {
    return undefined;
  }
  async touch(): Promise<void> {}
  async audit(_event: AuditEvent): Promise<void> {}
  async healthCheck(): Promise<void> {}
  async close(): Promise<void> {}
}

test("does not route one user's MCP command to another user's phone", async () => {
  const registry = new DeviceBrokerRegistry(1_000, new FakeStore());
  const connection = new FakeConnection();
  registry.attach("user-a", "phone-a", connection);

  const wrongUser = await registry.executeForUser(
    "user-b",
    "device_status",
    {},
  );
  assert.equal(wrongUser.result.status, "device_offline");
  assert.equal(connection.sent.length, 0);

  const pending = registry.executeForUser("user-a", "device_status", {});
  assert.equal(connection.sent.length, 1);
  const envelope = JSON.parse(connection.sent[0] ?? "{}") as {
    command: { id: string };
  };
  registry.receive(
    "phone-a",
    JSON.stringify({
      type: "result",
      commandId: envelope.command.id,
      status: "succeeded",
      result: { connected: true },
    }),
  );
  assert.equal((await pending).result.status, "succeeded");
});

test("revocation immediately closes an already connected phone", async () => {
  const registry = new DeviceBrokerRegistry(1_000, new FakeStore());
  const connection = new FakeConnection();
  registry.attach("user-a", "phone-a", connection);

  registry.revoke("phone-a");

  assert.equal(connection.closedCode, 4003);
  const result = await registry.executeForUser("user-a", "device_status", {});
  assert.equal(result.result.status, "device_offline");
});
