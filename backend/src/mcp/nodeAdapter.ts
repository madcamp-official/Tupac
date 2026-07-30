import { Readable } from "node:stream";

import type { AuthInfo } from "@modelcontextprotocol/server";
import type { Request as ExpressRequest, Response as ExpressResponse } from "express";

interface FetchHandler {
  fetch(
    request: Request,
    options?: { authInfo?: AuthInfo },
  ): Promise<Response>;
}

/**
 * Adapts the SDK's web-standard handler without pulling a second HTTP server
 * implementation into the production dependency graph.
 */
export async function handleMcpRequest(
  handler: FetchHandler,
  request: ExpressRequest,
  response: ExpressResponse,
  publicBaseUrl: URL,
  authInfo?: AuthInfo,
): Promise<void> {
  const url = new URL(request.originalUrl, publicBaseUrl);
  const headers = new Headers();
  for (const [name, value] of Object.entries(request.headers)) {
    if (Array.isArray(value)) {
      for (const item of value) headers.append(name, item);
    } else if (value !== undefined) {
      headers.set(name, value);
    }
  }

  const init: RequestInit & { duplex?: "half" } = {
    method: request.method,
    headers,
  };
  if (request.method !== "GET" && request.method !== "HEAD") {
    init.body = Readable.toWeb(request) as ReadableStream<Uint8Array>;
    init.duplex = "half";
  }

  const webResponse = await handler.fetch(
    new Request(url, init),
    authInfo ? { authInfo } : {},
  );
  response.status(webResponse.status);
  webResponse.headers.forEach((value, name) => {
    response.setHeader(name, value);
  });
  if (webResponse.body === null) {
    response.end();
    return;
  }
  const body = Readable.fromWeb(
    webResponse.body as import("node:stream/web").ReadableStream<Uint8Array>,
  );
  body.on("error", (error) => response.destroy(error));
  body.pipe(response);
}
