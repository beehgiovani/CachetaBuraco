-- Chat de sala (pedido do usuario): mensagens somem quando a partida encerra,
-- pra nao acumular historico no banco pra sempre feito o restante da tabela
-- de eventos. Tabela dedicada em vez de reaproveitar match_events -- o
-- append_match_event exige que o tipo do evento caiba numa das 3 listas de
-- direcao (host-privado/host-publico/cliente-pro-host), nenhuma delas cobre
-- "qualquer assento manda pra todo mundo", que e o caso do chat. Mexer
-- nessa RPC critica so pra isso seria risco desnecessario.

create table if not exists public.room_chat_messages (
    id bigserial primary key,
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    sender_id uuid references public.profiles(id) on delete set null,
    sender_seat integer check (sender_seat between 0 and 3),
    body text not null check (char_length(trim(body)) between 1 and 500),
    created_at timestamptz not null default now()
);

create index if not exists idx_room_chat_messages_room
on public.room_chat_messages (room_id, created_at);

alter table public.room_chat_messages enable row level security;

grant select, insert on public.room_chat_messages to authenticated;

create policy "room_chat_messages_select_member"
on public.room_chat_messages for select
to authenticated
using ((select private.is_room_member(room_id)));

create policy "room_chat_messages_insert_member"
on public.room_chat_messages for insert
to authenticated
with check (
    sender_id = (select auth.uid())
    and (select private.is_room_member(room_id))
);

alter publication supabase_realtime add table public.room_chat_messages;

-- Limpeza: as duas unicas formas de uma sala "encerrar" hoje sao o host
-- cancelar (close_match_room) ou a partida terminar de verdade (complete_match).
-- Nenhuma delas jamais apaga a linha de match_rooms (fica so com o status
-- mudado), entao a limpeza do chat precisa ser explicita nos dois lugares.

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

    delete from public.room_chat_messages where room_id = p_room_id;
end;
$$;

create or replace function public.complete_match(
    p_room_id uuid,
    p_result_key text,
    p_winner_team integer,
    p_scores jsonb,
    p_breakdown jsonb
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_existing public.match_results%rowtype;
    v_winner_profile_id uuid;
    v_player_count integer;
    v_start_event_id bigint;
    v_result_event public.match_events%rowtype;
    v_summary jsonb;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;
    if char_length(trim(coalesce(p_result_key, ''))) not between 1 and 100 then
        raise exception 'INVALID_RESULT_KEY' using errcode = 'P0001';
    end if;
    if p_winner_team not in (0, 1) then
        raise exception 'INVALID_WINNER_TEAM' using errcode = 'P0001';
    end if;
    if jsonb_typeof(coalesce(p_scores, '{}'::jsonb)) is distinct from 'object'
       or jsonb_typeof(p_scores -> 'teamScores') is distinct from 'array'
       or jsonb_array_length(p_scores -> 'teamScores') <> 2
       or exists (
           select 1
           from jsonb_array_elements(p_scores -> 'teamScores') score
           where jsonb_typeof(score) <> 'number'
       ) then
        raise exception 'INVALID_SCORES' using errcode = 'P0001';
    end if;
    if jsonb_typeof(coalesce(p_breakdown, '{}'::jsonb)) is distinct from 'object'
       or octet_length(coalesce(p_breakdown, '{}'::jsonb)::text) > 65536 then
        raise exception 'INVALID_BREAKDOWN' using errcode = 'P0001';
    end if;

    select room.*
    into v_room
    from public.match_rooms room
    where room.id = p_room_id
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_room.host_id <> v_user_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;
    if v_room.status not in ('playing', 'finished') then
        raise exception 'MATCH_NOT_STARTED' using errcode = 'P0001';
    end if;

    select count(*)
    into v_player_count
    from public.room_players player
    where player.room_id = p_room_id;

    if v_player_count <> v_room.max_players then
        raise exception 'INCOMPLETE_ROOM' using errcode = 'P0001';
    end if;
    if not exists (
        select 1
        from public.room_players player
        where player.room_id = p_room_id
          and player.team = p_winner_team
    ) then
        raise exception 'WINNER_TEAM_NOT_IN_ROOM' using errcode = 'P0001';
    end if;

    select event.*
    into v_result_event
    from public.match_events event
    where event.room_id = p_room_id
      and event.message_id = trim(p_result_key)
      and event.event_type = 'ROUND_SUMMARY'
      and event.actor_id = v_user_id;

    if not found then
        raise exception 'RESULT_EVENT_REQUIRED' using errcode = 'P0001';
    end if;

    begin
        v_summary := (v_result_event.payload ->> 'payload')::jsonb;
        if jsonb_typeof(v_summary) is distinct from 'object'
           or v_summary -> 'isMatchOver' is distinct from 'true'::jsonb
           or (v_summary ->> 'winnerTeam')::integer <> p_winner_team
           or v_summary -> 'teamScores' is distinct from p_scores -> 'teamScores'
           or coalesce(v_summary ->> 'breakdown', '')
              <> coalesce(p_breakdown ->> 'text', '') then
            raise exception 'RESULT_EVENT_MISMATCH' using errcode = 'P0001';
        end if;
    exception
        when others then
            raise exception 'INVALID_RESULT_EVENT' using errcode = 'P0001';
    end;

    select max(event.id)
    into v_start_event_id
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'GAME_START'
      and event.id < v_result_event.id;

    if v_start_event_id is null then
        raise exception 'GAME_START_REQUIRED' using errcode = 'P0001';
    end if;

    select result.*
    into v_existing
    from public.match_results result
    where result.room_id = p_room_id
      and result.start_event_id = v_start_event_id;

    if found then
        if v_existing.result_key = trim(p_result_key)
           and v_existing.winner_team = p_winner_team
           and v_existing.scores = coalesce(p_scores, '{}'::jsonb)
           and v_existing.breakdown = coalesce(p_breakdown, '{}'::jsonb) then
            return true;
        end if;
        raise exception 'MATCH_RESULT_CONFLICT' using errcode = 'P0001';
    end if;

    select case
        when count(*) = 1 then (array_agg(player.profile_id order by player.seat))[1]
        else null
    end
    into v_winner_profile_id
    from public.room_players player
    where player.room_id = p_room_id
      and player.team = p_winner_team;

    insert into public.match_results(
        room_id,
        result_key,
        start_event_id,
        winner_team,
        winner_profile_id,
        scores,
        breakdown
    ) values (
        p_room_id,
        trim(p_result_key),
        v_start_event_id,
        p_winner_team,
        v_winner_profile_id,
        coalesce(p_scores, '{}'::jsonb),
        coalesce(p_breakdown, '{}'::jsonb)
    );

    update public.player_stats stats
    set total_matches = stats.total_matches + 1,
        total_wins = stats.total_wins + case when player.team = p_winner_team then 1 else 0 end,
        cacheta_wins = stats.cacheta_wins + case
            when player.team = p_winner_team and v_room.game_type = 'CACHETA' then 1 else 0 end,
        buraco_wins = stats.buraco_wins + case
            when player.team = p_winner_team and v_room.game_type = 'BURACO' then 1 else 0 end,
        tranca_wins = stats.tranca_wins + case
            when player.team = p_winner_team and v_room.game_type = 'TRANCA' then 1 else 0 end,
        best_streak = greatest(
            stats.best_streak,
            case when player.team = p_winner_team then stats.current_streak + 1 else stats.best_streak end
        ),
        current_streak = case
            when player.team = p_winner_team then stats.current_streak + 1 else 0 end,
        xp = stats.xp + case when player.team = p_winner_team then 100 else 25 end,
        updated_at = now()
    from public.room_players player
    where player.room_id = p_room_id
      and player.profile_id = stats.profile_id;

    update public.match_rooms room
    set updated_at = now()
    where room.id = p_room_id;

    delete from public.room_chat_messages where room_id = p_room_id;

    return true;
end;
$$;
