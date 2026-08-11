-- Sala ganha nivel opcional (mesmo noob/mid/hard/expert calculado pela
-- 0049, nunca autodeclarado): quem tenta entrar numa sala com nivel
-- diferente do calculado leva ROOM_LEVEL_MISMATCH -- mantem as partidas
-- equilibradas sem precisar de um "kick" manual depois (o bloqueio e na
-- entrada, ninguem incompativel chega a entrar).
--
-- Fica dentro do config jsonb (campo nomeado "roomLevel", ver
-- toOnlineRoomConfigJson em SupabaseOnlineRoomDataSource.kt) em vez de virar
-- parametro novo em create_match_room -- essa RPC ja foi mexida duas vezes
-- (0001, 0031) e o comentario da 0034 already avisa pra evitar uma terceira.
-- join_match_room so ganha o corpo novo aqui; create_match_room nao muda.

create or replace function public.join_match_room(p_room_code text, p_password text default null)
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
    v_room_level text;
    v_total_matches integer;
    v_total_wins integer;
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
        -- Reconectando: quem ja estava sentado sempre pode voltar, mesmo que
        -- o nivel calculado tenha mudado no meio da partida.
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

        -- Vaga nova (nao reconexao): barra quem nao bate com o nivel da sala.
        v_room_level := nullif(upper(trim(coalesce(v_room.config ->> 'roomLevel', ''))), '');
        if v_room_level is not null then
            select coalesce(stats.total_matches, 0), coalesce(stats.total_wins, 0)
            into v_total_matches, v_total_wins
            from public.player_stats stats
            where stats.profile_id = v_user_id;

            if not found then
                v_total_matches := 0;
                v_total_wins := 0;
            end if;

            if private.cbr_player_level(v_total_matches, v_total_wins) <> v_room_level then
                raise exception 'ROOM_LEVEL_MISMATCH' using errcode = 'P0001';
            end if;
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

revoke all on function public.join_match_room(text, text) from public;
grant execute on function public.join_match_room(text, text) to authenticated;
