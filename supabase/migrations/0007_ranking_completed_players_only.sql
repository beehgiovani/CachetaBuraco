-- Abrir a tela cria o perfil online, mas isso nao deve colocar um jogador sem
-- partida no ranking. A posicao passa a considerar apenas partidas concluidas.

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
        where stats.total_matches > 0
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
