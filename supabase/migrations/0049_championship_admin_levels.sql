-- Redesenho de campeonatos pedido pelo Bruno: so o admin do jogo cria (nada
-- de qualquer jogador criar campeonato solto), campeonato ganha cadencia
-- (semanal/mensal/manual) e nivel opcional (noob/mid/hard/expert calculado
-- por estatistica, nunca autodeclarado), e toda partida do inscrito conta
-- automaticamente -- acabou a exigencia de vincular a sala antes de comecar
-- (link_room_to_championship), que era o principal ponto de atrito.
--
-- Sem usuario real em producao ainda (confirmado com o Bruno), entao dropo
-- o mecanismo antigo de vinculo manual em vez de manter os dois convivendo.

-- ─── Quem e admin (so o Bruno, por enquanto) ────────────────────────────────

create or replace function private.cbr_is_app_admin(p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from auth.users u
        where u.id = p_user_id
          and lower(u.email) = 'brunogp.corretor@gmail.com'
    );
$$;

revoke all on function private.cbr_is_app_admin(uuid) from public;
grant execute on function private.cbr_is_app_admin(uuid) to authenticated;

-- ─── Nivel calculado por estatistica -- nunca autodeclarado pelo jogador ────

create or replace function private.cbr_player_level(p_total_matches integer, p_total_wins integer)
returns text
language sql
immutable
set search_path = ''
as $$
    select case
        when coalesce(p_total_matches, 0) < 5 then 'NOOB'
        when p_total_matches < 20 then 'MID'
        when p_total_matches >= 50
             and p_total_wins::numeric / greatest(p_total_matches, 1) >= 0.5
            then 'EXPERT'
        else 'HARD'
    end;
$$;

revoke all on function private.cbr_player_level(integer, integer) from public;
grant execute on function private.cbr_player_level(integer, integer) to authenticated;

create or replace function public.get_my_level()
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_total_matches integer;
    v_total_wins integer;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select coalesce(stats.total_matches, 0), coalesce(stats.total_wins, 0)
    into v_total_matches, v_total_wins
    from public.player_stats stats
    where stats.profile_id = v_user_id;

    if not found then
        v_total_matches := 0;
        v_total_wins := 0;
    end if;

    return private.cbr_player_level(v_total_matches, v_total_wins);
end;
$$;

revoke all on function public.get_my_level() from public;
grant execute on function public.get_my_level() to authenticated;

-- ─── Campeonato ganha cadencia, nivel e janela de datas ─────────────────────

alter table public.championships
add column if not exists cadence text not null default 'MANUAL'
    check (cadence in ('WEEKLY', 'MONTHLY', 'MANUAL')),
add column if not exists level text
    check (level in ('NOOB', 'MID', 'HARD', 'EXPERT')),
add column if not exists starts_at timestamptz not null default now(),
add column if not exists ends_at timestamptz;

-- ─── Cada partida completada conta pra todo campeonato ativo compativel ────
-- em vez de exigir link_room_to_championship manual antes de comecar. Uma
-- partida pode contar pra varios campeonatos ao mesmo tempo (ex: semanal e
-- mensal rodando juntos), entao vira tabela propria em vez de uma unica FK.

create table if not exists public.championship_match_entries (
    id bigserial primary key,
    championship_id uuid not null references public.championships(id) on delete cascade,
    match_result_id uuid not null references public.match_results(id) on delete cascade,
    profile_id uuid not null references public.profiles(id) on delete cascade,
    won boolean not null,
    created_at timestamptz not null default now(),
    unique (championship_id, match_result_id, profile_id)
);

create index if not exists idx_championship_match_entries_championship
on public.championship_match_entries(championship_id);

create index if not exists idx_championship_match_entries_profile
on public.championship_match_entries(profile_id);

alter table public.championship_match_entries enable row level security;

create policy "championship_match_entries_select_member"
on public.championship_match_entries for select
to authenticated
using (private.is_championship_participant(championship_id));

grant select on public.championship_match_entries to authenticated;

-- ─── create_championship: so admin, com cadencia e nivel opcional ──────────

drop function if exists public.create_championship(text, text);

create or replace function public.create_championship(
    p_name text,
    p_game_type text,
    p_cadence text default 'MANUAL',
    p_level text default null
)
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text,
    cadence text,
    level text,
    starts_at timestamptz,
    ends_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_name text := trim(coalesce(p_name, ''));
    v_cadence text := upper(trim(coalesce(p_cadence, 'MANUAL')));
    v_level text := nullif(upper(trim(coalesce(p_level, ''))), '');
    v_starts_at timestamptz := now();
    v_ends_at timestamptz;
    v_code text;
    v_championship public.championships%rowtype;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if not private.cbr_is_app_admin(v_user_id) then
        raise exception 'ADMIN_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(v_name) not between 2 and 40 then
        raise exception 'INVALID_CHAMPIONSHIP_NAME' using errcode = 'P0001';
    end if;
    if p_game_type not in ('CACHETA', 'BURACO', 'TRANCA') then
        raise exception 'INVALID_GAME_TYPE' using errcode = 'P0001';
    end if;
    if v_cadence not in ('WEEKLY', 'MONTHLY', 'MANUAL') then
        raise exception 'INVALID_CADENCE' using errcode = 'P0001';
    end if;
    if v_level is not null and v_level not in ('NOOB', 'MID', 'HARD', 'EXPERT') then
        raise exception 'INVALID_LEVEL' using errcode = 'P0001';
    end if;

    v_ends_at := case v_cadence
        when 'WEEKLY' then v_starts_at + interval '7 days'
        when 'MONTHLY' then v_starts_at + interval '30 days'
        else null
    end;

    loop
        v_code := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
        exit when not exists (select 1 from public.championships where championships.code = v_code);
    end loop;

    insert into public.championships (code, name, game_type, host_id, cadence, level, starts_at, ends_at)
    values (v_code, v_name, p_game_type, v_user_id, v_cadence, v_level, v_starts_at, v_ends_at)
    returning * into v_championship;

    insert into public.championship_participants (championship_id, profile_id)
    values (v_championship.id, v_user_id);

    return query
    select
        v_championship.id, v_championship.code, v_championship.name, v_championship.game_type,
        v_championship.status, v_championship.cadence, v_championship.level,
        v_championship.starts_at, v_championship.ends_at;
end;
$$;

revoke all on function public.create_championship(text, text, text, text) from public;
grant execute on function public.create_championship(text, text, text, text) to authenticated;

-- ─── join_championship: exige o nivel calculado bater, quando a sala pede ──
-- drop explicito: create or replace nao deixa mudar as colunas de saida.

drop function if exists public.join_championship(text);

create or replace function public.join_championship(p_code text)
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text,
    cadence text,
    level text,
    starts_at timestamptz,
    ends_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_championship public.championships%rowtype;
    v_total_matches integer;
    v_total_wins integer;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select *
    into v_championship
    from public.championships
    where championships.code = upper(trim(coalesce(p_code, '')));

    if not found then
        raise exception 'CHAMPIONSHIP_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_championship.status <> 'ACTIVE' then
        raise exception 'CHAMPIONSHIP_FINISHED' using errcode = 'P0001';
    end if;
    if v_championship.ends_at is not null and now() > v_championship.ends_at then
        raise exception 'CHAMPIONSHIP_FINISHED' using errcode = 'P0001';
    end if;

    if v_championship.level is not null then
        select coalesce(stats.total_matches, 0), coalesce(stats.total_wins, 0)
        into v_total_matches, v_total_wins
        from public.player_stats stats
        where stats.profile_id = v_user_id;

        if not found then
            v_total_matches := 0;
            v_total_wins := 0;
        end if;

        if private.cbr_player_level(v_total_matches, v_total_wins) <> v_championship.level then
            raise exception 'LEVEL_MISMATCH' using errcode = 'P0001';
        end if;
    end if;

    -- "on conflict (championship_id, profile_id)" da "column reference is
    -- ambiguous" aqui (achado real, testado localmente): o RETURNS TABLE
    -- desta funcao tem uma coluna de saida chamada championship_id, que vira
    -- variavel PL/pgSQL implicita, e a lista de colunas do ON CONFLICT nao
    -- aceita qualificar com o nome da tabela pra desambiguar. Insert simples
    -- + capturar duplicidade e o mesmo resultado idempotente sem esse problema.
    begin
        insert into public.championship_participants (championship_id, profile_id)
        values (v_championship.id, v_user_id);
    exception
        when unique_violation then
            null;
    end;

    return query
    select
        v_championship.id, v_championship.code, v_championship.name, v_championship.game_type,
        v_championship.status, v_championship.cadence, v_championship.level,
        v_championship.starts_at, v_championship.ends_at;
end;
$$;

revoke all on function public.join_championship(text) from public;
grant execute on function public.join_championship(text) to authenticated;

-- ─── link_room_to_championship morre aqui: nao precisa mais vincular sala ──

drop function if exists public.link_room_to_championship(uuid, text);

alter table public.match_rooms drop column if exists championship_id;
alter table public.match_results drop column if exists championship_id;

-- ─── list_championship_standings/matches passam a ler a tabela nova ───────

create or replace function public.list_championship_standings(p_championship_id uuid, p_limit integer default 50)
returns table (
    rank_position bigint,
    profile_id uuid,
    nickname text,
    avatar_url text,
    avatar_photo_path text,
    total_wins integer,
    total_matches integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if not exists (
        select 1
        from public.championship_participants cp
        where cp.championship_id = p_championship_id
          and cp.profile_id = v_user_id
    ) then
        raise exception 'NOT_ENROLLED' using errcode = 'P0001';
    end if;

    return query
    with match_totals as (
        select
            entry.profile_id,
            count(*) filter (where entry.won)::integer as total_wins,
            count(*)::integer as total_matches
        from public.championship_match_entries entry
        where entry.championship_id = p_championship_id
        group by entry.profile_id
    ),
    ranked as (
        select
            row_number() over (
                order by
                    coalesce(match_totals.total_wins, 0) desc,
                    coalesce(match_totals.total_matches, 0) asc,
                    profile.nickname asc,
                    profile.id asc
            ) as rank_position,
            profile.id as profile_id,
            profile.nickname,
            profile.avatar_url,
            profile.avatar_photo_path,
            coalesce(match_totals.total_wins, 0) as total_wins,
            coalesce(match_totals.total_matches, 0) as total_matches
        from public.championship_participants cp
        join public.profiles profile on profile.id = cp.profile_id
        left join match_totals on match_totals.profile_id = cp.profile_id
        where cp.championship_id = p_championship_id
    )
    select
        ranked.rank_position,
        ranked.profile_id,
        ranked.nickname,
        ranked.avatar_url,
        ranked.avatar_photo_path,
        ranked.total_wins,
        ranked.total_matches
    from ranked
    order by ranked.rank_position
    limit v_limit;
end;
$$;

revoke all on function public.list_championship_standings(uuid, integer) from public;
grant execute on function public.list_championship_standings(uuid, integer) to authenticated;

create or replace function public.list_championship_matches(p_championship_id uuid, p_limit integer default 50)
returns table (
    match_result_id uuid,
    winner_team integer,
    winner_nickname text,
    scores jsonb,
    finished_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if not exists (
        select 1
        from public.championship_participants cp
        where cp.championship_id = p_championship_id
          and cp.profile_id = v_user_id
    ) then
        raise exception 'NOT_ENROLLED' using errcode = 'P0001';
    end if;

    return query
    select distinct
        result.id,
        result.winner_team,
        winner_profile.nickname,
        result.scores,
        result.finished_at
    from public.championship_match_entries entry
    join public.match_results result on result.id = entry.match_result_id
    left join public.profiles winner_profile on winner_profile.id = result.winner_profile_id
    where entry.championship_id = p_championship_id
    order by result.finished_at desc
    limit v_limit;
end;
$$;

revoke all on function public.list_championship_matches(uuid, integer) from public;
grant execute on function public.list_championship_matches(uuid, integer) to authenticated;

-- ─── list_my_championships ganha os campos novos ───────────────────────────
-- drop explicito: create or replace nao deixa mudar as colunas de saida.

drop function if exists public.list_my_championships();

create or replace function public.list_my_championships()
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text,
    is_host boolean,
    participant_count integer,
    cadence text,
    level text,
    starts_at timestamptz,
    ends_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    return query
    select
        c.id,
        c.code,
        c.name,
        c.game_type,
        c.status,
        c.host_id = v_user_id,
        (
            select count(*)::integer
            from public.championship_participants p2
            where p2.championship_id = c.id
        ),
        c.cadence,
        c.level,
        c.starts_at,
        c.ends_at
    from public.championships c
    join public.championship_participants cp on cp.championship_id = c.id
    where cp.profile_id = v_user_id
    order by c.created_at desc;
end;
$$;

revoke all on function public.list_my_championships() from public;
grant execute on function public.list_my_championships() to authenticated;

-- ─── complete_match: contabiliza automaticamente pra todo campeonato ativo ─
-- compativel, sem precisar de link manual. Corpo copiado verbatim da 0048
-- (conferido no arquivo antes de editar) + o bloco novo de atribuicao no
-- final, antes da limpeza do chat.

create or replace function public.complete_match(
    p_room_id uuid,
    p_result_key text,
    p_winner_team integer,
    p_scores jsonb,
    p_breakdown jsonb
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_existing public.match_results%rowtype;
    v_winner_profile_id uuid;
    v_player_count integer;
    v_start_event_id bigint;
    v_result_event public.match_events%rowtype;
    v_summary jsonb;
    v_round_end_event_type text;
    v_round_end_seat integer;
    v_round_winner_team integer;
    v_server_summary jsonb;
    v_match_result_id uuid;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(trim(coalesce(p_result_key, ''))) not between 1 and 100 then
        raise exception 'INVALID_RESULT_KEY' using errcode = 'P0001';
    end if;
    if p_winner_team not in (0, 1) then
        raise exception 'INVALID_WINNER_TEAM' using errcode = 'P0001';
    end if;
    if jsonb_typeof(coalesce(p_scores, '{}'::jsonb)) is distinct from 'object'
       or jsonb_typeof(p_scores -> 'teamScores') is distinct from 'array'
       or jsonb_array_length(p_scores -> 'teamScores') <> 2
       or exists (
           select 1
           from jsonb_array_elements(p_scores -> 'teamScores') score
           where jsonb_typeof(score) <> 'number'
       ) then
        raise exception 'INVALID_SCORES' using errcode = 'P0001';
    end if;
    if jsonb_typeof(coalesce(p_breakdown, '{}'::jsonb)) is distinct from 'object'
       or octet_length(coalesce(p_breakdown, '{}'::jsonb)::text) > 65536 then
        raise exception 'INVALID_BREAKDOWN' using errcode = 'P0001';
    end if;

    select room.*
    into v_room
    from public.match_rooms room
    where room.id = p_room_id
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_room.host_id <> v_user_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;
    if v_room.status not in ('playing', 'finished') then
        raise exception 'MATCH_NOT_STARTED' using errcode = 'P0001';
    end if;

    select count(*)
    into v_player_count
    from public.room_players player
    where player.room_id = p_room_id;

    if v_player_count <> v_room.max_players then
        raise exception 'INCOMPLETE_ROOM' using errcode = 'P0001';
    end if;
    if not exists (
        select 1
        from public.room_players player
        where player.room_id = p_room_id
          and player.team = p_winner_team
    ) then
        raise exception 'WINNER_TEAM_NOT_IN_ROOM' using errcode = 'P0001';
    end if;

    select event.*
    into v_result_event
    from public.match_events event
    where event.room_id = p_room_id
      and event.message_id = trim(p_result_key)
      and event.event_type = 'ROUND_SUMMARY'
      and event.actor_id = v_user_id;

    if not found then
        raise exception 'RESULT_EVENT_REQUIRED' using errcode = 'P0001';
    end if;

    begin
        v_summary := (v_result_event.payload ->> 'payload')::jsonb;
        if jsonb_typeof(v_summary) is distinct from 'object'
           or v_summary -> 'isMatchOver' is distinct from 'true'::jsonb
           or (v_summary ->> 'winnerTeam')::integer <> p_winner_team
           or v_summary -> 'teamScores' is distinct from p_scores -> 'teamScores'
           or coalesce(v_summary ->> 'breakdown', '')
              <> coalesce(p_breakdown ->> 'text', '') then
            raise exception 'RESULT_EVENT_MISMATCH' using errcode = 'P0001';
        end if;
    exception
        when others then
            raise exception 'INVALID_RESULT_EVENT' using errcode = 'P0001';
    end;

    select max(event.id)
    into v_start_event_id
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'GAME_START'
      and event.id < v_result_event.id;

    if v_start_event_id is null then
        raise exception 'GAME_START_REQUIRED' using errcode = 'P0001';
    end if;

    select event.event_type, event.seat
    into v_round_end_event_type, v_round_end_seat
    from public.match_events event
    where event.room_id = p_room_id
      and event.id > v_start_event_id
      and event.id < v_result_event.id
      and event.event_type in ('WIN_ROUND', 'COUNT_ROUND')
    order by event.id desc
    limit 1;

    if not found then
        raise exception 'ROUND_END_EVENT_REQUIRED' using errcode = 'P0001';
    end if;

    if v_round_end_event_type = 'WIN_ROUND' then
        select player.team
        into v_round_winner_team
        from public.room_players player
        where player.room_id = p_room_id and player.seat = v_round_end_seat;

        if v_room.game_type = 'CACHETA'
           and v_round_winner_team is not null
           and v_round_winner_team <> p_winner_team then
            raise exception 'WINNER_TEAM_MISMATCH' using errcode = 'P0001';
        end if;
    end if;

    select case
        when count(*) = 1 then (array_agg(player.profile_id order by player.seat))[1]
        else null
    end
    into v_winner_profile_id
    from public.room_players player
    where player.room_id = p_room_id
      and player.team = p_winner_team;

    select result.*
    into v_existing
    from public.match_results result
    where result.room_id = p_room_id
      and result.start_event_id = v_start_event_id;

    if found then
        if v_existing.result_key = trim(p_result_key)
           and v_existing.winner_team = p_winner_team
           and v_existing.scores = coalesce(p_scores, '{}'::jsonb)
           and v_existing.breakdown = coalesce(p_breakdown, '{}'::jsonb) then
            return true;
        end if;
        raise exception 'MATCH_RESULT_CONFLICT' using errcode = 'P0001';
    end if;

    begin
        v_server_summary := private.cbr_compute_round_summary(
            p_room_id,
            coalesce(v_round_winner_team, p_winner_team),
            v_round_end_event_type = 'COUNT_ROUND'
        );
    exception
        when others then
            v_server_summary := null;
    end;

    insert into public.match_results(
        room_id,
        result_key,
        start_event_id,
        winner_team,
        winner_profile_id,
        scores,
        breakdown,
        server_round_scores,
        server_round_breakdown
    ) values (
        p_room_id,
        trim(p_result_key),
        v_start_event_id,
        p_winner_team,
        v_winner_profile_id,
        coalesce(p_scores, '{}'::jsonb),
        coalesce(p_breakdown, '{}'::jsonb),
        v_server_summary -> 'teamRoundScores',
        v_server_summary ->> 'breakdown'
    )
    returning id into v_match_result_id;

    update public.player_stats stats
    set total_matches = stats.total_matches + 1,
        total_wins = stats.total_wins + case when player.team = p_winner_team then 1 else 0 end,
        cacheta_wins = stats.cacheta_wins + case
            when player.team = p_winner_team and v_room.game_type = 'CACHETA' then 1 else 0 end,
        buraco_wins = stats.buraco_wins + case
            when player.team = p_winner_team and v_room.game_type = 'BURACO' then 1 else 0 end,
        tranca_wins = stats.tranca_wins + case
            when player.team = p_winner_team and v_room.game_type = 'TRANCA' then 1 else 0 end,
        best_streak = greatest(
            stats.best_streak,
            case when player.team = p_winner_team then stats.current_streak + 1 else stats.best_streak end
        ),
        current_streak = case
            when player.team = p_winner_team then stats.current_streak + 1 else 0 end,
        xp = stats.xp + case when player.team = p_winner_team then 100 else 25 end,
        updated_at = now()
    from public.room_players player
    where player.room_id = p_room_id
      and player.profile_id = stats.profile_id;

    -- Toda partida completada conta pra todo campeonato ativo e compativel
    -- (mesmo jogo, dentro da janela de datas) que o jogador ja esteja
    -- inscrito -- nao precisa mais vincular a sala antes de comecar.
    insert into public.championship_match_entries (championship_id, match_result_id, profile_id, won)
    select
        champ.id,
        v_match_result_id,
        player.profile_id,
        player.team = p_winner_team
    from public.championships champ
    join public.championship_participants cp on cp.championship_id = champ.id
    join public.room_players player
        on player.profile_id = cp.profile_id
        and player.room_id = p_room_id
    where champ.status = 'ACTIVE'
      and champ.game_type = v_room.game_type
      and now() >= champ.starts_at
      and (champ.ends_at is null or now() <= champ.ends_at)
    on conflict (championship_id, match_result_id, profile_id) do nothing;

    update public.match_rooms room
    set updated_at = now()
    where room.id = p_room_id;

    delete from public.room_chat_messages where room_id = p_room_id;

    return true;
end;
$$;

revoke all on function public.complete_match(uuid, text, integer, jsonb, jsonb) from public;
grant execute on function public.complete_match(uuid, text, integer, jsonb, jsonb) to authenticated;
