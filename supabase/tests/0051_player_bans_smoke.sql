begin;

-- Parte 1: so o admin bane; ban derruba o banido da sala em que estava.
do $$
declare
    v_admin_id uuid := '51515151-5151-5151-5151-515151515001';
    v_troll_id uuid := '51515151-5151-5151-5151-515151515002';
    v_other_id uuid := '51515151-5151-5151-5151-515151515003';
    v_rejected boolean := false;
    v_until timestamptz;
    v_still_seated integer;
begin
    insert into auth.users(
        id, aud, role, email, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_admin_id, 'authenticated', 'authenticated', 'brunogp.corretor@gmail.com',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Admin51"}'::jsonb, now(), now(), false, true),
    (v_troll_id, 'authenticated', 'authenticated', 'troll@example.com',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Troll51"}'::jsonb, now(), now(), false, true),
    (v_other_id, 'authenticated', 'authenticated', 'outro@example.com',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Outro51"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values
    (v_admin_id, 'Admin51'),
    (v_troll_id, 'Troll51'),
    (v_other_id, 'Outro51');

    -- Nao-admin nao consegue banir ninguem.
    perform set_config('request.jwt.claim.sub', v_other_id::text, true);
    begin
        perform public.admin_ban_profile(v_troll_id, 'tentativa indevida', 7);
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'BAN_SHOULD_REJECT_NON_ADMIN';
    end if;
    raise notice 'BAN_ADMIN_REQUIRED_OK';

    -- O troll cria uma sala e senta nela antes de ser banido.
    perform set_config('request.jwt.claim.sub', v_troll_id::text, true);
    perform created.room_id from public.create_match_room(
        'SMK0511', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;

    -- Admin bane por 7 dias.
    perform set_config('request.jwt.claim.sub', v_admin_id::text, true);
    select banned.banned_until into v_until
    from public.admin_ban_profile(v_troll_id, 'ofensa no chat', 7)
    banned(profile_id, nickname, reason, banned_until);
    if v_until is null or v_until <= now() then
        raise exception 'BAN_SHOULD_SET_FUTURE_EXPIRY';
    end if;
    if v_until > now() + interval '8 days' then
        raise exception 'BAN_EXPIRY_TOO_FAR got=%', v_until;
    end if;
    raise notice 'BAN_ADMIN_OK';

    -- Ban tira o banido de qualquer sala em que ele estava.
    select count(*)::integer into v_still_seated
    from public.room_players rp where rp.profile_id = v_troll_id;
    if v_still_seated <> 0 then
        raise exception 'BAN_SHOULD_REMOVE_FROM_ROOMS got=%', v_still_seated;
    end if;
    raise notice 'BAN_EVICTS_FROM_ROOMS_OK';
end;
$$;

-- Parte 2: banido nao cria sala, nao entra em sala, nao fala nos dois chats.
do $$
declare
    v_admin_id uuid := '51515151-5151-5151-5151-515151515001';
    v_troll_id uuid := '51515151-5151-5151-5151-515151515002';
    v_other_id uuid := '51515151-5151-5151-5151-515151515003';
    v_room_id uuid;
    v_rejected boolean;
begin
    -- Um jogador limpo abre uma sala pro banido tentar entrar.
    perform set_config('request.jwt.claim.sub', v_other_id::text, true);
    select created.room_id into v_room_id
    from public.create_match_room(
        'SMK0512', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;

    perform set_config('request.jwt.claim.sub', v_troll_id::text, true);

    -- Nao cria sala.
    v_rejected := false;
    begin
        perform public.create_match_room(
            'SMK0513', 'CACHETA',
            jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
            2, null
        );
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'BANNED_SHOULD_NOT_CREATE_ROOM';
    end if;
    raise notice 'BANNED_CANNOT_CREATE_ROOM_OK';

    -- Nao entra em sala.
    v_rejected := false;
    begin
        perform public.join_match_room('SMK0512', null);
    exception
        when others then
            v_rejected := true;
    end;
    if not v_rejected then
        raise exception 'BANNED_SHOULD_NOT_JOIN_ROOM';
    end if;
    raise notice 'BANNED_CANNOT_JOIN_ROOM_OK';

    -- Nao fala no chat geral (RLS de insert). Precisa rodar como
    -- "authenticated": o role postgres tem rolbypassrls, entao um insert
    -- direto aqui passaria batido pela policy e o teste daria falso-positivo
    -- (achado real -- a primeira versao deste teste passou sem exercitar
    -- policy nenhuma).
    v_rejected := false;
    begin
        set local role authenticated;
        insert into public.global_chat_messages(sender_id, sender_name, body)
        values (v_troll_id, 'Troll51', 'mensagem de banido');
    exception
        when others then
            v_rejected := true;
    end;
    reset role;
    if not v_rejected then
        raise exception 'BANNED_SHOULD_NOT_POST_GLOBAL_CHAT';
    end if;
    raise notice 'BANNED_CANNOT_POST_GLOBAL_CHAT_OK';

    -- get_my_ban devolve o motivo pro proprio banido.
    if not exists (
        select 1 from public.get_my_ban() mine(reason, banned_until)
        where mine.reason = 'ofensa no chat'
    ) then
        raise exception 'BANNED_SHOULD_SEE_OWN_BAN';
    end if;
    raise notice 'BANNED_SEES_OWN_BAN_OK';

    -- Contraprova: jogador limpo continua falando normalmente. Sem isso o
    -- teste acima passaria ate se a policy bloqueasse todo mundo.
    perform set_config('request.jwt.claim.sub', v_other_id::text, true);
    set local role authenticated;
    insert into public.global_chat_messages(sender_id, sender_name, body)
    values (v_other_id, 'Outro51', 'mensagem normal');
    reset role;
    raise notice 'CLEAN_PLAYER_CAN_POST_GLOBAL_CHAT_OK';
end;
$$;

-- Parte 3: desbanir devolve o acesso; ban expirado nao bloqueia mais.
do $$
declare
    v_admin_id uuid := '51515151-5151-5151-5151-515151515001';
    v_troll_id uuid := '51515151-5151-5151-5151-515151515002';
    v_expired_id uuid := '51515151-5151-5151-5151-515151515004';
begin
    perform set_config('request.jwt.claim.sub', v_admin_id::text, true);
    perform public.admin_unban_profile(v_troll_id);

    perform set_config('request.jwt.claim.sub', v_troll_id::text, true);
    perform created.room_id from public.create_match_room(
        'SMK0514', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;
    raise notice 'UNBAN_RESTORES_ACCESS_OK';

    -- Ban ja vencido (banned_until no passado) nao pode bloquear nada.
    insert into auth.users(
        id, aud, role, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at, is_sso_user, is_anonymous
    ) values
    (v_expired_id, 'authenticated', 'authenticated',
     '{"provider":"anonymous","providers":["anonymous"]}'::jsonb,
     '{"nickname":"Expirado51"}'::jsonb, now(), now(), false, true);
    insert into public.profiles(id, nickname) values (v_expired_id, 'Expirado51');
    insert into public.profile_bans(profile_id, reason, banned_at, banned_until)
    values (v_expired_id, 'ban antigo', now() - interval '30 days', now() - interval '1 day');

    perform set_config('request.jwt.claim.sub', v_expired_id::text, true);
    perform created.room_id from public.create_match_room(
        'SMK0515', 'CACHETA',
        jsonb_build_object('cardsPerPlayer', 9, 'serialized', ''),
        2, null
    ) created;
    raise notice 'EXPIRED_BAN_DOES_NOT_BLOCK_OK';
end;
$$;

rollback;
