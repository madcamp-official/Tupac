# MobileGUIAgent MCP Gateway

Remote Streamable HTTP MCP gateway for per-user Android devices.

## Production configuration

1. Use the dedicated Supabase project. The migrations under
   `../supabase/migrations` are already applied to project
   `ljedsvsbnigjovxokimn`.
2. Enable Supabase OAuth 2.1 Server and dynamic client registration so MCP
   clients can register their loopback callback URLs. Keep the consent screen
   mandatory for every newly registered client and review the OAuth Apps list
   for spam or misleading client names.
3. Set the Supabase site URL to the gateway origin and the authorization path
   to `/oauth/consent`.
4. Copy `.env.example` to `.env` and provide the project URL, publishable key,
   private Postgres URL, and a random device-token pepper.
5. Configure the Android app's untracked root `local.properties`:

   ```properties
   SUPABASE_URL=https://your-project-ref.supabase.co
   SUPABASE_PUBLISHABLE_KEY=sb_publishable_replace_me
   MCP_GATEWAY_URL=https://mcp.example.com
   ```

Only the publishable key belongs in Android. Database passwords, secret keys,
`service_role` keys, and the token pepper belong on the gateway only.

Run locally:

```bash
npm install
npm run check
npm test
npm run dev
```

The MCP endpoint is `POST /mcp`. OAuth discovery is exposed at
`/.well-known/oauth-protected-resource/mcp`, and the browser consent page is
`/oauth/consent`.

The current production gateway is:

```text
https://gateway-production-00f3.up.railway.app/mcp
```

Railway uses outbound IPv6 to reach the Supabase direct Postgres endpoint.
TLS is verified with the bundled Supabase root CA. The database login is the
least-privilege `mobilegui_gateway` role, not the `postgres` owner role.

The Android app signs in with an email OTP, calls
`POST /api/v1/devices/register`, stores the one-time device credential in
Android Keystore, then connects to `/device/ws` with:

```http
Authorization: Bearer <device-token>
X-Device-ID: <device-id>
```

## Development-static fallback

If `SUPABASE_URL` is absent, the gateway starts in the original single-user
development mode using `MCP_API_TOKEN`, `DEVICE_API_TOKEN`, and `DEVICE_ID`.
This fallback exists for local relay testing only.
