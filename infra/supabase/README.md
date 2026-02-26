# Supabase Pre-Staging For NuvioTV

This folder provides a full bootstrap for a **new Supabase project** used by NuvioTV:

- Database schema + RLS + RPCs: `bootstrap.sql`
- Edge function for QR token exchange: `functions/tv-logins-exchange/index.ts`

## 1. Apply Database Bootstrap

1. Open your Supabase project.
2. Go to SQL Editor.
3. Run `infra/supabase/bootstrap.sql`.

Expected important RPCs after apply:

- `generate_sync_code`
- `get_sync_code`
- `claim_sync_code`
- `unlink_device`
- `get_sync_owner`
- `get_sync_overview`
- `start_tv_login_session`
- `poll_tv_login_session`
- `approve_tv_login_session`
- `sync_push_*` and `sync_pull_*` variants

## 2. Deploy Edge Function `tv-logins-exchange`

NuvioTV app calls:

- `POST {SUPABASE_URL}/functions/v1/tv-logins-exchange`

Deploy options:

### Option A: Supabase Dashboard

- Create function `tv-logins-exchange`.
- Paste file contents from `infra/supabase/functions/tv-logins-exchange/index.ts`.
- Deploy.

### Option B: Supabase CLI

From repo root:

```powershell
New-Item -ItemType Directory -Force supabase\functions\tv-logins-exchange | Out-Null
Copy-Item infra\supabase\functions\tv-logins-exchange\index.ts supabase\functions\tv-logins-exchange\index.ts -Force
supabase functions deploy tv-logins-exchange
```

The function expects platform env vars:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`

## 3. Configure App Properties

Use these in `local.properties` (release) and `local.dev.properties` (debug):

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `TV_LOGIN_WEB_BASE_URL` -> URL where the web app is hosted

Also keep your existing:

- `TRAKT_CLIENT_ID`
- `TRAKT_CLIENT_SECRET`

## 4. Quick Smoke Test

1. In app, open Account -> QR login.
2. QR code opens your hosted web app with `?code=...&nonce=...`.
3. Sign in on web page and approve.
4. TV should switch from `pending` to `approved` then finish exchange.

If TV shows `QR login service is outdated`, re-run `bootstrap.sql`.
