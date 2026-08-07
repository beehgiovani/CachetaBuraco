-- O ranking por periodo precisa lembrar quem realmente participou de cada
-- partida. A ocupacao da sala pode mudar depois de um novo jogo, por isso eu
-- congelo os participantes junto com o resultado confirmado pelo host.

create table if not exists public.match_result_players (
    match_result_id uuid not null references public.match_results(id) on delete cascade,
    profile_id uuid not null references public.profiles(id) on delete cascade,
    team integer not null check (team between 0 and 1),
    game_type text not null check (game_type in ('CACHETA', 'BURACO', 'TRANCA')),
    won boolean not null,
    primary key (match_result_id, profile_id)
);

alter table public.match_result_players enable row level security;

revoke all on table public.match_result_players from anon, authenticated;

create index if not exists idx_match_result_players_profile
on public.match_result_players(profile_id, match_result_id);

create or replace function private.snapshot_match_result_players()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.match_result_players(
        match_result_id,
        profile_id,
        team,
        game_type,
        won
    )
    select
        new.id,
        player.profile_id,
        player.team,
        room.game_type,
        player.team = new.winner_team
    from public.match_rooms room
    join public.room_players player on player.room_id = room.id
    where room.id = new.room_id
    on conflict (match_result_id, profile_id) do nothing;

    return new;
end;
$$;

drop trigger if exists match_results_snapshot_players on public.match_results;
create trigger match_results_snapshot_players
after insert on public.match_results
for each row execute function private.snapshot_match_result_players();

-- Os resultados antigos nao tinham snapshot. Este preenchimento usa a ultima
-- composicao conhecida da sala; resultados novos sempre passam pelo trigger.
insert into public.match_result_players(
    match_result_id,
    profile_id,
    team,
    game_type,
    won
)
select
    result.id,
    player.profile_id,
    player.team,
    room.game_type,
    player.team = result.winner_team
from public.match_results result
join public.match_rooms room on room.id = result.room_id
join public.room_players player on player.room_id = result.room_id
on conflict (match_result_id, profile_id) do nothing;

create or replace function public.list_period_ranking(
    p_period text,
    p_limit integer default 50
)
returns table (
    rank_position bigint,
    profile_id uuid,
    nickname text,
    avatar_url text,
    total_wins integer,
    total_matches integer,
    cacheta_wins integer,
    buraco_wins integer,
    tranca_wins integer,
    best_streak integer,
    current_streak integer,
    xp integer,
    last_match_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_period text := upper(trim(coalesce(p_period, '')));
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
    v_start timestamptz;
begin
    if (select auth.uid()) is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    v_start := case v_period
        when 'WEEKLY' then
            date_trunc('week', timezone('America/Sao_Paulo', now()))
                at time zone 'America/Sao_Paulo'
        when 'MONTHLY' then
            date_trunc('month', timezone('America/Sao_Paulo', now()))
                at time zone 'America/Sao_Paulo'
        else null
    end;

    if v_start is null then
        raise exception 'INVALID_RANKING_PERIOD' using errcode = 'P0001';
    end if;

    return query
    with period_matches as (
        select
            snapshot.profile_id,
            snapshot.won,
            snapshot.game_type,
            result.id as match_result_id,
            result.finished_at
        from public.match_result_players snapshot
        join public.match_results result on result.id = snapshot.match_result_id
        where result.finished_at >= v_start
    ),
    ordered_matches as (
        select
            period_matches.*,
            sum(case when period_matches.won then 0 else 1 end) over (
                partition by period_matches.profile_id
                order by period_matches.finished_at, period_matches.match_result_id
                rows between unbounded preceding and current row
            ) as loss_group
        from period_matches
    ),
    period_totals as (
        select
            ordered_matches.profile_id,
            count(*) filter (where ordered_matches.won)::integer as total_wins,
            count(*)::integer as total_matches,
            count(*) filter (
                where ordered_matches.won and ordered_matches.game_type = 'CACHETA'
            )::integer as cacheta_wins,
            count(*) filter (
                where ordered_matches.won and ordered_matches.game_type = 'BURACO'
            )::integer as buraco_wins,
            count(*) filter (
                where ordered_matches.won and ordered_matches.game_type = 'TRANCA'
            )::integer as tranca_wins,
            sum(case when ordered_matches.won then 100 else 25 end)::integer as xp,
            max(ordered_matches.finished_at) as last_match_at
        from ordered_matches
        group by ordered_matches.profile_id
    ),
    streak_runs as (
        select
            ordered_matches.profile_id,
            ordered_matches.loss_group,
            count(*) filter (where ordered_matches.won)::integer as streak_size
        from ordered_matches
        group by ordered_matches.profile_id, ordered_matches.loss_group
    ),
    best_streaks as (
        select
            streak_runs.profile_id,
            max(streak_runs.streak_size)::integer as best_streak
        from streak_runs
        group by streak_runs.profile_id
    ),
    current_streaks as (
        select distinct on (streak_runs.profile_id)
            streak_runs.profile_id,
            streak_runs.streak_size as current_streak
        from streak_runs
        order by streak_runs.profile_id, streak_runs.loss_group desc
    ),
    ranked as (
        select
            row_number() over (
                order by
                    period_totals.total_wins desc,
                    period_totals.xp desc,
                    period_totals.total_matches asc,
                    profile.nickname asc,
                    profile.id asc
            ) as rank_position,
            profile.id as profile_id,
            profile.nickname,
            profile.avatar_url,
            period_totals.total_wins,
            period_totals.total_matches,
            period_totals.cacheta_wins,
            period_totals.buraco_wins,
            period_totals.tranca_wins,
            coalesce(best_streaks.best_streak, 0) as best_streak,
            coalesce(current_streaks.current_streak, 0) as current_streak,
            period_totals.xp,
            period_totals.last_match_at
        from period_totals
        join public.profiles profile on profile.id = period_totals.profile_id
        left join best_streaks on best_streaks.profile_id = period_totals.profile_id
        left join current_streaks on current_streaks.profile_id = period_totals.profile_id
    )
    select
        ranked.rank_position,
        ranked.profile_id,
        ranked.nickname,
        ranked.avatar_url,
        ranked.total_wins,
        ranked.total_matches,
        ranked.cacheta_wins,
        ranked.buraco_wins,
        ranked.tranca_wins,
        ranked.best_streak,
        ranked.current_streak,
        ranked.xp,
        ranked.last_match_at
    from ranked
    order by ranked.rank_position
    limit v_limit;
end;
$$;

revoke all on function public.list_period_ranking(text, integer) from public;
grant execute on function public.list_period_ranking(text, integer) to authenticated;
