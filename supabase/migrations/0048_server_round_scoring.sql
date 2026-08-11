-- Fecho o ultimo item do roadmap online: o resultado final ainda dependia
-- inteiramente do que o host calculava e mandava pro complete_match (p_scores/
-- p_breakdown so eram conferidos por consistencia interna contra o proprio
-- ROUND_SUMMARY que o host mesmo postou, nunca contra o estado real da mesa).
-- Um host malicioso podia postar um ROUND_SUMMARY fabricado com isMatchOver=true
-- e vencer a partida sem nunca ter jogado de verdade.
--
-- Porto aqui a mesma conta que o GameRulesEngine/MatchViewModel fazem em
-- Kotlin (calculateBuracoTrancaScore + a perda de vidas da Cacheta) para o
-- servidor recalcular a rodada usando so o ledger privado (private.match_seat_state/
-- match_team_state), que ja e autoritativo pros dois lados desde a 0045-0047.
--
-- Endureço o complete_match em duas frentes seguras (sem exigir recalculo
-- numerico perfeito, que eu ainda nao testei em partida real com celular):
--   1. Exige que exista um WIN_ROUND ou COUNT_ROUND de verdade (ja validado
--      pelos gatilhos da 0016/0045 no momento em que foi inserido) entre o
--      GAME_START da rodada e o ROUND_SUMMARY -- fecha o golpe de fabricar um
--      resultado sem ter jogado a rodada.
--   2. Na Cacheta, onde "quem bateu ganha a partida" e uma regra inequivoca
--      (o time que fica com 0 vidas e sempre o adversario de quem bateu por
--      ultimo), exijo que p_winner_team bata com o time do WIN_ROUND. No
--      Buraco/Tranca isso não é garantido (o time que bate nem sempre é quem
--      cruza o limite de pontos primeiro, depende do acumulado das rodadas
--      anteriores que o servidor ainda não rastreia) -- fica como lacuna
--      documentada, não bloqueio.
-- A pontuação canônica calculada aqui é gravada em match_results só como
-- auditoria (server_round_scores/server_round_breakdown), sem sobrescrever o
-- que o host mostra na tela nem travar o fechamento se divergir -- prefiro
-- não arriscar quebrar uma partida legítima por causa de um bug meu no port
-- que eu ainda não vi rodar em campo.

-- ─── Valor em pontos de uma carta (espelha GameRulesEngine.getCardPointsValue) ──

create or replace function private.cbr_card_points_value(
    p_card text,
    p_game_type text,
    p_uniform boolean,
    p_penalize_black_threes boolean
)
returns integer
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_rank text := private.cbr_card_rank(p_card);
    v_suit text := private.cbr_card_suit(p_card);
begin
    if private.cbr_is_joker(p_card) then
        return case when coalesce(p_uniform, false) then 10 else 20 end;
    end if;

    if p_game_type = 'TRANCA' then
        if v_rank = 'THREE' and v_suit in ('SPADES', 'CLUBS') then
            if coalesce(p_penalize_black_threes, true) then
                return 100;
            elsif coalesce(p_uniform, false) then
                return 10;
            else
                return 5;
            end if;
        end if;
        if v_rank = 'THREE' and v_suit in ('HEARTS', 'DIAMONDS') then
            return 100;
        end if;
        if coalesce(p_uniform, false) then
            return 10;
        end if;
    end if;

    if coalesce(p_uniform, false) then
        return 10;
    end if;
    if v_rank = 'TWO' and p_game_type = 'BURACO' then
        return 10;
    end if;

    return case v_rank
        when 'ACE' then 15
        when 'EIGHT' then 10
        when 'NINE' then 10
        when 'TEN' then 10
        when 'JACK' then 10
        when 'QUEEN' then 10
        when 'KING' then 10
        else 5
    end;
end;
$$;

revoke all on function private.cbr_card_points_value(text, text, boolean, boolean)
from public, anon, authenticated;

-- ─── Placar canonico da rodada a partir do ledger privado ──────────────────
-- Reaproveito private.match_team_state/match_seat_state, que ainda guardam o
-- estado da ultima rodada jogada nesse ponto: a limpeza so acontece depois,
-- no GAME_START da proxima rodada (0017) ou logo apos o complete_match
-- inserir em match_results (private.clear_completed_match_private_state).

create or replace function private.cbr_compute_round_summary(
    p_room_id uuid,
    p_winner_team integer,
    p_count_only boolean
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room public.match_rooms%rowtype;
    v_uniform boolean;
    v_penalize_black boolean;
    v_allow_wildcards boolean;
    v_allow_charutos boolean;
    v_team_scores integer[] := array[0, 0];
    v_lines text[] := '{}';
    v_team integer;
    v_melds jsonb;
    v_meld jsonb;
    v_meld_check jsonb;
    v_card text;
    v_table_points integer;
    v_canastra_points integer;
    v_red_three_count integer;
    v_red_three_points integer;
    v_has_canastra boolean;
    v_hand_penalty integer;
    v_picked_morto boolean;
    v_winner_card_count integer;
    v_lost_lives integer;
begin
    select room.* into v_room from public.match_rooms room where room.id = p_room_id;
    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    v_uniform := coalesce((v_room.config ->> 'uniformCardPoints')::boolean, false);
    v_penalize_black := coalesce((v_room.config ->> 'penalizeBlackThreesInHand')::boolean, true);
    v_allow_wildcards := coalesce((v_room.config ->> 'allowWildcards')::boolean, true);
    v_allow_charutos := coalesce((v_room.config ->> 'allowCharutos')::boolean, true);

    if v_room.game_type = 'CACHETA' then
        select coalesce(sum(jsonb_array_length(meld.value)), 0)
        into v_winner_card_count
        from private.match_team_state state,
             jsonb_array_elements(coalesce(state.melds, '[]'::jsonb)) meld(value)
        where state.room_id = p_room_id and state.team = p_winner_team;

        v_lost_lives := case when v_winner_card_count >= 10 then 2 else 1 end;
        v_team_scores[(1 - p_winner_team) + 1] := -v_lost_lives;
        v_lines := v_lines || (
            'Equipe [TEAM_' || (1 - p_winner_team) || '] perdeu ' || v_lost_lives || ' vida(s).'
        );

        return jsonb_build_object(
            'teamRoundScores', jsonb_build_array(v_team_scores[1], v_team_scores[2]),
            'breakdown', array_to_string(v_lines, E'\n')
        );
    end if;

    for v_team in 0..1 loop
        select coalesce(state.melds, '[]'::jsonb) into v_melds
        from private.match_team_state state
        where state.room_id = p_room_id and state.team = v_team;
        v_melds := coalesce(v_melds, '[]'::jsonb);

        select exists(
            select 1 from jsonb_array_elements(v_melds) item(value)
            where jsonb_array_length(item.value) >= 7
        ) into v_has_canastra;

        v_table_points := 0;
        v_canastra_points := 0;
        v_red_three_count := 0;

        for v_meld in select item.value from jsonb_array_elements(v_melds) as item(value) loop
            if jsonb_array_length(v_meld) >= 7 then
                v_meld_check := private.cbr_validate_meld(
                    v_meld, v_room.game_type, v_allow_wildcards, v_allow_charutos, null
                );
                if v_meld_check ->> 'meldType' = 'CANASTRA_LIMPA' then
                    v_canastra_points := v_canastra_points + 200;
                else
                    v_canastra_points := v_canastra_points + 100;
                end if;
            end if;

            for v_card in select jsonb_array_elements_text(v_meld) loop
                if v_room.game_type = 'TRANCA'
                   and private.cbr_card_rank(v_card) = 'THREE'
                   and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS') then
                    v_red_three_count := v_red_three_count + 1;
                else
                    v_table_points := v_table_points + private.cbr_card_points_value(
                        v_card, v_room.game_type, v_uniform, v_penalize_black
                    );
                end if;
            end loop;
        end loop;

        if v_room.game_type = 'TRANCA' and v_red_three_count > 0 then
            v_red_three_points := case when v_red_three_count = 4 then 800 else v_red_three_count * 100 end;
            v_table_points := v_table_points + (
                case when v_has_canastra then v_red_three_points else -v_red_three_points end
            );
        end if;

        select coalesce(sum(
            private.cbr_card_points_value(card.value, v_room.game_type, v_uniform, v_penalize_black)
        ), 0)
        into v_hand_penalty
        from private.match_seat_state state
        join public.room_players player
            on player.room_id = state.room_id and player.seat = state.seat
        cross join lateral jsonb_array_elements_text(coalesce(state.hand, '[]'::jsonb)) card(value)
        where state.room_id = p_room_id and player.team = v_team;

        v_team_scores[v_team + 1] := v_table_points + v_canastra_points - v_hand_penalty;
        v_lines := v_lines || (
            'Equipe [TEAM_' || v_team || ']: mesa = ' || (v_table_points + v_canastra_points)
            || ' pts, mao = -' || v_hand_penalty || ' pts'
        );

        select coalesce(state.picked_morto, false) into v_picked_morto
        from private.match_team_state state
        where state.room_id = p_room_id and state.team = v_team;
        if not coalesce(v_picked_morto, false) then
            v_team_scores[v_team + 1] := v_team_scores[v_team + 1] - 100;
            v_lines := v_lines || ('Equipe [TEAM_' || v_team || ']: morto nao pego -100');
        end if;
    end loop;

    if coalesce(p_count_only, false) then
        v_lines := v_lines || 'Rodada encerrada por contagem, sem bonus de bate.';
    else
        v_team_scores[p_winner_team + 1] := v_team_scores[p_winner_team + 1] + 100;
        v_lines := v_lines || ('Equipe [TEAM_' || p_winner_team || ']: bonus de bate +100');
    end if;

    return jsonb_build_object(
        'teamRoundScores', jsonb_build_array(v_team_scores[1], v_team_scores[2]),
        'breakdown', array_to_string(v_lines, E'\n')
    );
end;
$$;

revoke all on function private.cbr_compute_round_summary(uuid, integer, boolean)
from public, anon, authenticated;

-- ─── match_results ganha colunas de auditoria com o placar canonico ────────

alter table public.match_results
add column if not exists server_round_scores jsonb,
add column if not exists server_round_breakdown text;

-- ─── complete_match exige uma rodada de verdade por tras do resultado ──────
-- Corpo copiado verbatim da 0034 (conferido no arquivo antes de editar, igual
-- o comentario de la ja avisa pra fazer) + o bloco novo de verificacao.

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

    -- Novo: precisa existir um WIN_ROUND/COUNT_ROUND de verdade entre o
    -- inicio da rodada e o resumo. Os dois ja passaram pelos gatilhos da
    -- 0016/0045 quando foram inseridos, entao a existencia deles aqui prova
    -- que a rodada realmente aconteceu -- fecha o golpe de fabricar um
    -- ROUND_SUMMARY vitorioso sem ter jogado.
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

        -- Na Cacheta quem bateu por ultimo sempre e o time vencedor da
        -- partida (o time que fica com 0 vidas e sempre o adversario). No
        -- Buraco/Tranca isso nao vale (depende do acumulado de rodadas
        -- anteriores, que o servidor ainda nao rastreia), entao so bloqueio
        -- aqui pra Cacheta.
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

    -- Auditoria apenas: calculo a rodada final a partir do ledger privado e
    -- guardo do lado, sem travar o fechamento se divergir do que o host
    -- mandou -- ainda nao rodei esse port em partida real o bastante pra
    -- confiar nele como bloqueio.
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
        championship_id,
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
        v_room.championship_id,
        v_server_summary -> 'teamRoundScores',
        v_server_summary ->> 'breakdown'
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

revoke all on function public.complete_match(uuid, text, integer, jsonb, jsonb) from public;
grant execute on function public.complete_match(uuid, text, integer, jsonb, jsonb) to authenticated;
