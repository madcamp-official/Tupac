-- Applied to Supabase project ljedsvsbnigjovxokimn as migration 20260728023417.
create schema if not exists remote_mcp;

comment on schema remote_mcp is
  'Private application data for the MobileGUIAgent MCP gateway. Not exposed through the Data API.';

revoke all on schema remote_mcp from public, anon, authenticated;

create table remote_mcp.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  installation_id text not null,
  display_name text not null,
  platform text not null default 'android'
    check (platform = 'android'),
  token_hash bytea not null,
  app_version text,
  last_seen_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint devices_installation_id_length
    check (char_length(installation_id) between 16 and 200),
  constraint devices_display_name_length
    check (char_length(display_name) between 1 and 100),
  constraint devices_token_hash_sha256_length
    check (octet_length(token_hash) = 32),
  unique (user_id, installation_id)
);

create index devices_active_user_last_seen_idx
  on remote_mcp.devices (user_id, last_seen_at desc nulls last)
  where revoked_at is null;

create table remote_mcp.audit_logs (
  id bigint generated always as identity primary key,
  user_id uuid references auth.users(id) on delete set null,
  device_id uuid references remote_mcp.devices(id) on delete set null,
  oauth_client_id text,
  event_type text not null,
  tool_name text,
  outcome text not null,
  error_code text,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  constraint audit_logs_event_type_length
    check (char_length(event_type) between 1 and 80),
  constraint audit_logs_outcome_length
    check (char_length(outcome) between 1 and 40),
  constraint audit_logs_metadata_object
    check (jsonb_typeof(metadata) = 'object')
);

create index audit_logs_user_created_idx
  on remote_mcp.audit_logs (user_id, created_at desc);

create index audit_logs_device_created_idx
  on remote_mcp.audit_logs (device_id, created_at desc)
  where device_id is not null;

alter table remote_mcp.devices enable row level security;
alter table remote_mcp.audit_logs enable row level security;

-- The gateway owns all writes through its direct Postgres connection. Mobile
-- and browser clients must use the authenticated gateway API, so no Data API
-- policies or grants are created for anon/authenticated.
revoke all on all tables in schema remote_mcp from public, anon, authenticated;
revoke all on all sequences in schema remote_mcp from public, anon, authenticated;

alter default privileges in schema remote_mcp
  revoke all on tables from public, anon, authenticated;
alter default privileges in schema remote_mcp
  revoke all on sequences from public, anon, authenticated;
