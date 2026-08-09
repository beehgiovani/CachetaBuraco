-- Campeonatos (Fase 6, retomada 2026-08-09): campeonato simples por pontos,
-- inscricao por codigo (mesmo padrao de sala privada -- nada de lista publica
-- de campeonatos, so quem tem o codigo entra), tabela de classificacao e
-- historico de partidas. Cada partida "conta" pro campeonato quando o host
-- vincula a sala antes de comecar (link_room_to_championship) -- nao mexe em
-- create_match_room de proposito, pra nao arriscar a assinatura dessa RPC
-- critica pela terceira vez (0001, 0031 ja mexeram nela).

create table if not exists public.championships (
    id uuid primary key default gen_random_uuid(),
    code text not null unique,
    name text not null check (char_length(name) between 2 and 40),
    game_type text not null check (game_type in ('CACHETA', 'BURACO', 'TRANCA')),
    host_id uuid not null references public.profiles(id) on delete cascade,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'FINISHED')),
    created_at timestamptz not null default now(),
    finished_at timestamptz
);

create table if not exists public.championship_participants (
    championship_id uuid not null references public.championships(id) on delete cascade,
    profile_id uuid not null references public.profiles(id) on delete cascade,
    joined_at timestamptz not null default now(),
    primary key (championship_id, profile_id)
);

create index if not exists idx_championship_participants_profile
on public.championship_participants(profile_id);

-- Uma policy que consulta a propria tabela dentro do USING dispara "infinite
-- recursion detected in policy" (Postgres reaplica a mesma RLS pra avaliar a
-- subquery). O helper security definer abaixo e o mesmo truque ja usado em
-- private.is_room_member (migration 0002) pra checar pertencimento em
-- room_players a partir da propria policy de room_players sem recursao --
-- a funcao roda fora da RLS de quem chamou, entao a consulta interna nao
-- reaciona a mesma policy.
create or replace function private.is_championship_participant(p_championship_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.championship_participants cp
        where cp.championship_id = p_championship_id
          and cp.profile_id = (select auth.uid())
    );
$$;

revoke all on function private.is_championship_participant(uuid) from public;
grant execute on function private.is_championship_participant(uuid) to authenticated;

-- RLS das duas tabelas so depois de ambas existirem -- a policy de
-- championships referencia championship_participants (via helper acima).
alter table public.championships enable row level security;
alter table public.championship_participants enable row level security;

-- Igual sala privada: nao e descoberta publica, so quem tem o codigo (pra
-- entrar) ou ja esta inscrito/e o host (pra ver) enxerga a linha.
create policy "championships_select_participant_or_host"
on public.championships for select
to authenticated
using (
    host_id = (select auth.uid())
    or (select private.is_championship_participant(id))
);

grant select on public.championships to authenticated;

-- Quem ja esta inscrito consegue ver os outros inscritos do mesmo campeonato
-- (senao a classificacao no app nao teria como mostrar quem esta jogando).
create policy "championship_participants_select_member"
on public.championship_participants for select
to authenticated
using (
    (select private.is_championship_participant(championship_id))
);

grant select on public.championship_participants to authenticated;

alter table public.match_rooms
add column if not exists championship_id uuid references public.championships(id) on delete set null;

alter table public.match_results
add column if not exists championship_id uuid references public.championships(id) on delete set null;

create index if not exists idx_match_results_championship
on public.match_results(championship_id);

create or replace function public.create_championship(p_name text, p_game_type text)
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_name text := trim(coalesce(p_name, ''));
    v_code text;
    v_championship public.championships%rowtype;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(v_name) not between 2 and 40 then
        raise exception 'INVALID_CHAMPIONSHIP_NAME' using errcode = 'P0001';
    end if;
    if p_game_type not in ('CACHETA', 'BURACO', 'TRANCA') then
        raise exception 'INVALID_GAME_TYPE' using errcode = 'P0001';
    end if;

    loop
        v_code := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
        exit when not exists (select 1 from public.championships where championships.code = v_code);
    end loop;

    insert into public.championships (code, name, game_type, host_id)
    values (v_code, v_name, p_game_type, v_user_id)
    returning * into v_championship;

    insert into public.championship_participants (championship_id, profile_id)
    values (v_championship.id, v_user_id);

    return query
    select v_championship.id, v_championship.code, v_championship.name, v_championship.game_type, v_championship.status;
end;
$$;

revoke all on function public.create_championship(text, text) from public;
grant execute on function public.create_championship(text, text) to authenticated;

create or replace function public.join_championship(p_code text)
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_championship public.championships%rowtype;
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
    select v_championship.id, v_championship.code, v_championship.name, v_championship.game_type, v_championship.status;
end;
$$;

revoke all on function public.join_championship(text) from public;
grant execute on function public.join_championship(text) to authenticated;

create or replace function public.link_room_to_championship(p_room_id uuid, p_championship_code text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_championship public.championships%rowtype;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select *
    into v_room
    from public.match_rooms
    where id = p_room_id
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_room.host_id <> v_user_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;
    if v_room.status <> 'waiting' then
        raise exception 'ROOM_ALREADY_STARTED' using errcode = 'P0001';
    end if;

    select *
    into v_championship
    from public.championships
    where championships.code = upper(trim(coalesce(p_championship_code, '')));

    if not found then
        raise exception 'CHAMPIONSHIP_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_championship.status <> 'ACTIVE' then
        raise exception 'CHAMPIONSHIP_FINISHED' using errcode = 'P0001';
    end if;
    if v_championship.game_type <> v_room.game_type then
        raise exception 'CHAMPIONSHIP_GAME_TYPE_MISMATCH' using errcode = 'P0001';
    end if;
    if not exists (
        select 1
        from public.championship_participants
        where championship_id = v_championship.id
          and profile_id = v_user_id
    ) then
        raise exception 'NOT_ENROLLED' using errcode = 'P0001';
    end if;

    update public.match_rooms
    set championship_id = v_championship.id
    where id = p_room_id;
end;
$$;

revoke all on function public.link_room_to_championship(uuid, text) from public;
grant execute on function public.link_room_to_championship(uuid, text) to authenticated;

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
    -- Qualificado com o nome da tabela de proposito: profile_id tambem e
    -- nome de coluna do RETURNS TABLE desta funcao, e vira variavel PL/pgSQL
    -- implicita -- uma referencia solta ficaria ambigua (achado real, testado
    -- localmente).
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
            snapshot.profile_id,
            count(*) filter (where snapshot.won)::integer as total_wins,
            count(*)::integer as total_matches
        from public.match_result_players snapshot
        join public.match_results result on result.id = snapshot.match_result_id
        where result.championship_id = p_championship_id
        group by snapshot.profile_id
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
    select
        result.id,
        result.winner_team,
        winner_profile.nickname,
        result.scores,
        result.finished_at
    from public.match_results result
    left join public.profiles winner_profile on winner_profile.id = result.winner_profile_id
    where result.championship_id = p_championship_id
    order by result.finished_at desc
    limit v_limit;
end;
$$;

revoke all on function public.list_championship_matches(uuid, integer) from public;
grant execute on function public.list_championship_matches(uuid, integer) to authenticated;

create or replace function public.finish_championship(p_championship_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    update public.championships
    set status = 'FINISHED', finished_at = now()
    where id = p_championship_id
      and host_id = v_user_id
      and status = 'ACTIVE';

    if not found then
        raise exception 'CHAMPIONSHIP_NOT_FOUND_OR_NOT_HOST' using errcode = 'P0001';
    end if;
end;
$$;

revoke all on function public.finish_championship(uuid) from public;
grant execute on function public.finish_championship(uuid) to authenticated;

create or replace function public.list_my_championships()
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text,
    is_host boolean,
    participant_count integer
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
        )
    from public.championships c
    join public.championship_participants cp on cp.championship_id = c.id
    where cp.profile_id = v_user_id
    order by c.created_at desc;
end;
$$;

revoke all on function public.list_my_championships() from public;
grant execute on function public.list_my_championships() to authenticated;

-- complete_match ganha so uma coluna a mais no insert (championship_id, vindo
-- da sala) -- corpo copiado verbatim do atual (0032_room_chat.sql, conferido
-- direto no arquivo antes de editar) pra nao repetir o erro ja cometido uma
-- vez nesta mesma funcao de reconstruir de memoria.
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

    select case
        when count(*) = 1 then (array_agg(player.profile_id order by player.seat))[1]
        else null
    end
    into v_winner_profile_id
    from public.room_players player
    where player.room_id = p_room_id
      and player.team = p_winner_team;

    insert into public.match_results(
        room_id,
        result_key,
        start_event_id,
        winner_team,
        winner_profile_id,
        scores,
        breakdown,
        championship_id
    ) values (
        p_room_id,
        trim(p_result_key),
        v_start_event_id,
        p_winner_team,
        v_winner_profile_id,
        coalesce(p_scores, '{}'::jsonb),
        coalesce(p_breakdown, '{}'::jsonb),
        v_room.championship_id
    );

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

    update public.match_rooms room
    set updated_at = now()
    where room.id = p_room_id;

    delete from public.room_chat_messages where room_id = p_room_id;

    return true;
end;
$$;
