-- O ranking global e calculado no banco para todos os aparelhos enxergarem a
-- mesma ordem. A data da ultima partida vem do resultado confirmado pelo host.

alter table public.player_stats
add column if not exists last_match_at timestamptz;

update public.player_stats stats
set last_match_at = history.last_match_at
from (
    select player.profile_id, max(result.finished_at) as last_match_at
    from public.match_results result
    join public.room_players player on player.room_id = result.room_id
    group by player.profile_id
) history
where stats.profile_id = history.profile_id
  and stats.last_match_at is null;

create or replace function private.mark_match_players_activity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.player_stats stats
    set last_match_at = new.finished_at,
        updated_at = greatest(stats.updated_at, new.finished_at)
    where stats.profile_id in (
        select player.profile_id
        from public.room_players player
        where player.room_id = new.room_id
    );
    return new;
end;
$$;

drop trigger if exists match_results_mark_player_activity on public.match_results;
create trigger match_results_mark_player_activity
after insert on public.match_results
for each row execute function private.mark_match_players_activity();

create or replace function public.list_global_ranking(p_limit integer default 50)
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
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
begin
    if (select auth.uid()) is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    return query
    with ranked as (
        select
            row_number() over (
                order by
                    stats.total_wins desc,
                    stats.xp desc,
                    stats.total_matches asc,
                    profile.nickname asc,
                    profile.id asc
            ) as rank_position,
            profile.id as profile_id,
            profile.nickname,
            profile.avatar_url,
            stats.total_wins,
            stats.total_matches,
            stats.cacheta_wins,
            stats.buraco_wins,
            stats.tranca_wins,
            stats.best_streak,
            stats.current_streak,
            stats.xp,
            stats.last_match_at
        from public.player_stats stats
        join public.profiles profile on profile.id = stats.profile_id
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

revoke all on function public.list_global_ranking(integer) from public;
grant execute on function public.list_global_ranking(integer) to authenticated;
