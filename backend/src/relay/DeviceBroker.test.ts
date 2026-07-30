import assert from "node:assert/strict";
import test from "node:test";

import { DeviceBroker } from "./DeviceBroker.js";
import type { DeviceConnection } from "./protocol.js";

class FakeConnection implements DeviceConnection {
  sent: string[] = [];
  closed = false;

  send(payload: string): void {
    this.sent.push(payload);
  }

  close(): void {
    this.closed = true;
  }
}

test("returns a retryable offline result without creating a command", async () => {
  const broker = new DeviceBroker("test-phone", 100);
  const result = await broker.execute("device_status", {});
  assert.equal(result.status, "device_offline");
  if (result.status === "device_offline") {
    assert.equal(result.error.retryable, true);
  }
});

test("routes one command to the attached phone and completes it", async () => {
  const broker = new DeviceBroker("test-phone", 1_000);
  const connection = new FakeConnection();
  broker.attach(connection);

  const pending = broker.execute("device_observe", { max_nodes: 20 });
  assert.equal(connection.sent.length, 1);
  const sent = JSON.parse(connection.sent[0] ?? "{}") as {
    command: { id: string; toolName: string; deviceId: string };
  };
  assert.equal(sent.command.toolName, "device_observe");
  assert.equal(sent.command.deviceId, "test-phone");

  broker.receive(
    JSON.stringify({
      type: "result",
      commandId: sent.command.id,
      status: "succeeded",
      result: { node_count: 3 },
    }),
  );
  const result = await pending;
  assert.equal(result.status, "succeeded");
  if (result.status === "succeeded") {
    assert.deepEqual(result.result, { node_count: 3 });
  }
});

test("grants only the control scope to an app launch command", async () => {
  const broker = new DeviceBroker("test-phone", 1_000);
  const connection = new FakeConnection();
  broker.attach(connection);

  const pending = broker.execute("device_launch_app", { name: "메가박스" });
  const sent = JSON.parse(connection.sent[0] ?? "{}") as {
    command: {
      id: string;
      toolName: string;
      grantedScopes: string[];
    };
  };
  assert.equal(sent.command.toolName, "device_launch_app");
  assert.deepEqual(sent.command.grantedScopes, ["device:control"]);

  broker.receive(
    JSON.stringify({
      type: "result",
      commandId: sent.command.id,
      status: "succeeded",
      result: { success: true },
    }),
  );
  assert.equal((await pending).status, "succeeded");
});

for (const toolName of [
  "device_open_uri",
  "device_find_node",
  "device_set_checked",
  "device_select_option",
] as const) {
  test(`grants only the control scope to ${toolName}`, async () => {
    const broker = new DeviceBroker("test-phone", 1_000);
    const connection = new FakeConnection();
    broker.attach(connection);

    const pending = broker.execute(toolName, {});
    const sent = JSON.parse(connection.sent[0] ?? "{}") as {
      command: { id: string; grantedScopes: string[] };
    };
    assert.deepEqual(sent.command.grantedScopes, ["device:control"]);

    broker.receive(
      JSON.stringify({
        type: "result",
        commandId: sent.command.id,
        status: "succeeded",
        result: { success: true },
      }),
    );
    assert.equal((await pending).status, "succeeded");
  });
}

test("grants only the sensitive scope to a credential fill command", async () => {
  const broker = new DeviceBroker("test-phone", 1_000);
  const connection = new FakeConnection();
  broker.attach(connection);

  const pending = broker.execute("device_fill_field", {
    snapshot_id: "snapshot",
    node_id: "password",
    field: "password",
  });
  const sent = JSON.parse(connection.sent[0] ?? "{}") as {
    command: {
      id: string;
      toolName: string;
      grantedScopes: string[];
    };
  };
  assert.equal(sent.command.toolName, "device_fill_field");
  assert.deepEqual(sent.command.grantedScopes, ["device:sensitive"]);

  broker.receive(
    JSON.stringify({
      type: "result",
      commandId: sent.command.id,
      status: "succeeded",
      result: { success: true },
    }),
  );
  assert.equal((await pending).status, "succeeded");
});

test("disconnect fails in-flight commands instead of executing them later", async () => {
  const broker = new DeviceBroker("test-phone", 1_000);
  const connection = new FakeConnection();
  broker.attach(connection);

  const pending = broker.execute("device_back", {});
  broker.detach(connection);
  const result = await pending;

  assert.equal(result.status, "failed");
  if (result.status === "failed") {
    assert.equal(result.error.code, "DEVICE_DISCONNECTED");
  }
});

test("allows only one in-flight command per device", async () => {
  const broker = new DeviceBroker("test-phone", 1_000);
  const connection = new FakeConnection();
  broker.attach(connection);

  const first = broker.execute("device_observe", {});
  const second = await broker.execute("device_back", {});
  assert.equal(second.status, "failed");
  if (second.status === "failed") {
    assert.equal(second.error.code, "DEVICE_BUSY");
  }

  const sent = JSON.parse(connection.sent[0] ?? "{}") as {
    command: { id: string };
  };
  broker.receive(
    JSON.stringify({
      type: "result",
      commandId: sent.command.id,
      status: "succeeded",
      result: {},
    }),
  );
  await first;
});
