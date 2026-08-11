-- A interface ja bloqueava uma segunda compra na mesma vez, mas esse estado
-- ainda existia apenas no aparelho host. Um cliente modificado podia chamar a
-- RPC com outra chave e retirar mais cartas antes de descartar.

alter table private.match_deck_state
add column if not exists drawn_seat integer
check (drawn_seat is null or drawn_seat between 0 and 3);

-- Guardo a implementacao anterior no schema privado. Somente a versao
-- idempotente continua exposta ao aplicativo.
alter function public.online_draw_deck_card(uuid, integer) set schema private;
alter function private.online_draw_deck_card(uuid, integer)
rename to cbr_online_draw_deck_card_without_turn_guard;

revoke all on function private.cbr_online_draw_deck_card_without_turn_guard(uuid, integer)
from public, anon, authenticated;

create or replace function public.online_draw_deck_card(
    p_room_id uuid,
    p_seat integer
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room public.match_rooms%rowtype;
    v_drawn_seat integer;
    v_result jsonb;
    v_card text;
    v_auto_red_three boolean;
begin
    -- Mantenho a mesma ordem de bloqueio usada pela implementacao interna:
    -- sala primeiro, estado do baralho depois. Isso evita corrida e deadlock.
    select room.*
    into v_room
    from public.match_rooms room
    where room.id = p_room_id
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    select state.drawn_seat
    into v_drawn_seat
    from private.match_deck_state state
    where state.room_id = p_room_id
    for update;

    if not found then
        raise exception 'DECK_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_drawn_seat is not null then
        raise exception 'DRAW_ALREADY_COMPLETED' using errcode = 'P0001';
    end if;

    v_result := private.cbr_online_draw_deck_card_without_turn_guard(p_room_id, p_seat);
    if coalesce(v_result ->> 'status', '') <> 'OK' then
        return v_result;
    end if;

    v_card := nullif(v_result ->> 'card', '');
    v_auto_red_three := v_room.game_type = 'TRANCA'
        and coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true)
        and private.cbr_card_rank(v_card) = 'THREE'
        and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS');

    -- O 3 vermelho automatico nao encerra a compra: ele vai para a mesa e o
    -- mesmo assento precisa receber uma carta de reposicao.
    if not v_auto_red_three then
        update private.match_deck_state
        set drawn_seat = p_seat,
            updated_at = now()
        where room_id = p_room_id;
    end if;

    return v_result;
end;
$$;

-- O retorno de rodada ja foi sanitizado na 0040. Aqui acrescento a trava que
-- impede redistribuir uma rodada ativa usando outra chave de idempotencia.
create or replace function public.start_online_round(p_room_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_status text;
    v_last_control_event text;
    v_result jsonb;
begin
    select room.status
    into v_status
    from public.match_rooms room
    where room.id = p_room_id
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    if v_status = 'playing' then
        select event.event_type
        into v_last_control_event
        from public.match_events event
        where event.room_id = p_room_id
          and event.event_type in ('GAME_START', 'NEXT_ROUND')
        order by event.id desc
        limit 1;

        if v_last_control_event is distinct from 'NEXT_ROUND' then
            raise exception 'ROUND_ALREADY_ACTIVE' using errcode = 'P0001';
        end if;
    end if;

    v_result := private.cbr_start_online_round_with_private_material(p_room_id);

    update private.match_deck_state
    set drawn_seat = null,
        updated_at = now()
    where room_id = p_room_id;

    return v_result - 'mortos';
end;
$$;

create or replace function private.enforce_single_draw_per_turn()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_drawn_seat integer;
    v_room public.match_rooms%rowtype;
    v_inner jsonb;
    v_cards jsonb;
    v_card text;
    v_auto_red_three boolean := false;
begin
    if new.seat is null
       or new.event_type not in ('DRAW_DISCARD', 'MELD', 'DISCARD') then
        return new;
    end if;

    -- Retry identico precisa chegar ao ON CONFLICT da RPC, mesmo depois de o
    -- primeiro envio ter alterado o marcador da vez.
    if exists (
        select 1
        from public.match_events event
        where event.room_id = new.room_id
          and event.message_id = new.message_id
    ) then
        return new;
    end if;

    select room.*
    into v_room
    from public.match_rooms room
    where room.id = new.room_id;

    select state.drawn_seat
    into v_drawn_seat
    from private.match_deck_state state
    where state.room_id = new.room_id
    for update;

    -- Salas antigas que ainda nao possuem estado de baralho continuam sendo
    -- aceitas pela camada legada; a trava vale para rodadas server-authoritative.
    if not found then
        return new;
    end if;

    if new.event_type = 'DRAW_DISCARD' then
        if v_drawn_seat is not null then
            raise exception 'DRAW_ALREADY_COMPLETED' using errcode = 'P0001';
        end if;

        update private.match_deck_state
        set drawn_seat = new.seat,
            updated_at = now()
        where room_id = new.room_id;
        return new;
    end if;

    if new.event_type = 'MELD' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
            v_cards := coalesce(v_inner -> 'cards', '[]'::jsonb);
            if jsonb_typeof(v_cards) = 'array' and jsonb_array_length(v_cards) = 1 then
                v_card := v_cards ->> 0;
                v_auto_red_three := v_room.game_type = 'TRANCA'
                    and coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true)
                    and private.cbr_card_rank(v_card) = 'THREE'
                    and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS');
            end if;
        exception
            when others then
                v_auto_red_three := false;
        end;

        if not v_auto_red_three and v_drawn_seat is distinct from new.seat then
            raise exception 'DRAW_REQUIRED_BEFORE_ACTION' using errcode = 'P0001';
        end if;
        return new;
    end if;

    if v_drawn_seat is distinct from new.seat then
        raise exception 'DRAW_REQUIRED_BEFORE_DISCARD' using errcode = 'P0001';
    end if;

    update private.match_deck_state
    set drawn_seat = null,
        updated_at = now()
    where room_id = new.room_id;
    return new;
end;
$$;

drop trigger if exists enforce_single_draw_per_turn on public.match_events;
create trigger enforce_single_draw_per_turn
before insert on public.match_events
for each row execute function private.enforce_single_draw_per_turn();

-- As funcoes sem recibo continuam disponiveis apenas internamente. As tres
-- versoes idempotentes sao o unico caminho aceito pelo aplicativo.
revoke all on function public.start_online_round(uuid) from public, anon, authenticated;
revoke all on function public.online_draw_deck_card(uuid, integer) from public, anon, authenticated;
revoke all on function public.online_take_morto(uuid, integer, boolean) from public, anon, authenticated;

grant execute on function public.start_online_round_idempotent(uuid, uuid) to authenticated;
grant execute on function public.online_draw_deck_card_idempotent(uuid, integer, uuid) to authenticated;
grant execute on function public.online_take_morto_idempotent(uuid, integer, boolean, uuid) to authenticated;

comment on column private.match_deck_state.drawn_seat is
'Assento que ja concluiu a compra da vez e ainda precisa descartar.';
