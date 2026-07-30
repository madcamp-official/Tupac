import { createServer } from "node:http";
import path from "node:path";

import {
  buildOAuthProtectedResourceMetadata,
  createMcpHandler,
  getOAuthProtectedResourceMetadataUrl,
  type OAuthMetadata,
} from "@modelcontextprotocol/server";
import express from "express";
import { WebSocketServer } from "ws";

import { requireMcpBearer } from "./auth/expressAuth.js";
import { createTokenVerifier } from "./auth/tokenVerifier.js";
import { loadConfig } from "./config.js";
import { createDeviceApi } from "./devices/deviceApi.js";
import { createDeviceStore } from "./devices/DeviceStore.js";
import { createMobileGuiMcpServer } from "./mcp/createServer.js";
import { handleMcpRequest } from "./mcp/nodeAdapter.js";
import { DeviceBrokerRegistry } from "./relay/DeviceBrokerRegistry.js";
import { requireTrustedHost } from "./security.js";

const config = loadConfig();
const store = createDeviceStore(config.authentication);
const registry = new DeviceBrokerRegistry(config.commandTimeoutMs, store);
const verifier = createTokenVerifier(config.authentication);
const app = express();
const httpServer = createServer(app);
const mcpUrl = new URL("/mcp", config.publicBaseUrl);
const resourceMetadataUrl = getOAuthProtectedResourceMetadataUrl(mcpUrl);
const deviceWebSocketUrl = new URL("/device/ws", config.publicBaseUrl)
  .toString()
  .replace(/^http/, "ws");
const bearerOptions = { verifier, resourceMetadataUrl };

app.disable("x-powered-by");
app.get("/health/live", (_request, response) => {
  response.json({ status: "ok" });
});
app.get("/health/ready", async (_request, response) => {
  try {
    await store.healthCheck();
    const status = registry.status();
    response.json({
      status: "ready",
      authenticationMode: config.authentication.mode,
      database: "connected",
      ...status,
    });
  } catch {
    response.status(503).json({
      status: "not_ready",
      authenticationMode: config.authentication.mode,
      database: "unavailable",
    });
  }
});

if (config.authentication.mode === "supabase") {
  const supabaseConfig = config.authentication;
  const oauthMetadata = {
    issuer: new URL("/auth/v1", supabaseConfig.supabaseUrl)
      .toString()
      .replace(/\/$/, ""),
  } as OAuthMetadata;
  const protectedResourceMetadata = buildOAuthProtectedResourceMetadata({
    oauthMetadata,
    resourceServerUrl: mcpUrl,
    scopesSupported: ["openid", "email", "profile"],
    resourceName: "MobileGUIAgent MCP",
    dangerouslyAllowInsecureIssuerUrl:
      supabaseConfig.supabaseUrl.hostname === "localhost" ||
      supabaseConfig.supabaseUrl.hostname === "127.0.0.1",
  });
  app.get(
    ["/.well-known/oauth-protected-resource", new URL(resourceMetadataUrl).pathname],
    (_request, response) => {
      response
        .set("Access-Control-Allow-Origin", "*")
        .json(protectedResourceMetadata);
    },
  );
  app.use("/oauth", (_request, response, next) => {
    response.set({
      "Cache-Control": "no-store",
      "Content-Security-Policy":
        "default-src 'self'; script-src 'self'; style-src 'self'; " +
        "connect-src 'self' " +
        supabaseConfig.supabaseUrl.origin +
        "; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
    });
    next();
  });
  app.get("/oauth/config", (_request, response) => {
    response
      .set("Cache-Control", "no-store")
      .json({
        supabaseUrl: supabaseConfig.supabaseUrl.toString(),
        publishableKey: supabaseConfig.supabasePublishableKey,
      });
  });
  app.get("/oauth/supabase.js", (_request, response) => {
    response.sendFile(
      path.resolve(
        "node_modules/@supabase/supabase-js/dist/umd/supabase.js",
      ),
    );
  });
  app.get("/oauth/consent", (_request, response) => {
    response.sendFile(path.resolve("src/oauth/consent.html"));
  });
  app.use(
    "/oauth",
    express.static(path.resolve("src/oauth"), {
      fallthrough: false,
      index: false,
      setHeaders(response) {
        response.setHeader("Cache-Control", "no-store");
      },
    }),
  );
}

const apiAuth = requireMcpBearer({ verifier });
app.use(
  "/api/v1/devices",
  express.json({ limit: "32kb", strict: true }),
  apiAuth,
  createDeviceApi(store, deviceWebSocketUrl, (deviceId) =>
    registry.revoke(deviceId),
  ),
);

const mcpHandler = createMcpHandler(({ authInfo }) =>
  createMobileGuiMcpServer(registry, store, authInfo),
);
app.all(
  "/mcp",
  requireTrustedHost(config.publicBaseUrl),
  requireMcpBearer(bearerOptions),
  (request, response) => {
    void handleMcpRequest(
      mcpHandler,
      request,
      response,
      config.publicBaseUrl,
      response.locals.authInfo,
    ).catch((error: unknown) => {
      if (!response.headersSent) {
        response.status(500).json({ error: "mcp_transport_error" });
      } else {
        response.destroy(error instanceof Error ? error : undefined);
      }
    });
  },
);

app.use((_request, response) => {
  response.status(404).json({ error: "not_found" });
});

const webSockets = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
httpServer.on("upgrade", (request, socket, head) => {
  void (async () => {
    const requestUrl = new URL(request.url ?? "/", config.publicBaseUrl);
    const deviceId = headerValue(request.headers["x-device-id"]);
    const token = bearerToken(request.headers.authorization);
    if (requestUrl.pathname !== "/device/ws" || !deviceId || !token) {
      rejectUpgrade(socket);
      return;
    }
    const device = await store.authenticate(deviceId, token);
    if (!device) {
      rejectUpgrade(socket);
      return;
    }

    webSockets.handleUpgrade(request, socket, head, (webSocket) => {
      const broker = registry.attach(device.userId, device.id, webSocket);
      void store.touch(device.id);
      webSocket.on("message", (payload) => {
        registry.receive(device.id, payload.toString());
      });
      webSocket.on("close", () => {
        registry.detach(device.id, webSocket);
      });
      webSocket.on("error", () => {
        registry.detach(device.id, webSocket);
      });
      webSocket.send(
        JSON.stringify({
          type: "connected",
          deviceId: broker.deviceId,
          serverTime: new Date().toISOString(),
        }),
      );
    });
  })().catch(() => rejectUpgrade(socket));
});

httpServer.listen(config.port, "0.0.0.0", () => {
  process.stdout.write(
    JSON.stringify({
      level: "info",
      event: "gateway_started",
      port: config.port,
      authenticationMode: config.authentication.mode,
      mcpEndpoint: mcpUrl.toString(),
      deviceWebSocket: deviceWebSocketUrl,
      resourceMetadataUrl:
        config.authentication.mode === "supabase" ? resourceMetadataUrl : null,
    }) + "\n",
  );
});

function shutdown(): void {
  registry.close();
  webSockets.close();
  httpServer.close(() => {
    void store.close().finally(() => process.exit(0));
  });
}

function bearerToken(authorization: string | undefined): string | undefined {
  return authorization?.startsWith("Bearer ")
    ? authorization.slice("Bearer ".length)
    : undefined;
}

function headerValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function rejectUpgrade(socket: import("node:stream").Duplex): void {
  if (socket.destroyed) return;
  socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
  socket.destroy();
}

process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
