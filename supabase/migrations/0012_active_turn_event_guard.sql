-- O host continua validando cartas e regras completas. Aqui eu uso somente o
-- estado publico para barrar uma acao critica enviada por quem nao esta na vez.

create or replace function private.enforce_active_turn_for_match_event()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_public_payload jsonb;
    v_active_seat integer;
begin
    if new.seat is null
       or new.seat = 0
       or new.event_type not in ('REQ_DRAW_DECK', 'DRAW_DISCARD', 'MELD', 'DISCARD') then
        return new;
    end if;

    -- Um retry antigo precisa chegar ao ON CONFLICT da RPC. A comparacao do
    -- envelope feita ali continua decidindo se e repeticao valida ou colisao.
    if exists (
        select 1
        from public.match_events event
        where event.room_id = new.room_id
          and event.message_id = new.message_id
    ) then
        return new;
    end if;

    begin
        select (event.payload ->> 'payload')::jsonb
        into v_public_payload
        from public.match_events event
        where event.room_id = new.room_id
          and event.event_type = 'PUBLIC_STATE'
        order by event.id desc
        limit 1;

        if v_public_payload is null
           or jsonb_typeof(v_public_payload) <> 'object'
           or coalesce(v_public_payload ->> 'activeSeat', '') !~ '^[0-3]$' then
            return new;
        end if;

        v_active_seat := (v_public_payload ->> 'activeSeat')::integer;
    exception
        when others then
            -- Estado antigo ou incompleto nao pode travar uma sala em andamento.
            return new;
    end;

    if new.seat <> v_active_seat then
        raise exception 'OUT_OF_TURN_EVENT' using errcode = 'P0001';
    end if;

    return new;
end;
$$;

revoke all on function private.enforce_active_turn_for_match_event() from public;

drop trigger if exists enforce_active_turn_for_match_event
on public.match_events;

create trigger enforce_active_turn_for_match_event
before insert on public.match_events
for each row
execute function private.enforce_active_turn_for_match_event();
