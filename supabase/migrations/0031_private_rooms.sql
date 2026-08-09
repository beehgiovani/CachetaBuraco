-- Sala privada com senha (pedido do usuario): dedicar uma partida entre duas
-- pessoas especificas sem aparecer na busca publica, e sem dar pra entrar so
-- sabendo/adivinhando o codigo. `room_password_hash` nulo = sala publica,
-- comportamento identico ao que ja existia. A senha em si nunca fica gravada
-- nem volta pro cliente -- so o hash (pgcrypto, ja habilitado desde a 0001).

alter table public.match_rooms
add column if not exists room_password_hash text;

drop policy if exists "rooms_select_joined_or_waiting" on public.match_rooms;
create policy "rooms_select_joined_or_waiting"
on public.match_rooms for select
to authenticated
using (
    (status = 'waiting' and room_password_hash is null)
    or host_id = (select auth.uid())
    or (select private.is_room_member(id))
);

-- create_match_room e join_match_room mudam de assinatura (parametro novo no
-- fim) -- "create or replace" nao troca o overload antigo automaticamente
-- quando a lista de parametros muda, entao precisa dropar antes pra nao
-- deixar as duas versoes ativas ao mesmo tempo.
drop function if exists public.create_match_room(text, text, jsonb, integer);
drop function if exists public.join_match_room(text);
drop function if exists public.list_waiting_match_rooms();

create function public.create_match_room(
    p_room_code text,
    p_game_type text,
    p_config jsonb,
    p_max_players integer,
    p_password text default null
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
    v_password_hash text;
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

    if p_password is not null and length(trim(p_password)) > 0 then
        v_password_hash := extensions.crypt(p_password, extensions.gen_salt('bf'));
    end if;

    insert into public.match_rooms(
        room_code,
        host_id,
        game_type,
        config,
        status,
        max_players,
        room_password_hash
    ) values (
        v_room_code,
        v_user_id,
        p_game_type,
        coalesce(p_config, '{}'::jsonb),
        'waiting',
        p_max_players,
        v_password_hash
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

create function public.join_match_room(p_room_code text, p_password text default null)
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

    if v_room.room_password_hash is not null
       and v_room.room_password_hash <> extensions.crypt(coalesce(p_password, ''), v_room.room_password_hash)
    then
        raise exception 'ROOM_PASSWORD_INVALID' using errcode = 'P0001';
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

create function public.list_waiting_match_rooms()
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
      and r.room_password_hash is null
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

revoke all on function public.create_match_room(text, text, jsonb, integer, text) from public;
revoke all on function public.join_match_room(text, text) from public;
revoke all on function public.list_waiting_match_rooms() from public;
grant execute on function public.create_match_room(text, text, jsonb, integer, text) to authenticated;
grant execute on function public.join_match_room(text, text) to authenticated;
grant execute on function public.list_waiting_match_rooms() to authenticated;
