begin;

-- Parte 1: so o admin (email fixo) cria campeonato; qualquer outro leva
-- ADMIN_REQUIRED.
do $$
declare
    v_admin_id uuid := '49494949-4949-4949-4949-494949494901';
    v_regular_id uuid := '49494949-4949-4949-4949-494949494902';
    v_rejected boolean := false;
begin
    insert into auth.users(
        id, aud, role, email, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_admin_id, 'authenticated', 'authenticated', 'brunogp.corretor@gmail.com',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Admin"}'::jsonb, now(), now(), false, true),
    (v_regular_id, 'authenticated', 'authenticated', 'jogador@example.com',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Jogador"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values
    (v_admin_id, 'Admin'),
    (v_regular_id, 'Jogador');

    perform set_config('request.jwt.claim.sub', v_regular_id::text, true);
    begin
        perform public.create_championship('Semanal Teste', 'CACHETA', 'WEEKLY', null);
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'CREATE_CHAMPIONSHIP_SHOULD_REJECT_NON_ADMIN';
    end if;
    raise notice 'CREATE_CHAMPIONSHIP_ADMIN_REQUIRED_OK';

    perform set_config('request.jwt.claim.sub', v_admin_id::text, true);
    if not exists (
        select 1 from public.create_championship('Semanal Teste', 'CACHETA', 'WEEKLY', null)
        created(championship_id, code, name, game_type, status, cadence, level, starts_at, ends_at)
        where created.cadence = 'WEEKLY'
          and created.ends_at is not null
          and created.ends_at > created.starts_at + interval '6 days'
          and created.ends_at < created.starts_at + interval '8 days'
    ) then
        raise exception 'CREATE_CHAMPIONSHIP_WEEKLY_WINDOW_WRONG';
    end if;
    raise notice 'CREATE_CHAMPIONSHIP_ADMIN_OK';
end;
$$;

-- Parte 2: nivel calculado bloqueia inscricao incompativel.
do $$
declare
    v_admin_id uuid := '49494949-4949-4949-4949-494949494901';
    v_noob_id uuid := '49494949-4949-4949-4949-494949494903';
    v_expert_id uuid := '49494949-4949-4949-4949-494949494904';
    v_code text;
    v_rejected boolean := false;
begin
    insert into auth.users(
        id, aud, role, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_noob_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Noob"}'::jsonb, now(), now(), false, true),
    (v_expert_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Expert"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values
    (v_noob_id, 'Noob'),
    (v_expert_id, 'Expert');
    insert into public.player_stats(profile_id, total_matches, total_wins)
    values (v_expert_id, 60, 40)
    on conflict (profile_id) do update
    set total_matches = excluded.total_matches, total_wins = excluded.total_wins;

    perform set_config('request.jwt.claim.sub', v_admin_id::text, true);
    select created.code into v_code
    from public.create_championship('So Iniciante', 'CACHETA', 'MANUAL', 'NOOB')
    created(championship_id, code, name, game_type, status, cadence, level, starts_at, ends_at);

    perform set_config('request.jwt.claim.sub', v_noob_id::text, true);
    perform joined.code from public.join_championship(v_code)
    joined(championship_id, code, name, game_type, status, cadence, level, starts_at, ends_at);
    raise notice 'JOIN_CHAMPIONSHIP_LEVEL_MATCH_OK';

    perform set_config('request.jwt.claim.sub', v_expert_id::text, true);
    begin
        perform public.join_championship(v_code);
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'JOIN_CHAMPIONSHIP_SHOULD_REJECT_LEVEL_MISMATCH';
    end if;
    raise notice 'JOIN_CHAMPIONSHIP_LEVEL_MISMATCH_OK';
end;
$$;

-- Parte 3: partida completada conta automaticamente pro campeonato ativo
-- compativel, sem precisar vincular a sala.
do $$
declare
    v_admin_id uuid := '49494949-4949-4949-4949-494949494901';
    v_host_id uuid := '49494949-4949-4949-4949-494949494905';
    v_guest_id uuid := '49494949-4949-4949-4949-494949494906';
    v_room_id uuid;
    v_round_id text;
    v_code text;
    v_entries integer;
    v_ok boolean;
begin
    insert into auth.users(
        id, aud, role, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_host_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Host49"}'::jsonb, now(), now(), false, true),
    (v_guest_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Guest49"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values
    (v_host_id, 'Host49'),
    (v_guest_id, 'Guest49');

    perform set_config('request.jwt.claim.sub', v_admin_id::text, true);
    select created.code into v_code
    from public.create_championship('Cacheta Livre', 'CACHETA', 'MANUAL', null)
    created(championship_id, code, name, game_type, status, cadence, level, starts_at, ends_at);

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform joined.code from public.join_championship(v_code)
    joined(championship_id, code, name, game_type, status, cadence, level, starts_at, ends_at);
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.code from public.join_championship(v_code)
    joined(championship_id, code, name, game_type, status, cadence, level, starts_at, ends_at);

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0491', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK0491', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id, '49494949-4949-4949-4949-494949494910'
    );
    select event.payload ->> 'roundId' into v_round_id
    from public.match_events event
    where event.room_id = v_room_id and event.event_type = 'PUBLIC_STATE'
    order by event.id desc limit 1;

    -- Convidado (assento 1, time 1) esvazia a mao e bate.
    update private.match_seat_state
    set hand = '[]'::jsonb, pending_discard_cards = '[]'::jsonb, required_discard_card = null
    where room_id = v_room_id and seat = 1;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0049-win-round',
        'WIN_ROUND',
        jsonb_build_object(
            'type', 'WIN_ROUND', 'senderId', v_guest_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0049-win-round',
            'payload', jsonb_build_object('seat', 1)::text
        ),
        0
    );

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0049-summary',
        'ROUND_SUMMARY',
        jsonb_build_object(
            'type', 'ROUND_SUMMARY', 'senderId', v_host_id::text,
            'roundId', v_round_id, 'messageId', 'smoke-0049-summary',
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
        'smoke-0049-summary',
        1,
        jsonb_build_object('teamScores', jsonb_build_array(1499, 1500)),
        jsonb_build_object('text', 'ok')
    );
    if not coalesce(v_ok, false) then
        raise exception 'COMPLETE_MATCH_SHOULD_SUCCEED';
    end if;

    select count(*) into v_entries
    from public.championship_match_entries entry
    join public.championships champ on champ.id = entry.championship_id
    where champ.code = v_code;
    if v_entries <> 2 then
        raise exception 'CHAMPIONSHIP_SHOULD_AUTO_ATTRIBUTE_BOTH_PLAYERS got=%', v_entries;
    end if;
    raise notice 'CHAMPIONSHIP_AUTO_ATTRIBUTION_OK';
end;
$$;

rollback;
