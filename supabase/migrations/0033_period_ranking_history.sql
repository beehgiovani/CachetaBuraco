-- Temporadas semanais/mensais (pedido do usuario): poder ver quem foi campeao
-- da semana/mes passado, nao so o periodo atual. Nao precisa de tabela nova,
-- cron ou "reset" de verdade -- match_results/match_result_players ja sao
-- permanentes, entao "temporada passada" e so rodar a mesma agregacao com uma
-- janela de datas fechada no passado em vez da janela aberta "desde o inicio
-- do periodo atual ate agora". Mudanca baseada no corpo real e atual de
-- list_period_ranking (0030_ranking_avatar_photo.sql), copiado verbatim, com
-- so os acrescimos de p_offset/period_start/period_end.

drop function if exists public.list_period_ranking(text, integer);

create function public.list_period_ranking(
    p_period text,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    rank_position bigint,
    profile_id uuid,
    nickname text,
    avatar_url text,
    avatar_photo_path text,
    total_wins integer,
    total_matches integer,
    cacheta_wins integer,
    buraco_wins integer,
    tranca_wins integer,
    best_streak integer,
    current_streak integer,
    xp integer,
    last_match_at timestamptz,
    period_start timestamptz,
    period_end timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_period text := upper(trim(coalesce(p_period, '')));
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
    v_offset integer := coalesce(p_offset, 0);
    v_start timestamptz;
    v_end timestamptz;
begin
    if (select auth.uid()) is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    -- 0 = periodo atual (em andamento), negativo = periodos anteriores.
    -- Positivo nao existe (periodo futuro nao tem partida nenhuma).
    if v_offset > 0 then
        raise exception 'INVALID_RANKING_PERIOD_OFFSET' using errcode = 'P0001';
    end if;

    v_start := case v_period
        when 'WEEKLY' then
            date_trunc('week', timezone('America/Sao_Paulo', now()))
                at time zone 'America/Sao_Paulo'
                + (v_offset * interval '1 week')
        when 'MONTHLY' then
            date_trunc('month', timezone('America/Sao_Paulo', now()))
                at time zone 'America/Sao_Paulo'
                + (v_offset * interval '1 month')
        else null
    end;

    if v_start is null then
        raise exception 'INVALID_RANKING_PERIOD' using errcode = 'P0001';
    end if;

    v_end := case v_period
        when 'WEEKLY' then v_start + interval '1 week'
        when 'MONTHLY' then v_start + interval '1 month'
    end;

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
          and result.finished_at < v_end
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
            profile.avatar_photo_path,
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
    -- left join a partir de uma ancora de 1 linha: garante que period_start/
    -- period_end sempre voltem pro cliente, mesmo quando o periodo nao teve
    -- nenhuma partida (ranked vazio) -- sem isso a UI nao teria como mostrar
    -- "semana de 28/07 a 03/08: nenhuma partida" pra um periodo fechado.
    select
        ranked.rank_position,
        ranked.profile_id,
        ranked.nickname,
        ranked.avatar_url,
        ranked.avatar_photo_path,
        ranked.total_wins,
        ranked.total_matches,
        ranked.cacheta_wins,
        ranked.buraco_wins,
        ranked.tranca_wins,
        ranked.best_streak,
        ranked.current_streak,
        ranked.xp,
        ranked.last_match_at,
        v_start,
        v_end
    from (select 1) as anchor
    left join ranked on true
    order by ranked.rank_position nulls last
    limit v_limit;
end;
$$;

revoke all on function public.list_period_ranking(text, integer, integer) from public;
grant execute on function public.list_period_ranking(text, integer, integer) to authenticated;
