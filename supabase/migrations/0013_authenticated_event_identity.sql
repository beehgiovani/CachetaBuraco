-- O remetente salvo no evento precisa vir da sessao autenticada, nunca do
-- texto enviado pelo aparelho. Tambem confiro o assento declarado nos payloads
-- criticos para manter o historico e a validacao do host falando a mesma lingua.

create or replace function public.append_match_event(
    p_room_id uuid,
    p_message_id text,
    p_event_type text,
    p_payload jsonb,
    p_recipient_seat integer default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_event_type text := trim(coalesce(p_event_type, ''));
    v_seat integer;
    v_inserted integer;
    v_same_event boolean;
    v_canonical_payload jsonb;
    v_inner_payload jsonb;
    v_claimed_seat integer;
    v_host_private_types constant text[] := array[
        'GAME_START', 'SERVE_CARD', 'SERVE_MORTO', 'RECONNECT_STATE'
    ];
    v_host_public_types constant text[] := array[
        'DRAW_DECK', 'PICK_MORTO', 'PUBLIC_STATE', 'COUNT_ROUND',
        'ROUND_SUMMARY', 'NEXT_ROUND', 'RESTART_MATCH'
    ];
    v_client_types constant text[] := array[
        'CLIENT_READY', 'REQ_DRAW_DECK', 'REQ_PICK_MORTO',
        'REPLY_WIN_ROUND', 'REPLY_COUNT_ROUND', 'REQ_NEXT_ROUND',
        'REPLY_RESTART', 'REQ_RECONNECT'
    ];
    v_bidirectional_types constant text[] := array[
        'DISCARD', 'DRAW_DISCARD', 'MELD', 'WIN_ROUND'
    ];
    v_seat_payload_types constant text[] := array[
        'REQ_DRAW_DECK', 'REQ_PICK_MORTO', 'DISCARD', 'MELD',
        'WIN_ROUND', 'REPLY_WIN_ROUND', 'REPLY_COUNT_ROUND',
        'REQ_RECONNECT'
    ];
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(trim(coalesce(p_message_id, ''))) not between 1 and 100 then
        raise exception 'INVALID_MESSAGE_ID' using errcode = 'P0001';
    end if;
    if v_event_type <> all(
        v_host_private_types || v_host_public_types || v_client_types || v_bidirectional_types
    ) then
        raise exception 'UNKNOWN_EVENT_TYPE' using errcode = 'P0001';
    end if;
    if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
        raise exception 'INVALID_EVENT_ENVELOPE' using errcode = 'P0001';
    end if;
    if jsonb_typeof(p_payload -> 'payload') <> 'string' then
        raise exception 'INVALID_EVENT_PAYLOAD' using errcode = 'P0001';
    end if;
    if octet_length(p_payload::text) > 65536 then
        raise exception 'PAYLOAD_TOO_LARGE' using errcode = 'P0001';
    end if;
    if coalesce(p_payload ->> 'messageId', '') <> trim(p_message_id)
       or coalesce(p_payload ->> 'type', '') <> v_event_type then
        raise exception 'EVENT_ENVELOPE_MISMATCH' using errcode = 'P0001';
    end if;

    select player.seat
    into v_seat
    from public.room_players player
    join public.match_rooms room on room.id = player.room_id
    where player.room_id = p_room_id
      and player.profile_id = v_user_id
      and room.status in ('waiting', 'playing');

    if v_seat is null then
        raise exception 'ACTIVE_ROOM_MEMBERSHIP_REQUIRED' using errcode = 'P0001';
    end if;

    -- O senderId original nao participa da decisao nem fica gravado. O mesmo
    -- payload canonico tambem e usado para reconhecer retries idempotentes.
    v_canonical_payload := p_payload || jsonb_build_object('senderId', v_user_id::text);

    if v_event_type = any(v_seat_payload_types) then
        begin
            v_inner_payload := (v_canonical_payload ->> 'payload')::jsonb;
        exception
            when others then
                raise exception 'INVALID_EVENT_PAYLOAD' using errcode = 'P0001';
        end;

        if jsonb_typeof(v_inner_payload) <> 'object'
           or coalesce(v_inner_payload ->> 'seat', '') !~ '^[0-9]+$' then
            raise exception 'INVALID_EVENT_PAYLOAD' using errcode = 'P0001';
        end if;

        v_claimed_seat := (v_inner_payload ->> 'seat')::integer;
        if v_claimed_seat <> v_seat then
            raise exception 'EVENT_SEAT_MISMATCH' using errcode = 'P0001';
        end if;
    end if;

    if v_event_type = 'DISCARD' then
        if char_length(trim(coalesce(v_inner_payload ->> 'card', ''))) not between 1 and 80 then
            raise exception 'INVALID_EVENT_PAYLOAD' using errcode = 'P0001';
        end if;
    elsif v_event_type = 'MELD' then
        if jsonb_typeof(v_inner_payload -> 'cards') <> 'array'
           or jsonb_array_length(v_inner_payload -> 'cards') not between 1 and 64
           or exists (
               select 1
               from jsonb_array_elements(v_inner_payload -> 'cards') card
               where jsonb_typeof(card) <> 'string'
                  or char_length(trim(card #>> '{}')) not between 1 and 80
           ) then
            raise exception 'INVALID_EVENT_PAYLOAD' using errcode = 'P0001';
        end if;
    end if;

    if v_event_type = any(v_host_private_types) then
        if v_seat <> 0 then
            raise exception 'HOST_REQUIRED' using errcode = 'P0001';
        end if;
        if p_recipient_seat is null or p_recipient_seat = 0 then
            raise exception 'CLIENT_RECIPIENT_REQUIRED' using errcode = 'P0001';
        end if;
    elsif v_event_type = any(v_host_public_types) then
        if v_seat <> 0 then
            raise exception 'HOST_REQUIRED' using errcode = 'P0001';
        end if;
        if p_recipient_seat is not null then
            raise exception 'PUBLIC_EVENT_CANNOT_HAVE_RECIPIENT' using errcode = 'P0001';
        end if;
    elsif v_event_type = any(v_client_types) then
        if v_seat = 0 then
            raise exception 'CLIENT_REQUIRED' using errcode = 'P0001';
        end if;
        if p_recipient_seat is distinct from 0 then
            raise exception 'HOST_RECIPIENT_REQUIRED' using errcode = 'P0001';
        end if;
    elsif v_seat = 0 then
        if p_recipient_seat is not null then
            raise exception 'PUBLIC_EVENT_CANNOT_HAVE_RECIPIENT' using errcode = 'P0001';
        end if;
    elsif p_recipient_seat is distinct from 0 then
        raise exception 'HOST_RECIPIENT_REQUIRED' using errcode = 'P0001';
    end if;

    if p_recipient_seat is not null and not exists (
        select 1
        from public.room_players recipient
        where recipient.room_id = p_room_id
          and recipient.seat = p_recipient_seat
    ) then
        raise exception 'INVALID_RECIPIENT' using errcode = 'P0001';
    end if;

    update public.room_players player
    set connected = true,
        last_seen = now()
    where player.room_id = p_room_id
      and player.profile_id = v_user_id;

    insert into public.match_events(
        room_id,
        message_id,
        actor_id,
        seat,
        recipient_seat,
        event_type,
        payload
    ) values (
        p_room_id,
        trim(p_message_id),
        v_user_id,
        v_seat,
        p_recipient_seat,
        v_event_type,
        v_canonical_payload
    )
    on conflict (room_id, message_id) do nothing;

    get diagnostics v_inserted = row_count;

    if v_inserted = 1 and v_event_type = 'GAME_START' then
        update public.match_rooms room
        set status = 'playing',
            updated_at = now()
        where room.id = p_room_id
          and room.status = 'waiting';
    end if;

    if v_inserted = 1 then
        return true;
    end if;

    select exists (
        select 1
        from public.match_events event
        where event.room_id = p_room_id
          and event.message_id = trim(p_message_id)
          and event.actor_id = v_user_id
          and event.seat = v_seat
          and event.recipient_seat is not distinct from p_recipient_seat
          and event.event_type = v_event_type
          and event.payload = v_canonical_payload
    ) into v_same_event;

    return v_same_event;
end;
$$;

revoke all on function public.append_match_event(uuid, text, text, jsonb, integer) from public;
grant execute on function public.append_match_event(uuid, text, text, jsonb, integer) to authenticated;
