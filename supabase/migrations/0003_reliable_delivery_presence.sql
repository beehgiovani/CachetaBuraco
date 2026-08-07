-- Carteado BR - confirmacao idempotente de eventos e presenca com expiracao.
-- Esta migracao preserva os assentos durante partidas e libera vagas abandonadas
-- somente enquanto a sala ainda esta esperando jogadores.

alter table public.room_players
add column if not exists last_seen timestamptz not null default now();

update public.room_players
set last_seen = coalesce(last_seen, joined_at, now());

create index if not exists idx_room_players_active_presence
on public.room_players (room_id, connected, last_seen desc);

create or replace function public.touch_match_room_presence(p_room_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_touched integer;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    update public.room_players rp
    set connected = true,
        last_seen = now()
    from public.match_rooms r
    where rp.room_id = p_room_id
      and rp.profile_id = v_user_id
      and r.id = rp.room_id
      and r.status in ('waiting', 'playing');

    get diagnostics v_touched = row_count;
    if v_touched <> 1 then
        return false;
    end if;

    -- Cada jogador ativo ajuda a retirar da presenca quem fechou o app sem sair.
    update public.room_players rp
    set connected = false
    where rp.room_id = p_room_id
      and rp.connected
      and rp.profile_id <> v_user_id
      and rp.last_seen < now() - interval '30 seconds';

    return true;
end;
$$;

create or replace function public.join_match_room(p_room_code text)
returns table (
    room_id uuid,
    room_code text,
    host_id uuid,
    config jsonb,
    status text,
    connected_players integer,
    seat integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_seat integer;
    v_team integer;
    v_connected integer;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select r.*
    into v_room
    from public.match_rooms r
    where r.room_code = upper(trim(p_room_code))
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    update public.room_players rp
    set connected = false
    where rp.room_id = v_room.id
      and rp.connected
      and rp.last_seen < now() - interval '30 seconds';

    select rp.seat
    into v_seat
    from public.room_players rp
    where rp.room_id = v_room.id
      and rp.profile_id = v_user_id;

    if v_seat is not null then
        if v_room.status not in ('waiting', 'playing') then
            raise exception 'ROOM_CLOSED' using errcode = 'P0001';
        end if;

        update public.room_players rp
        set connected = true,
            last_seen = now()
        where rp.room_id = v_room.id
          and rp.profile_id = v_user_id;
    else
        if v_room.status <> 'waiting' then
            raise exception 'ROOM_NOT_WAITING' using errcode = 'P0001';
        end if;
        if not exists (
            select 1
            from public.room_players host_presence
            where host_presence.room_id = v_room.id
              and host_presence.profile_id = v_room.host_id
              and host_presence.connected
              and host_presence.last_seen >= now() - interval '30 seconds'
        ) then
            raise exception 'ROOM_HOST_OFFLINE' using errcode = 'P0001';
        end if;

        -- Em uma sala ainda nao iniciada, uma vaga abandonada pode ser reutilizada.
        delete from public.room_players rp
        where rp.room_id = v_room.id
          and rp.seat <> 0
          and not rp.connected;

        select count(*)::integer
        into v_connected
        from public.room_players rp
        where rp.room_id = v_room.id
          and rp.connected
          and rp.last_seen >= now() - interval '30 seconds';

        if v_connected >= v_room.max_players then
            raise exception 'ROOM_FULL' using errcode = 'P0001';
        end if;

        select candidate
        into v_seat
        from generate_series(1, v_room.max_players - 1) candidate
        where not exists (
            select 1
            from public.room_players rp
            where rp.room_id = v_room.id
              and rp.seat = candidate
        )
        order by candidate
        limit 1;

        if v_seat is null then
            raise exception 'ROOM_FULL' using errcode = 'P0001';
        end if;

        v_team := case when v_room.max_players = 4 then v_seat % 2 else v_seat end;
        insert into public.room_players(
            room_id,
            profile_id,
            seat,
            team,
            connected,
            last_seen
        ) values (
            v_room.id,
            v_user_id,
            v_seat,
            v_team,
            true,
            now()
        );
    end if;

    select count(*)::integer
    into v_connected
    from public.room_players rp
    where rp.room_id = v_room.id
      and rp.connected
      and rp.last_seen >= now() - interval '30 seconds';

    return query
    select
        v_room.id,
        v_room.room_code,
        v_room.host_id,
        v_room.config,
        v_room.status,
        v_connected,
        v_seat;
end;
$$;

create or replace function public.list_waiting_match_rooms()
returns table (
    room_id uuid,
    room_code text,
    host_id uuid,
    config jsonb,
    status text,
    connected_players integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if (select auth.uid()) is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    return query
    select
        r.id,
        r.room_code,
        r.host_id,
        r.config,
        r.status,
        count(rp.profile_id) filter (
            where rp.connected
              and rp.last_seen >= now() - interval '30 seconds'
        )::integer
    from public.match_rooms r
    left join public.room_players rp on rp.room_id = r.id
    where r.status = 'waiting'
      and exists (
          select 1
          from public.room_players host_presence
          where host_presence.room_id = r.id
            and host_presence.profile_id = r.host_id
            and host_presence.connected
            and host_presence.last_seen >= now() - interval '30 seconds'
      )
    group by r.id
    having count(rp.profile_id) filter (
        where rp.connected
          and rp.last_seen >= now() - interval '30 seconds'
    ) < r.max_players
    order by r.created_at desc
    limit 50;
end;
$$;

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
    v_seat integer;
    v_inserted integer;
    v_same_event boolean;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(trim(p_message_id)) not between 1 and 100 then
        raise exception 'INVALID_MESSAGE_ID' using errcode = 'P0001';
    end if;
    if char_length(trim(p_event_type)) not between 1 and 80 then
        raise exception 'INVALID_EVENT_TYPE' using errcode = 'P0001';
    end if;
    if octet_length(coalesce(p_payload, '{}'::jsonb)::text) > 65536 then
        raise exception 'PAYLOAD_TOO_LARGE' using errcode = 'P0001';
    end if;
    if coalesce(p_payload ->> 'messageId', '') <> trim(p_message_id)
       or coalesce(p_payload ->> 'type', '') <> trim(p_event_type) then
        raise exception 'EVENT_ENVELOPE_MISMATCH' using errcode = 'P0001';
    end if;
    if p_event_type in ('GAME_START', 'SERVE_CARD', 'SERVE_MORTO', 'RECONNECT_STATE')
       and p_recipient_seat is null then
        raise exception 'RECIPIENT_REQUIRED' using errcode = 'P0001';
    end if;
    if p_event_type in (
        'GAME_START',
        'SERVE_CARD',
        'SERVE_MORTO',
        'PUBLIC_STATE',
        'ROUND_SUMMARY',
        'NEXT_ROUND',
        'RECONNECT_STATE'
    ) and not (select private.is_room_host(p_room_id)) then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;
    if p_recipient_seat is not null and not exists (
        select 1
        from public.room_players recipient
        where recipient.room_id = p_room_id
          and recipient.seat = p_recipient_seat
    ) then
        raise exception 'INVALID_RECIPIENT' using errcode = 'P0001';
    end if;

    select rp.seat
    into v_seat
    from public.room_players rp
    join public.match_rooms r on r.id = rp.room_id
    where rp.room_id = p_room_id
      and rp.profile_id = v_user_id
      and r.status in ('waiting', 'playing');

    if v_seat is null then
        raise exception 'ACTIVE_ROOM_MEMBERSHIP_REQUIRED' using errcode = 'P0001';
    end if;

    update public.room_players rp
    set connected = true,
        last_seen = now()
    where rp.room_id = p_room_id
      and rp.profile_id = v_user_id;

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
        trim(p_event_type),
        coalesce(p_payload, '{}'::jsonb)
    )
    on conflict (room_id, message_id) do nothing;

    get diagnostics v_inserted = row_count;

    if v_inserted = 1 and p_event_type = 'GAME_START' then
        update public.match_rooms r
        set status = 'playing',
            updated_at = now()
        where r.id = p_room_id
          and r.status = 'waiting';
    end if;

    if v_inserted = 1 then
        return true;
    end if;

    -- Uma resposta pode se perder depois do INSERT. O mesmo envelope recebe ACK;
    -- colisao de messageId com qualquer conteudo diferente continua recusada.
    select exists (
        select 1
        from public.match_events event
        where event.room_id = p_room_id
          and event.message_id = trim(p_message_id)
          and event.actor_id = v_user_id
          and event.seat = v_seat
          and event.recipient_seat is not distinct from p_recipient_seat
          and event.event_type = trim(p_event_type)
          and event.payload = coalesce(p_payload, '{}'::jsonb)
    ) into v_same_event;

    return v_same_event;
end;
$$;

revoke all on function public.touch_match_room_presence(uuid) from public;
revoke all on function public.join_match_room(text) from public;
revoke all on function public.list_waiting_match_rooms() from public;
revoke all on function public.append_match_event(uuid, text, text, jsonb, integer) from public;

grant execute on function public.touch_match_room_presence(uuid) to authenticated;
grant execute on function public.join_match_room(text) to authenticated;
grant execute on function public.list_waiting_match_rooms() to authenticated;
grant execute on function public.append_match_event(uuid, text, text, jsonb, integer) to authenticated;
