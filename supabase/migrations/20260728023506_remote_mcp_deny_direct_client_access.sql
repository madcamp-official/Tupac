-- Applied to Supabase project ljedsvsbnigjovxokimn as migration 20260728023506.
create policy "deny direct client access to devices"
  on remote_mcp.devices
  as restrictive
  for all
  to anon, authenticated
  using (false)
  with check (false);

create policy "deny direct client access to audit logs"
  on remote_mcp.audit_logs
  as restrictive
  for all
  to anon, authenticated
  using (false)
  with check (false);
