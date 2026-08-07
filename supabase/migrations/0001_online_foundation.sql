-- Carteado BR - base inicial para ranking, salas online e auditoria de eventos.
-- Todas as tabelas publicas nascem com RLS ligada.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    nickname text not null check (char_length(nickname) between 2 and 24),
    avatar_url text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.player_stats (
    profile_id uuid primary key references public.profiles(id) on delete cascade,
    total_wins integer not null default 0 check (total_wins >= 0),
    total_matches integer not null default 0 check (total_matches >= 0),
    cacheta_wins integer not null default 0 check (cacheta_wins >= 0),
    buraco_wins integer not null default 0 check (buraco_wins >= 0),
    tranca_wins integer not null default 0 check (tranca_wins >= 0),
    best_streak integer not null default 0 check (best_streak >= 0),
    current_streak integer not null default 0 check (current_streak >= 0),
    xp integer not null default 0 check (xp >= 0),
    updated_at timestamptz not null default now()
);

create table if not exists public.match_rooms (
    id uuid primary key default gen_random_uuid(),
    room_code text not null unique check (char_length(room_code) between 4 and 8),
    host_id uuid not null references public.profiles(id) on delete cascade,
    game_type text not null check (game_type in ('CACHETA', 'BURACO', 'TRANCA')),
    config jsonb not null default '{}'::jsonb,
    status text not null default 'waiting' check (status in ('waiting', 'playing', 'finished', 'cancelled')),
    max_players integer not null default 2 check (max_players in (2, 4)),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.room_players (
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    profile_id uuid not null references public.profiles(id) on delete cascade,
    seat integer not null check (seat between 0 and 3),
    team integer not null check (team between 0 and 1),
    connected boolean not null default true,
    joined_at timestamptz not null default now(),
    primary key (room_id, profile_id),
    unique (room_id, seat)
);

create table if not exists public.match_events (
    id bigint generated always as identity primary key,
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    message_id text not null,
    actor_id uuid references public.profiles(id) on delete set null,
    seat integer check (seat between 0 and 3),
    event_type text not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (room_id, message_id)
);

create table if not exists public.match_results (
    id uuid primary key default gen_random_uuid(),
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    winner_team integer check (winner_team between 0 and 1),
    winner_profile_id uuid references public.profiles(id) on delete set null,
    scores jsonb not null default '{}'::jsonb,
    breakdown jsonb not null default '{}'::jsonb,
    finished_at timestamptz not null default now()
);

alter table public.profiles enable row level security;
alter table public.player_stats enable row level security;
alter table public.match_rooms enable row level security;
alter table public.room_players enable row level security;
alter table public.match_events enable row level security;
alter table public.match_results enable row level security;

create policy "profiles_select_public"
on public.profiles for select
to authenticated
using (true);

create policy "profiles_insert_self"
on public.profiles for insert
to authenticated
with check (auth.uid() = id);

create policy "profiles_update_self"
on public.profiles for update
to authenticated
using (auth.uid() = id)
with check (auth.uid() = id);

create policy "player_stats_select_public"
on public.player_stats for select
to authenticated
using (true);

create policy "rooms_select_joined_or_waiting"
on public.match_rooms for select
to authenticated
using (
    status = 'waiting'
    or host_id = auth.uid()
    or exists (
        select 1 from public.room_players rp
        where rp.room_id = match_rooms.id
        and rp.profile_id = auth.uid()
    )
);

create policy "rooms_insert_host"
on public.match_rooms for insert
to authenticated
with check (host_id = auth.uid());

create policy "rooms_update_host"
on public.match_rooms for update
to authenticated
using (host_id = auth.uid())
with check (host_id = auth.uid());

create policy "room_players_select_same_room"
on public.room_players for select
to authenticated
using (
    profile_id = auth.uid()
    or exists (
        select 1 from public.room_players mine
        where mine.room_id = room_players.room_id
        and mine.profile_id = auth.uid()
    )
);

create policy "room_players_insert_self"
on public.room_players for insert
to authenticated
with check (profile_id = auth.uid());

create policy "room_players_update_self_or_host"
on public.room_players for update
to authenticated
using (
    profile_id = auth.uid()
    or exists (
        select 1 from public.match_rooms r
        where r.id = room_players.room_id
        and r.host_id = auth.uid()
    )
)
with check (
    profile_id = auth.uid()
    or exists (
        select 1 from public.match_rooms r
        where r.id = room_players.room_id
        and r.host_id = auth.uid()
    )
);

create policy "events_select_room_players"
on public.match_events for select
to authenticated
using (
    exists (
        select 1 from public.room_players rp
        where rp.room_id = match_events.room_id
        and rp.profile_id = auth.uid()
    )
);

create policy "events_insert_room_players"
on public.match_events for insert
to authenticated
with check (
    actor_id = auth.uid()
    and exists (
        select 1 from public.room_players rp
        where rp.room_id = match_events.room_id
        and rp.profile_id = auth.uid()
    )
);

create policy "results_select_room_players"
on public.match_results for select
to authenticated
using (
    exists (
        select 1 from public.room_players rp
        where rp.room_id = match_results.room_id
        and rp.profile_id = auth.uid()
    )
);

create index if not exists idx_match_rooms_status_created
on public.match_rooms (status, created_at desc);

create index if not exists idx_room_players_profile
on public.room_players (profile_id);

create index if not exists idx_match_events_room_created
on public.match_events (room_id, created_at);

create index if not exists idx_player_stats_ranking
on public.player_stats (total_wins desc, xp desc, total_matches asc);
