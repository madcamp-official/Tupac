-- Dedicated login used only by the hosted MCP gateway.
-- Its password is provisioned out-of-band and is never stored in this migration.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'mobilegui_gateway') then
    create role mobilegui_gateway
      login
      nosuperuser
      nocreatedb
      nocreaterole
      noinherit
      nobypassrls;
  end if;
end
$$;

alter role mobilegui_gateway set statement_timeout = '15s';
alter role mobilegui_gateway set idle_in_transaction_session_timeout = '15s';

grant usage on schema remote_mcp to mobilegui_gateway;

grant select, insert, update
  on remote_mcp.devices
  to mobilegui_gateway;

grant insert
  on remote_mcp.audit_logs
  to mobilegui_gateway;

grant usage, select
  on sequence remote_mcp.audit_logs_id_seq
  to mobilegui_gateway;

create policy "gateway manages devices"
  on remote_mcp.devices
  for all
  to mobilegui_gateway
  using (true)
  with check (true);

create policy "gateway writes audit logs"
  on remote_mcp.audit_logs
  for insert
  to mobilegui_gateway
  with check (true);
