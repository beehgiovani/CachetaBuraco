-- Carteado BR - fechamento idempotente da partida e atualizacao do ranking.
-- O aplicativo nao escreve estatistica diretamente: somente o host da sala
-- chama esta funcao, e toda a atualizacao acontece na mesma transacao.

alter table public.match_results
add column if not exists result_key text;

update public.match_results
set result_key = id::text
where result_key is null;

alter table public.match_results
alter column result_key set not null;

create unique index if not exists uq_match_results_room
on public.match_results(room_id);

create unique index if not exists uq_match_results_key
on public.match_results(room_id, result_key);

create index if not exists idx_match_results_finished
on public.match_results(finished_at desc);

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

    select result.*
    into v_existing
    from public.match_results result
    where result.room_id = p_room_id;

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
        winner_team,
        winner_profile_id,
        scores,
        breakdown
    ) values (
        p_room_id,
        trim(p_result_key),
        p_winner_team,
        v_winner_profile_id,
        coalesce(p_scores, '{}'::jsonb),
        coalesce(p_breakdown, '{}'::jsonb)
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
    set status = 'finished',
        updated_at = now()
    where room.id = p_room_id;

    return true;
end;
$$;

revoke all on function public.complete_match(uuid, text, integer, jsonb, jsonb) from public;
grant execute on function public.complete_match(uuid, text, integer, jsonb, jsonb) to authenticated;
