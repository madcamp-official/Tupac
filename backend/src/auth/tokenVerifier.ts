import {
  createRemoteJWKSet,
  errors as joseErrors,
  jwtVerify,
  type JWTPayload,
} from "jose";
import {
  OAuthError,
  OAuthErrorCode,
  type AuthInfo,
  type OAuthTokenVerifier,
} from "@modelcontextprotocol/server";

import type { AuthenticationConfig } from "../config.js";

const DEVELOPMENT_USER_ID = "00000000-0000-0000-0000-000000000001";

export function createTokenVerifier(
  config: AuthenticationConfig,
): OAuthTokenVerifier {
  if (config.mode === "development-static") {
    return {
      async verifyAccessToken(token: string): Promise<AuthInfo> {
        if (token !== config.mcpApiToken) throw invalidToken();
        return {
          token,
          clientId: "development-mcp-client",
          scopes: ["openid", "email", "profile"],
          expiresAt: Math.floor(Date.now() / 1000) + 3_600,
          extra: { userId: DEVELOPMENT_USER_ID },
        };
      },
    };
  }
  return new SupabaseJwtVerifier(config.supabaseUrl);
}

export function userIdFromAuthInfo(authInfo: AuthInfo | undefined): string {
  const userId = authInfo?.extra?.userId;
  if (typeof userId !== "string" || userId.length === 0) {
    throw new Error("Authenticated request is missing its user identity");
  }
  return userId;
}

class SupabaseJwtVerifier implements OAuthTokenVerifier {
  private readonly issuer: string;
  private readonly jwks: ReturnType<typeof createRemoteJWKSet>;

  constructor(supabaseUrl: URL) {
    this.issuer = new URL("/auth/v1", supabaseUrl).toString().replace(/\/$/, "");
    this.jwks = createRemoteJWKSet(
      new URL("/auth/v1/.well-known/jwks.json", supabaseUrl),
    );
  }

  async verifyAccessToken(token: string): Promise<AuthInfo> {
    try {
      const { payload } = await jwtVerify(token, this.jwks, {
        issuer: this.issuer,
        audience: "authenticated",
        algorithms: ["ES256", "RS256"],
      });
      return toAuthInfo(token, payload);
    } catch (error) {
      if (
        error instanceof joseErrors.JOSEError ||
        error instanceof TypeError
      ) {
        throw invalidToken();
      }
      throw error;
    }
  }
}

function toAuthInfo(token: string, payload: JWTPayload): AuthInfo {
  if (
    typeof payload.sub !== "string" ||
    typeof payload.exp !== "number" ||
    payload.role !== "authenticated" ||
    payload.is_anonymous === true
  ) {
    throw invalidToken();
  }
  const clientId =
    typeof payload.client_id === "string"
      ? payload.client_id
      : typeof payload.session_id === "string"
        ? payload.session_id
        : "supabase-user-session";
  return {
    token,
    clientId,
    scopes: parseScopes(payload.scope),
    expiresAt: payload.exp,
    extra: {
      userId: payload.sub,
      sessionId:
        typeof payload.session_id === "string" ? payload.session_id : undefined,
    },
  };
}

function parseScopes(value: unknown): string[] {
  if (typeof value === "string") {
    return value.split(/\s+/).filter(Boolean);
  }
  if (Array.isArray(value)) {
    return value.filter((scope): scope is string => typeof scope === "string");
  }
  return [];
}

function invalidToken(): OAuthError {
  return new OAuthError(OAuthErrorCode.InvalidToken, "Invalid access token");
}
