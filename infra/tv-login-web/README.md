# TV Login Web App (Static)

This is the web approval page used by NuvioTV QR sign-in.

## Files

- `index.html`
- `app.js`
- `styles.css`
- `config.example.js`

## Setup

1. Copy `config.example.js` to `config.js`.
2. Fill:

```js
window.TV_LOGIN_CONFIG = {
  SUPABASE_URL: "https://YOUR_PROJECT_REF.supabase.co",
  SUPABASE_ANON_KEY: "YOUR_SUPABASE_ANON_KEY"
};
```

## Docker Hosting

You can host this site in Docker with runtime env injection.

1. Create `.env` from `.env.example`.
2. Fill `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
3. Run:

```powershell
docker compose up -d --build
```

4. Site will be available at `http://localhost:8080`.

## Deploy

Host these static files on any static host (Cloudflare Pages, Netlify, Vercel static, GitHub Pages, etc.).

The app URL must match your `TV_LOGIN_WEB_BASE_URL` setting (without query params), for example:

- `https://tv-login.example.com`

Nuvio app will open:

- `https://tv-login.example.com?code=XXXX&nonce=YYYY`

## Behavior

- Signs in user with Supabase email/password.
- Can create new credentials (email/password) via **Create Account**.
- Calls RPC `approve_tv_login_session(p_code, p_device_nonce)`.
- TV app polls and finishes exchange via edge function.

## Creating Users Manually

Preferred: use **Create Account** in this page or Supabase Dashboard -> Authentication -> Users -> Add user.

If email confirmation is enabled in your Supabase auth settings, users must confirm email before sign-in.
