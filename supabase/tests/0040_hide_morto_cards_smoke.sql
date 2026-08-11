begin;

do $$
declare
    v_host_id uuid := '22222222-2222-2222-2222-222222222222';
    v_guest_id uuid := '40404040-4040-4040-4040-404040404040';
    v_room_id uuid;
    v_request_id uuid := '40404040-4040-4040-4040-404040404041';
    v_first jsonb;
    v_retry jsonb;
    v_private_mortos integer;
    v_receipts integer;
begin
    if not exists (select 1 from auth.users where id = v_host_id) then
        raise exception 'SMOKE_HOST_FIXTURE_NOT_FOUND';
    end if;

    insert into auth.users(
        id,
        aud,
        role,
        raw_app_meta_data,
        raw_user_meta_data,
        created_at,
        updated_at,
        is_sso_user,
        is_anonymous
    ) values (
        v_guest_id,
        'authenticated',
        'authenticated',
        '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
        '{"nickname":"Smoke 0040"}'::jsonb,
        now(),
        now(),
        false,
        true
    );

    insert into public.profiles(id, nickname)
    values (v_guest_id, 'Smoke 0040');

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    select created.room_id
    into v_room_id
    from public.create_match_room(
        'SMK040',
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
    perform joined.room_id
    from public.join_match_room('SMK040', null) joined;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    v_first := public.start_online_round_idempotent(v_room_id, v_request_id);
    v_retry := public.start_online_round_idempotent(v_room_id, v_request_id);

    if v_first ? 'mortos' then
        raise exception 'MORTO_CONTENT_LEAKED_TO_HOST';
    end if;
    if jsonb_array_length(coalesce(v_first -> 'hand', '[]'::jsonb)) <> 11 then
        raise exception 'HOST_HAND_SIZE_MISMATCH';
    end if;
    if coalesce((v_first ->> 'mortosLeft')::integer, -1) <> 2 then
        raise exception 'PUBLIC_MORTO_COUNT_MISMATCH';
    end if;
    if v_retry is distinct from v_first then
        raise exception 'START_ROUND_RETRY_CHANGED_RESPONSE';
    end if;

    select jsonb_array_length(state.mortos)
    into v_private_mortos
    from private.match_deck_state state
    where state.room_id = v_room_id;

    if v_private_mortos <> 2 then
        raise exception 'PRIVATE_MORTO_STATE_MISMATCH';
    end if;

    select count(*)::integer
    into v_receipts
    from private.match_rpc_receipts receipt
    where receipt.room_id = v_room_id
      and receipt.operation_type = 'START_ROUND'
      and receipt.request_id = v_request_id;

    if v_receipts <> 1 then
        raise exception 'START_ROUND_RECEIPT_DUPLICATED';
    end if;
end;
$$;

rollback;
