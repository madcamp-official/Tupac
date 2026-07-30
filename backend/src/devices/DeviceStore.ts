import { createHmac, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";

import { Pool } from "pg";

import type { AuthenticationConfig } from "../config.js";

export interface DeviceRecord {
  id: string;
  userId: string;
  installationId: string;
  displayName: string;
  appVersion: string | null;
  lastSeenAt: Date | null;
  revokedAt: Date | null;
}

export interface DeviceRegistration {
  device: DeviceRecord;
  token: string;
}

export interface AuditEvent {
  userId?: string;
  deviceId?: string;
  oauthClientId?: string;
  eventType: string;
  toolName?: string;
  outcome: string;
  errorCode?: string;
  metadata?: Record<string, unknown>;
}

export interface DeviceStore {
  register(
    userId: string,
    installationId: string,
    displayName: string,
    appVersion?: string,
  ): Promise<DeviceRegistration>;
  listForUser(userId: string): Promise<DeviceRecord[]>;
  revoke(userId: string, deviceId: string): Promise<boolean>;
  authenticate(deviceId: string, token: string): Promise<DeviceRecord | undefined>;
  touch(deviceId: string): Promise<void>;
  audit(event: AuditEvent): Promise<void>;
  healthCheck(): Promise<void>;
  close(): Promise<void>;
}

export function createDeviceStore(config: AuthenticationConfig): DeviceStore {
  return config.mode === "supabase"
    ? new PostgresDeviceStore(
        config.databaseUrl,
        config.databaseSsl,
        config.deviceTokenPepper,
      )
    : new DevelopmentDeviceStore(
        config.deviceId,
        config.deviceApiToken,
      );
}

class PostgresDeviceStore implements DeviceStore {
  private readonly pool: Pool;

  constructor(
    databaseUrl: string,
    ssl: boolean,
    private readonly tokenPepper: string,
  ) {
    this.pool = new Pool({
      connectionString: databaseUrl,
      max: 10,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
      ssl: ssl
        ? {
            ca: readFileSync(
              new URL("../../certs/supabase-ca-2021.crt", import.meta.url),
              "utf8",
            ),
            rejectUnauthorized: true,
          }
        : false,
    });
  }

  async register(
    userId: string,
    installationId: string,
    displayName: string,
    appVersion?: string,
  ): Promise<DeviceRegistration> {
    const id = randomUUID();
    const token = randomBytes(32).toString("base64url");
    const tokenHash = hashToken(id, token, this.tokenPepper);
    const result = await this.pool.query<DeviceRow>(
      `insert into remote_mcp.devices
         (id, user_id, installation_id, display_name, token_hash, app_version)
       values ($1, $2, $3, $4, $5, $6)
       on conflict (user_id, installation_id) do update
       set display_name = excluded.display_name,
           token_hash = excluded.token_hash,
           app_version = excluded.app_version,
           revoked_at = null,
           updated_at = now()
       returning id, user_id, installation_id, display_name, app_version,
                 last_seen_at, revoked_at`,
      [id, userId, installationId, displayName, tokenHash, appVersion ?? null],
    );
    const row = requiredRow(result.rows[0]);
    return { device: fromRow(row), token };
  }

  async listForUser(userId: string): Promise<DeviceRecord[]> {
    const result = await this.pool.query<DeviceRow>(
      `select id, user_id, installation_id, display_name, app_version,
              last_seen_at, revoked_at
       from remote_mcp.devices
       where user_id = $1
       order by revoked_at nulls first, last_seen_at desc nulls last, created_at desc`,
      [userId],
    );
    return result.rows.map(fromRow);
  }

  async revoke(userId: string, deviceId: string): Promise<boolean> {
    const result = await this.pool.query(
      `update remote_mcp.devices
       set revoked_at = now(), updated_at = now()
       where id = $1 and user_id = $2 and revoked_at is null`,
      [deviceId, userId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  async authenticate(
    deviceId: string,
    token: string,
  ): Promise<DeviceRecord | undefined> {
    const result = await this.pool.query<DeviceRow & { token_hash: Buffer }>(
      `select id, user_id, installation_id, display_name, app_version,
              last_seen_at, revoked_at, token_hash
       from remote_mcp.devices
       where id = $1 and revoked_at is null`,
      [deviceId],
    );
    const row = result.rows[0];
    if (!row) return undefined;
    const actual = hashToken(deviceId, token, this.tokenPepper);
    if (
      actual.length !== row.token_hash.length ||
      !timingSafeEqual(actual, row.token_hash)
    ) {
      return undefined;
    }
    return fromRow(row);
  }

  async touch(deviceId: string): Promise<void> {
    await this.pool.query(
      `update remote_mcp.devices
       set last_seen_at = now(), updated_at = now()
       where id = $1 and revoked_at is null`,
      [deviceId],
    );
  }

  async audit(event: AuditEvent): Promise<void> {
    await this.pool.query(
      `insert into remote_mcp.audit_logs
         (user_id, device_id, oauth_client_id, event_type, tool_name,
          outcome, error_code, metadata)
       values ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [
        event.userId ?? null,
        event.deviceId ?? null,
        event.oauthClientId ?? null,
        event.eventType,
        event.toolName ?? null,
        event.outcome,
        event.errorCode ?? null,
        event.metadata ?? {},
      ],
    );
  }

  async healthCheck(): Promise<void> {
    await this.pool.query("select 1");
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}

class DevelopmentDeviceStore implements DeviceStore {
  private readonly record: DeviceRecord;

  constructor(
    deviceId: string,
    private readonly deviceToken: string,
  ) {
    this.record = {
      id: deviceId,
      userId: "00000000-0000-0000-0000-000000000001",
      installationId: "development-installation",
      displayName: "Development phone",
      appVersion: null,
      lastSeenAt: null,
      revokedAt: null,
    };
  }

  async register(): Promise<DeviceRegistration> {
    return { device: this.record, token: this.deviceToken };
  }

  async listForUser(userId: string): Promise<DeviceRecord[]> {
    return userId === this.record.userId ? [this.record] : [];
  }

  async revoke(): Promise<boolean> {
    return false;
  }

  async authenticate(
    deviceId: string,
    token: string,
  ): Promise<DeviceRecord | undefined> {
    if (deviceId !== this.record.id) return undefined;
    const actual = Buffer.from(token);
    const expected = Buffer.from(this.deviceToken);
    return actual.length === expected.length && timingSafeEqual(actual, expected)
      ? this.record
      : undefined;
  }

  async touch(): Promise<void> {
    this.record.lastSeenAt = new Date();
  }

  async audit(): Promise<void> {}

  async healthCheck(): Promise<void> {}

  async close(): Promise<void> {}
}

interface DeviceRow {
  id: string;
  user_id: string;
  installation_id: string;
  display_name: string;
  app_version: string | null;
  last_seen_at: Date | null;
  revoked_at: Date | null;
}

function fromRow(row: DeviceRow): DeviceRecord {
  return {
    id: row.id,
    userId: row.user_id,
    installationId: row.installation_id,
    displayName: row.display_name,
    appVersion: row.app_version,
    lastSeenAt: row.last_seen_at,
    revokedAt: row.revoked_at,
  };
}

function hashToken(deviceId: string, token: string, pepper: string): Buffer {
  return createHmac("sha256", pepper).update(deviceId).update(".").update(token).digest();
}

function requiredRow<T>(row: T | undefined): T {
  if (!row) throw new Error("Database did not return the registered device");
  return row;
}
