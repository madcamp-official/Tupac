import assert from "node:assert/strict";
import test from "node:test";

import { createTokenVerifier, userIdFromAuthInfo } from "./tokenVerifier.js";

const config = {
  mode: "development-static" as const,
  mcpApiToken: "m".repeat(32),
  deviceApiToken: "d".repeat(32),
  deviceId: "development-phone",
};

test("development verifier binds the MCP token to one user", async () => {
  const authInfo = await createTokenVerifier(config).verifyAccessToken(
    config.mcpApiToken,
  );
  assert.equal(
    userIdFromAuthInfo(authInfo),
    "00000000-0000-0000-0000-000000000001",
  );
  assert.equal(authInfo.clientId, "development-mcp-client");
  assert.ok((authInfo.expiresAt ?? 0) > Date.now() / 1000);
});

test("development verifier rejects the device token at the MCP boundary", async () => {
  await assert.rejects(
    createTokenVerifier(config).verifyAccessToken(config.deviceApiToken),
  );
});
