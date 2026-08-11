begin;

do $$
declare
    v_host_id uuid := '22222222-2222-2222-2222-222222222222';
    v_guest_id uuid := '41414141-4141-4141-4141-414141414141';
    v_room_id uuid;
    v_round_id text;
    v_card text;
    v_first_draw jsonb;
    v_second_draw jsonb;
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
        '{"nickname":"Smoke 0041"}'::jsonb,
        now(),
        now(),
        false,
        true
    );
    insert into public.profiles(id, nickname)
    values (v_guest_id, 'Smoke 0041');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id
    into v_room_id
    from public.create_match_room(
        'SMK041',
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
    perform joined.room_id from public.join_match_room('SMK041', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform public.start_online_round_idempotent(
        v_room_id,
        '41414141-4141-4141-4141-414141414142'
    );

    select event.payload ->> 'roundId'
    into v_round_id
    from public.match_events event
    where event.room_id = v_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;

    -- Este smoke verifica a trava de compra, nao a regra de descarte de
    -- curinga. Escolho uma carta comum para a distribuicao aleatoria nao
    -- transformar o teste em flakey quando o primeiro item for um 2/Joker.
    select hand_card.card
    into v_card
    from private.match_seat_state state
    cross join lateral jsonb_array_elements_text(state.hand) hand_card(card)
    where state.room_id = v_room_id
      and state.seat = 1
      and hand_card.card not like 'TWO\_%' escape '\'
      and hand_card.card not like 'JOKER\_%' escape '\'
    limit 1;

    if v_card is null then
        raise exception 'NON_WILDCARD_FIXTURE_CARD_NOT_FOUND';
    end if;

    -- Nem um cliente adulterado pode descartar antes de comprar.
    v_rejected := false;
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    begin
        perform public.append_match_event(
            v_room_id,
            'smoke-0041-discard-before-draw',
            'DISCARD',
            jsonb_build_object(
                'type', 'DISCARD',
                'senderId', v_guest_id::text,
                'roundId', v_round_id,
                'messageId', 'smoke-0041-discard-before-draw',
                'payload', jsonb_build_object('v', 1, 'seat', 1, 'card', v_card)::text
            ),
            0
        );
    exception
        when sqlstate 'P0001' then
            if sqlerrm <> 'DRAW_REQUIRED_BEFORE_DISCARD' then
                raise exception 'UNEXPECTED_DISCARD_REJECTION: %', sqlerrm;
            end if;
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'DISCARD_BEFORE_DRAW_WAS_ACCEPTED';
    end if;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    v_first_draw := public.online_draw_deck_card_idempotent(
        v_room_id,
        1,
        '41414141-4141-4141-4141-414141414143'
    );
    if coalesce(v_first_draw ->> 'status', '') <> 'OK' then
        raise exception 'FIRST_DRAW_FAILED';
    end if;

    -- Uma nova chave nao representa uma nova vez.
    v_rejected := false;
    begin
        perform public.online_draw_deck_card_idempotent(
            v_room_id,
            1,
            '41414141-4141-4141-4141-414141414144'
        );
    exception
        when sqlstate 'P0001' then
            if sqlerrm <> 'DRAW_ALREADY_COMPLETED' then
                raise exception 'UNEXPECTED_DRAW_REJECTION: %', sqlerrm;
            end if;
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'SECOND_DRAW_WAS_ACCEPTED';
    end if;

    -- Descartar encerra a vez de compra e libera a proxima transicao.
    perform set_config('request.jwt.claim.sub', v_guest_id::text, true);
    perform public.append_match_event(
        v_room_id,
        'smoke-0041-valid-discard',
        'DISCARD',
        jsonb_build_object(
            'type', 'DISCARD',
            'senderId', v_guest_id::text,
            'roundId', v_round_id,
            'messageId', 'smoke-0041-valid-discard',
            'payload', jsonb_build_object('v', 1, 'seat', 1, 'card', v_card)::text
        ),
        0
    );

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    v_second_draw := public.online_draw_deck_card_idempotent(
        v_room_id,
        1,
        '41414141-4141-4141-4141-414141414145'
    );
    if coalesce(v_second_draw ->> 'status', '') <> 'OK' then
        raise exception 'DRAW_AFTER_DISCARD_FAILED';
    end if;

    -- Uma rodada ativa nao pode ser redistribuida com outra chave.
    v_rejected := false;
    begin
        perform public.start_online_round_idempotent(
            v_room_id,
            '41414141-4141-4141-4141-414141414146'
        );
    exception
        when sqlstate 'P0001' then
            if sqlerrm <> 'ROUND_ALREADY_ACTIVE' then
                raise exception 'UNEXPECTED_REDEAL_REJECTION: %', sqlerrm;
            end if;
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'ACTIVE_ROUND_WAS_REDEALT';
    end if;

    if has_function_privilege('authenticated', 'public.start_online_round(uuid)', 'EXECUTE')
       or has_function_privilege('authenticated', 'public.online_draw_deck_card(uuid,integer)', 'EXECUTE')
       or has_function_privilege('authenticated', 'public.online_take_morto(uuid,integer,boolean)', 'EXECUTE') then
        raise exception 'NON_IDEMPOTENT_RPC_STILL_EXPOSED';
    end if;
end;
$$;

rollback;
