import type { NextFunction, Request, Response } from "express";

export function requireTrustedHost(publicBaseUrl: URL) {
  const allowed = new Set([
    publicBaseUrl.hostname,
    "localhost",
    "127.0.0.1",
    "[::1]",
  ]);
  return (request: Request, response: Response, next: NextFunction): void => {
    const rawHost = request.header("host");
    const hostname = rawHost
      ? runCatchingHostname(rawHost)
      : undefined;
    if (hostname === undefined || !allowed.has(hostname)) {
      response.status(403).json({ error: "invalid_host" });
      return;
    }
    if (request.header("origin")) {
      response.status(403).json({ error: "browser_origin_not_allowed" });
      return;
    }
    next();
  };
}

function runCatchingHostname(rawHost: string): string | undefined {
  try {
    return new URL(`http://${rawHost}`).hostname;
  } catch {
    return undefined;
  }
}
