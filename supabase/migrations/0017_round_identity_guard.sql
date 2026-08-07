-- A sala pode continuar aberta por varias rodadas. Este estado privado impede
-- que uma jogada atrasada da rodada anterior seja aplicada na mesa nova.

create table if not exists private.match_round_state (
    room_id uuid primary key references public.match_rooms(id) on delete cascade,
    round_id uuid not null,
    phase text not null default 'active'
        check (phase in ('active', 'between_rounds', 'finished')),
    started_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

revoke all on table private.match_round_state from public, anon, authenticated;

create or replace function private.validate_match_event_round()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_round_text text := nullif(trim(coalesce(new.payload ->> 'roundId', '')), '');
    v_message_round_id uuid;
    v_current_round_id uuid;
    v_phase text;
    v_inner jsonb;
    v_has_state boolean := false;
begin
    -- Retry identico precisa chegar ao ON CONFLICT da RPC para manter o ACK idempotente.
    if exists (
        select 1
        from public.match_events event
        where event.room_id = new.room_id
          and event.message_id = new.message_id
    ) then
        return new;
    end if;

    -- Estes dois eventos preparam a conexao antes de existir uma rodada ativa.
    if new.event_type in ('CLIENT_READY', 'REQ_RECONNECT') then
        return new;
    end if;

    select state.round_id, state.phase
    into v_current_round_id, v_phase
    from private.match_round_state state
    where state.room_id = new.room_id;
    v_has_state := found;

    if v_round_text is not null then
        begin
            v_message_round_id := v_round_text::uuid;
        exception
            when others then
                raise exception 'INVALID_ROUND_ID' using errcode = 'P0001';
        end;
    end if;

    if new.event_type = 'GAME_START' then
        -- Salas antigas continuam abertas ate receberem o primeiro GAME_START
        -- da versao com roundId. Depois disso a protecao passa a ser obrigatoria.
        if v_message_round_id is null then
            if v_has_state then
                raise exception 'ROUND_ID_REQUIRED' using errcode = 'P0001';
            end if;
            return new;
        end if;

        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
        exception
            when others then
                raise exception 'INVALID_GAME_START_ROUND' using errcode = 'P0001';
        end;

        if jsonb_typeof(v_inner) <> 'object'
           or nullif(trim(coalesce(v_inner ->> 'roundId', '')), '') is null
           or (v_inner ->> 'roundId')::uuid <> v_message_round_id then
            raise exception 'GAME_START_ROUND_MISMATCH' using errcode = 'P0001';
        end if;

        if not v_has_state then
            -- A primeira distribuicao da nova rodada limpa qualquer mao antiga.
            -- As demais copias de GAME_START, uma por assento, mantem o mesmo estado.
            delete from private.match_seat_state where room_id = new.room_id;
            delete from private.match_team_state where room_id = new.room_id;

            insert into private.match_round_state(
                room_id, round_id, phase, started_at, updated_at
            ) values (
                new.room_id, v_message_round_id, 'active', now(), now()
            )
            on conflict (room_id) do update
            set round_id = excluded.round_id,
                phase = 'active',
                started_at = now(),
                updated_at = now();
        elsif v_current_round_id <> v_message_round_id then
            if v_phase <> 'between_rounds' then
                raise exception 'ROUND_TRANSITION_REQUIRED' using errcode = 'P0001';
            end if;

            delete from private.match_seat_state where room_id = new.room_id;
            delete from private.match_team_state where room_id = new.room_id;
            update private.match_round_state state
            set round_id = v_message_round_id,
                phase = 'active',
                started_at = now(),
                updated_at = now()
            where state.room_id = new.room_id;
        elsif v_phase <> 'active' then
            raise exception 'ROUND_NOT_ACTIVE' using errcode = 'P0001';
        end if;

        return new;
    end if;

    if not v_has_state then
        if v_message_round_id is null then
            return new;
        end if;
        raise exception 'ROUND_NOT_STARTED' using errcode = 'P0001';
    end if;

    if v_message_round_id is null then
        raise exception 'ROUND_ID_REQUIRED' using errcode = 'P0001';
    end if;
    if v_message_round_id <> v_current_round_id then
        raise exception 'ROUND_ID_MISMATCH' using errcode = 'P0001';
    end if;

    if v_phase = 'between_rounds' then
        raise exception 'ROUND_NOT_ACTIVE' using errcode = 'P0001';
    end if;
    if v_phase = 'finished'
       and new.event_type not in ('RESTART_MATCH', 'REPLY_RESTART', 'REQ_NEXT_ROUND', 'NEXT_ROUND') then
        raise exception 'MATCH_ALREADY_FINISHED' using errcode = 'P0001';
    end if;

    if new.event_type = 'RECONNECT_STATE' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
        exception
            when others then
                raise exception 'INVALID_RECONNECT_ROUND' using errcode = 'P0001';
        end;
        if jsonb_typeof(v_inner) <> 'object'
           or nullif(trim(coalesce(v_inner ->> 'roundId', '')), '') is null
           or (v_inner ->> 'roundId')::uuid <> v_message_round_id then
            raise exception 'RECONNECT_ROUND_MISMATCH' using errcode = 'P0001';
        end if;
    end if;

    if new.event_type = 'NEXT_ROUND' then
        update private.match_round_state state
        set phase = 'between_rounds',
            updated_at = now()
        where state.room_id = new.room_id
          and state.round_id = v_message_round_id;
    end if;

    return new;
exception
    when invalid_text_representation then
        raise exception 'INVALID_ROUND_ID' using errcode = 'P0001';
end;
$$;

revoke all on function private.validate_match_event_round() from public;

drop trigger if exists validate_match_event_round on public.match_events;
create trigger validate_match_event_round
before insert on public.match_events
for each row
execute function private.validate_match_event_round();

-- Um resultado pode ser persistido depois que o host ja abriu outra rodada.
-- A limpeza so acontece quando o ROUND_SUMMARY pertence a rodada ainda registrada.
create or replace function private.clear_completed_match_private_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_result_round_id uuid;
    v_current_round_id uuid;
begin
    begin
        select nullif(trim(coalesce(event.payload ->> 'roundId', '')), '')::uuid
        into v_result_round_id
        from public.match_events event
        where event.room_id = new.room_id
          and event.message_id = new.result_key
          and event.event_type = 'ROUND_SUMMARY'
        limit 1;
    exception
        when others then
            v_result_round_id := null;
    end;

    select state.round_id
    into v_current_round_id
    from private.match_round_state state
    where state.room_id = new.room_id;

    if v_result_round_id is null then
        -- Compatibilidade com partidas iniciadas antes da identidade de rodada.
        if v_current_round_id is null then
            delete from private.match_seat_state where room_id = new.room_id;
            delete from private.match_team_state where room_id = new.room_id;
        end if;
        return new;
    end if;

    if v_current_round_id = v_result_round_id then
        delete from private.match_seat_state where room_id = new.room_id;
        delete from private.match_team_state where room_id = new.room_id;
        update private.match_round_state state
        set phase = 'finished',
            updated_at = now()
        where state.room_id = new.room_id
          and state.round_id = v_result_round_id;
    end if;

    return new;
end;
$$;

revoke all on function private.clear_completed_match_private_state() from public;
