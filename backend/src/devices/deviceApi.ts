import type { Router } from "express";
import express from "express";
import { z } from "zod";

import { userIdFromAuthInfo } from "../auth/tokenVerifier.js";
import type { DeviceStore } from "./DeviceStore.js";

const registrationSchema = z.object({
  installationId: z.string().min(16).max(200),
  displayName: z.string().trim().min(1).max(100),
  appVersion: z.string().trim().min(1).max(50).optional(),
});

export function createDeviceApi(
  store: DeviceStore,
  deviceWebSocketUrl: string,
  onDeviceRevoked: (deviceId: string) => void,
): Router {
  const router = express.Router();

  router.get("/", (request, response) => {
    void store
      .listForUser(userIdFromAuthInfo(response.locals.authInfo))
      .then((devices) => {
        response.json({ devices: devices.map(publicDevice) });
      })
      .catch((error: unknown) => apiFailure(response, error));
  });

  router.post("/register", (request, response) => {
    const parsed = registrationSchema.safeParse(request.body);
    if (!parsed.success) {
      response.status(400).json({
        error: "invalid_request",
        details: parsed.error.flatten(),
      });
      return;
    }
    const userId = userIdFromAuthInfo(response.locals.authInfo);
    void store
      .register(
        userId,
        parsed.data.installationId,
        parsed.data.displayName,
        parsed.data.appVersion,
      )
      .then(async ({ device, token }) => {
        await safeAudit(store, {
          userId,
          deviceId: device.id,
          eventType: "device_registered",
          outcome: "succeeded",
        });
        response.status(201).json({
          device: publicDevice(device),
          credentials: {
            webSocketUrl: deviceWebSocketUrl,
            deviceToken: token,
          },
        });
      })
      .catch((error: unknown) => apiFailure(response, error));
  });

  router.delete("/:deviceId", (request, response) => {
    const userId = userIdFromAuthInfo(response.locals.authInfo);
    void store
      .revoke(userId, request.params.deviceId ?? "")
      .then(async (revoked) => {
        if (!revoked) {
          response.status(404).json({ error: "device_not_found" });
          return;
        }
        onDeviceRevoked(request.params.deviceId ?? "");
        await safeAudit(store, {
          userId,
          deviceId: request.params.deviceId,
          eventType: "device_revoked",
          outcome: "succeeded",
        });
        response.status(204).end();
      })
      .catch((error: unknown) => apiFailure(response, error));
  });

  return router;
}

function publicDevice(device: {
  id: string;
  installationId: string;
  displayName: string;
  appVersion: string | null;
  lastSeenAt: Date | null;
  revokedAt: Date | null;
}) {
  return {
    id: device.id,
    installationId: device.installationId,
    displayName: device.displayName,
    platform: "android",
    appVersion: device.appVersion,
    lastSeenAt: device.lastSeenAt?.toISOString() ?? null,
    revokedAt: device.revokedAt?.toISOString() ?? null,
  };
}

function apiFailure(
  response: import("express").Response,
  error: unknown,
): void {
  process.stderr.write(
    JSON.stringify({
      level: "error",
      event: "device_api_error",
      message: error instanceof Error ? error.message : "unknown_error",
    }) + "\n",
  );
  response.status(500).json({ error: "internal_error" });
}

async function safeAudit(
  store: DeviceStore,
  event: Parameters<DeviceStore["audit"]>[0],
): Promise<void> {
  await store.audit(event).catch((error: unknown) => {
    process.stderr.write(
      JSON.stringify({
        level: "error",
        event: "audit_log_write_failed",
        eventType: event.eventType,
        message: error instanceof Error ? error.message : "unknown_error",
      }) + "\n",
    );
  });
}
