
begin;
create extension if not exists pgcrypto;

create table if not exists public.linked_devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_user_id uuid not null references auth.users(id) on delete cascade,
  device_name text,
  linked_at timestamptz not null default now(),
  unique (owner_id, device_user_id),
  unique (device_user_id)
);

create table if not exists public.sync_codes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  code text not null unique,
  pin_hash text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  claimed_at timestamptz,
  claimed_by uuid references auth.users(id) on delete set null
);

create table if not exists public.tv_login_sessions (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  requester_user_id uuid not null references auth.users(id) on delete cascade,
  approved_by_user_id uuid references auth.users(id) on delete set null,
  device_nonce text not null,
  device_name text,
  redirect_base_url text not null,
  web_url text not null,
  status text not null default 'pending' check (status in ('pending', 'approved', 'used', 'expired', 'cancelled')),
  poll_interval_seconds integer not null default 3,
  created_at timestamptz not null default now(),
  approved_at timestamptz,
  used_at timestamptz,
  expires_at timestamptz not null
);

create table if not exists public.profiles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_index integer not null,
  name text not null default '',
  avatar_color_hex text not null default '#1E88E5',
  uses_primary_addons boolean not null default false,
  uses_primary_plugins boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_index)
);

create table if not exists public.addons (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  url text not null,
  name text,
  enabled boolean not null default true,
  sort_order integer not null default 0,
  profile_id integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, url)
);

create table if not exists public.plugins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  url text not null,
  name text,
  enabled boolean not null default true,
  sort_order integer not null default 0,
  profile_id integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, url)
);

create table if not exists public.library_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content_id text not null,
  content_type text not null,
  name text not null default '',
  poster text,
  poster_shape text not null default 'POSTER',
  background text,
  description text,
  release_info text,
  imdb_rating real,
  genres text[] not null default '{}',
  addon_base_url text,
  added_at bigint not null default (extract(epoch from now())::bigint),
  profile_id integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, content_id, content_type)
);

create table if not exists public.watch_progress (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content_id text not null,
  content_type text not null,
  video_id text not null,
  season integer,
  episode integer,
  position bigint not null default 0,
  duration bigint not null default 0,
  last_watched bigint not null default 0,
  progress_key text not null,
  profile_id integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, progress_key)
);

create table if not exists public.watched_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content_id text not null,
  content_type text not null,
  title text not null default '',
  season integer,
  episode integer,
  season_key integer generated always as (coalesce(season, -1)) stored,
  episode_key integer generated always as (coalesce(episode, -1)) stored,
  watched_at bigint not null default 0,
  profile_id integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, content_id, content_type, season_key, episode_key)
);

create index if not exists idx_linked_devices_owner on public.linked_devices(owner_id);
create index if not exists idx_sync_codes_owner_active on public.sync_codes(owner_id, expires_at) where claimed_at is null;
create index if not exists idx_profiles_user_profile_index on public.profiles(user_id, profile_index);
create index if not exists idx_addons_user_profile on public.addons(user_id, profile_id);
create index if not exists idx_plugins_user_profile on public.plugins(user_id, profile_id);
create index if not exists idx_library_items_user_profile on public.library_items(user_id, profile_id);
create index if not exists idx_watch_progress_user_profile on public.watch_progress(user_id, profile_id);
create index if not exists idx_watched_items_user_profile on public.watched_items(user_id, profile_id);

create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists trg_profiles_touch_updated_at on public.profiles;
create trigger trg_profiles_touch_updated_at before update on public.profiles for each row execute function public.touch_updated_at();
drop trigger if exists trg_addons_touch_updated_at on public.addons;
create trigger trg_addons_touch_updated_at before update on public.addons for each row execute function public.touch_updated_at();
drop trigger if exists trg_plugins_touch_updated_at on public.plugins;
create trigger trg_plugins_touch_updated_at before update on public.plugins for each row execute function public.touch_updated_at();
drop trigger if exists trg_library_touch_updated_at on public.library_items;
create trigger trg_library_touch_updated_at before update on public.library_items for each row execute function public.touch_updated_at();
drop trigger if exists trg_watch_progress_touch_updated_at on public.watch_progress;
create trigger trg_watch_progress_touch_updated_at before update on public.watch_progress for each row execute function public.touch_updated_at();
drop trigger if exists trg_watched_items_touch_updated_at on public.watched_items;
create trigger trg_watched_items_touch_updated_at before update on public.watched_items for each row execute function public.touch_updated_at();

create or replace function public.generate_short_code(p_len integer default 8)
returns text language plpgsql set search_path = public as $$
declare
  v_chars constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_result text := '';
  v_i integer;
begin
  if p_len < 4 then raise exception 'Code length too short'; end if;
  for v_i in 1..p_len loop
    v_result := v_result || substr(v_chars, 1 + floor(random() * length(v_chars))::integer, 1);
  end loop;
  return v_result;
end;
$$;

create or replace function public.get_sync_owner()
returns uuid language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_owner uuid;
begin
  if v_uid is null then raise exception 'Not authenticated'; end if;
  select owner_id into v_owner from public.linked_devices where device_user_id = v_uid order by linked_at desc limit 1;
  return coalesce(v_owner, v_uid);
end;
$$;

create or replace function public.ensure_profile_exists(p_user_id uuid, p_profile_id integer)
returns void language plpgsql security definer set search_path = public as $$
begin
  if p_profile_id is null or p_profile_id < 1 then raise exception 'Invalid profile id'; end if;
  insert into public.profiles (user_id, profile_index, name)
  values (p_user_id, p_profile_id, 'Profile ' || p_profile_id::text)
  on conflict (user_id, profile_index) do nothing;
end;
$$;
create or replace function public.generate_sync_code(p_pin text)
returns table(code text) language plpgsql security definer set search_path = public as $$
declare
  v_owner uuid := auth.uid();
  v_code text;
  v_inserted boolean := false;
  v_attempt integer;
begin
  if v_owner is null then raise exception 'Not authenticated'; end if;
  if p_pin is null or length(trim(p_pin)) < 4 or length(trim(p_pin)) > 8 then raise exception 'Invalid PIN'; end if;

  update public.sync_codes set claimed_at = now() where owner_id = v_owner and claimed_at is null;

  for v_attempt in 1..20 loop
    v_code := public.generate_short_code(8);
    begin
      insert into public.sync_codes (owner_id, code, pin_hash, expires_at)
      values (v_owner, v_code, crypt(trim(p_pin), gen_salt('bf')), now() + interval '15 minutes');
      v_inserted := true;
      exit;
    exception when unique_violation then null;
    end;
  end loop;

  if not v_inserted then raise exception 'Unable to generate sync code'; end if;
  return query select v_code;
end;
$$;

create or replace function public.get_sync_code(p_pin text)
returns table(code text) language plpgsql security definer set search_path = public as $$
declare
  v_owner uuid := auth.uid();
  v_code text;
  v_hash text;
begin
  if v_owner is null then raise exception 'Not authenticated'; end if;

  select sc.code, sc.pin_hash
    into v_code, v_hash
    from public.sync_codes sc
   where sc.owner_id = v_owner and sc.claimed_at is null and sc.expires_at > now()
   order by sc.created_at desc limit 1;

  if v_code is null then raise exception 'No sync code found'; end if;
  if crypt(trim(coalesce(p_pin, '')), v_hash) <> v_hash then raise exception 'Incorrect PIN'; end if;
  return query select v_code;
end;
$$;

create or replace function public.claim_sync_code(
  p_code text,
  p_pin text,
  p_device_name text default null
)
returns table(result_owner_id uuid, success boolean, message text)
language plpgsql security definer set search_path = public as $$
declare
  v_device_user uuid := auth.uid();
  v_sync public.sync_codes%rowtype;
begin
  if v_device_user is null then raise exception 'Not authenticated'; end if;
  if p_code is null or length(trim(p_code)) = 0 then raise exception 'Invalid sync code'; end if;

  select * into v_sync from public.sync_codes
   where upper(code) = upper(trim(p_code))
   order by created_at desc limit 1;

  if not found then raise exception 'Sync code not found'; end if;
  if v_sync.claimed_at is not null then raise exception 'Sync code already used'; end if;
  if v_sync.expires_at <= now() then
    update public.sync_codes set claimed_at = now() where id = v_sync.id;
    raise exception 'Sync code expired';
  end if;
  if crypt(trim(coalesce(p_pin, '')), v_sync.pin_hash) <> v_sync.pin_hash then raise exception 'Incorrect PIN'; end if;
  if v_sync.owner_id = v_device_user then raise exception 'Cannot claim your own sync code'; end if;

  insert into public.linked_devices (owner_id, device_user_id, device_name)
  values (v_sync.owner_id, v_device_user, nullif(trim(coalesce(p_device_name, '')), ''))
  on conflict (device_user_id) do update
    set owner_id = excluded.owner_id,
        device_name = coalesce(excluded.device_name, public.linked_devices.device_name),
        linked_at = now();

  update public.sync_codes set claimed_at = now(), claimed_by = v_device_user where id = v_sync.id;
  return query select v_sync.owner_id, true, 'Linked successfully';
end;
$$;

create or replace function public.unlink_device(p_device_user_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_owner uuid := auth.uid();
begin
  if v_owner is null then raise exception 'Not authenticated'; end if;
  delete from public.linked_devices where owner_id = v_owner and device_user_id = p_device_user_id;
end;
$$;

create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
returns table(code text, web_url text, expires_at timestamptz, poll_interval_seconds integer)
language plpgsql security definer set search_path = public as $$
declare
  v_requester uuid := auth.uid();
  v_code text;
  v_base_url text;
  v_web_url text;
  v_expires_at timestamptz := now() + interval '10 minutes';
  v_poll_interval integer := 3;
  v_attempt integer;
begin
  if v_requester is null then raise exception 'Not authenticated'; end if;
  if p_device_nonce is null or p_device_nonce !~ '^[A-Za-z0-9_-]{16,}$' then raise exception 'Invalid device nonce'; end if;
  if p_redirect_base_url is null or length(trim(p_redirect_base_url)) = 0 then raise exception 'Invalid TV login redirect base URL'; end if;
  if trim(p_redirect_base_url) !~* '^https?://[A-Za-z0-9\.-]+' then raise exception 'Invalid TV login redirect base URL'; end if;

  update public.tv_login_sessions tls
     set status = 'expired'
   where tls.requester_user_id = v_requester
     and tls.status = 'pending'
     and tls.expires_at <= now();

  v_base_url := regexp_replace(trim(p_redirect_base_url), '/+$', '');

  for v_attempt in 1..20 loop
    v_code := public.generate_short_code(8);
    v_web_url := v_base_url || '?code=' || v_code || '&nonce=' || p_device_nonce;
    begin
      insert into public.tv_login_sessions (
        code, requester_user_id, device_nonce, device_name, redirect_base_url,
        web_url, status, poll_interval_seconds, expires_at
      ) values (
        v_code, v_requester, p_device_nonce, nullif(trim(coalesce(p_device_name, '')), ''),
        v_base_url, v_web_url, 'pending', v_poll_interval, v_expires_at
      );
      return query select v_code, v_web_url, v_expires_at, v_poll_interval;
      return;
    exception when unique_violation then null;
    end;
  end loop;

  raise exception 'Unable to start TV login session';
end;
$$;

create or replace function public.poll_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table(status text, expires_at timestamptz, poll_interval_seconds integer)
language plpgsql security definer set search_path = public as $$
declare
  v_requester uuid := auth.uid();
  v_session public.tv_login_sessions%rowtype;
begin
  if v_requester is null then raise exception 'Not authenticated'; end if;

  select * into v_session from public.tv_login_sessions
   where upper(code) = upper(trim(coalesce(p_code, '')))
     and requester_user_id = v_requester
     and device_nonce = coalesce(p_device_nonce, '')
   order by created_at desc limit 1;

  if not found then raise exception 'Invalid TV login code or nonce'; end if;

  if v_session.expires_at <= now() and v_session.status in ('pending', 'approved') then
    update public.tv_login_sessions set status = 'expired' where id = v_session.id;
    v_session.status := 'expired';
  end if;

  return query select v_session.status, v_session.expires_at, v_session.poll_interval_seconds;
end;
$$;

create or replace function public.approve_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table(status text, message text)
language plpgsql security definer set search_path = public as $$
declare
  v_owner uuid := auth.uid();
  v_is_anonymous boolean := coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false);
  v_session public.tv_login_sessions%rowtype;
begin
  if v_owner is null then raise exception 'Not authenticated'; end if;
  if v_is_anonymous then raise exception 'Full account required to approve TV login'; end if;

  select * into v_session from public.tv_login_sessions
   where upper(code) = upper(trim(coalesce(p_code, '')))
     and device_nonce = coalesce(p_device_nonce, '')
   order by created_at desc limit 1;

  if not found then raise exception 'Invalid TV login code or nonce'; end if;
  if v_session.expires_at <= now() then
    update public.tv_login_sessions set status = 'expired' where id = v_session.id;
    raise exception 'TV login expired';
  end if;
  if v_session.status = 'used' then raise exception 'TV login already used'; end if;
  if v_session.status = 'cancelled' then raise exception 'TV login cancelled'; end if;

  update public.tv_login_sessions
     set status = 'approved', approved_by_user_id = v_owner, approved_at = now()
   where id = v_session.id;

  return query select 'approved'::text, 'TV login approved'::text;
end;
$$;
create or replace function public.sync_push_profiles(p_profiles jsonb)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  if p_profiles is null or jsonb_typeof(p_profiles) <> 'array' then return; end if;

  insert into public.profiles (
    user_id, profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins
  )
  select v_owner,
         x.profile_index,
         coalesce(nullif(x.name, ''), 'Profile ' || x.profile_index::text),
         coalesce(nullif(x.avatar_color_hex, ''), '#1E88E5'),
         coalesce(x.uses_primary_addons, false),
         coalesce(x.uses_primary_plugins, false)
  from jsonb_to_recordset(p_profiles) as x(
    profile_index integer,
    name text,
    avatar_color_hex text,
    uses_primary_addons boolean,
    uses_primary_plugins boolean
  )
  where x.profile_index is not null and x.profile_index >= 1
  on conflict (user_id, profile_index) do update
    set name = excluded.name,
        avatar_color_hex = excluded.avatar_color_hex,
        uses_primary_addons = excluded.uses_primary_addons,
        uses_primary_plugins = excluded.uses_primary_plugins,
        updated_at = now();
end;
$$;

create or replace function public.sync_pull_profiles()
returns table(
  id uuid,
  user_id uuid,
  profile_index integer,
  name text,
  avatar_color_hex text,
  uses_primary_addons boolean,
  uses_primary_plugins boolean,
  created_at timestamptz,
  updated_at timestamptz
)
language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  return query
  select p.id, p.user_id, p.profile_index, p.name, p.avatar_color_hex,
         p.uses_primary_addons, p.uses_primary_plugins, p.created_at, p.updated_at
    from public.profiles p
   where p.user_id = v_owner
   order by p.profile_index asc;
end;
$$;

create or replace function public.sync_delete_profile_data(p_profile_id integer)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  if p_profile_id is null or p_profile_id < 1 then raise exception 'Invalid profile id'; end if;
  delete from public.addons where user_id = v_owner and profile_id = p_profile_id;
  delete from public.plugins where user_id = v_owner and profile_id = p_profile_id;
  delete from public.library_items where user_id = v_owner and profile_id = p_profile_id;
  delete from public.watch_progress where user_id = v_owner and profile_id = p_profile_id;
  delete from public.watched_items where user_id = v_owner and profile_id = p_profile_id;
  if p_profile_id <> 1 then delete from public.profiles where user_id = v_owner and profile_index = p_profile_id; end if;
end;
$$;

create or replace function public.sync_push_addons(p_addons jsonb, p_profile_id integer default 1)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  perform public.ensure_profile_exists(v_owner, p_profile_id);
  delete from public.addons where user_id = v_owner and profile_id = p_profile_id;
  if p_addons is null or jsonb_typeof(p_addons) <> 'array' then return; end if;

  insert into public.addons (user_id, url, name, enabled, sort_order, profile_id)
  select v_owner,
         trim(x.url),
         nullif(trim(coalesce(x.name, '')), ''),
         coalesce(x.enabled, true),
         coalesce(x.sort_order, 0),
         p_profile_id
    from jsonb_to_recordset(p_addons) as x(url text, name text, enabled boolean, sort_order integer)
   where x.url is not null and length(trim(x.url)) > 0
  on conflict (user_id, profile_id, url) do update
    set name = excluded.name,
        enabled = excluded.enabled,
        sort_order = excluded.sort_order,
        updated_at = now();
end;
$$;

create or replace function public.sync_push_plugins(p_plugins jsonb, p_profile_id integer default 1)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  perform public.ensure_profile_exists(v_owner, p_profile_id);
  delete from public.plugins where user_id = v_owner and profile_id = p_profile_id;
  if p_plugins is null or jsonb_typeof(p_plugins) <> 'array' then return; end if;

  insert into public.plugins (user_id, url, name, enabled, sort_order, profile_id)
  select v_owner,
         trim(x.url),
         nullif(trim(coalesce(x.name, '')), ''),
         coalesce(x.enabled, true),
         coalesce(x.sort_order, 0),
         p_profile_id
    from jsonb_to_recordset(p_plugins) as x(url text, name text, enabled boolean, sort_order integer)
   where x.url is not null and length(trim(x.url)) > 0
  on conflict (user_id, profile_id, url) do update
    set name = excluded.name,
        enabled = excluded.enabled,
        sort_order = excluded.sort_order,
        updated_at = now();
end;
$$;

create or replace function public.sync_push_library(p_items jsonb, p_profile_id integer default 1)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  perform public.ensure_profile_exists(v_owner, p_profile_id);
  delete from public.library_items where user_id = v_owner and profile_id = p_profile_id;
  if p_items is null or jsonb_typeof(p_items) <> 'array' then return; end if;

  insert into public.library_items (
    user_id, content_id, content_type, name, poster, poster_shape, background,
    description, release_info, imdb_rating, genres, addon_base_url, profile_id
  )
  select v_owner,
         trim(x.content_id),
         trim(x.content_type),
         coalesce(x.name, ''),
         x.poster,
         coalesce(nullif(x.poster_shape, ''), 'POSTER'),
         x.background,
         x.description,
         x.release_info,
         x.imdb_rating::real,
         coalesce(array(select jsonb_array_elements_text(coalesce(x.genres, '[]'::jsonb))), '{}'::text[]),
         x.addon_base_url,
         p_profile_id
    from jsonb_to_recordset(p_items) as x(
      content_id text,
      content_type text,
      name text,
      poster text,
      poster_shape text,
      background text,
      description text,
      release_info text,
      imdb_rating double precision,
      genres jsonb,
      addon_base_url text
    )
   where x.content_id is not null and length(trim(x.content_id)) > 0
     and x.content_type is not null and length(trim(x.content_type)) > 0
  on conflict (user_id, profile_id, content_id, content_type) do update
    set name = excluded.name,
        poster = excluded.poster,
        poster_shape = excluded.poster_shape,
        background = excluded.background,
        description = excluded.description,
        release_info = excluded.release_info,
        imdb_rating = excluded.imdb_rating,
        genres = excluded.genres,
        addon_base_url = excluded.addon_base_url,
        updated_at = now();
end;
$$;

create or replace function public.sync_pull_library(p_profile_id integer default 1)
returns table(
  id uuid,
  user_id uuid,
  content_id text,
  content_type text,
  name text,
  poster text,
  poster_shape text,
  background text,
  description text,
  release_info text,
  imdb_rating real,
  genres text[],
  addon_base_url text,
  added_at bigint,
  profile_id integer
)
language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  return query
  select li.id, li.user_id, li.content_id, li.content_type, li.name, li.poster,
         li.poster_shape, li.background, li.description, li.release_info,
         li.imdb_rating, li.genres, li.addon_base_url, li.added_at, li.profile_id
    from public.library_items li
   where li.user_id = v_owner and li.profile_id = p_profile_id
   order by li.added_at desc;
end;
$$;

create or replace function public.sync_push_watch_progress(p_entries jsonb, p_profile_id integer default 1)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  perform public.ensure_profile_exists(v_owner, p_profile_id);
  if p_entries is null or jsonb_typeof(p_entries) <> 'array' then return; end if;

  insert into public.watch_progress (
    user_id, content_id, content_type, video_id, season, episode,
    position, duration, last_watched, progress_key, profile_id
  )
  select v_owner,
         trim(x.content_id),
         trim(x.content_type),
         trim(x.video_id),
         x.season,
         x.episode,
         coalesce(x.position, 0),
         coalesce(x.duration, 0),
         coalesce(x.last_watched, 0),
         trim(x.progress_key),
         p_profile_id
    from jsonb_to_recordset(p_entries) as x(
      content_id text,
      content_type text,
      video_id text,
      season integer,
      episode integer,
      position bigint,
      duration bigint,
      last_watched bigint,
      progress_key text
    )
   where x.progress_key is not null and length(trim(x.progress_key)) > 0
     and x.content_id is not null and length(trim(x.content_id)) > 0
     and x.content_type is not null and length(trim(x.content_type)) > 0
     and x.video_id is not null and length(trim(x.video_id)) > 0
  on conflict (user_id, profile_id, progress_key) do update
    set content_id = excluded.content_id,
        content_type = excluded.content_type,
        video_id = excluded.video_id,
        season = excluded.season,
        episode = excluded.episode,
        position = excluded.position,
        duration = excluded.duration,
        last_watched = excluded.last_watched,
        updated_at = now();
end;
$$;

create or replace function public.sync_delete_watch_progress(p_keys text[], p_profile_id integer default 1)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  if p_keys is null or cardinality(p_keys) = 0 then return; end if;
  delete from public.watch_progress
   where user_id = v_owner and profile_id = p_profile_id and progress_key = any(p_keys);
end;
$$;

create or replace function public.sync_pull_watch_progress(p_profile_id integer default 1)
returns table(
  id uuid,
  user_id uuid,
  content_id text,
  content_type text,
  video_id text,
  season integer,
  episode integer,
  "position" bigint,
  duration bigint,
  last_watched bigint,
  progress_key text,
  profile_id integer
)
language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  return query
  select wp.id, wp.user_id, wp.content_id, wp.content_type, wp.video_id,
         wp.season, wp.episode, wp.position as "position", wp.duration, wp.last_watched,
         wp.progress_key, wp.profile_id
    from public.watch_progress wp
   where wp.user_id = v_owner and wp.profile_id = p_profile_id
   order by wp.last_watched desc;
end;
$$;

create or replace function public.sync_push_watched_items(p_items jsonb, p_profile_id integer default 1)
returns void language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  perform public.ensure_profile_exists(v_owner, p_profile_id);
  delete from public.watched_items where user_id = v_owner and profile_id = p_profile_id;
  if p_items is null or jsonb_typeof(p_items) <> 'array' then return; end if;

  insert into public.watched_items (
    user_id, content_id, content_type, title, season, episode, watched_at, profile_id
  )
  select v_owner,
         trim(x.content_id),
         trim(x.content_type),
         coalesce(x.title, ''),
         x.season,
         x.episode,
         coalesce(x.watched_at, 0),
         p_profile_id
    from jsonb_to_recordset(p_items) as x(
      content_id text,
      content_type text,
      title text,
      season integer,
      episode integer,
      watched_at bigint
    )
   where x.content_id is not null and length(trim(x.content_id)) > 0
     and x.content_type is not null and length(trim(x.content_type)) > 0
  on conflict (user_id, profile_id, content_id, content_type, season_key, episode_key) do update
    set title = excluded.title,
        watched_at = excluded.watched_at,
        updated_at = now();
end;
$$;

create or replace function public.sync_pull_watched_items(p_profile_id integer default 1)
returns table(
  id uuid,
  user_id uuid,
  content_id text,
  content_type text,
  title text,
  season integer,
  episode integer,
  watched_at bigint,
  profile_id integer
)
language plpgsql security definer set search_path = public as $$
declare v_owner uuid := public.get_sync_owner();
begin
  return query
  select wi.id, wi.user_id, wi.content_id, wi.content_type, wi.title,
         wi.season, wi.episode, wi.watched_at, wi.profile_id
    from public.watched_items wi
   where wi.user_id = v_owner and wi.profile_id = p_profile_id
   order by wi.watched_at desc;
end;
$$;

create or replace function public.get_sync_overview()
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_owner uuid := public.get_sync_owner();
  v_result jsonb;
begin
  with addons_json as (
    select coalesce(jsonb_object_agg(profile_id::text, cnt), '{}'::jsonb) as data
      from (select profile_id, count(*)::int as cnt from public.addons where user_id = v_owner group by profile_id) q
  ), plugins_json as (
    select coalesce(jsonb_object_agg(profile_id::text, cnt), '{}'::jsonb) as data
      from (select profile_id, count(*)::int as cnt from public.plugins where user_id = v_owner group by profile_id) q
  ), library_json as (
    select coalesce(jsonb_object_agg(profile_id::text, cnt), '{}'::jsonb) as data
      from (select profile_id, count(*)::int as cnt from public.library_items where user_id = v_owner group by profile_id) q
  ), watch_progress_json as (
    select coalesce(jsonb_object_agg(profile_id::text, cnt), '{}'::jsonb) as data
      from (select profile_id, count(*)::int as cnt from public.watch_progress where user_id = v_owner group by profile_id) q
  ), watched_items_json as (
    select coalesce(jsonb_object_agg(profile_id::text, cnt), '{}'::jsonb) as data
      from (select profile_id, count(*)::int as cnt from public.watched_items where user_id = v_owner group by profile_id) q
  ), profiles_json as (
    select coalesce(jsonb_object_agg(profile_index::text, jsonb_build_object('name', name, 'color', avatar_color_hex)), '{}'::jsonb) as data
      from public.profiles where user_id = v_owner
  )
  select jsonb_build_object(
    'addons', (select data from addons_json),
    'plugins', (select data from plugins_json),
    'library_items', (select data from library_json),
    'watch_progress', (select data from watch_progress_json),
    'watched_items', (select data from watched_items_json),
    'profiles', (select data from profiles_json)
  ) into v_result;

  return coalesce(v_result, '{}'::jsonb);
end;
$$;
grant usage on schema public to authenticated;
grant select, insert, update, delete on public.addons to authenticated;
grant select, insert, update, delete on public.plugins to authenticated;
grant select on public.linked_devices to authenticated;

grant execute on function public.get_sync_owner() to authenticated;
grant execute on function public.generate_sync_code(text) to authenticated;
grant execute on function public.get_sync_code(text) to authenticated;
grant execute on function public.claim_sync_code(text, text, text) to authenticated;
grant execute on function public.unlink_device(uuid) to authenticated;
grant execute on function public.start_tv_login_session(text, text, text) to authenticated;
grant execute on function public.poll_tv_login_session(text, text) to authenticated;
grant execute on function public.approve_tv_login_session(text, text) to authenticated;
grant execute on function public.sync_push_profiles(jsonb) to authenticated;
grant execute on function public.sync_pull_profiles() to authenticated;
grant execute on function public.sync_delete_profile_data(integer) to authenticated;
grant execute on function public.sync_push_addons(jsonb, integer) to authenticated;
grant execute on function public.sync_push_plugins(jsonb, integer) to authenticated;
grant execute on function public.sync_push_library(jsonb, integer) to authenticated;
grant execute on function public.sync_pull_library(integer) to authenticated;
grant execute on function public.sync_push_watch_progress(jsonb, integer) to authenticated;
grant execute on function public.sync_delete_watch_progress(text[], integer) to authenticated;
grant execute on function public.sync_pull_watch_progress(integer) to authenticated;
grant execute on function public.sync_push_watched_items(jsonb, integer) to authenticated;
grant execute on function public.sync_pull_watched_items(integer) to authenticated;
grant execute on function public.get_sync_overview() to authenticated;

alter table public.linked_devices enable row level security;
alter table public.sync_codes enable row level security;
alter table public.tv_login_sessions enable row level security;
alter table public.profiles enable row level security;
alter table public.addons enable row level security;
alter table public.plugins enable row level security;
alter table public.library_items enable row level security;
alter table public.watch_progress enable row level security;
alter table public.watched_items enable row level security;

drop policy if exists linked_devices_select on public.linked_devices;
create policy linked_devices_select on public.linked_devices
for select to authenticated
using (owner_id = public.get_sync_owner() or device_user_id = auth.uid());

drop policy if exists linked_devices_owner_manage on public.linked_devices;
create policy linked_devices_owner_manage on public.linked_devices
for all to authenticated
using (owner_id = auth.uid())
with check (owner_id = auth.uid());

drop policy if exists sync_codes_owner_only on public.sync_codes;
create policy sync_codes_owner_only on public.sync_codes
for all to authenticated
using (owner_id = auth.uid())
with check (owner_id = auth.uid());

drop policy if exists tv_login_requester_select on public.tv_login_sessions;
create policy tv_login_requester_select on public.tv_login_sessions
for select to authenticated
using (requester_user_id = auth.uid());

drop policy if exists tv_login_requester_insert on public.tv_login_sessions;
create policy tv_login_requester_insert on public.tv_login_sessions
for insert to authenticated
with check (requester_user_id = auth.uid());

drop policy if exists tv_login_requester_update on public.tv_login_sessions;
create policy tv_login_requester_update on public.tv_login_sessions
for update to authenticated
using (requester_user_id = auth.uid())
with check (requester_user_id = auth.uid());

drop policy if exists profiles_sync_owner on public.profiles;
create policy profiles_sync_owner on public.profiles
for all to authenticated
using (user_id = public.get_sync_owner())
with check (user_id = public.get_sync_owner());

drop policy if exists addons_sync_owner on public.addons;
create policy addons_sync_owner on public.addons
for all to authenticated
using (user_id = public.get_sync_owner())
with check (user_id = public.get_sync_owner());

drop policy if exists plugins_sync_owner on public.plugins;
create policy plugins_sync_owner on public.plugins
for all to authenticated
using (user_id = public.get_sync_owner())
with check (user_id = public.get_sync_owner());

drop policy if exists library_items_sync_owner on public.library_items;
create policy library_items_sync_owner on public.library_items
for all to authenticated
using (user_id = public.get_sync_owner())
with check (user_id = public.get_sync_owner());

drop policy if exists watch_progress_sync_owner on public.watch_progress;
create policy watch_progress_sync_owner on public.watch_progress
for all to authenticated
using (user_id = public.get_sync_owner())
with check (user_id = public.get_sync_owner());

drop policy if exists watched_items_sync_owner on public.watched_items;
create policy watched_items_sync_owner on public.watched_items
for all to authenticated
using (user_id = public.get_sync_owner())
with check (user_id = public.get_sync_owner());

commit;
