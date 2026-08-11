-- Uma resposta HTTP pode se perder depois que o Postgres ja confirmou a
-- compra, a distribuicao ou o morto. Guardo o resultado pela chave enviada
-- pelo host para uma repeticao devolver exatamente a mesma operacao.

create table if not exists private.match_rpc_receipts (
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    operation_type text not null check (operation_type in ('START_ROUND', 'DRAW_DECK', 'TAKE_MORTO')),
    request_id uuid not null,
    actor_id uuid not null,
    seat integer not null check (seat between 0 and 3),
    arguments jsonb not null default '{}'::jsonb,
    response jsonb not null,
    created_at timestamptz not null default now(),
    primary key (room_id, operation_type, request_id)
);

create index if not exists idx_match_rpc_receipts_created_at
on private.match_rpc_receipts (created_at);

revoke all on private.match_rpc_receipts from public, anon, authenticated;

-- O cliente pode pedir somente o proprio morto. Reaproveito a funcao da
-- 0026, que ja valida mao vazia, equipe, cartas e 3 vermelhos, executando-a
-- com a identidade do host apenas dentro desta transacao fechada.
create or replace function private.cbr_take_morto_for_member(
    p_room_id uuid,
    p_seat integer,
    p_indirect boolean
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_caller_seat integer;
    v_original_sub text := current_setting('request.jwt.claim.sub', true);
    v_response jsonb;
    v_public_state jsonb;
    v_hand_counts jsonb;
    v_round_id text;
    v_message_id text;
    v_next_seat integer;
begin
    if v_actor_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select room.* into v_room
    from public.match_rooms room
    where room.id = p_room_id
      and room.status = 'playing';
    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    select player.seat into v_caller_seat
    from public.room_players player
    where player.room_id = p_room_id
      and player.profile_id = v_actor_id;
    if v_caller_seat is null then
        raise exception 'ACTIVE_ROOM_MEMBERSHIP_REQUIRED' using errcode = 'P0001';
    end if;
    if v_caller_seat <> 0 and v_caller_seat <> p_seat then
        raise exception 'MORTO_SEAT_MISMATCH' using errcode = 'P0001';
    end if;

    if v_caller_seat = 0 then
        return public.online_take_morto(p_room_id, p_seat, p_indirect);
    end if;

    -- A funcao original aceita somente o host. A troca e local a esta
    -- transacao e acontece depois de eu provar o assento real do solicitante.
    perform set_config('request.jwt.claim.sub', v_room.host_id::text, true);
    begin
        v_response := public.online_take_morto(p_room_id, p_seat, p_indirect);
    exception
        when others then
            perform set_config('request.jwt.claim.sub', coalesce(v_original_sub, ''), true);
            raise;
    end;
    perform set_config('request.jwt.claim.sub', coalesce(v_original_sub, ''), true);

    v_public_state := private.cbr_latest_public_state(p_room_id);
    v_hand_counts := coalesce(v_public_state -> 'handCounts', '[]'::jsonb);
    v_hand_counts := jsonb_set(
        v_hand_counts,
        array[p_seat::text],
        to_jsonb(jsonb_array_length(v_response -> 'hand')),
        false
    );
    v_next_seat := case
        when coalesce(p_indirect, false) then (p_seat + 1) % v_room.max_players
        else p_seat
    end;

    select event.payload ->> 'roundId' into v_round_id
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;
    v_round_id := coalesce(v_round_id, '');

    -- Corrige o estado publico sem depender do host receber a propria
    -- transmissao. Nenhuma carta privada entra neste evento.
    v_message_id := gen_random_uuid()::text;
    perform private.cbr_publish_server_event(
        p_room_id => p_room_id,
        p_actor_id => v_room.host_id,
        p_event_type => 'PUBLIC_STATE',
        p_payload => jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_room.host_id::text,
            'roundId', v_round_id,
            'messageId', v_message_id,
            'payload', (
                v_public_state || jsonb_build_object(
                    'activeSeat', v_next_seat,
                    'handCounts', v_hand_counts
                )
            )::text
        ),
        p_recipient_seat => null
    );

    -- O host recebe apenas o aviso. As cartas continuam no schema privado e
    -- sao consultadas pela RPC host-only abaixo.
    v_message_id := gen_random_uuid()::text;
    insert into public.match_events(
        room_id, message_id, actor_id, seat, recipient_seat, event_type, payload
    ) values (
        p_room_id,
        v_message_id,
        v_actor_id,
        p_seat,
        0,
        'MORTO_TAKEN',
        jsonb_build_object(
            'type', 'MORTO_TAKEN',
            'senderId', v_actor_id::text,
            'roundId', v_round_id,
            'messageId', v_message_id,
            'payload', jsonb_build_object(
                'v', 1,
                'seat', p_seat,
                'indirect', coalesce(p_indirect, false)
            )::text
        )
    );

    return v_response;
end;
$$;

revoke all on function private.cbr_take_morto_for_member(uuid, integer, boolean) from public;

create or replace function public.online_get_remote_hand(p_room_id uuid, p_seat integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_actor_id uuid := (select auth.uid());
    v_host_id uuid;
    v_team integer;
    v_hand jsonb;
    v_team_melds jsonb;
    v_deck_size integer;
    v_mortos_left integer;
begin
    select room.host_id into v_host_id
    from public.match_rooms room
    where room.id = p_room_id
      and room.status = 'playing';
    if v_actor_id is null or v_host_id is distinct from v_actor_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;
    if p_seat <= 0 then
        raise exception 'REMOTE_SEAT_REQUIRED' using errcode = 'P0001';
    end if;

    select player.team, state.hand
    into v_team, v_hand
    from public.room_players player
    join private.match_seat_state state
      on state.room_id = player.room_id and state.seat = player.seat
    where player.room_id = p_room_id
      and player.seat = p_seat
      and state.initialized;
    if v_hand is null then
        raise exception 'PRIVATE_PLAYER_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;

    select coalesce(team_state.melds, '[]'::jsonb)
    into v_team_melds
    from private.match_team_state team_state
    where team_state.room_id = p_room_id
      and team_state.team = v_team;

    select jsonb_array_length(deck_state.deck), jsonb_array_length(deck_state.mortos)
    into v_deck_size, v_mortos_left
    from private.match_deck_state deck_state
    where deck_state.room_id = p_room_id;

    return jsonb_build_object(
        'status', 'OK',
        'seat', p_seat,
        'team', v_team,
        'hand', v_hand,
        'teamMelds', coalesce(v_team_melds, '[]'::jsonb),
        'deckSize', coalesce(v_deck_size, 0),
        'mortosLeft', coalesce(v_mortos_left, 0)
    );
end;
$$;

create or replace function public.start_online_round_idempotent(
    p_room_id uuid,
    p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor_id uuid := (select auth.uid());
    v_receipt private.match_rpc_receipts%rowtype;
    v_response jsonb;
begin
    if v_actor_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if p_request_id is null then
        raise exception 'REQUEST_ID_REQUIRED' using errcode = 'P0001';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(p_room_id::text || ':START_ROUND:' || p_request_id::text, 0)
    );

    select * into v_receipt
    from private.match_rpc_receipts receipt
    where receipt.room_id = p_room_id
      and receipt.operation_type = 'START_ROUND'
      and receipt.request_id = p_request_id;

    if found then
        if v_receipt.actor_id <> v_actor_id or v_receipt.seat <> 0 or v_receipt.arguments <> '{}'::jsonb then
            raise exception 'IDEMPOTENCY_KEY_REUSED' using errcode = 'P0001';
        end if;
        return v_receipt.response;
    end if;

    v_response := public.start_online_round(p_room_id);
    insert into private.match_rpc_receipts(
        room_id, operation_type, request_id, actor_id, seat, arguments, response
    ) values (
        p_room_id, 'START_ROUND', p_request_id, v_actor_id, 0, '{}'::jsonb, v_response
    );
    delete from private.match_rpc_receipts where created_at < now() - interval '7 days';
    return v_response;
end;
$$;

create or replace function public.online_draw_deck_card_idempotent(
    p_room_id uuid,
    p_seat integer,
    p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor_id uuid := (select auth.uid());
    v_receipt private.match_rpc_receipts%rowtype;
    v_response jsonb;
begin
    if v_actor_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if p_request_id is null then
        raise exception 'REQUEST_ID_REQUIRED' using errcode = 'P0001';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(p_room_id::text || ':DRAW_DECK:' || p_request_id::text, 0)
    );

    select * into v_receipt
    from private.match_rpc_receipts receipt
    where receipt.room_id = p_room_id
      and receipt.operation_type = 'DRAW_DECK'
      and receipt.request_id = p_request_id;

    if found then
        if v_receipt.actor_id <> v_actor_id or v_receipt.seat <> p_seat or v_receipt.arguments <> '{}'::jsonb then
            raise exception 'IDEMPOTENCY_KEY_REUSED' using errcode = 'P0001';
        end if;
        return v_receipt.response;
    end if;

    v_response := public.online_draw_deck_card(p_room_id, p_seat);
    insert into private.match_rpc_receipts(
        room_id, operation_type, request_id, actor_id, seat, arguments, response
    ) values (
        p_room_id, 'DRAW_DECK', p_request_id, v_actor_id, p_seat, '{}'::jsonb, v_response
    );
    delete from private.match_rpc_receipts where created_at < now() - interval '7 days';
    return v_response;
end;
$$;

create or replace function public.online_take_morto_idempotent(
    p_room_id uuid,
    p_seat integer,
    p_indirect boolean default false,
    p_request_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor_id uuid := (select auth.uid());
    v_arguments jsonb := jsonb_build_object('indirect', coalesce(p_indirect, false));
    v_receipt private.match_rpc_receipts%rowtype;
    v_response jsonb;
begin
    if v_actor_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if p_request_id is null then
        raise exception 'REQUEST_ID_REQUIRED' using errcode = 'P0001';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(p_room_id::text || ':TAKE_MORTO:' || p_request_id::text, 0)
    );

    select * into v_receipt
    from private.match_rpc_receipts receipt
    where receipt.room_id = p_room_id
      and receipt.operation_type = 'TAKE_MORTO'
      and receipt.request_id = p_request_id;

    if found then
        if v_receipt.actor_id <> v_actor_id or v_receipt.seat <> p_seat or v_receipt.arguments <> v_arguments then
            raise exception 'IDEMPOTENCY_KEY_REUSED' using errcode = 'P0001';
        end if;
        return v_receipt.response;
    end if;

    v_response := private.cbr_take_morto_for_member(p_room_id, p_seat, p_indirect);
    insert into private.match_rpc_receipts(
        room_id, operation_type, request_id, actor_id, seat, arguments, response
    ) values (
        p_room_id, 'TAKE_MORTO', p_request_id, v_actor_id, p_seat, v_arguments, v_response
    );
    delete from private.match_rpc_receipts where created_at < now() - interval '7 days';
    return v_response;
end;
$$;

revoke all on function public.start_online_round_idempotent(uuid, uuid) from public;
revoke all on function public.online_draw_deck_card_idempotent(uuid, integer, uuid) from public;
revoke all on function public.online_take_morto_idempotent(uuid, integer, boolean, uuid) from public;
revoke all on function public.online_get_remote_hand(uuid, integer) from public;
grant execute on function public.start_online_round_idempotent(uuid, uuid) to authenticated;
grant execute on function public.online_draw_deck_card_idempotent(uuid, integer, uuid) to authenticated;
grant execute on function public.online_take_morto_idempotent(uuid, integer, boolean, uuid) to authenticated;
grant execute on function public.online_get_remote_hand(uuid, integer) to authenticated;
