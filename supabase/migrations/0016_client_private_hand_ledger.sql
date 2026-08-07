-- A mao dos clientes fica em um ledger privado do banco. O aplicativo nao
-- recebe acesso a estas tabelas: elas existem apenas para conferir posse das
-- cartas, compra do lixo, entrega do morto e declaracao de vitoria.
--
-- O host continua sendo a autoridade do baralho e das regras completas. Para
-- salas abertas antes desta migration, a validacao de posse so passa a valer
-- depois que GAME_START ou RECONNECT_STATE inicializar o assento.

create table if not exists private.match_seat_state (
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    seat integer not null check (seat between 1 and 3),
    hand jsonb not null default '[]'::jsonb,
    required_discard_card text,
    pending_discard_cards jsonb not null default '[]'::jsonb,
    initialized boolean not null default false,
    updated_at timestamptz not null default now(),
    primary key (room_id, seat)
);

create table if not exists private.match_team_state (
    room_id uuid not null references public.match_rooms(id) on delete cascade,
    team integer not null check (team between 0 and 1),
    melds jsonb not null default '[]'::jsonb,
    melds_initialized boolean not null default false,
    picked_morto boolean not null default false,
    updated_at timestamptz not null default now(),
    primary key (room_id, team)
);

revoke all on table private.match_seat_state from public, anon, authenticated;
revoke all on table private.match_team_state from public, anon, authenticated;

create or replace function private.cbr_is_valid_card_array(
    p_cards jsonb,
    p_min_size integer,
    p_max_size integer
)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_size integer;
begin
    if jsonb_typeof(p_cards) <> 'array' then
        return false;
    end if;

    v_size := jsonb_array_length(p_cards);
    if v_size not between p_min_size and p_max_size then
        return false;
    end if;

    if exists (
        select 1
        from jsonb_array_elements(p_cards) as item(value)
        where jsonb_typeof(item.value) <> 'string'
           or not private.cbr_is_valid_card_id(item.value #>> '{}')
    ) then
        return false;
    end if;

    return (
        select count(distinct item.value #>> '{}')
        from jsonb_array_elements(p_cards) as item(value)
    ) = v_size;
end;
$$;

create or replace function private.cbr_is_valid_meld_collection(p_melds jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_meld jsonb;
    v_total integer;
    v_distinct integer;
begin
    if jsonb_typeof(p_melds) <> 'array' or jsonb_array_length(p_melds) > 40 then
        return false;
    end if;

    for v_meld in
        select item.value from jsonb_array_elements(p_melds) as item(value)
    loop
        if not private.cbr_is_valid_card_array(v_meld, 1, 64) then
            return false;
        end if;
    end loop;

    select count(*), count(distinct card)
    into v_total, v_distinct
    from jsonb_array_elements(p_melds) as meld(value)
    cross join lateral jsonb_array_elements_text(meld.value) as item(card);

    return v_total = v_distinct and v_total <= 108;
end;
$$;

create or replace function private.cbr_cards_contained(p_container jsonb, p_cards jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select coalesce(not exists (
        select 1
        from jsonb_array_elements_text(p_cards) as wanted(card)
        where not exists (
            select 1
            from jsonb_array_elements_text(p_container) as owned(card)
            where owned.card = wanted.card
        )
    ), false);
$$;

create or replace function private.cbr_card_arrays_overlap(p_first jsonb, p_second jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select coalesce(exists (
        select 1
        from jsonb_array_elements_text(p_first) as first_card(card)
        join jsonb_array_elements_text(p_second) as second_card(card)
          on second_card.card = first_card.card
    ), false);
$$;

create or replace function private.cbr_subtract_cards(p_container jsonb, p_cards jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select coalesce(jsonb_agg(to_jsonb(hand_card.card) order by hand_card.ordinal), '[]'::jsonb)
    from jsonb_array_elements_text(p_container) with ordinality as hand_card(card, ordinal)
    where not exists (
        select 1
        from jsonb_array_elements_text(p_cards) as removed(card)
        where removed.card = hand_card.card
    );
$$;

create or replace function private.cbr_latest_public_state(p_room_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_state jsonb;
begin
    select (event.payload ->> 'payload')::jsonb
    into v_state
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;

    if jsonb_typeof(v_state) = 'object' then
        return v_state;
    end if;
    return null;
exception
    when others then
        return null;
end;
$$;

create or replace function private.cbr_cards_overlap_other_hands(
    p_room_id uuid,
    p_seat integer,
    p_cards jsonb
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(exists (
        select 1
        from private.match_seat_state state
        cross join lateral jsonb_array_elements_text(state.hand) as owned(card)
        join jsonb_array_elements_text(p_cards) as candidate(card)
          on candidate.card = owned.card
        where state.room_id = p_room_id
          and state.seat <> p_seat
          and state.initialized
    ), false);
$$;

revoke all on function private.cbr_is_valid_card_array(jsonb, integer, integer) from public;
revoke all on function private.cbr_is_valid_meld_collection(jsonb) from public;
revoke all on function private.cbr_cards_contained(jsonb, jsonb) from public;
revoke all on function private.cbr_card_arrays_overlap(jsonb, jsonb) from public;
revoke all on function private.cbr_subtract_cards(jsonb, jsonb) from public;
revoke all on function private.cbr_latest_public_state(uuid) from public;
revoke all on function private.cbr_cards_overlap_other_hands(uuid, integer, jsonb) from public;

create or replace function private.track_client_private_match_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_inner jsonb;
    v_public_state jsonb;
    v_game_type text;
    v_room_config jsonb;
    v_expected_hand_size integer;
    v_hand jsonb;
    v_new_hand jsonb;
    v_pending jsonb;
    v_card text;
    v_discard_pile jsonb;
    v_required_card text;
    v_team integer;
    v_payload_team integer;
    v_team_melds jsonb;
    v_team_melds_initialized boolean;
    v_resulting_meld jsonb;
    v_existing_meld jsonb;
    v_cards_from_hand jsonb;
    v_replace_index integer;
    v_allow_wildcards boolean;
    v_allow_charutos boolean;
    v_require_clean boolean;
    v_cacheta_turn_card text;
    v_meld_check jsonb;
    v_meld jsonb;
    v_has_canastra boolean := false;
    v_has_clean_canastra boolean := false;
    v_picked_morto boolean := false;
    v_mortos_left integer;
    v_state_initialized boolean;
begin
    select room.game_type, room.config
    into v_game_type, v_room_config
    from public.match_rooms room
    where room.id = new.room_id;

    if v_game_type is null then
        return new;
    end if;

    v_expected_hand_size := case when v_game_type = 'CACHETA' then 9 else 11 end;
    v_allow_wildcards := coalesce((v_room_config ->> 'allowWildcards')::boolean, true);
    v_allow_charutos := coalesce((v_room_config ->> 'allowCharutos')::boolean, true);
    v_require_clean := coalesce((v_room_config ->> 'requireCleanCanastraToWin')::boolean, true);

    -- O primeiro estado publico da rodada inicializa as mesas por equipe.
    -- Depois disso, os MELDs aceitos sao aplicados diretamente para nao voltar
    -- a uma fotografia atrasada quando dois eventos chegam muito proximos.
    if new.seat = 0 and new.event_type = 'PUBLIC_STATE' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
        exception
            when others then
                return new;
        end;

        if jsonb_typeof(v_inner) <> 'object' then
            return new;
        end if;

        for v_payload_team in 0..1 loop
            v_team_melds := v_inner -> ('team' || v_payload_team || 'Melds');
            if private.cbr_is_valid_meld_collection(v_team_melds) then
                insert into private.match_team_state as team_state(
                    room_id, team, melds, melds_initialized, updated_at
                ) values (
                    new.room_id, v_payload_team, v_team_melds, true, now()
                )
                on conflict (room_id, team) do update
                set melds = excluded.melds,
                    melds_initialized = true,
                    updated_at = now()
                where not team_state.melds_initialized;
            end if;
        end loop;
        return new;
    end if;

    if new.seat = 0 and new.event_type = 'NEXT_ROUND' then
        delete from private.match_seat_state where room_id = new.room_id;
        delete from private.match_team_state where room_id = new.room_id;
        return new;
    end if;

    if new.seat = 0 and new.event_type = 'PICK_MORTO' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
            if coalesce(v_inner ->> 'team', '') ~ '^[01]$' then
                v_payload_team := (v_inner ->> 'team')::integer;
                insert into private.match_team_state(room_id, team, picked_morto, updated_at)
                values (new.room_id, v_payload_team, true, now())
                on conflict (room_id, team) do update
                set picked_morto = true,
                    updated_at = now();
            end if;
        exception
            when others then
                null;
        end;
        return new;
    end if;

    if new.seat = 0 and new.event_type in ('GAME_START', 'RECONNECT_STATE') then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
        exception
            when others then
                raise exception 'INVALID_PRIVATE_HAND_PAYLOAD' using errcode = 'P0001';
        end;

        if new.recipient_seat not between 1 and 3
           or jsonb_typeof(v_inner) <> 'object'
           or coalesce(v_inner ->> 'seat', '') !~ '^[1-3]$'
           or (v_inner ->> 'seat')::integer <> new.recipient_seat then
            raise exception 'PRIVATE_HAND_SEAT_MISMATCH' using errcode = 'P0001';
        end if;

        v_hand := v_inner -> 'hand';
        if new.event_type = 'GAME_START' then
            if not private.cbr_is_valid_card_array(v_hand, v_expected_hand_size, v_expected_hand_size) then
                raise exception 'INVALID_INITIAL_HAND' using errcode = 'P0001';
            end if;
        elsif not private.cbr_is_valid_card_array(v_hand, 0, 64) then
            raise exception 'INVALID_RECONNECT_HAND' using errcode = 'P0001';
        end if;

        if private.cbr_cards_overlap_other_hands(new.room_id, new.recipient_seat, v_hand) then
            raise exception 'CARD_ASSIGNED_TO_MULTIPLE_PLAYERS' using errcode = 'P0001';
        end if;

        insert into private.match_seat_state(
            room_id, seat, hand, required_discard_card,
            pending_discard_cards, initialized, updated_at
        ) values (
            new.room_id, new.recipient_seat, v_hand, null,
            '[]'::jsonb, true, now()
        )
        on conflict (room_id, seat) do update
        set hand = excluded.hand,
            required_discard_card = null,
            pending_discard_cards = '[]'::jsonb,
            initialized = true,
            updated_at = now();
        return new;
    end if;

    if new.seat = 0 and new.event_type = 'SERVE_CARD' then
        v_card := trim(coalesce(new.payload ->> 'payload', ''));
        if not private.cbr_is_valid_card_id(v_card) then
            raise exception 'INVALID_SERVED_CARD' using errcode = 'P0001';
        end if;

        select state.hand, state.initialized
        into v_hand, v_state_initialized
        from private.match_seat_state state
        where state.room_id = new.room_id
          and state.seat = new.recipient_seat
        for update;

        if not coalesce(v_state_initialized, false) then
            return new;
        end if;

        -- Na Tranca o 3 vermelho e baixado automaticamente e outra carta e
        -- servida; por isso ele nunca entra na mao privada reconstruida.
        if v_game_type = 'TRANCA'
           and private.cbr_card_rank(v_card) = 'THREE'
           and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS') then
            return new;
        end if;

        if v_hand @> jsonb_build_array(v_card)
           or private.cbr_cards_overlap_other_hands(
               new.room_id, new.recipient_seat, jsonb_build_array(v_card)
           ) then
            raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
        end if;

        update private.match_seat_state
        set hand = hand || jsonb_build_array(v_card),
            updated_at = now()
        where room_id = new.room_id
          and seat = new.recipient_seat;
        return new;
    end if;

    if new.seat = 0 and new.event_type = 'SERVE_MORTO' then
        if v_game_type = 'CACHETA' then
            raise exception 'MORTO_NOT_ALLOWED' using errcode = 'P0001';
        end if;

        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
        exception
            when others then
                raise exception 'INVALID_MORTO_PAYLOAD' using errcode = 'P0001';
        end;
        v_hand := v_inner -> 'hand';

        if new.recipient_seat not between 1 and 3
           or not private.cbr_is_valid_card_array(v_hand, 11, 11) then
            raise exception 'INVALID_MORTO_HAND' using errcode = 'P0001';
        end if;

        select state.hand, state.pending_discard_cards,
               state.required_discard_card, state.initialized
        into v_new_hand, v_pending, v_required_card, v_state_initialized
        from private.match_seat_state state
        where state.room_id = new.room_id
          and state.seat = new.recipient_seat
        for update;

        if coalesce(v_state_initialized, false)
           and (jsonb_array_length(v_new_hand) <> 0
                or jsonb_array_length(v_pending) <> 0
                or v_required_card is not null) then
            raise exception 'MORTO_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
        end if;

        if private.cbr_cards_overlap_other_hands(new.room_id, new.recipient_seat, v_hand) then
            raise exception 'CARD_ASSIGNED_TO_MULTIPLE_PLAYERS' using errcode = 'P0001';
        end if;

        select player.team
        into v_team
        from public.room_players player
        where player.room_id = new.room_id
          and player.seat = new.recipient_seat;

        if v_team is null then
            raise exception 'MORTO_PLAYER_NOT_FOUND' using errcode = 'P0001';
        end if;

        select coalesce(team_state.picked_morto, false)
        into v_picked_morto
        from private.match_team_state team_state
        where team_state.room_id = new.room_id
          and team_state.team = v_team
        for update;

        if coalesce(v_picked_morto, false) then
            raise exception 'TEAM_ALREADY_PICKED_MORTO' using errcode = 'P0001';
        end if;

        insert into private.match_seat_state(
            room_id, seat, hand, required_discard_card,
            pending_discard_cards, initialized, updated_at
        ) values (
            new.room_id, new.recipient_seat, v_hand, null,
            '[]'::jsonb, true, now()
        )
        on conflict (room_id, seat) do update
        set hand = excluded.hand,
            required_discard_card = null,
            pending_discard_cards = '[]'::jsonb,
            initialized = true,
            updated_at = now();

        insert into private.match_team_state(room_id, team, picked_morto, updated_at)
        values (new.room_id, v_team, true, now())
        on conflict (room_id, team) do update
        set picked_morto = true,
            updated_at = now();
        return new;
    end if;

    -- As jogadas do host nao usam ledger de mao, mas seus MELDs alimentam a
    -- mesa compartilhada para o parceiro poder encaixar cartas com seguranca.
    if new.seat = 0 and new.event_type = 'MELD' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
            v_resulting_meld := v_inner -> 'cards';
            v_replace_index := coalesce((v_inner ->> 'replaceIndex')::integer, -1);
            select player.team into v_team
            from public.room_players player
            where player.room_id = new.room_id and player.seat = 0;

            select state.melds, state.melds_initialized
            into v_team_melds, v_team_melds_initialized
            from private.match_team_state state
            where state.room_id = new.room_id and state.team = v_team
            for update;

            if not coalesce(v_team_melds_initialized, false) then
                v_public_state := private.cbr_latest_public_state(new.room_id);
                v_team_melds := coalesce(v_public_state -> ('team' || v_team || 'Melds'), '[]'::jsonb);
            end if;

            if not private.cbr_is_valid_meld_collection(v_team_melds)
               or not private.cbr_is_valid_card_array(v_resulting_meld, 1, 64) then
                return new;
            end if;

            if v_replace_index < 0 then
                v_team_melds := v_team_melds || jsonb_build_array(v_resulting_meld);
            elsif v_replace_index < jsonb_array_length(v_team_melds) then
                v_team_melds := jsonb_set(
                    v_team_melds,
                    array[v_replace_index::text],
                    v_resulting_meld,
                    false
                );
            else
                return new;
            end if;

            insert into private.match_team_state(room_id, team, melds, melds_initialized, updated_at)
            values (new.room_id, v_team, v_team_melds, true, now())
            on conflict (room_id, team) do update
            set melds = excluded.melds,
                melds_initialized = true,
                updated_at = now();
        exception
            when others then
                null;
        end;
        return new;
    end if;

    if new.seat is null or new.seat = 0 then
        return new;
    end if;

    select state.hand, state.pending_discard_cards,
           state.required_discard_card, state.initialized
    into v_hand, v_pending, v_required_card, v_state_initialized
    from private.match_seat_state state
    where state.room_id = new.room_id
      and state.seat = new.seat
    for update;

    if not coalesce(v_state_initialized, false) then
        return new;
    end if;

    select player.team
    into v_team
    from public.room_players player
    where player.room_id = new.room_id
      and player.seat = new.seat;

    if v_team is null then
        raise exception 'PRIVATE_PLAYER_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;

    if new.event_type = 'DRAW_DISCARD' then
        if v_required_card is not null or jsonb_array_length(v_pending) <> 0 then
            raise exception 'DISCARD_DRAW_ALREADY_PENDING' using errcode = 'P0001';
        end if;

        v_public_state := private.cbr_latest_public_state(new.room_id);
        v_discard_pile := v_public_state -> 'discardPile';
        v_card := trim(coalesce(new.payload ->> 'payload', ''));

        if not private.cbr_is_valid_card_array(v_discard_pile, 1, 108)
           or v_card <> v_discard_pile ->> (jsonb_array_length(v_discard_pile) - 1) then
            raise exception 'DISCARD_TOP_MISMATCH' using errcode = 'P0001';
        end if;

        if v_hand @> jsonb_build_array(v_card)
           or private.cbr_cards_overlap_other_hands(
               new.room_id, new.seat, jsonb_build_array(v_card)
           ) then
            raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
        end if;

        if v_game_type = 'CACHETA' then
            v_pending := '[]'::jsonb;
            v_required_card := null;
        else
            select coalesce(jsonb_agg(to_jsonb(pile.card) order by pile.ordinal), '[]'::jsonb)
            into v_pending
            from jsonb_array_elements_text(v_discard_pile) with ordinality as pile(card, ordinal)
            where pile.ordinal < jsonb_array_length(v_discard_pile);
            v_required_card := v_card;
        end if;

        update private.match_seat_state
        set hand = hand || jsonb_build_array(v_card),
            required_discard_card = v_required_card,
            pending_discard_cards = v_pending,
            updated_at = now()
        where room_id = new.room_id and seat = new.seat;
        return new;
    end if;

    if new.event_type = 'DISCARD' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
            v_card := trim(coalesce(v_inner ->> 'card', ''));
        exception
            when others then
                raise exception 'INVALID_DISCARD_PAYLOAD' using errcode = 'P0001';
        end;

        if v_required_card is not null or jsonb_array_length(v_pending) <> 0 then
            raise exception 'DISCARD_DRAW_TOP_REQUIRED' using errcode = 'P0001';
        end if;
        if not private.cbr_is_valid_card_id(v_card)
           or not (v_hand @> jsonb_build_array(v_card)) then
            raise exception 'CARD_NOT_IN_HAND' using errcode = 'P0001';
        end if;

        update private.match_seat_state
        set hand = private.cbr_subtract_cards(hand, jsonb_build_array(v_card)),
            updated_at = now()
        where room_id = new.room_id and seat = new.seat;
        return new;
    end if;

    if new.event_type = 'MELD' then
        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
            v_resulting_meld := v_inner -> 'cards';
            v_replace_index := coalesce((v_inner ->> 'replaceIndex')::integer, -1);
        exception
            when others then
                raise exception 'INVALID_MELD_PAYLOAD' using errcode = 'P0001';
        end;

        select state.melds, state.melds_initialized
        into v_team_melds, v_team_melds_initialized
        from private.match_team_state state
        where state.room_id = new.room_id
          and state.team = v_team
        for update;

        if not coalesce(v_team_melds_initialized, false) then
            v_public_state := private.cbr_latest_public_state(new.room_id);
            v_team_melds := coalesce(v_public_state -> ('team' || v_team || 'Melds'), '[]'::jsonb);
            if not private.cbr_is_valid_meld_collection(v_team_melds) then
                raise exception 'PRIVATE_TABLE_STATE_UNAVAILABLE' using errcode = 'P0001';
            end if;
        end if;

        if v_replace_index < 0 then
            v_cards_from_hand := v_resulting_meld;
            v_team_melds := v_team_melds || jsonb_build_array(v_resulting_meld);
        else
            if v_replace_index >= jsonb_array_length(v_team_melds) then
                raise exception 'INVALID_MELD_TARGET' using errcode = 'P0001';
            end if;
            v_existing_meld := v_team_melds -> v_replace_index;
            if jsonb_typeof(v_existing_meld) <> 'array'
               or not private.cbr_cards_contained(v_resulting_meld, v_existing_meld) then
                raise exception 'MELD_CHANGED_TABLE_CARDS' using errcode = 'P0001';
            end if;
            v_cards_from_hand := private.cbr_subtract_cards(v_resulting_meld, v_existing_meld);
            v_team_melds := jsonb_set(
                v_team_melds,
                array[v_replace_index::text],
                v_resulting_meld,
                false
            );
        end if;

        if jsonb_array_length(v_cards_from_hand) = 0
           or not private.cbr_cards_contained(v_hand, v_cards_from_hand) then
            raise exception 'CARD_NOT_IN_HAND' using errcode = 'P0001';
        end if;

        if v_required_card is not null
           and not (v_cards_from_hand @> jsonb_build_array(v_required_card)) then
            raise exception 'DISCARD_DRAW_TOP_REQUIRED' using errcode = 'P0001';
        end if;

        v_new_hand := private.cbr_subtract_cards(v_hand, v_cards_from_hand);
        if v_required_card is not null then
            if private.cbr_card_arrays_overlap(v_new_hand, v_pending)
               or private.cbr_cards_overlap_other_hands(new.room_id, new.seat, v_pending) then
                raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
            end if;
            v_new_hand := v_new_hand || v_pending;
        end if;

        if not private.cbr_is_valid_card_array(v_new_hand, 0, 108)
           or not private.cbr_is_valid_meld_collection(v_team_melds) then
            raise exception 'INVALID_PRIVATE_MATCH_STATE' using errcode = 'P0001';
        end if;

        update private.match_seat_state
        set hand = v_new_hand,
            required_discard_card = null,
            pending_discard_cards = '[]'::jsonb,
            updated_at = now()
        where room_id = new.room_id and seat = new.seat;

        insert into private.match_team_state(room_id, team, melds, melds_initialized, updated_at)
        values (new.room_id, v_team, v_team_melds, true, now())
        on conflict (room_id, team) do update
        set melds = excluded.melds,
            melds_initialized = true,
            updated_at = now();
        return new;
    end if;

    if new.event_type = 'REQ_PICK_MORTO' then
        if v_game_type = 'CACHETA' then
            raise exception 'MORTO_NOT_ALLOWED' using errcode = 'P0001';
        end if;
        if jsonb_array_length(v_hand) <> 0
           or jsonb_array_length(v_pending) <> 0
           or v_required_card is not null then
            raise exception 'MORTO_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
        end if;

        select coalesce(team_state.picked_morto, false)
        into v_picked_morto
        from private.match_team_state team_state
        where team_state.room_id = new.room_id
          and team_state.team = v_team;
        if coalesce(v_picked_morto, false) then
            raise exception 'TEAM_ALREADY_PICKED_MORTO' using errcode = 'P0001';
        end if;

        v_public_state := private.cbr_latest_public_state(new.room_id);
        if coalesce(v_public_state ->> 'mortosLeft', '') ~ '^[0-9]+$' then
            v_mortos_left := (v_public_state ->> 'mortosLeft')::integer;
            if v_mortos_left <= 0 then
                raise exception 'NO_MORTO_AVAILABLE' using errcode = 'P0001';
            end if;
        end if;
        return new;
    end if;

    if new.event_type = 'WIN_ROUND' then
        if jsonb_array_length(v_hand) <> 0
           or jsonb_array_length(v_pending) <> 0
           or v_required_card is not null then
            raise exception 'WIN_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
        end if;

        if v_game_type <> 'CACHETA' then
            select state.melds, state.melds_initialized, state.picked_morto
            into v_team_melds, v_team_melds_initialized, v_picked_morto
            from private.match_team_state state
            where state.room_id = new.room_id
              and state.team = v_team;

            if not coalesce(v_picked_morto, false) then
                raise exception 'WIN_REQUIRES_MORTO' using errcode = 'P0001';
            end if;
            if not coalesce(v_team_melds_initialized, false)
               or not private.cbr_is_valid_meld_collection(v_team_melds) then
                raise exception 'PRIVATE_TABLE_STATE_UNAVAILABLE' using errcode = 'P0001';
            end if;

            v_public_state := private.cbr_latest_public_state(new.room_id);
            v_cacheta_turn_card := nullif(v_public_state ->> 'turnCard', '');
            for v_meld in
                select item.value from jsonb_array_elements(v_team_melds) as item(value)
            loop
                if jsonb_array_length(v_meld) >= 7 then
                    v_has_canastra := true;
                    v_meld_check := private.cbr_validate_meld(
                        v_meld,
                        v_game_type,
                        v_allow_wildcards,
                        v_allow_charutos,
                        v_cacheta_turn_card
                    );
                    if v_meld_check ->> 'meldType' = 'CANASTRA_LIMPA' then
                        v_has_clean_canastra := true;
                    end if;
                end if;
            end loop;

            if not v_has_canastra then
                raise exception 'WIN_REQUIRES_CANASTRA' using errcode = 'P0001';
            end if;
            if v_require_clean and not v_has_clean_canastra then
                raise exception 'WIN_REQUIRES_CLEAN_CANASTRA' using errcode = 'P0001';
            end if;
        end if;
        return new;
    end if;

    return new;
end;
$$;

revoke all on function private.track_client_private_match_state() from public;

drop trigger if exists track_client_private_match_state on public.match_events;
create trigger track_client_private_match_state
after insert on public.match_events
for each row
execute function private.track_client_private_match_state();

create or replace function private.clear_completed_match_private_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from private.match_seat_state where room_id = new.room_id;
    delete from private.match_team_state where room_id = new.room_id;
    return new;
end;
$$;

revoke all on function private.clear_completed_match_private_state() from public;

drop trigger if exists clear_completed_match_private_state on public.match_results;
create trigger clear_completed_match_private_state
after insert on public.match_results
for each row
execute function private.clear_completed_match_private_state();
