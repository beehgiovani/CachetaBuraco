-- Banimento com prazo, aplicado pelo admin do jogo (mesmo email fixo da
-- 0049). Um ban ativo bloqueia TUDO: criar sala, entrar em sala, mandar
-- mensagem em chat (de sala e geral) e entrar em campeonato.
--
-- O ban expira sozinho (banned_until), entao nao precisa de rotina de
-- limpeza: private.cbr_is_banned so olha se existe uma linha ainda dentro
-- do prazo. Desbanir antes da hora e so apagar a linha (unban_profile).
--
-- Sem painel de admin no app por decisao do Bruno -- ban e raro o bastante
-- pra rodar a RPC direto no painel do Supabase.

create table if not exists public.profile_bans (
    profile_id uuid primary key references public.profiles(id) on delete cascade,
    reason text not null check (char_length(trim(reason)) between 3 and 500),
    banned_at timestamptz not null default now(),
    banned_until timestamptz not null,
    banned_by uuid references public.profiles(id) on delete set null,
    check (banned_until > banned_at)
);

create index if not exists idx_profile_bans_until on public.profile_bans(banned_until);

alter table public.profile_bans enable row level security;

-- O proprio banido precisa conseguir ler o proprio ban (pro app explicar por
-- que ele esta bloqueado e ate quando). Ninguem enxerga ban de terceiro --
-- nao existe lista publica de banidos, por design.
create policy "profile_bans_select_self"
on public.profile_bans for select
to authenticated
using (profile_id = (select auth.uid()));

grant select on public.profile_bans to authenticated;

-- ─── Helper central: um unico lugar que decide se alguem esta banido ──────

create or replace function private.cbr_is_banned(p_profile_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.profile_bans ban
        where ban.profile_id = p_profile_id
          and ban.banned_until > now()
    );
$$;

revoke all on function private.cbr_is_banned(uuid) from public;
grant execute on function private.cbr_is_banned(uuid) to authenticated;

-- ─── RPCs de admin: banir e desbanir ─────────────────────────────────────

create or replace function public.admin_ban_profile(
    p_profile_id uuid,
    p_reason text,
    p_days integer default 7
)
returns table (
    profile_id uuid,
    nickname text,
    reason text,
    banned_until timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_reason text := trim(coalesce(p_reason, ''));
    v_days integer := coalesce(p_days, 7);
    v_ban public.profile_bans%rowtype;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if not private.cbr_is_app_admin(v_user_id) then
        raise exception 'ADMIN_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(v_reason) not between 3 and 500 then
        raise exception 'INVALID_BAN_REASON' using errcode = 'P0001';
    end if;
    if v_days not between 1 and 3650 then
        raise exception 'INVALID_BAN_DURATION' using errcode = 'P0001';
    end if;
    if p_profile_id = v_user_id then
        raise exception 'CANNOT_BAN_SELF' using errcode = 'P0001';
    end if;
    if not exists (select 1 from public.profiles p where p.id = p_profile_id) then
        raise exception 'PROFILE_NOT_FOUND' using errcode = 'P0001';
    end if;

    -- delete + insert em vez de "on conflict (profile_id)": profile_id tambem
    -- e coluna do RETURNS TABLE desta funcao, vira variavel PL/pgSQL implicita,
    -- e a lista de colunas do ON CONFLICT nao aceita qualificar com o nome da
    -- tabela pra desambiguar (mesma armadilha ja documentada na 0034 pro
    -- join_championship -- confirmada de novo aqui, testando local).
    delete from public.profile_bans ban where ban.profile_id = p_profile_id;

    insert into public.profile_bans (profile_id, reason, banned_until, banned_by)
    values (p_profile_id, v_reason, now() + make_interval(days => v_days), v_user_id)
    returning * into v_ban;

    -- Tira o banido de qualquer sala em que ele esteja agora -- senao o ban so
    -- valeria na proxima vez que ele tentasse entrar em alguma.
    delete from public.room_players rp where rp.profile_id = p_profile_id;

    return query
    select v_ban.profile_id, prof.nickname, v_ban.reason, v_ban.banned_until
    from public.profiles prof
    where prof.id = v_ban.profile_id;
end;
$$;

revoke all on function public.admin_ban_profile(uuid, text, integer) from public;
grant execute on function public.admin_ban_profile(uuid, text, integer) to authenticated;

create or replace function public.admin_unban_profile(p_profile_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if not private.cbr_is_app_admin(v_user_id) then
        raise exception 'ADMIN_REQUIRED' using errcode = 'P0001';
    end if;

    delete from public.profile_bans ban where ban.profile_id = p_profile_id;
end;
$$;

revoke all on function public.admin_unban_profile(uuid) from public;
grant execute on function public.admin_unban_profile(uuid) to authenticated;

-- Pro app saber que esta banido e ate quando (a policy de select acima ja
-- restringe ao proprio usuario; esta RPC so evita o app precisar conhecer o
-- formato da tabela).
create or replace function public.get_my_ban()
returns table (reason text, banned_until timestamptz)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    return query
    select ban.reason, ban.banned_until
    from public.profile_bans ban
    where ban.profile_id = v_user_id
      and ban.banned_until > now();
end;
$$;

revoke all on function public.get_my_ban() from public;
grant execute on function public.get_my_ban() to authenticated;

-- ─── Bloqueio nos dois chats (policy de insert) ──────────────────────────
-- O chat geral virou tabela na 0036 (antes era Broadcast puro, que nao teria
-- RLS pra barrar), entao os dois chats sao bloqueaveis do mesmo jeito.

drop policy if exists "room_chat_messages_insert_member" on public.room_chat_messages;

create policy "room_chat_messages_insert_member"
on public.room_chat_messages for insert
to authenticated
with check (
    sender_id = (select auth.uid())
    and (select private.is_room_member(room_id))
    and not (select private.cbr_is_banned((select auth.uid())))
);

drop policy if exists "global_chat_messages_insert_self" on public.global_chat_messages;

create policy "global_chat_messages_insert_self"
on public.global_chat_messages for insert
to authenticated
with check (
    sender_id = (select auth.uid())
    and not (select private.cbr_is_banned((select auth.uid())))
);

-- ─── Bloqueio em criar sala ──────────────────────────────────────────────
-- Corpo copiado verbatim da 0031 (conferido no arquivo antes de editar) +
-- a checagem de ban logo apos a de AUTH_REQUIRED.

create or replace function public.create_match_room(
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
    if private.cbr_is_banned(v_user_id) then
        raise exception 'PROFILE_BANNED' using errcode = 'P0001';
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

revoke all on function public.create_match_room(text, text, jsonb, integer, text) from public;
grant execute on function public.create_match_room(text, text, jsonb, integer, text) to authenticated;

-- ─── Bloqueio em entrar em sala ──────────────────────────────────────────
-- Corpo copiado verbatim da 0050 (conferido no arquivo antes de editar) +
-- a checagem de ban logo apos a de AUTH_REQUIRED. Diferente do nivel de
-- sala, o ban vale tambem pra reconexao: quem foi banido no meio da partida
-- nao volta pra mesa.

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
    if private.cbr_is_banned(v_user_id) then
        raise exception 'PROFILE_BANNED' using errcode = 'P0001';
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

-- ─── Bloqueio em entrar em campeonato ────────────────────────────────────
-- Corpo copiado verbatim da 0049 (conferido no arquivo antes de editar) +
-- a checagem de ban logo apos a de AUTH_REQUIRED.

create or replace function public.join_championship(p_code text)
returns table (
    championship_id uuid,
    code text,
    name text,
    game_type text,
    status text,
    cadence text,
    level text,
    starts_at timestamptz,
    ends_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_championship public.championships%rowtype;
    v_total_matches integer;
    v_total_wins integer;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if private.cbr_is_banned(v_user_id) then
        raise exception 'PROFILE_BANNED' using errcode = 'P0001';
    end if;

    select *
    into v_championship
    from public.championships
    where championships.code = upper(trim(coalesce(p_code, '')));

    if not found then
        raise exception 'CHAMPIONSHIP_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_championship.status <> 'ACTIVE' then
        raise exception 'CHAMPIONSHIP_FINISHED' using errcode = 'P0001';
    end if;
    if v_championship.ends_at is not null and now() > v_championship.ends_at then
        raise exception 'CHAMPIONSHIP_FINISHED' using errcode = 'P0001';
    end if;

    if v_championship.level is not null then
        select coalesce(stats.total_matches, 0), coalesce(stats.total_wins, 0)
        into v_total_matches, v_total_wins
        from public.player_stats stats
        where stats.profile_id = v_user_id;

        if not found then
            v_total_matches := 0;
            v_total_wins := 0;
        end if;

        if private.cbr_player_level(v_total_matches, v_total_wins) <> v_championship.level then
            raise exception 'LEVEL_MISMATCH' using errcode = 'P0001';
        end if;
    end if;

    -- "on conflict (championship_id, profile_id)" da "column reference is
    -- ambiguous" aqui (achado real, testado localmente): o RETURNS TABLE
    -- desta funcao tem uma coluna de saida chamada championship_id, que vira
    -- variavel PL/pgSQL implicita, e a lista de colunas do ON CONFLICT nao
    -- aceita qualificar com o nome da tabela pra desambiguar. Insert simples
    -- + capturar duplicidade e o mesmo resultado idempotente sem esse problema.
    begin
        insert into public.championship_participants (championship_id, profile_id)
        values (v_championship.id, v_user_id);
    exception
        when unique_violation then
            null;
    end;

    return query
    select
        v_championship.id, v_championship.code, v_championship.name, v_championship.game_type,
        v_championship.status, v_championship.cadence, v_championship.level,
        v_championship.starts_at, v_championship.ends_at;
end;
$$;

revoke all on function public.join_championship(text) from public;
grant execute on function public.join_championship(text) to authenticated;
