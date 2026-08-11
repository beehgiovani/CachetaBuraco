begin;

do $$
declare
    v_host_id uuid := '22222222-2222-2222-2222-222222222222';
    v_guest_id uuid := '45454545-4545-4545-4545-454545454545';
    v_room_id uuid;
    v_round_id text;
    v_host_hand_size integer;
    v_draw_result jsonb;
    v_morto_result jsonb;
    v_rejected boolean;
begin
    if not exists (select 1 from auth.users where id = v_host_id) then
        raise exception 'SMOKE_HOST_FIXTURE_NOT_FOUND';
    end if;

    insert into auth.users(
        id, aud, role, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values (
        v_guest_id,
        'authenticated',
        'authenticated',
        '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
        '{"nickname":"Smoke 0045"}'::jsonb,
        now(), now(), false, true
    );
    insert into public.profiles(id, nickname)
    values (v_guest_id, 'Smoke 0045');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK045',
        'BURACO',
        jsonb_build_object(
            'cardsPerPlayer', 11,
            'allowWildcards', true,
            'allowCharutos', true,
            'allowDrawFromDiscard', true,
            'requireCleanCanastraToWin', true,
            'serialized', ''
        ),
        2,
        null
    ) created;

    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK045', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id,
        '45454545-4545-4545-4545-454545454546'
    );

    select jsonb_array_length(state.hand)
    into v_host_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 0 and state.initialized;
    if v_host_hand_size <> 11 then
        raise exception 'HOST_PRIVATE_HAND_NOT_INITIALIZED';
    end if;

    select event.payload ->> 'roundId' into v_round_id
    from public.match_events event
    where event.room_id = v_room_id and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;

    perform public.append_match_event(
        v_room_id,
        'smoke-0045-host-draw-turn',
        'PUBLIC_STATE',
        jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_host_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0045-host-draw-turn',
            'payload', (
                private.cbr_latest_public_state(v_room_id)
                || jsonb_build_object('activeSeat', 0)
            )::text
        ),
        null
    );
    v_draw_result := public.online_draw_deck_card_idempotent(
        v_room_id,
        0,
        '45454545-4545-4545-4545-454545454547'
    );
    if coalesce(v_draw_result ->> 'status', '') <> 'OK' then
        raise exception 'HOST_SERVER_DRAW_FAILED';
    end if;
    select jsonb_array_length(state.hand) into v_host_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 0;
    if v_host_hand_size <> 12 then
        raise exception 'HOST_SERVER_DRAW_DID_NOT_UPDATE_LEDGER';
    end if;

    -- Nem o proprio host pode fabricar uma distribuicao privada pelo endpoint
    -- de eventos. Apenas start_online_round liga a marca interna da transacao.
    v_rejected := false;
    begin
        perform public.append_match_event(
            v_room_id,
            'smoke-0045-forged-game-start',
            'GAME_START',
            jsonb_build_object(
                'type', 'GAME_START',
                'senderId', v_host_id::text,
                'roundId', v_round_id,
                'messageId', 'smoke-0045-forged-game-start',
                'payload', jsonb_build_object(
                    'v', 1,
                    'roundId', v_round_id,
                    'seat', 1,
                    'hand', jsonb_build_array(
                        'ACE_HEARTS_RED', 'TWO_HEARTS_RED', 'THREE_HEARTS_RED',
                        'FOUR_HEARTS_RED', 'FIVE_HEARTS_RED', 'SIX_HEARTS_RED',
                        'SEVEN_HEARTS_RED', 'EIGHT_HEARTS_RED', 'NINE_HEARTS_RED',
                        'TEN_HEARTS_RED', 'JACK_HEARTS_RED'
                    )
                )::text
            ),
            1
        );
    exception
        when sqlstate 'P0001' then
            if sqlerrm <> 'SERVER_DELIVERY_REQUIRED' then
                raise exception 'UNEXPECTED_FORGED_DELIVERY_REJECTION: %', sqlerrm;
            end if;
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'FORGED_GAME_START_WAS_ACCEPTED';
    end if;

    -- Preparo um estado deterministico para provar que baixa e descarte do
    -- host alteram o ledger, sem depender das cartas aleatorias da distribuicao.
    update private.match_seat_state
    set hand = '["THREE_HEARTS_RED","FOUR_HEARTS_RED","FIVE_HEARTS_RED","KING_CLUBS_RED"]'::jsonb,
        required_discard_card = null,
        pending_discard_cards = '[]'::jsonb
    where room_id = v_room_id and seat = 0;
    update private.match_team_state
    set melds = '[]'::jsonb,
        melds_initialized = true
    where room_id = v_room_id and team = 0;
    update private.match_deck_state
    set drawn_seat = 0
    where room_id = v_room_id;

    perform public.append_match_event(
        v_room_id,
        'smoke-0045-host-turn',
        'PUBLIC_STATE',
        jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_host_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0045-host-turn',
            'payload', (
                private.cbr_latest_public_state(v_room_id)
                || jsonb_build_object('activeSeat', 0)
            )::text
        ),
        null
    );

    perform public.append_match_event(
        v_room_id,
        'smoke-0045-host-meld',
        'MELD',
        jsonb_build_object(
            'type', 'MELD',
            'senderId', v_host_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0045-host-meld',
            'payload', jsonb_build_object(
                'v', 1,
                'seat', 0,
                'cards', jsonb_build_array(
                    'THREE_HEARTS_RED',
                    'FOUR_HEARTS_RED',
                    'FIVE_HEARTS_RED'
                ),
                'replaceIndex', -1
            )::text
        ),
        null
    );
    select jsonb_array_length(state.hand) into v_host_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 0;
    if v_host_hand_size <> 1 then
        raise exception 'HOST_MELD_DID_NOT_UPDATE_PRIVATE_HAND';
    end if;

    perform public.append_match_event(
        v_room_id,
        'smoke-0045-host-discard',
        'DISCARD',
        jsonb_build_object(
            'type', 'DISCARD',
            'senderId', v_host_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0045-host-discard',
            'payload', jsonb_build_object(
                'v', 1,
                'seat', 0,
                'card', 'KING_CLUBS_RED'
            )::text
        ),
        null
    );
    select jsonb_array_length(state.hand) into v_host_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 0;
    if v_host_hand_size <> 0 then
        raise exception 'HOST_DISCARD_DID_NOT_UPDATE_PRIVATE_HAND';
    end if;

    v_morto_result := public.online_take_morto_idempotent(
        v_room_id,
        0,
        false,
        '45454545-4545-4545-4545-454545454548'
    );
    if coalesce(v_morto_result ->> 'status', '') <> 'OK'
       or jsonb_array_length(coalesce(v_morto_result -> 'hand', '[]'::jsonb)) <> 11 then
        raise exception 'HOST_SERVER_MORTO_FAILED';
    end if;
    select jsonb_array_length(state.hand) into v_host_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 0;
    if v_host_hand_size <> 11 then
        raise exception 'HOST_SERVER_MORTO_DID_NOT_UPDATE_LEDGER';
    end if;
end;
$$;

rollback;
