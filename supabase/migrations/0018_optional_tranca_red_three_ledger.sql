-- Mantem o ledger privado alinhado com a opcao da sala para o 3 vermelho.
-- Salas antigas continuam funcionando pelo valor salvo no config serializado.

create or replace function private.cbr_auto_meld_tranca_red_threes(p_config jsonb)
returns boolean
language plpgsql
stable
set search_path = ''
as $$
declare
    v_named_value text;
    v_serialized_value text;
begin
    v_named_value := p_config ->> 'autoMeldTrancaRedThrees';
    if v_named_value in ('true', 'false') then
        return v_named_value::boolean;
    end if;

    -- O nono campo do CSV e autoMeldTrancaRedThrees nas configuracoes expandidas.
    v_serialized_value := split_part(coalesce(p_config ->> 'serialized', ''), ',', 9);
    if v_serialized_value in ('true', 'false') then
        return v_serialized_value::boolean;
    end if;

    return true;
end;
$$;

revoke all on function private.cbr_auto_meld_tranca_red_threes(jsonb) from public;

create or replace function private.enforce_tranca_red_three_room_option()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_game_type text;
    v_room_config jsonb;
    v_inner jsonb;
    v_cards jsonb;
    v_card text;
begin
    if new.event_type <> 'MELD' then
        return new;
    end if;

    select room.game_type, room.config
    into v_game_type, v_room_config
    from public.match_rooms room
    where room.id = new.room_id;

    if v_game_type <> 'TRANCA'
       or private.cbr_auto_meld_tranca_red_threes(v_room_config) then
        return new;
    end if;

    begin
        v_inner := (new.payload ->> 'payload')::jsonb;
    exception
        when others then
            return new;
    end;

    v_cards := v_inner -> 'cards';
    if coalesce(jsonb_typeof(v_cards), '') <> 'array' then
        return new;
    end if;
    if jsonb_array_length(v_cards) <> 1 then
        return new;
    end if;

    v_card := v_cards ->> 0;
    if private.cbr_card_rank(v_card) = 'THREE'
       and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS') then
        raise exception 'AUTO_RED_THREE_DISABLED' using errcode = 'P0001';
    end if;

    return new;
end;
$$;

revoke all on function private.enforce_tranca_red_three_room_option() from public;

drop trigger if exists enforce_tranca_red_three_room_option on public.match_events;
create trigger enforce_tranca_red_three_room_option
before insert on public.match_events
for each row
execute function private.enforce_tranca_red_three_room_option();

create or replace function private.track_manual_tranca_red_three()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_game_type text;
    v_room_config jsonb;
    v_card text;
    v_hand jsonb;
    v_initialized boolean;
begin
    if new.seat <> 0 or new.event_type <> 'SERVE_CARD' then
        return new;
    end if;

    select room.game_type, room.config
    into v_game_type, v_room_config
    from public.match_rooms room
    where room.id = new.room_id;

    if v_game_type <> 'TRANCA'
       or private.cbr_auto_meld_tranca_red_threes(v_room_config) then
        return new;
    end if;

    v_card := trim(coalesce(new.payload ->> 'payload', ''));
    if private.cbr_card_rank(v_card) <> 'THREE'
       or private.cbr_card_suit(v_card) not in ('HEARTS', 'DIAMONDS') then
        return new;
    end if;

    if new.recipient_seat not between 1 and 3 then
        raise exception 'PRIVATE_HAND_SEAT_MISMATCH' using errcode = 'P0001';
    end if;

    select state.hand, state.initialized
    into v_hand, v_initialized
    from private.match_seat_state state
    where state.room_id = new.room_id
      and state.seat = new.recipient_seat
    for update;

    if not coalesce(v_initialized, false) then
        return new;
    end if;

    if v_hand @> jsonb_build_array(v_card)
       or private.cbr_cards_overlap_other_hands(
           new.room_id,
           new.recipient_seat,
           jsonb_build_array(v_card)
       ) then
        raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
    end if;

    update private.match_seat_state
    set hand = hand || jsonb_build_array(v_card),
        updated_at = now()
    where room_id = new.room_id
      and seat = new.recipient_seat;

    return new;
end;
$$;

revoke all on function private.track_manual_tranca_red_three() from public;

-- O prefixo zz garante que esta reconciliacao rode depois do tracker da 0016.
drop trigger if exists zz_track_manual_tranca_red_three on public.match_events;
create trigger zz_track_manual_tranca_red_three
after insert on public.match_events
for each row
execute function private.track_manual_tranca_red_three();
