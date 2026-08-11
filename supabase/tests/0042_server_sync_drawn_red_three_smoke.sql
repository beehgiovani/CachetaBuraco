begin;

do $$
declare
    v_host_id uuid := '22222222-2222-2222-2222-222222222222';
    v_guest_id uuid := '42424242-4242-4242-4242-424242424242';
    v_room_id uuid;
    v_red_three text := 'THREE_HEARTS_RED';
    v_draw jsonb;
    v_drawn_seat integer;
    v_private_count integer;
    v_public_count integer;
    v_duplicate_events integer;
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
        '{"nickname":"Smoke 0042"}'::jsonb,
        now(),
        now(),
        false,
        true
    );
    insert into public.profiles(id, nickname)
    values (v_guest_id, 'Smoke 0042');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id
    into v_room_id
    from public.create_match_room(
        'SMK042',
        'TRANCA',
        jsonb_build_object(
            'cardsPerPlayer', 11,
            'allowWildcards', true,
            'allowCharutos', true,
            'allowDrawFromDiscard', true,
            'autoMeldTrancaRedThrees', true,
            'serialized', ''
        ),
        2,
        null
    ) created;

    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK042', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id,
        '42424242-4242-4242-4242-424242424243'
    );

    -- O teste roda em rollback. Forco uma carta fisica conhecida no topo para
    -- nao depender da ordem aleatoria do embaralhamento.
    update private.match_deck_state
    set deck = deck || jsonb_build_array(v_red_three),
        drawn_seat = null
    where room_id = v_room_id;

    v_draw := public.online_draw_deck_card_idempotent(
        v_room_id,
        1,
        '42424242-4242-4242-4242-424242424244'
    );

    if v_draw ->> 'card' <> v_red_three then
        raise exception 'FORCED_RED_THREE_WAS_NOT_DRAWN';
    end if;

    select state.drawn_seat
    into v_drawn_seat
    from private.match_deck_state state
    where state.room_id = v_room_id;
    if v_drawn_seat is not null then
        raise exception 'RED_THREE_BLOCKED_REPLACEMENT_DRAW';
    end if;

    select count(*)::integer
    into v_private_count
    from private.match_team_state state
    cross join lateral jsonb_array_elements(state.melds) meld(value)
    where state.room_id = v_room_id
      and state.team = 1
      and meld.value = jsonb_build_array(v_red_three);
    if v_private_count <> 1 then
        raise exception 'PRIVATE_RED_THREE_LEDGER_MISMATCH';
    end if;

    select count(*)::integer
    into v_public_count
    from jsonb_array_elements(
        coalesce(private.cbr_latest_public_state(v_room_id) -> 'team1Melds', '[]'::jsonb)
    ) meld(value)
    where meld.value = jsonb_build_array(v_red_three);
    if v_public_count <> 1 then
        raise exception 'PUBLIC_RED_THREE_STATE_MISMATCH';
    end if;

    select count(*)::integer
    into v_duplicate_events
    from public.match_events event
    where event.room_id = v_room_id
      and event.event_type = 'MELD'
      and event.payload::text like '%' || v_red_three || '%';
    if v_duplicate_events <> 0 then
        raise exception 'RED_THREE_MELD_EVENT_DUPLICATED';
    end if;
end;
$$;

rollback;
