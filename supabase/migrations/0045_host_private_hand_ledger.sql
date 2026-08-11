-- O assento do host agora participa do mesmo ledger privado usado pelos
-- convidados. Assim o banco consegue conferir posse, baixa, descarte, morto
-- e vitoria sem confiar na copia da mao mantida pelo aparelho do host.

alter table private.match_seat_state
drop constraint if exists match_seat_state_seat_check;

alter table private.match_seat_state
add constraint match_seat_state_seat_check
check (seat between 0 and 3);

-- Marco entregas criadas pelas RPCs internas. O host continua sendo o ator
-- dos eventos para o Realtime, mas nao pode fabricar GAME_START/SERVE_* pelo
-- endpoint publico e fazer o banco aceitar cartas que nao sairam do baralho.
create or replace function private.cbr_publish_server_event(
    p_room_id uuid,
    p_actor_id uuid,
    p_event_type text,
    p_payload jsonb,
    p_recipient_seat integer
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_previous_marker text := current_setting('cbr.server_private_delivery', true);
begin
    perform set_config('cbr.server_private_delivery', 'on', true);
    begin
        insert into public.match_events(
            room_id, message_id, actor_id, seat, recipient_seat, event_type, payload
        ) values (
            p_room_id,
            trim(p_payload ->> 'messageId'),
            p_actor_id,
            0,
            p_recipient_seat,
            p_event_type,
            p_payload
        )
        on conflict (room_id, message_id) do nothing;
    exception
        when others then
            perform set_config(
                'cbr.server_private_delivery',
                coalesce(v_previous_marker, ''),
                true
            );
            raise;
    end;
    perform set_config(
        'cbr.server_private_delivery',
        coalesce(v_previous_marker, ''),
        true
    );
end;
$$;

revoke all on function private.cbr_publish_server_event(
    uuid, uuid, text, jsonb, integer
) from public, anon, authenticated;

create or replace function private.enforce_server_private_delivery()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if new.event_type in ('GAME_START', 'SERVE_CARD', 'SERVE_MORTO')
       and current_setting('cbr.server_private_delivery', true) is distinct from 'on' then
        raise exception 'SERVER_DELIVERY_REQUIRED' using errcode = 'P0001';
    end if;
    return new;
end;
$$;

revoke all on function private.enforce_server_private_delivery()
from public, anon, authenticated;

drop trigger if exists enforce_server_private_delivery on public.match_events;
create trigger enforce_server_private_delivery
before insert on public.match_events
for each row execute function private.enforce_server_private_delivery();

-- Alem da trava contra uma rodada duplicada, inicializo a mao do host depois
-- que a distribuicao interna conclui. As maos dos convidados continuam sendo
-- inicializadas pelos GAME_START privados da mesma transacao.
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
    v_host_hand jsonb;
    v_expected_hand_size integer;
    v_previous_marker text := current_setting('cbr.server_private_delivery', true);
begin
    select room.status,
           case when room.game_type = 'CACHETA' then 9 else 11 end
    into v_status, v_expected_hand_size
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

    perform set_config('cbr.server_private_delivery', 'on', true);
    begin
        v_result := private.cbr_start_online_round_with_private_material(p_room_id);
    exception
        when others then
            perform set_config(
                'cbr.server_private_delivery',
                coalesce(v_previous_marker, ''),
                true
            );
            raise;
    end;
    perform set_config(
        'cbr.server_private_delivery',
        coalesce(v_previous_marker, ''),
        true
    );

    v_host_hand := v_result -> 'hand';
    if not private.cbr_is_valid_card_array(
        v_host_hand,
        v_expected_hand_size,
        v_expected_hand_size
    ) then
        raise exception 'INVALID_HOST_INITIAL_HAND' using errcode = 'P0001';
    end if;
    if private.cbr_cards_overlap_other_hands(p_room_id, 0, v_host_hand) then
        raise exception 'CARD_ASSIGNED_TO_MULTIPLE_PLAYERS' using errcode = 'P0001';
    end if;

    insert into private.match_seat_state(
        room_id, seat, hand, required_discard_card,
        pending_discard_cards, initialized, updated_at
    ) values (
        p_room_id, 0, v_host_hand, null, '[]'::jsonb, true, now()
    )
    on conflict (room_id, seat) do update
    set hand = excluded.hand,
        required_discard_card = null,
        pending_discard_cards = '[]'::jsonb,
        initialized = true,
        updated_at = now();

    update private.match_deck_state
    set drawn_seat = null,
        updated_at = now()
    where room_id = p_room_id;

    return v_result - 'mortos';
end;
$$;

revoke all on function public.start_online_round(uuid)
from public, anon, authenticated;

-- As jogadas normais do host passam pelo mesmo tipo de conferencia aplicada
-- aos convidados. Esta funcao tambem atualiza a mesa da equipe no MELD; por
-- isso o tracker antigo e ignorado somente nesse ramo para nao somar duas vezes.
create or replace function private.track_host_private_match_state()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room public.match_rooms%rowtype;
    v_inner jsonb;
    v_public_state jsonb;
    v_hand jsonb;
    v_new_hand jsonb;
    v_pending jsonb;
    v_required_card text;
    v_card text;
    v_discard_pile jsonb;
    v_team integer;
    v_team_melds jsonb;
    v_team_melds_initialized boolean;
    v_resulting_meld jsonb;
    v_existing_meld jsonb;
    v_cards_from_hand jsonb;
    v_replace_index integer;
    v_meld_check jsonb;
    v_cacheta_turn_card text;
    v_allow_wildcards boolean;
    v_allow_charutos boolean;
    v_require_clean boolean;
    v_picked_morto boolean := false;
    v_has_canastra boolean := false;
    v_has_clean_canastra boolean := false;
    v_meld jsonb;
    v_wild_rank text;
    v_wild_suit text;
    v_remaining_hand jsonb;
    v_has_normal_option boolean;
    v_has_clean_target boolean := false;
begin
    -- SERVE_MORTO usa o assento do servidor no evento. O destinatario e quem
    -- diz qual ledger deve receber as cartas, entao deixo os convidados para
    -- o tracker de assentos remotos logo abaixo.
    if new.event_type = 'SERVE_MORTO' and new.recipient_seat is distinct from 0 then
        return new;
    end if;

    if new.seat <> 0 then
        return new;
    end if;

    if new.event_type = 'SERVE_MORTO' then
        if current_setting('cbr.server_private_delivery', true) is distinct from 'on' then
            raise exception 'SERVER_DELIVERY_REQUIRED' using errcode = 'P0001';
        end if;

        select room.* into v_room
        from public.match_rooms room
        where room.id = new.room_id;
        if v_room.game_type = 'CACHETA' then
            raise exception 'MORTO_NOT_ALLOWED' using errcode = 'P0001';
        end if;

        begin
            v_inner := (new.payload ->> 'payload')::jsonb;
        exception
            when others then
                raise exception 'INVALID_MORTO_PAYLOAD' using errcode = 'P0001';
        end;
        v_new_hand := v_inner -> 'hand';
        if new.recipient_seat <> 0
           or not private.cbr_is_valid_card_array(v_new_hand, 11, 11) then
            raise exception 'INVALID_MORTO_HAND' using errcode = 'P0001';
        end if;

        select state.hand, state.pending_discard_cards, state.required_discard_card
        into v_hand, v_pending, v_required_card
        from private.match_seat_state state
        where state.room_id = new.room_id and state.seat = 0
        for update;
        if v_hand is null then
            raise exception 'PRIVATE_HOST_STATE_NOT_FOUND' using errcode = 'P0001';
        end if;
        if jsonb_array_length(v_hand) <> 0
           or jsonb_array_length(v_pending) <> 0
           or v_required_card is not null then
            raise exception 'MORTO_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
        end if;
        if private.cbr_cards_overlap_other_hands(new.room_id, 0, v_new_hand) then
            raise exception 'CARD_ASSIGNED_TO_MULTIPLE_PLAYERS' using errcode = 'P0001';
        end if;

        select player.team into v_team
        from public.room_players player
        where player.room_id = new.room_id and player.seat = 0;
        select coalesce(state.picked_morto, false) into v_picked_morto
        from private.match_team_state state
        where state.room_id = new.room_id and state.team = v_team
        for update;
        if coalesce(v_picked_morto, false) then
            raise exception 'TEAM_ALREADY_PICKED_MORTO' using errcode = 'P0001';
        end if;

        update private.match_seat_state
        set hand = v_new_hand,
            required_discard_card = null,
            pending_discard_cards = '[]'::jsonb,
            updated_at = now()
        where room_id = new.room_id and seat = 0;
        insert into private.match_team_state(room_id, team, picked_morto, updated_at)
        values (new.room_id, v_team, true, now())
        on conflict (room_id, team) do update
        set picked_morto = true,
            updated_at = now();
        return new;
    end if;

    if new.event_type not in (
        'DRAW_DISCARD', 'DISCARD', 'MELD', 'REQ_PICK_MORTO', 'WIN_ROUND'
    ) then
        return new;
    end if;

    select room.* into v_room
    from public.match_rooms room
    where room.id = new.room_id;
    select state.hand, state.pending_discard_cards, state.required_discard_card
    into v_hand, v_pending, v_required_card
    from private.match_seat_state state
    where state.room_id = new.room_id and state.seat = 0
    for update;
    if v_hand is null then
        raise exception 'PRIVATE_HOST_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;

    select player.team into v_team
    from public.room_players player
    where player.room_id = new.room_id and player.seat = 0;
    if v_team is null then
        raise exception 'PRIVATE_PLAYER_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;

    v_public_state := private.cbr_latest_public_state(new.room_id);
    if new.event_type in ('DRAW_DISCARD', 'DISCARD', 'MELD')
       and coalesce(v_public_state ->> 'activeSeat', '') <> '0' then
        raise exception 'OUT_OF_TURN_EVENT' using errcode = 'P0001';
    end if;

    v_allow_wildcards := coalesce((v_room.config ->> 'allowWildcards')::boolean, true);
    v_allow_charutos := coalesce((v_room.config ->> 'allowCharutos')::boolean, true);
    v_require_clean := coalesce(
        (v_room.config ->> 'requireCleanCanastraToWin')::boolean,
        true
    );
    v_cacheta_turn_card := nullif(v_public_state ->> 'turnCard', '');
    select state.melds, state.melds_initialized, state.picked_morto
    into v_team_melds, v_team_melds_initialized, v_picked_morto
    from private.match_team_state state
    where state.room_id = new.room_id and state.team = v_team
    for update;
    if not coalesce(v_team_melds_initialized, false) then
        v_team_melds := coalesce(
            v_public_state -> ('team' || v_team || 'Melds'),
            '[]'::jsonb
        );
    end if;

    if new.event_type = 'DRAW_DISCARD' then
        v_discard_pile := v_public_state -> 'discardPile';
        v_card := trim(coalesce(new.payload ->> 'payload', ''));
        if not private.cbr_is_valid_card_array(v_discard_pile, 1, 108)
           or v_card <> v_discard_pile ->> (jsonb_array_length(v_discard_pile) - 1) then
            raise exception 'DISCARD_TOP_MISMATCH' using errcode = 'P0001';
        end if;
        if v_room.game_type <> 'CACHETA'
           and not private.cbr_can_justify_discard_draw(
               v_card, v_hand, v_team_melds, v_room.game_type,
               v_allow_wildcards, v_allow_charutos, v_cacheta_turn_card
           ) then
            raise exception 'DISCARD_DRAW_NOT_JUSTIFIED' using errcode = 'P0001';
        end if;
        if v_hand @> jsonb_build_array(v_card)
           or private.cbr_cards_overlap_other_hands(
               new.room_id, 0, jsonb_build_array(v_card)
           ) then
            raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
        end if;

        if v_room.game_type = 'CACHETA' then
            v_pending := '[]'::jsonb;
            v_required_card := null;
        else
            select coalesce(
                jsonb_agg(to_jsonb(pile.card) order by pile.ordinal),
                '[]'::jsonb
            )
            into v_pending
            from jsonb_array_elements_text(v_discard_pile)
                 with ordinality as pile(card, ordinal)
            where pile.ordinal < jsonb_array_length(v_discard_pile);
            v_required_card := v_card;
        end if;

        update private.match_seat_state
        set hand = hand || jsonb_build_array(v_card),
            required_discard_card = v_required_card,
            pending_discard_cards = v_pending,
            updated_at = now()
        where room_id = new.room_id and seat = 0;
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

        if v_room.game_type <> 'CACHETA'
           and private.cbr_is_wildcard(
               v_card, v_room.game_type, v_allow_wildcards,
               v_wild_rank, v_wild_suit
           ) then
            v_remaining_hand := private.cbr_subtract_cards(
                v_hand,
                jsonb_build_array(v_card)
            );
            select exists (
                select 1
                from jsonb_array_elements_text(v_remaining_hand) remaining(card)
                where not private.cbr_is_wildcard(
                    remaining.card, v_room.game_type, v_allow_wildcards,
                    v_wild_rank, v_wild_suit
                )
            ) into v_has_normal_option;

            if v_has_normal_option then
                for v_meld in
                    select meld.value
                    from jsonb_array_elements(v_team_melds) meld(value)
                loop
                    if jsonb_array_length(v_meld) >= 3 and not exists (
                        select 1
                        from jsonb_array_elements_text(v_meld) meld_card(card)
                        where private.cbr_is_wildcard(
                            meld_card.card, v_room.game_type, v_allow_wildcards,
                            v_wild_rank, v_wild_suit
                        )
                    ) then
                        v_has_clean_target := true;
                        exit;
                    end if;
                end loop;
                if v_has_clean_target or private.cbr_can_justify_discard_draw(
                    v_card, v_remaining_hand, v_team_melds, v_room.game_type,
                    v_allow_wildcards, v_allow_charutos, v_cacheta_turn_card
                ) then
                    raise exception 'WILDCARD_DISCARD_REQUIRED_FOR_PLAY'
                        using errcode = 'P0001';
                end if;
            end if;
        end if;

        update private.match_seat_state
        set hand = private.cbr_subtract_cards(hand, jsonb_build_array(v_card)),
            updated_at = now()
        where room_id = new.room_id and seat = 0;
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

        v_meld_check := private.cbr_validate_meld(
            v_resulting_meld,
            v_room.game_type,
            v_allow_wildcards,
            v_allow_charutos,
            v_cacheta_turn_card
        );
        if not coalesce((v_meld_check ->> 'valid')::boolean, false) then
            raise exception 'INVALID_MELD_SHAPE' using errcode = 'P0001';
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
            v_cards_from_hand := private.cbr_subtract_cards(
                v_resulting_meld,
                v_existing_meld
            );
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
               or private.cbr_cards_overlap_other_hands(new.room_id, 0, v_pending) then
                raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
            end if;
            v_new_hand := v_new_hand || v_pending;
        end if;

        update private.match_seat_state
        set hand = v_new_hand,
            required_discard_card = null,
            pending_discard_cards = '[]'::jsonb,
            updated_at = now()
        where room_id = new.room_id and seat = 0;
        insert into private.match_team_state(
            room_id, team, melds, melds_initialized, updated_at
        ) values (
            new.room_id, v_team, v_team_melds, true, now()
        )
        on conflict (room_id, team) do update
        set melds = excluded.melds,
            melds_initialized = true,
            updated_at = now();
        return new;
    end if;

    if new.event_type = 'REQ_PICK_MORTO' then
        if v_room.game_type = 'CACHETA' then
            raise exception 'MORTO_NOT_ALLOWED' using errcode = 'P0001';
        end if;
        if jsonb_array_length(v_hand) <> 0
           or jsonb_array_length(v_pending) <> 0
           or v_required_card is not null then
            raise exception 'MORTO_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
        end if;
        if coalesce(v_picked_morto, false) then
            raise exception 'TEAM_ALREADY_PICKED_MORTO' using errcode = 'P0001';
        end if;
        return new;
    end if;

    if jsonb_array_length(v_hand) <> 0
       or jsonb_array_length(v_pending) <> 0
       or v_required_card is not null then
        raise exception 'WIN_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
    end if;
    if v_room.game_type <> 'CACHETA' then
        if not coalesce(v_picked_morto, false) then
            raise exception 'WIN_REQUIRES_MORTO' using errcode = 'P0001';
        end if;
        for v_meld in
            select item.value from jsonb_array_elements(v_team_melds) item(value)
        loop
            if jsonb_array_length(v_meld) >= 7 then
                v_has_canastra := true;
                v_meld_check := private.cbr_validate_meld(
                    v_meld, v_room.game_type, v_allow_wildcards,
                    v_allow_charutos, v_cacheta_turn_card
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
end;
$$;

revoke all on function private.track_host_private_match_state()
from public, anon, authenticated;

drop trigger if exists a_track_host_private_match_state on public.match_events;
create trigger a_track_host_private_match_state
after insert on public.match_events
for each row execute function private.track_host_private_match_state();

-- O tracker antigo ainda cuida dos convidados e dos eventos de controle. Os
-- dois ramos abaixo passaram ao tracker do host para nao atualizar duas vezes.
drop trigger if exists track_client_private_match_state on public.match_events;
create trigger track_client_private_match_state
after insert on public.match_events
for each row
when (
    not (
        (new.event_type = 'MELD' and new.seat is not distinct from 0)
        or (
            new.event_type = 'SERVE_MORTO'
            and new.recipient_seat is not distinct from 0
        )
    )
)
execute function private.track_client_private_match_state();

-- Quando o 3 vermelho automatico estiver desligado ele entra na mao, inclusive
-- no assento 0. O tracker principal ignora esse caso para a regra automatica,
-- portanto esta reconciliacao continua sendo necessaria.
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
    if new.recipient_seat not between 0 and 3 then
        raise exception 'PRIVATE_HAND_SEAT_MISMATCH' using errcode = 'P0001';
    end if;

    select state.hand, state.initialized
    into v_hand, v_initialized
    from private.match_seat_state state
    where state.room_id = new.room_id and state.seat = new.recipient_seat
    for update;
    if not coalesce(v_initialized, false) then
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
    where room_id = new.room_id and seat = new.recipient_seat;
    return new;
end;
$$;

revoke all on function private.track_manual_tranca_red_three()
from public, anon, authenticated;

comment on table private.match_seat_state is
'Ledger privado das maos de todos os assentos, inclusive o host.';

comment on function private.enforce_server_private_delivery() is
'Impede que um cliente fabrique distribuicao ou entrega privada fora das RPCs do servidor.';
