export type AuthenticationConfig =
  | {
      mode: "supabase";
      supabaseUrl: URL;
      supabasePublishableKey: string;
      databaseUrl: string;
      databaseSsl: boolean;
      deviceTokenPepper: string;
    }
  | {
      mode: "development-static";
      mcpApiToken: string;
      deviceApiToken: string;
      deviceId: string;
    };

export interface GatewayConfig {
  port: number;
  publicBaseUrl: URL;
  commandTimeoutMs: number;
  authentication: AuthenticationConfig;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): GatewayConfig {
  const publicBaseUrl = new URL(env.PUBLIC_BASE_URL || "http://localhost:8080");
  const supabaseUrl = optionalUrl(env.SUPABASE_URL);
  const authentication: AuthenticationConfig = supabaseUrl
    ? {
        mode: "supabase",
        supabaseUrl,
        supabasePublishableKey: requiredValue(
          env.SUPABASE_PUBLISHABLE_KEY,
          "SUPABASE_PUBLISHABLE_KEY",
        ),
        databaseUrl: requiredValue(env.SUPABASE_DATABASE_URL, "SUPABASE_DATABASE_URL"),
        databaseSsl: booleanValue(
          env.SUPABASE_DATABASE_SSL,
          supabaseUrl.hostname !== "127.0.0.1" && supabaseUrl.hostname !== "localhost",
        ),
        deviceTokenPepper: requiredSecret(
          env.DEVICE_TOKEN_PEPPER,
          "DEVICE_TOKEN_PEPPER",
        ),
      }
    : {
        mode: "development-static",
        mcpApiToken: requiredSecret(env.MCP_API_TOKEN, "MCP_API_TOKEN"),
        deviceApiToken: requiredSecret(env.DEVICE_API_TOKEN, "DEVICE_API_TOKEN"),
        deviceId: env.DEVICE_ID?.trim() || "development-phone",
      };

  if (
    authentication.mode === "supabase" &&
    publicBaseUrl.protocol !== "https:" &&
    publicBaseUrl.hostname !== "localhost" &&
    publicBaseUrl.hostname !== "127.0.0.1"
  ) {
    throw new Error("PUBLIC_BASE_URL must use HTTPS outside local development");
  }

  return {
    port: positiveInteger(env.PORT, 8080),
    publicBaseUrl,
    commandTimeoutMs: positiveInteger(env.COMMAND_TIMEOUT_MS, 30_000),
    authentication,
  };
}

function positiveInteger(value: string | undefined, fallback: number): number {
  if (value === undefined || value.trim() === "") return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`Expected a positive integer, received: ${value}`);
  }
  return parsed;
}

function booleanValue(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined || value.trim() === "") return fallback;
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`Expected true or false, received: ${value}`);
}

function optionalUrl(value: string | undefined): URL | undefined {
  const normalized = value?.trim();
  return normalized ? new URL(normalized) : undefined;
}

function requiredValue(value: string | undefined, name: string): string {
  const normalized = value?.trim();
  if (!normalized) throw new Error(`${name} is required`);
  return normalized;
}

function requiredSecret(value: string | undefined, name: string): string {
  const secret = requiredValue(value, name);
  if (Buffer.byteLength(secret, "utf8") < 32) {
    throw new Error(`${name} must contain at least 32 bytes`);
  }
  return secret;
}
