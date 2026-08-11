-- Mantem o ultimo descarte visivel quando um cliente pega o morto indireto.
--
-- O cliente confirma DISCARD e logo depois chama online_take_morto. A funcao
-- anterior corrigia mao, turno e quantidade de mortos, mas reaproveitava um
-- PUBLIC_STATE criado antes do descarte. O resultado era uma mao canonica
-- correta com o lixo visual atrasado. O host antigo ja publica a fotografia
-- completa antes de pedir o morto; por isso so completo o lixo quando a carta
-- confirmada ainda nao esta no topo.

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
    v_discard_pile jsonb;
    v_discard_card text;
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

    -- A funcao original aceita somente o host. A troca fica restrita a esta
    -- transacao e acontece depois da comprovacao do assento do solicitante.
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

    v_discard_pile := coalesce(v_public_state -> 'discardPile', '[]'::jsonb);
    if jsonb_typeof(v_discard_pile) <> 'array' then
        v_discard_pile := '[]'::jsonb;
    end if;

    if coalesce(p_indirect, false) then
        select trim(coalesce((event.payload ->> 'payload')::jsonb ->> 'card', ''))
        into v_discard_card
        from public.match_events event
        where event.room_id = p_room_id
          and event.event_type = 'DISCARD'
          and event.seat = p_seat
          and event.payload ->> 'roundId' = v_round_id
        order by event.id desc
        limit 1;

        if coalesce(v_discard_card, '') <> ''
           and (
               jsonb_array_length(v_discard_pile) = 0
               or v_discard_pile ->> (jsonb_array_length(v_discard_pile) - 1) <> v_discard_card
           ) then
            v_discard_pile := v_discard_pile || jsonb_build_array(v_discard_card);
        end if;
    end if;

    -- Nenhuma carta privada entra no estado publico. A unica carta acrescentada
    -- e o descarte que o proprio servidor acabou de aceitar como evento publico.
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
                    'handCounts', v_hand_counts,
                    'discardCount', jsonb_array_length(v_discard_pile),
                    'discardPile', v_discard_pile
                )
            )::text
        ),
        p_recipient_seat => null
    );

    -- O host recebe apenas o aviso e consulta a mao canonica pela RPC privada.
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

