-- O host ja conferia estas duas regras com a copia local da mao do convidado.
-- Como o servidor tambem mantem essa mao no ledger privado, a decisao pode ser
-- repetida aqui e deixa de depender de um aplicativo host sem alteracoes.

create or replace function private.cbr_can_justify_discard_draw(
    p_top_discard text,
    p_hand jsonb,
    p_team_melds jsonb,
    p_game_type text,
    p_allow_wildcards boolean,
    p_allow_charutos boolean,
    p_cacheta_turn_card text
)
returns boolean
language plpgsql
stable
set search_path = ''
as $$
declare
    v_meld jsonb;
    v_candidate jsonb;
    v_check jsonb;
    v_hand_size integer;
begin
    if not private.cbr_is_valid_card_id(p_top_discard)
       or jsonb_typeof(coalesce(p_hand, '[]'::jsonb)) <> 'array'
       or jsonb_typeof(coalesce(p_team_melds, '[]'::jsonb)) <> 'array' then
        return false;
    end if;

    -- Primeiro tento encaixar o topo em cada jogo que ja esta na mesa.
    for v_meld in
        select meld.value
        from jsonb_array_elements(coalesce(p_team_melds, '[]'::jsonb)) meld(value)
    loop
        v_candidate := v_meld || jsonb_build_array(p_top_discard);
        v_check := private.cbr_validate_meld(
            v_candidate,
            p_game_type,
            p_allow_wildcards,
            p_allow_charutos,
            p_cacheta_turn_card
        );
        if coalesce((v_check ->> 'valid')::boolean, false) then
            return true;
        end if;
    end loop;

    -- Qualquer novo jogo valido que use o topo possui um nucleo de tres
    -- cartas. Portanto basta testar o topo com cada par da mao; cartas extras
    -- do lixo nunca entram nesta justificativa antes de o topo ser baixado.
    v_hand_size := jsonb_array_length(coalesce(p_hand, '[]'::jsonb));
    if v_hand_size < 2 then
        return false;
    end if;

    for i in 0 .. v_hand_size - 2 loop
        for j in i + 1 .. v_hand_size - 1 loop
            v_candidate := jsonb_build_array(
                p_top_discard,
                p_hand ->> i,
                p_hand ->> j
            );
            v_check := private.cbr_validate_meld(
                v_candidate,
                p_game_type,
                p_allow_wildcards,
                p_allow_charutos,
                p_cacheta_turn_card
            );
            if coalesce((v_check ->> 'valid')::boolean, false) then
                return true;
            end if;
        end loop;
    end loop;

    return false;
end;
$$;

revoke all on function private.cbr_can_justify_discard_draw(
    text, jsonb, jsonb, text, boolean, boolean, text
) from public, anon, authenticated;

create or replace function private.enforce_discard_strategy_rules()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room public.match_rooms%rowtype;
    v_public_state jsonb;
    v_hand jsonb;
    v_team integer;
    v_team_melds jsonb;
    v_top_discard text;
    v_inner jsonb;
    v_card text;
    v_remaining_hand jsonb;
    v_allow_wildcards boolean;
    v_allow_charutos boolean;
    v_cacheta_turn_card text;
    v_wild_rank text;
    v_wild_suit text;
    v_has_normal_option boolean;
    v_has_clean_target boolean := false;
    v_meld jsonb;
begin
    if new.seat is null
       or new.seat = 0
       or new.event_type not in ('DRAW_DISCARD', 'DISCARD') then
        return new;
    end if;

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

    select state.hand, player.team
    into v_hand, v_team
    from private.match_seat_state state
    join public.room_players player
      on player.room_id = state.room_id
     and player.seat = state.seat
    where state.room_id = new.room_id
      and state.seat = new.seat
      and state.initialized;

    -- Rodadas antigas sem ledger seguem na validacao legada do host.
    if v_hand is null then
        return new;
    end if;

    select coalesce(state.melds, '[]'::jsonb)
    into v_team_melds
    from private.match_team_state state
    where state.room_id = new.room_id
      and state.team = v_team;

    v_public_state := private.cbr_latest_public_state(new.room_id);
    v_team_melds := coalesce(
        v_team_melds,
        v_public_state -> ('team' || v_team || 'Melds'),
        '[]'::jsonb
    );
    v_allow_wildcards := coalesce((v_room.config ->> 'allowWildcards')::boolean, true);
    v_allow_charutos := coalesce((v_room.config ->> 'allowCharutos')::boolean, true);
    v_cacheta_turn_card := nullif(v_public_state ->> 'turnCard', '');

    if new.event_type = 'DRAW_DISCARD' then
        if v_room.game_type = 'CACHETA' then
            return new;
        end if;

        v_top_discard := trim(coalesce(new.payload ->> 'payload', ''));
        if not private.cbr_can_justify_discard_draw(
            v_top_discard,
            v_hand,
            v_team_melds,
            v_room.game_type,
            v_allow_wildcards,
            v_allow_charutos,
            v_cacheta_turn_card
        ) then
            raise exception 'DISCARD_DRAW_NOT_JUSTIFIED' using errcode = 'P0001';
        end if;
        return new;
    end if;

    begin
        v_inner := (new.payload ->> 'payload')::jsonb;
        v_card := trim(coalesce(v_inner ->> 'card', ''));
    exception
        when others then
            return new;
    end;

    -- Posse e formato continuam sendo verificados pelos gatilhos anteriores.
    if not (v_hand @> jsonb_build_array(v_card)) then
        return new;
    end if;

    if v_room.game_type = 'CACHETA' then
        return new;
    end if;

    if not private.cbr_is_wildcard(
        v_card,
        v_room.game_type,
        v_allow_wildcards,
        v_wild_rank,
        v_wild_suit
    ) then
        return new;
    end if;

    v_remaining_hand := private.cbr_subtract_cards(v_hand, jsonb_build_array(v_card));
    select exists (
        select 1
        from jsonb_array_elements_text(v_remaining_hand) remaining(card)
        where not private.cbr_is_wildcard(
            remaining.card,
            v_room.game_type,
            v_allow_wildcards,
            v_wild_rank,
            v_wild_suit
        )
    ) into v_has_normal_option;

    -- Se so restaram curingas, nao existe outro descarte possivel.
    if not v_has_normal_option then
        return new;
    end if;

    for v_meld in
        select meld.value
        from jsonb_array_elements(v_team_melds) meld(value)
    loop
        if jsonb_array_length(v_meld) >= 3 and not exists (
            select 1
            from jsonb_array_elements_text(v_meld) meld_card(card)
            where private.cbr_is_wildcard(
                meld_card.card,
                v_room.game_type,
                v_allow_wildcards,
                v_wild_rank,
                v_wild_suit
            )
        ) then
            v_has_clean_target := true;
            exit;
        end if;
    end loop;

    if v_has_clean_target or private.cbr_can_justify_discard_draw(
        v_card,
        v_remaining_hand,
        v_team_melds,
        v_room.game_type,
        v_allow_wildcards,
        v_allow_charutos,
        v_cacheta_turn_card
    ) then
        raise exception 'WILDCARD_DISCARD_REQUIRED_FOR_PLAY' using errcode = 'P0001';
    end if;

    return new;
end;
$$;

drop trigger if exists enforce_discard_strategy_rules on public.match_events;
create trigger enforce_discard_strategy_rules
before insert on public.match_events
for each row execute function private.enforce_discard_strategy_rules();

comment on function private.cbr_can_justify_discard_draw(
    text, jsonb, jsonb, text, boolean, boolean, text
) is 'Confere se o topo do lixo forma um jogo novo ou completa uma baixa existente.';
