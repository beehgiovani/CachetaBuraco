-- Carteado BR - salas online atomicas, RLS sem recursao e Realtime.

create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create or replace function private.is_room_member(p_room_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.room_players rp
        where rp.room_id = p_room_id
          and rp.profile_id = (select auth.uid())
    );
$$;

create or replace function private.is_room_host(p_room_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.match_rooms r
        where r.id = p_room_id
          and r.host_id = (select auth.uid())
    );
$$;

create or replace function private.current_room_seat(p_room_id uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
    select rp.seat
    from public.room_players rp
    where rp.room_id = p_room_id
      and rp.profile_id = (select auth.uid())
    limit 1;
$$;

revoke all on function private.is_room_member(uuid) from public;
revoke all on function private.is_room_host(uuid) from public;
revoke all on function private.current_room_seat(uuid) from public;
grant execute on function private.is_room_member(uuid) to authenticated;
grant execute on function private.is_room_host(uuid) to authenticated;
grant execute on function private.current_room_seat(uuid) to authenticated;

-- A politica anterior consultava room_players dentro da propria politica e
-- podia causar "infinite recursion detected". Os helpers acima fazem a mesma
-- verificacao com uma funcao fechada e sem expor as maos dos jogadores.
drop policy if exists "rooms_select_joined_or_waiting" on public.match_rooms;
create policy "rooms_select_joined_or_waiting"
on public.match_rooms for select
to authenticated
using (
    status = 'waiting'
    or host_id = (select auth.uid())
    or (select private.is_room_member(id))
);

drop policy if exists "room_players_select_same_room" on public.room_players;
create policy "room_players_select_same_room"
on public.room_players for select
to authenticated
using (
    profile_id = (select auth.uid())
    or (select private.is_room_member(room_id))
);

drop policy if exists "room_players_insert_self" on public.room_players;
drop policy if exists "room_players_update_self_or_host" on public.room_players;

drop policy if exists "events_select_room_players" on public.match_events;
alter table public.match_events
add column if not exists recipient_seat integer check (recipient_seat between 0 and 3);

create policy "events_select_room_players"
on public.match_events for select
to authenticated
using (
    (select private.is_room_member(room_id))
    and (
        recipient_seat is null
        or actor_id = (select auth.uid())
        or recipient_seat = (select private.current_room_seat(room_id))
    )
);

drop policy if exists "events_insert_room_players" on public.match_events;

drop policy if exists "results_select_room_players" on public.match_results;
create policy "results_select_room_players"
on public.match_results for select
to authenticated
using ((select private.is_room_member(room_id)));

-- O app so recebe leitura das tabelas da partida. Escritas sensiveis passam
-- pelas funcoes abaixo, que escolhem assento e actor_id no servidor.
revoke all on public.player_stats from anon, authenticated;
revoke all on public.match_rooms from anon, authenticated;
revoke all on public.room_players from anon, authenticated;
revoke all on public.match_events from anon, authenticated;
revoke all on public.match_results from anon, authenticated;

grant select on public.player_stats to authenticated;
grant select on public.match_rooms to authenticated;
grant select on public.room_players to authenticated;
grant select on public.match_events to authenticated;
grant select on public.match_results to authenticated;
grant select, insert, update on public.profiles to authenticated;

create or replace function private.initialize_player_stats()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.player_stats(profile_id)
    values (new.id)
    on conflict (profile_id) do nothing;
    return new;
end;
$$;

drop trigger if exists profiles_initialize_stats on public.profiles;
create trigger profiles_initialize_stats
after insert on public.profiles
for each row execute function private.initialize_player_stats();

insert into public.player_stats(profile_id)
select p.id
from public.profiles p
on conflict (profile_id) do nothing;

create or replace function private.touch_room_after_player_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room_id uuid := coalesce(new.room_id, old.room_id);
begin
    update public.match_rooms
    set updated_at = now()
    where id = v_room_id;
    return coalesce(new, old);
end;
$$;

drop trigger if exists room_players_touch_room on public.room_players;
create trigger room_players_touch_room
after insert or update or delete on public.room_players
for each row execute function private.touch_room_after_player_change();

create or replace function public.create_match_room(
    p_room_code text,
    p_game_type text,
    p_config jsonb,
    p_max_players integer
)
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
    v_room_code text := upper(trim(p_room_code));
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if v_room_code !~ '^[A-Z0-9]{4,8}$' then
        raise exception 'INVALID_ROOM_CODE' using errcode = 'P0001';
    end if;
    if p_game_type not in ('CACHETA', 'BURACO', 'TRANCA') then
        raise exception 'INVALID_GAME_TYPE' using errcode = 'P0001';
    end if;
    if p_max_players not in (2, 4) then
        raise exception 'INVALID_PLAYER_COUNT' using errcode = 'P0001';
    end if;

    insert into public.match_rooms(
        room_code,
        host_id,
        game_type,
        config,
        status,
        max_players
    ) values (
        v_room_code,
        v_user_id,
        p_game_type,
        coalesce(p_config, '{}'::jsonb),
        'waiting',
        p_max_players
    ) returning * into v_room;

    insert into public.room_players(room_id, profile_id, seat, team, connected)
    values (v_room.id, v_user_id, 0, 0, true);

    return query
    select
        v_room.id,
        v_room.room_code,
        v_room.host_id,
        v_room.config,
        v_room.status,
        1,
        0;
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
    if v_room.status <> 'waiting' then
        raise exception 'ROOM_NOT_WAITING' using errcode = 'P0001';
    end if;

    select rp.seat
    into v_seat
    from public.room_players rp
    where rp.room_id = v_room.id
      and rp.profile_id = v_user_id;

    if v_seat is not null then
        update public.room_players rp
        set connected = true
        where rp.room_id = v_room.id
          and rp.profile_id = v_user_id;
    else
        select count(*)::integer
        into v_connected
        from public.room_players rp
        where rp.room_id = v_room.id
          and rp.connected;

        if v_connected >= v_room.max_players then
            raise exception 'ROOM_FULL' using errcode = 'P0001';
        end if;

        select candidate
        into v_seat
        from generate_series(0, v_room.max_players - 1) candidate
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
        insert into public.room_players(room_id, profile_id, seat, team, connected)
        values (v_room.id, v_user_id, v_seat, v_team, true);
    end if;

    select count(*)::integer
    into v_connected
    from public.room_players rp
    where rp.room_id = v_room.id
      and rp.connected;

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
        count(rp.profile_id) filter (where rp.connected)::integer
    from public.match_rooms r
    left join public.room_players rp on rp.room_id = r.id
    where r.status = 'waiting'
    group by r.id
    having count(rp.profile_id) filter (where rp.connected) < r.max_players
    order by r.created_at desc
    limit 50;
end;
$$;

create or replace function public.leave_match_room(p_room_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if (select auth.uid()) is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    update public.room_players rp
    set connected = false
    where rp.room_id = p_room_id
      and rp.profile_id = (select auth.uid());
end;
$$;

create or replace function public.close_match_room(p_room_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not (select private.is_room_host(p_room_id)) then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;

    update public.match_rooms r
    set status = case when r.status = 'finished' then r.status else 'cancelled' end,
        updated_at = now()
    where r.id = p_room_id;

    update public.room_players rp
    set connected = false
    where rp.room_id = p_room_id;
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
          and recipient.connected
    ) then
        raise exception 'INVALID_RECIPIENT' using errcode = 'P0001';
    end if;

    select rp.seat
    into v_seat
    from public.room_players rp
    join public.match_rooms r on r.id = rp.room_id
    where rp.room_id = p_room_id
      and rp.profile_id = v_user_id
      and rp.connected
      and r.status in ('waiting', 'playing');

    if v_seat is null then
        raise exception 'ACTIVE_ROOM_MEMBERSHIP_REQUIRED' using errcode = 'P0001';
    end if;

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

    return v_inserted = 1;
end;
$$;

revoke all on function public.create_match_room(text, text, jsonb, integer) from public;
revoke all on function public.join_match_room(text) from public;
revoke all on function public.list_waiting_match_rooms() from public;
revoke all on function public.leave_match_room(uuid) from public;
revoke all on function public.close_match_room(uuid) from public;
revoke all on function public.append_match_event(uuid, text, text, jsonb, integer) from public;

grant execute on function public.create_match_room(text, text, jsonb, integer) to authenticated;
grant execute on function public.join_match_room(text) to authenticated;
grant execute on function public.list_waiting_match_rooms() to authenticated;
grant execute on function public.leave_match_room(uuid) to authenticated;
grant execute on function public.close_match_room(uuid) to authenticated;
grant execute on function public.append_match_event(uuid, text, text, jsonb, integer) to authenticated;

-- Novas tabelas nao entram automaticamente na publicacao Realtime. O bloco e
-- idempotente para funcionar tanto no projeto remoto quanto no ambiente local.
do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        if not exists (
            select 1 from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'match_events'
        ) then
            execute 'alter publication supabase_realtime add table public.match_events';
        end if;

        if not exists (
            select 1 from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'room_players'
        ) then
            execute 'alter publication supabase_realtime add table public.room_players';
        end if;

        if not exists (
            select 1 from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'match_rooms'
        ) then
            execute 'alter publication supabase_realtime add table public.match_rooms';
        end if;
    end if;
end;
$$;
