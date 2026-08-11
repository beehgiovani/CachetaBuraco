begin;

-- Parte 1: private.cbr_compute_round_summary isolado, manipulando o ledger
-- direto (mais rapido que jogar uma partida inteira pra cada cenario).
do $$
declare
    v_host_id uuid := '48484848-4848-4848-4848-484848484801';
    v_guest_id uuid := '48484848-4848-4848-4848-484848484802';
    v_room_id uuid;
    v_summary jsonb;
begin
    insert into auth.users(
        id, aud, role, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_host_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Smoke 0048 host"}'::jsonb, now(), now(), false, true),
    (v_guest_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Smoke 0048 guest"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values
    (v_host_id, 'Smoke 0048 host'),
    (v_guest_id, 'Smoke 0048 guest');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0481', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK0481', null) joined;

    -- Cacheta: time 0 bateu com 12 cartas na mesa (>=10 -> perde 2 vidas).
    insert into private.match_team_state(room_id, team, melds, melds_initialized)
    values (v_room_id, 0, jsonb_build_array(
        jsonb_build_array('THREE_HEARTS_RED','FOUR_HEARTS_RED','FIVE_HEARTS_RED','SIX_HEARTS_RED'),
        jsonb_build_array('TWO_SPADES_BLACK','TWO_CLUBS_BLACK','TWO_HEARTS_RED','TWO_DIAMONDS_RED'),
        jsonb_build_array('KING_SPADES_BLACK','KING_CLUBS_BLACK','KING_HEARTS_RED','KING_DIAMONDS_RED')
    ), true);

    v_summary := private.cbr_compute_round_summary(v_room_id, 0, false);
    if (v_summary -> 'teamRoundScores' -> 0)::text <> '0'
       or (v_summary -> 'teamRoundScores' -> 1)::text <> '-2' then
        raise exception 'CACHETA_12_CARDS_SHOULD_LOSE_2_LIVES: %', v_summary;
    end if;

    -- Menos de 10 cartas -> perde so 1 vida.
    update private.match_team_state
    set melds = jsonb_build_array(
        jsonb_build_array('THREE_HEARTS_RED','FOUR_HEARTS_RED','FIVE_HEARTS_RED')
    )
    where room_id = v_room_id and team = 0;
    v_summary := private.cbr_compute_round_summary(v_room_id, 0, false);
    if (v_summary -> 'teamRoundScores' -> 1)::text <> '-1' then
        raise exception 'CACHETA_3_CARDS_SHOULD_LOSE_1_LIFE: %', v_summary;
    end if;

    raise notice 'CACHETA_ROUND_SUMMARY_OK';
end;
$$;

-- Buraco: canastra suja (com curinga) + 3 vermelhos na mesa + morto nao
-- pego + bonus de bate.
do $$
declare
    v_host_id uuid := '48484848-4848-4848-4848-484848484801';
    v_guest_id uuid := '48484848-4848-4848-4848-484848484802';
    v_room_id uuid;
    v_summary jsonb;
    v_team0_score integer;
    v_team1_score integer;
begin
    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0482', 'BURACO',
        jsonb_build_object(
            'cardsPerPlayer', 11, 'allowWildcards', true, 'allowCharutos', true,
            'requireCleanCanastraToWin', false, 'serialized', ''
        ),
        2, null
    ) created;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK0482', null) joined;

    -- Time 0: uma sequencia de 7 cartas de paus com 1 curinga (canastra suja
    -- = 100 pts) + trinca simples de 3 (15 pts: A=15). Time 0 pegou o morto
    -- e vence a rodada (bonus de bate +100).
    insert into private.match_team_state(room_id, team, melds, melds_initialized, picked_morto)
    values (v_room_id, 0, jsonb_build_array(
        jsonb_build_array(
            'FOUR_CLUBS_BLACK','FIVE_CLUBS_BLACK','SIX_CLUBS_BLACK','SEVEN_CLUBS_BLACK',
            'EIGHT_CLUBS_BLACK','NINE_CLUBS_BLACK','JOKER_RED_HEARTS'
        )
    ), true, true);
    insert into private.match_team_state(room_id, team, melds, melds_initialized, picked_morto)
    values (v_room_id, 1, '[]'::jsonb, true, false);

    -- Maos vazias pros dois assentos (batida limpa).
    insert into private.match_seat_state(room_id, seat, hand, initialized)
    values (v_room_id, 0, '[]'::jsonb, true)
    on conflict (room_id, seat) do update set hand = excluded.hand, initialized = true;
    insert into private.match_seat_state(room_id, seat, hand, initialized)
    values (v_room_id, 1, '[]'::jsonb, true)
    on conflict (room_id, seat) do update set hand = excluded.hand, initialized = true;

    v_summary := private.cbr_compute_round_summary(v_room_id, 0, false);
    -- mesa: 6 cartas normais (4,5,6,7,8,9 de paus = 5+5+5+5+10+10=40) + curinga
    -- na propria canastra (20) + canastra suja 100 = 160
    -- time 0 pegou morto (sem penalidade) + bonus de bate 100 = 260
    -- time 1: sem mesa, sem mao, nao pegou morto = -100
    select (v_summary -> 'teamRoundScores' ->> 0)::integer,
           (v_summary -> 'teamRoundScores' ->> 1)::integer
    into v_team0_score, v_team1_score;
    if v_team0_score <> 260 or v_team1_score <> -100 then
        raise exception 'BURACO_DIRTY_CANASTRA_SCORE_MISMATCH team0=% team1=% summary=%',
            v_team0_score, v_team1_score, v_summary;
    end if;

    raise notice 'BURACO_ROUND_SUMMARY_OK';
end;
$$;

-- Tranca: 3 vermelhos na mesa contam separado, so somam quando o time tem
-- canastra (senao viram penalidade).
do $$
declare
    v_host_id uuid := '48484848-4848-4848-4848-484848484801';
    v_guest_id uuid := '48484848-4848-4848-4848-484848484802';
    v_room_id uuid;
    v_summary jsonb;
    v_team0_score integer;
begin
    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0483', 'TRANCA',
        jsonb_build_object(
            'cardsPerPlayer', 11, 'allowWildcards', true, 'allowCharutos', true,
            'requireCleanCanastraToWin', false, 'serialized', ''
        ),
        2, null
    ) created;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK0483', null) joined;

    -- Time 0 sem canastra nenhuma, so 1 trinca simples + 2 tres vermelhos
    -- "baixados" (sem canastra -> penalidade -200).
    insert into private.match_team_state(room_id, team, melds, melds_initialized, picked_morto)
    values (v_room_id, 0, jsonb_build_array(
        jsonb_build_array('FOUR_CLUBS_BLACK','FOUR_HEARTS_RED','FOUR_SPADES_BLACK'),
        jsonb_build_array('THREE_HEARTS_RED'),
        jsonb_build_array('THREE_DIAMONDS_RED')
    ), true, true);
    insert into private.match_team_state(room_id, team, melds, melds_initialized, picked_morto)
    values (v_room_id, 1, '[]'::jsonb, true, true);
    insert into private.match_seat_state(room_id, seat, hand, initialized)
    values (v_room_id, 0, '[]'::jsonb, true)
    on conflict (room_id, seat) do update set hand = excluded.hand, initialized = true;
    insert into private.match_seat_state(room_id, seat, hand, initialized)
    values (v_room_id, 1, '[]'::jsonb, true)
    on conflict (room_id, seat) do update set hand = excluded.hand, initialized = true;

    v_summary := private.cbr_compute_round_summary(v_room_id, 0, false);
    -- trinca de 4 (5+5+5=15) - 2 tres vermelhos = -200 (sem canastra) + bonus 100 = -85
    select (v_summary -> 'teamRoundScores' ->> 0)::integer into v_team0_score;
    if v_team0_score <> -85 then
        raise exception 'TRANCA_RED_THREE_WITHOUT_CANASTRA_MISMATCH team0=% summary=%',
            v_team0_score, v_summary;
    end if;

    raise notice 'TRANCA_ROUND_SUMMARY_OK';
end;
$$;

-- Parte 2: complete_match agora exige um WIN_ROUND/COUNT_ROUND de verdade e,
-- na Cacheta, que o time bata com quem realmente mandou o WIN_ROUND.
do $$
declare
    v_host_id uuid := '48484848-4848-4848-4848-484848484801';
    v_guest_id uuid := '48484848-4848-4848-4848-484848484802';
    v_room_id uuid;
    v_round_id text;
    v_rejected boolean := false;
    v_ok boolean;
begin
    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0484', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK0484', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id, '48484848-4848-4848-4848-484848484810'
    );
    select event.payload ->> 'roundId' into v_round_id
    from public.match_events event
    where event.room_id = v_room_id and event.event_type = 'PUBLIC_STATE'
    order by event.id desc limit 1;

    -- Sem WIN_ROUND/COUNT_ROUND nenhum: complete_match tem que rejeitar.
    begin
        v_ok := public.complete_match(
            v_room_id,
            'smoke-0048-fake-result',
            0,
            jsonb_build_object('teamScores', jsonb_build_array(1500, 0)),
            jsonb_build_object('text', 'fabricado')
        );
        v_rejected := false;
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'COMPLETE_MATCH_SHOULD_REJECT_WITHOUT_RESULT_EVENT';
    end if;
    raise notice 'COMPLETE_MATCH_ROUND_END_EVENT_REQUIRED_OK';

    -- Guest (assento 1, time 1) esvazia a mao e manda WIN_ROUND de verdade.
    update private.match_seat_state
    set hand = '[]'::jsonb, pending_discard_cards = '[]'::jsonb, required_discard_card = null
    where room_id = v_room_id and seat = 1;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0048-win-round',
        'WIN_ROUND',
        jsonb_build_object(
            'type', 'WIN_ROUND', 'senderId', v_guest_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0048-win-round',
            'payload', jsonb_build_object('seat', 1)::text
        ),
        0
    );

    -- Host tenta fechar como se o time 0 (dele) tivesse vencido -- mas quem
    -- bateu foi o time 1. Cacheta tem que rejeitar por WINNER_TEAM_MISMATCH.
    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0048-wrong-summary',
        'ROUND_SUMMARY',
        jsonb_build_object(
            'type', 'ROUND_SUMMARY', 'senderId', v_host_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0048-wrong-summary',
            'payload', jsonb_build_object(
                'v', 1, 'winnerId', v_host_id::text,
                'winnerRoundScore', 0, 'loserRoundScore', -1,
                'winnerTotal', 1500, 'loserTotal', 1499,
                'isMatchOver', true, 'breakdown', 'fake',
                'teamScores', jsonb_build_array(1500, 1499),
                'winnerTeam', 0, 'noWinner', false,
                'teamRoundScores', jsonb_build_array(0, -1)
            )::text
        ),
        null
    );
    v_rejected := false;
    begin
        v_ok := public.complete_match(
            v_room_id,
            'smoke-0048-wrong-summary',
            0,
            jsonb_build_object('teamScores', jsonb_build_array(1500, 1499)),
            jsonb_build_object('text', 'fake')
        );
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'COMPLETE_MATCH_SHOULD_REJECT_WRONG_WINNER_TEAM';
    end if;
    raise notice 'COMPLETE_MATCH_WINNER_TEAM_MISMATCH_OK';

    -- Fechamento correto: time 1 (quem bateu) declarado vencedor da partida.
    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0048-right-summary',
        'ROUND_SUMMARY',
        jsonb_build_object(
            'type', 'ROUND_SUMMARY', 'senderId', v_host_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0048-right-summary',
            'payload', jsonb_build_object(
                'v', 1, 'winnerId', v_guest_id::text,
                'winnerRoundScore', 0, 'loserRoundScore', -1,
                'winnerTotal', 1500, 'loserTotal', 1499,
                'isMatchOver', true, 'breakdown', 'ok',
                'teamScores', jsonb_build_array(1499, 1500),
                'winnerTeam', 1, 'noWinner', false,
                'teamRoundScores', jsonb_build_array(-1, 0)
            )::text
        ),
        null
    );
    v_ok := public.complete_match(
        v_room_id,
        'smoke-0048-right-summary',
        1,
        jsonb_build_object('teamScores', jsonb_build_array(1499, 1500)),
        jsonb_build_object('text', 'ok')
    );
    if not coalesce(v_ok, false) then
        raise exception 'COMPLETE_MATCH_SHOULD_SUCCEED_WITH_REAL_WIN_ROUND';
    end if;

    if not exists (
        select 1 from public.match_results result
        where result.room_id = v_room_id
          and result.result_key = 'smoke-0048-right-summary'
          and result.winner_team = 1
          and result.server_round_scores is not null
    ) then
        raise exception 'COMPLETE_MATCH_DID_NOT_STORE_SERVER_ROUND_SCORES';
    end if;

    raise notice 'COMPLETE_MATCH_HAPPY_PATH_OK';
end;
$$;

-- Parte 3: complete_match tambem tem que aceitar uma vitoria real de
-- Buraco/Tranca (canastra suja + morto pego) -- o caminho que ficou como
-- lacuna documentada (sem checagem de time vencedor), mas que ainda precisa
-- deixar passar o fechamento legitimo.
do $$
declare
    v_host_id uuid := '48484848-4848-4848-4848-484848484801';
    v_guest_id uuid := '48484848-4848-4848-4848-484848484802';
    v_room_id uuid;
    v_round_id text;
    v_ok boolean;
begin
    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0485', 'BURACO',
        jsonb_build_object(
            'cardsPerPlayer', 11, 'allowWildcards', true, 'allowCharutos', true,
            'requireCleanCanastraToWin', false, 'serialized', ''
        ),
        2, null
    ) created;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK0485', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id, '48484848-4848-4848-4848-484848484811'
    );
    select event.payload ->> 'roundId' into v_round_id
    from public.match_events event
    where event.room_id = v_room_id and event.event_type = 'PUBLIC_STATE'
    order by event.id desc limit 1;

    -- Time 1 (convidado) baixou uma canastra suja, pegou o morto e zerou a mao.
    insert into private.match_team_state(room_id, team, melds, melds_initialized, picked_morto)
    values (v_room_id, 1, jsonb_build_array(
        jsonb_build_array(
            'FOUR_CLUBS_BLACK','FIVE_CLUBS_BLACK','SIX_CLUBS_BLACK','SEVEN_CLUBS_BLACK',
            'EIGHT_CLUBS_BLACK','NINE_CLUBS_BLACK','JOKER_RED_HEARTS'
        )
    ), true, true)
    on conflict (room_id, team) do update
    set melds = excluded.melds, melds_initialized = true, picked_morto = true;
    update private.match_seat_state
    set hand = '[]'::jsonb, pending_discard_cards = '[]'::jsonb, required_discard_card = null
    where room_id = v_room_id and seat = 1;

    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0048-buraco-win-round',
        'WIN_ROUND',
        jsonb_build_object(
            'type', 'WIN_ROUND', 'senderId', v_guest_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0048-buraco-win-round',
            'payload', jsonb_build_object('seat', 1)::text
        ),
        0
    );

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0048-buraco-summary',
        'ROUND_SUMMARY',
        jsonb_build_object(
            'type', 'ROUND_SUMMARY', 'senderId', v_host_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0048-buraco-summary',
            'payload', jsonb_build_object(
                'v', 1, 'winnerId', v_guest_id::text,
                'winnerRoundScore', 260, 'loserRoundScore', -100,
                'winnerTotal', 260, 'loserTotal', -100,
                'isMatchOver', true, 'breakdown', 'ok-buraco',
                'teamScores', jsonb_build_array(-100, 260),
                'winnerTeam', 1, 'noWinner', false,
                'teamRoundScores', jsonb_build_array(-100, 260)
            )::text
        ),
        null
    );
    v_ok := public.complete_match(
        v_room_id,
        'smoke-0048-buraco-summary',
        1,
        jsonb_build_object('teamScores', jsonb_build_array(-100, 260)),
        jsonb_build_object('text', 'ok-buraco')
    );
    if not coalesce(v_ok, false) then
        raise exception 'COMPLETE_MATCH_SHOULD_ACCEPT_REAL_BURACO_WIN';
    end if;

    raise notice 'COMPLETE_MATCH_BURACO_HAPPY_PATH_OK';
end;
$$;

rollback;
