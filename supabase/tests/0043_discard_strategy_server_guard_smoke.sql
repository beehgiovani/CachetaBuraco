begin;

do $$
declare
    v_host_id uuid := '22222222-2222-2222-2222-222222222222';
    v_guest_id uuid := '43434343-4343-4343-4343-434343434343';
    v_room_id uuid;
    v_round_id text;
    v_public_state jsonb;
    v_rejected boolean;
begin
    if not private.cbr_can_justify_discard_draw(
        'FIVE_HEARTS_RED',
        '["THREE_HEARTS_RED","FOUR_HEARTS_RED"]'::jsonb,
        '[]'::jsonb,
        'BURACO', true, true, null
    ) then
        raise exception 'VALID_NEW_MELD_NOT_RECOGNIZED';
    end if;
    if private.cbr_can_justify_discard_draw(
        'FIVE_HEARTS_RED',
        '["KING_CLUBS_RED","QUEEN_DIAMONDS_RED"]'::jsonb,
        '[]'::jsonb,
        'BURACO', true, true, null
    ) then
        raise exception 'INVALID_NEW_MELD_WAS_ACCEPTED';
    end if;
    if not private.cbr_can_justify_discard_draw(
        'SIX_HEARTS_RED',
        '[]'::jsonb,
        '[["THREE_HEARTS_RED","FOUR_HEARTS_RED","FIVE_HEARTS_RED"]]'::jsonb,
        'BURACO', true, true, null
    ) then
        raise exception 'VALID_TABLE_EXTENSION_NOT_RECOGNIZED';
    end if;

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
        '{"nickname":"Smoke 0043"}'::jsonb,
        now(),
        now(),
        false,
        true
    );
    insert into public.profiles(id, nickname)
    values (v_guest_id, 'Smoke 0043');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id
    into v_room_id
    from public.create_match_room(
        'SMK043',
        'BURACO',
        jsonb_build_object(
            'cardsPerPlayer', 11,
            'allowWildcards', true,
            'allowCharutos', true,
            'allowDrawFromDiscard', true,
            'serialized', ''
        ),
        2,
        null
    ) created;

    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform joined.room_id from public.join_match_room('SMK043', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id,
        '43434343-4343-4343-4343-434343434344'
    );

    select event.payload ->> 'roundId'
    into v_round_id
    from public.match_events event
    where event.room_id = v_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;

    v_public_state := private.cbr_latest_public_state(v_room_id) || jsonb_build_object(
        'activeSeat', 1,
        'discardCount', 1,
        'discardPile', jsonb_build_array('FIVE_HEARTS_RED')
    );
    perform public.append_match_event(
        v_room_id,
        'smoke-0043-public-discard',
        'PUBLIC_STATE',
        jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_host_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0043-public-discard',
            'payload', v_public_state::text
        ),
        null
    );

    update private.match_seat_state
    set hand = '["KING_CLUBS_RED","QUEEN_DIAMONDS_RED","ACE_SPADES_RED"]'::jsonb,
        required_discard_card = null,
        pending_discard_cards = '[]'::jsonb
    where room_id = v_room_id and seat = 1;

    v_rejected := false;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    begin
        perform public.append_match_event(
            v_room_id,
            'smoke-0043-bad-draw',
            'DRAW_DISCARD',
            jsonb_build_object(
                'type', 'DRAW_DISCARD',
                'senderId', v_guest_id::text,
                'roundId', v_round_id,
                'messageId', 'smoke-0043-bad-draw',
                'payload', 'FIVE_HEARTS_RED'
            ),
            0
        );
    exception
        when sqlstate 'P0001' then
            if sqlerrm <> 'DISCARD_DRAW_NOT_JUSTIFIED' then
                raise exception 'UNEXPECTED_BAD_DRAW_REJECTION: %', sqlerrm;
            end if;
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'UNJUSTIFIED_DISCARD_DRAW_WAS_ACCEPTED';
    end if;

    update private.match_seat_state
    set hand = '["THREE_HEARTS_RED","FOUR_HEARTS_RED","ACE_SPADES_RED"]'::jsonb
    where room_id = v_room_id and seat = 1;
    perform public.append_match_event(
        v_room_id,
        'smoke-0043-good-draw',
        'DRAW_DISCARD',
        jsonb_build_object(
            'type', 'DRAW_DISCARD',
            'senderId', v_guest_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0043-good-draw',
            'payload', 'FIVE_HEARTS_RED'
        ),
        0
    );

    -- Preparo uma mesa limpa e uma mao em que o 2 ainda pode ser usado.
    update private.match_seat_state
    set hand = '["TWO_HEARTS_RED","KING_CLUBS_RED"]'::jsonb,
        required_discard_card = null,
        pending_discard_cards = '[]'::jsonb
    where room_id = v_room_id and seat = 1;
    update private.match_team_state
    set melds = '[["THREE_CLUBS_RED","FOUR_CLUBS_RED","FIVE_CLUBS_RED"]]'::jsonb,
        melds_initialized = true
    where room_id = v_room_id and team = 1;
    update private.match_deck_state
    set drawn_seat = 1
    where room_id = v_room_id;

    v_rejected := false;
    begin
        perform public.append_match_event(
            v_room_id,
            'smoke-0043-bad-wild-discard',
            'DISCARD',
            jsonb_build_object(
                'type', 'DISCARD',
                'senderId', v_guest_id::text,
                'roundId', v_round_id,
                'messageId', 'smoke-0043-bad-wild-discard',
                'payload', jsonb_build_object('v', 1, 'seat', 1, 'card', 'TWO_HEARTS_RED')::text
            ),
            0
        );
    exception
        when sqlstate 'P0001' then
            if sqlerrm <> 'WILDCARD_DISCARD_REQUIRED_FOR_PLAY' then
                raise exception 'UNEXPECTED_WILDCARD_REJECTION: %', sqlerrm;
            end if;
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'USEFUL_WILDCARD_DISCARD_WAS_ACCEPTED';
    end if;

    -- Sem carta comum restante, o jogador nao pode ficar preso ao curinga.
    update private.match_seat_state
    set hand = '["TWO_HEARTS_RED","JOKER_RED_SPADES"]'::jsonb
    where room_id = v_room_id and seat = 1;
    perform public.append_match_event(
        v_room_id,
        'smoke-0043-forced-wild-discard',
        'DISCARD',
        jsonb_build_object(
            'type', 'DISCARD',
            'senderId', v_guest_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0043-forced-wild-discard',
            'payload', jsonb_build_object('v', 1, 'seat', 1, 'card', 'TWO_HEARTS_RED')::text
        ),
        0
    );
end;
$$;

rollback;
