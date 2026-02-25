#!/bin/sh
set -eu

CFG=/usr/share/nginx/html/config.js

if [ -n "${SUPABASE_URL:-}" ] && [ -n "${SUPABASE_ANON_KEY:-}" ]; then
  cat > "$CFG" <<EOF
window.TV_LOGIN_CONFIG = {
  SUPABASE_URL: "${SUPABASE_URL}",
  SUPABASE_ANON_KEY: "${SUPABASE_ANON_KEY}"
};
EOF
  echo "Generated config.js from environment variables."
  exit 0
fi

if [ ! -f "$CFG" ] && [ -f /usr/share/nginx/html/config.example.js ]; then
  cp /usr/share/nginx/html/config.example.js "$CFG"
  echo "config.js missing; copied config.example.js as fallback."
fi
