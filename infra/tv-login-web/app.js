import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm";

const cfg = window.TV_LOGIN_CONFIG ?? {};
const code = (new URLSearchParams(window.location.search).get("code") ?? "").trim().toUpperCase();
const nonce = (new URLSearchParams(window.location.search).get("nonce") ?? "").trim();

const codeValue = document.getElementById("codeValue");
const nonceValue = document.getElementById("nonceValue");
const sessionInfo = document.getElementById("sessionInfo");
const signedInAs = document.getElementById("signedInAs");
const loginBlock = document.getElementById("loginBlock");
const emailInput = document.getElementById("emailInput");
const passwordInput = document.getElementById("passwordInput");
const signInBtn = document.getElementById("signInBtn");
const signUpBtn = document.getElementById("signUpBtn");
const approveBtn = document.getElementById("approveBtn");
const signOutBtn = document.getElementById("signOutBtn");
const notice = document.getElementById("notice");

codeValue.textContent = code || "(missing)";
nonceValue.textContent = nonce || "(missing)";

if (!cfg.SUPABASE_URL || !cfg.SUPABASE_ANON_KEY) {
  setNotice("Missing config.js. Copy config.example.js to config.js and fill credentials.", "err");
  throw new Error("Missing TV_LOGIN_CONFIG");
}

if (!code || !nonce) {
  setNotice("Missing code or nonce in URL.", "err");
  throw new Error("Missing code/nonce");
}

const supabase = createClient(cfg.SUPABASE_URL, cfg.SUPABASE_ANON_KEY, {
  auth: { persistSession: true, autoRefreshToken: true }
});

signInBtn.addEventListener("click", async () => {
  const email = emailInput.value.trim();
  const password = passwordInput.value;
  if (!email || !password) {
    setNotice("Email and password are required.", "err");
    return;
  }
  setNotice("Signing in...", "info");
  const { error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) {
    setNotice(`Sign-in failed: ${error.message}`, "err");
    return;
  }
  setNotice("Signed in.", "ok");
  await refreshSessionUI();
});

signUpBtn.addEventListener("click", async () => {
  const email = emailInput.value.trim();
  const password = passwordInput.value;
  if (!email || !password) {
    setNotice("Email and password are required.", "err");
    return;
  }

  if (password.length < 6) {
    setNotice("Password must be at least 6 characters.", "err");
    return;
  }

  setNotice("Creating account...", "info");
  const { data, error } = await supabase.auth.signUp({ email, password });
  if (error) {
    setNotice(`Sign-up failed: ${error.message}`, "err");
    return;
  }

  const confirmed = !!data.session;
  if (confirmed) {
    setNotice("Account created and signed in.", "ok");
    await refreshSessionUI();
    return;
  }

  setNotice("Account created. Check your email to confirm before sign-in.", "info");
});

approveBtn.addEventListener("click", async () => {
  setNotice("Approving TV login...", "info");
  const { data, error } = await supabase.rpc("approve_tv_login_session", {
    p_code: code,
    p_device_nonce: nonce
  });
  if (error) {
    setNotice(`Approval failed: ${error.message}`, "err");
    return;
  }

  const row = Array.isArray(data) ? data[0] : data;
  const msg = row?.message ?? "TV login approved.";
  setNotice(msg, "ok");
});

signOutBtn.addEventListener("click", async () => {
  await supabase.auth.signOut();
  setNotice("Signed out.", "info");
  await refreshSessionUI();
});

supabase.auth.onAuthStateChange(() => {
  refreshSessionUI().catch(() => {});
});

await refreshSessionUI();

async function refreshSessionUI() {
  const { data } = await supabase.auth.getSession();
  const session = data.session;
  if (!session) {
    sessionInfo.classList.add("hidden");
    signOutBtn.classList.add("hidden");
    approveBtn.classList.add("hidden");
    loginBlock.classList.remove("hidden");
    return;
  }

  sessionInfo.classList.remove("hidden");
  signOutBtn.classList.remove("hidden");
  approveBtn.classList.remove("hidden");
  loginBlock.classList.add("hidden");
  signedInAs.textContent = session.user.email ?? session.user.id;
}

function setNotice(message, type) {
  notice.classList.remove("hidden", "ok", "err", "info");
  notice.classList.add(type);
  notice.textContent = message;
}
