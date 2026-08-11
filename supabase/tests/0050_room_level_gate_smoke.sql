begin;

do $$
declare
    v_host_id uuid := '50505050-5050-5050-5050-505050505001';
    v_noob_id uuid := '50505050-5050-5050-5050-505050505002';
    v_expert_id uuid := '50505050-5050-5050-5050-505050505003';
    v_rejected boolean := false;
    v_seat integer;
begin
    insert into auth.users(
        id, aud, role, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_host_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Host50"}'::jsonb, now(), now(), false, true),
    (v_noob_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Noob50"}'::jsonb, now(), now(), false, true),
    (v_expert_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Expert50"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values
    (v_host_id, 'Host50'),
    (v_noob_id, 'Noob50'),
    (v_expert_id, 'Expert50');
    insert into public.player_stats(profile_id, total_matches, total_wins)
    values (v_expert_id, 60, 40)
    on conflict (profile_id) do update
    set total_matches = excluded.total_matches, total_wins = excluded.total_wins;

    perform set_config('request.jwt.claim.sub', v_host_id::text, true);
    perform created.room_id from public.create_match_room(
        'SMK0501', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', '', 'roomLevel', 'NOOB'),
        2, null
    ) created;

    -- Jogador iniciante (0 partidas) entra sem problema.
    perform set_config('request.jwt.claim.sub', v_noob_id::text, true);
    select joined.seat into v_seat
    from public.join_match_room('SMK0501', null)
    joined(room_id, room_code, host_id, config, status, connected_players, seat);
    if v_seat is null then
        raise exception 'NOOB_SHOULD_JOIN_NOOB_ROOM';
    end if;
    raise notice 'ROOM_LEVEL_MATCH_OK';

    -- Jogador expert (60 partidas, 40 vitorias) e barrado.
    perform set_config('request.jwt.claim.sub', v_expert_id::text, true);
    begin
        perform public.join_match_room('SMK0501', null);
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'EXPERT_SHOULD_NOT_JOIN_NOOB_ROOM';
    end if;
    raise notice 'ROOM_LEVEL_MISMATCH_OK';
end;
$$;

rollback;
