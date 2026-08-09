-- link_room_to_championship pedia o UUID da sala (p_room_id), mas nenhuma
-- camada do app expunha esse UUID pra UI -- room_code sempre foi o
-- identificador visivel/manuseado pelo Kotlin (igual join_match_room ja usa
-- p_room_code, nao p_room_id). Corrige antes de escrever a tela que chamaria
-- essa funcao com um dado que o app nao tinha como fornecer.

drop function if exists public.link_room_to_championship(uuid, text);

create function public.link_room_to_championship(p_room_code text, p_championship_code text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_championship public.championships%rowtype;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select *
    into v_room
    from public.match_rooms
    where room_code = upper(trim(coalesce(p_room_code, '')))
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_room.host_id <> v_user_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;
    if v_room.status <> 'waiting' then
        raise exception 'ROOM_ALREADY_STARTED' using errcode = 'P0001';
    end if;

    select *
    into v_championship
    from public.championships
    where championships.code = upper(trim(coalesce(p_championship_code, '')));

    if not found then
        raise exception 'CHAMPIONSHIP_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_championship.status <> 'ACTIVE' then
        raise exception 'CHAMPIONSHIP_FINISHED' using errcode = 'P0001';
    end if;
    if v_championship.game_type <> v_room.game_type then
        raise exception 'CHAMPIONSHIP_GAME_TYPE_MISMATCH' using errcode = 'P0001';
    end if;
    if not exists (
        select 1
        from public.championship_participants
        where championship_id = v_championship.id
          and profile_id = v_user_id
    ) then
        raise exception 'NOT_ENROLLED' using errcode = 'P0001';
    end if;

    update public.match_rooms
    set championship_id = v_championship.id
    where id = v_room.id;
end;
$$;

revoke all on function public.link_room_to_championship(text, text) from public;
grant execute on function public.link_room_to_championship(text, text) to authenticated;
