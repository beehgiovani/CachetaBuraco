begin;

do $$
declare
    v_host_id uuid := '22222222-2222-2222-2222-222222222222';
    v_guest_id uuid := '47474747-4747-4747-4747-474747474747';
    v_room_id uuid;
    v_round_id text;
    v_morto_result jsonb;
    v_guest_hand_size integer;
    v_mortos_left integer;
    v_public_state jsonb;
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
        '{"nickname":"Smoke 0047"}'::jsonb,
        now(), now(), false, true
    );
    insert into public.profiles(id, nickname)
    values (v_guest_id, 'Smoke 0047');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK047',
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
    perform joined.room_id from public.join_match_room('SMK047', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id,
        '47474747-4747-4747-4747-474747474748'
    );

    select event.payload ->> 'roundId' into v_round_id
    from public.match_events event
    where event.room_id = v_room_id and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;

    -- Deixo a ultima baixa do convidado deterministica. Assim este teste cobre
    -- exatamente a sequencia do aplicativo: confirma MELD, zera a mao e so
    -- depois pede o morto diretamente ao servidor com a identidade do assento.
    update private.match_seat_state
    set hand = '["THREE_HEARTS_RED","FOUR_HEARTS_RED","FIVE_HEARTS_RED"]'::jsonb,
        required_discard_card = null,
        pending_discard_cards = '[]'::jsonb
    where room_id = v_room_id and seat = 1;
    update private.match_team_state
    set melds = '[]'::jsonb,
        melds_initialized = true
    where room_id = v_room_id and team = 1;
    update private.match_deck_state
    set drawn_seat = 1
    where room_id = v_room_id;

    perform public.append_match_event(
        v_room_id,
        '47474747-4747-4747-4747-474747474749',
        'PUBLIC_STATE',
        jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_host_id::text,
            'roundId', v_round_id,
            'messageId', '47474747-4747-4747-4747-474747474749',
            'payload', (
                private.cbr_latest_public_state(v_room_id)
                || jsonb_build_object(
                    'activeSeat', 1,
                    'handCounts', jsonb_build_array(11, 3)
                )
            )::text
        ),
        null
    );

    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform public.append_match_event(
        v_room_id,
        '47474747-4747-4747-4747-474747474750',
        'MELD',
        jsonb_build_object(
            'type', 'MELD',
            'senderId', v_guest_id::text,
            'roundId', v_round_id,
            'messageId', '47474747-4747-4747-4747-474747474750',
            'payload', jsonb_build_object(
                'v', 1,
                'seat', 1,
                'cards', jsonb_build_array(
                    'THREE_HEARTS_RED',
                    'FOUR_HEARTS_RED',
                    'FIVE_HEARTS_RED'
                ),
                'replaceIndex', -1
            )::text
        ),
        0
    );

    select jsonb_array_length(state.hand) into v_guest_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 1;
    if v_guest_hand_size <> 0 then
        raise exception 'GUEST_MELD_DID_NOT_EMPTY_PRIVATE_HAND';
    end if;

    v_morto_result := public.online_take_morto_idempotent(
        v_room_id,
        1,
        false,
        '47474747-4747-4747-4747-474747474751'
    );
    if coalesce(v_morto_result ->> 'status', '') <> 'OK'
       or jsonb_array_length(coalesce(v_morto_result -> 'hand', '[]'::jsonb)) <> 11 then
        raise exception 'GUEST_DIRECT_MORTO_FAILED: %', v_morto_result;
    end if;

    select jsonb_array_length(state.hand) into v_guest_hand_size
    from private.match_seat_state state
    where state.room_id = v_room_id and state.seat = 1;
    if v_guest_hand_size <> 11 then
        raise exception 'GUEST_DIRECT_MORTO_DID_NOT_UPDATE_LEDGER';
    end if;

    select jsonb_array_length(state.mortos) into v_mortos_left
    from private.match_deck_state state
    where state.room_id = v_room_id;
    if v_mortos_left <> 1 then
        raise exception 'GUEST_DIRECT_MORTO_WRONG_REMAINING_COUNT: %', v_mortos_left;
    end if;

    v_public_state := private.cbr_latest_public_state(v_room_id);
    if coalesce((v_public_state ->> 'activeSeat')::integer, -1) <> 1
       or coalesce((v_public_state -> 'handCounts' ->> 1)::integer, -1) <> 11 then
        raise exception 'GUEST_DIRECT_MORTO_PUBLIC_STATE_NOT_SYNCED: %', v_public_state;
    end if;

    if not exists (
        select 1
        from public.match_events event
        where event.room_id = v_room_id
          and event.event_type = 'MORTO_TAKEN'
          and event.seat = 1
          and event.recipient_seat = 0
    ) then
        raise exception 'GUEST_DIRECT_MORTO_NOTICE_NOT_PUBLISHED';
    end if;
end;
$$;

rollback;
