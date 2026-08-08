-- Fase 3b da autoridade do servidor: fecha o ultimo gap conhecido da 0025.
-- O pedido explicito de um time pegar o morto inteiro como mao
-- (REQ_PICK_MORTO/SERVE_MORTO) ainda era decidido e servido pelo host em
-- memoria -- um host adulterado podia forjar o conteudo do morto entregue
-- (ou pra si mesmo, ou pro parceiro/adversario).
--
-- online_take_morto reaproveita a mesma tabela private.match_deck_state que
-- a 0025 ja usa pra compra do monte -- e por isso que os dois mecanismos
-- (compra que vira monte novo e pedido explicito de morto) nunca podem
-- tentar consumir o mesmo morto: os dois disputam a mesma coluna `mortos`,
-- com "for update" travando a linha inteira contra qualquer corrida.

create or replace function public.online_take_morto(p_room_id uuid, p_seat integer, p_indirect boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_team integer;
    v_hand jsonb;
    v_hand_initialized boolean;
    v_picked_morto boolean;
    v_deck jsonb;
    v_mortos jsonb;
    v_raw_morto jsonb;
    v_prepared_hand jsonb := '[]'::jsonb;
    v_red_three_melds jsonb := '[]'::jsonb;
    v_card text;
    v_auto_meld boolean;
    v_public_state jsonb;
    v_team_melds jsonb;
    v_team_melds_initialized boolean;
    v_message_id text;
    v_round_id text;
    v_cards_per_player integer;
    v_active_seat integer;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select r.* into v_room
    from public.match_rooms r
    where r.id = p_room_id
      and r.status = 'playing'
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    if v_room.host_id <> v_user_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;

    if v_room.game_type = 'CACHETA' then
        raise exception 'MORTO_NOT_ALLOWED' using errcode = 'P0001';
    end if;

    if p_seat not between 0 and v_room.max_players - 1 then
        raise exception 'INVALID_SEAT' using errcode = 'P0001';
    end if;

    v_cards_per_player := coalesce((v_room.config ->> 'cardsPerPlayer')::integer, 11);
    v_auto_meld := coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true);

    select player.team
    into v_team
    from public.room_players player
    where player.room_id = p_room_id
      and player.seat = p_seat;

    if v_team is null then
        raise exception 'PLAYER_NOT_FOUND' using errcode = 'P0001';
    end if;

    -- O assento 0 (host) nao tem ledger de mao privada (0016 so rastreia
    -- clientes remotos): a checagem de mao vazia pra ele fica por conta do
    -- proprio host, que so chama esta RPC quando sabe que zerou a mao --
    -- mesmo modelo de confianca ja usado pelo host em online_draw_deck_card
    -- pra decidir de quem e a vez.
    if p_seat <> 0 then
        select state.hand, state.initialized
        into v_hand, v_hand_initialized
        from private.match_seat_state state
        where state.room_id = p_room_id
          and state.seat = p_seat
        for update;

        if not coalesce(v_hand_initialized, false) then
            raise exception 'PRIVATE_PLAYER_STATE_NOT_FOUND' using errcode = 'P0001';
        end if;
        if jsonb_array_length(v_hand) <> 0 then
            raise exception 'MORTO_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
        end if;
    end if;

    select coalesce(team_state.picked_morto, false)
    into v_picked_morto
    from private.match_team_state team_state
    where team_state.room_id = p_room_id
      and team_state.team = v_team
    for update;

    if coalesce(v_picked_morto, false) then
        raise exception 'TEAM_ALREADY_PICKED_MORTO' using errcode = 'P0001';
    end if;

    select deck, mortos
    into v_deck, v_mortos
    from private.match_deck_state
    where room_id = p_room_id
    for update;

    if v_deck is null then
        raise exception 'DECK_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;
    if jsonb_array_length(v_mortos) = 0 then
        raise exception 'NO_MORTO_AVAILABLE' using errcode = 'P0001';
    end if;

    v_raw_morto := v_mortos -> 0;
    v_mortos := v_mortos - 0;

    -- Extrai 3 vermelho e repoe do monte, igual prepareMortoForPickup() no
    -- Kotlin: se a reposicao tambem for 3 vermelho, ela tambem vira meld e
    -- puxa outra carta, ate a mao estabilizar no tamanho regulamentar.
    for v_idx in 0 .. jsonb_array_length(v_raw_morto) - 1 loop
        v_card := v_raw_morto ->> v_idx;
        if v_room.game_type = 'TRANCA' and v_auto_meld and private.cbr_is_red_three(v_card) then
            v_red_three_melds := v_red_three_melds || jsonb_build_array(jsonb_build_array(v_card));
        else
            v_prepared_hand := v_prepared_hand || jsonb_build_array(v_card);
        end if;
    end loop;

    while jsonb_array_length(v_prepared_hand) < v_cards_per_player and jsonb_array_length(v_deck) > 0 loop
        v_card := v_deck ->> (jsonb_array_length(v_deck) - 1);
        v_deck := v_deck - (jsonb_array_length(v_deck) - 1);
        if v_room.game_type = 'TRANCA' and v_auto_meld and private.cbr_is_red_three(v_card) then
            v_red_three_melds := v_red_three_melds || jsonb_build_array(jsonb_build_array(v_card));
        else
            v_prepared_hand := v_prepared_hand || jsonb_build_array(v_card);
        end if;
    end loop;

    if jsonb_array_length(v_prepared_hand) <> v_cards_per_player then
        raise exception 'MORTO_PREPARATION_FAILED' using errcode = 'P0001';
    end if;

    update private.match_deck_state
    set deck = v_deck,
        mortos = v_mortos,
        updated_at = now()
    where room_id = p_room_id;

    -- Nao atualizo private.match_seat_state.hand aqui: o proprio trigger da
    -- 0016 (track_client_private_match_state, ramo SERVE_MORTO) ja faz isso
    -- sozinho ao processar o evento publicado abaixo -- e exige que a mao
    -- ainda esteja vazia NAQUELE momento. Achei isso testando local: se eu
    -- atualizasse a mao aqui antes, o trigger via a mao "ja preenchida" e
    -- recusava com MORTO_REQUIRES_EMPTY_HAND, achando duplicata.

    v_public_state := private.cbr_latest_public_state(p_room_id);
    v_active_seat := coalesce((v_public_state ->> 'activeSeat')::integer, p_seat);

    select state.melds, state.melds_initialized
    into v_team_melds, v_team_melds_initialized
    from private.match_team_state state
    where state.room_id = p_room_id and state.team = v_team
    for update;

    if not coalesce(v_team_melds_initialized, false) then
        v_team_melds := coalesce(v_public_state -> ('team' || v_team || 'Melds'), '[]'::jsonb);
    end if;
    if jsonb_array_length(v_red_three_melds) > 0 then
        v_team_melds := v_team_melds || v_red_three_melds;
    end if;

    select event.payload ->> 'roundId'
    into v_round_id
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;
    v_round_id := coalesce(v_round_id, '');

    if p_seat <> 0 then
        -- Publico o SERVE_MORTO ANTES de mexer em match_team_state.picked_morto:
        -- o trigger da 0016 tambem checa esse mesmo campo pra recusar pedido
        -- duplicado, e le a versao ATUAL da linha -- se eu marcasse
        -- picked_morto=true antes de publicar, o proprio trigger veria "true"
        -- e recusaria como se fosse a segunda tentativa (achei isso testando
        -- local). O trigger cuida sozinho de marcar picked_morto=true e de
        -- preencher a mao do assento; eu so preciso atualizar os jogos de 3
        -- vermelho depois, porque o trigger nao mexe em melds.
        v_message_id := gen_random_uuid()::text;
        perform private.cbr_publish_server_event(
            p_room_id => p_room_id,
            p_actor_id => v_user_id,
            p_event_type => 'SERVE_MORTO',
            p_payload => jsonb_build_object(
                'type', 'SERVE_MORTO',
                'senderId', v_user_id::text,
                'roundId', v_round_id,
                'messageId', v_message_id,
                'payload', jsonb_build_object(
                    'v', 1,
                    'hand', v_prepared_hand,
                    'mortosLeft', jsonb_array_length(v_mortos),
                    'indirect', coalesce(p_indirect, false),
                    'activeSeat', v_active_seat
                )::text
            ),
            p_recipient_seat => p_seat
        );

        update private.match_team_state
        set melds = v_team_melds,
            melds_initialized = true,
            updated_at = now()
        where room_id = p_room_id and team = v_team;
    else
        -- Assento 0 (host): sem evento privado pra si mesmo, entao sem
        -- trigger pra marcar picked_morto -- preciso fazer isso eu mesmo aqui.
        insert into private.match_team_state(room_id, team, melds, melds_initialized, picked_morto, updated_at)
        values (p_room_id, v_team, v_team_melds, true, true, now())
        on conflict (room_id, team) do update
        set melds = excluded.melds,
            melds_initialized = true,
            picked_morto = true,
            updated_at = now();
    end if;

    v_message_id := gen_random_uuid()::text;
    perform private.cbr_publish_server_event(
        p_room_id => p_room_id,
        p_actor_id => v_user_id,
        p_event_type => 'PUBLIC_STATE',
        p_payload => jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_user_id::text,
            'roundId', v_round_id,
            'messageId', v_message_id,
            'payload', jsonb_build_object(
                'v', 1,
                'activeSeat', v_active_seat,
                'deckSize', jsonb_array_length(v_deck),
                'discardCount', coalesce(jsonb_array_length(v_public_state -> 'discardPile'), 0),
                'discardPile', coalesce(v_public_state -> 'discardPile', '[]'::jsonb),
                'turnCard', coalesce(v_public_state ->> 'turnCard', ''),
                'mortosLeft', jsonb_array_length(v_mortos),
                'handCounts', coalesce(v_public_state -> 'handCounts', '[]'::jsonb),
                'team0Melds', case when v_team = 0 then v_team_melds else coalesce(v_public_state -> 'team0Melds', '[]'::jsonb) end,
                'team1Melds', case when v_team = 1 then v_team_melds else coalesce(v_public_state -> 'team1Melds', '[]'::jsonb) end
            )::text
        ),
        p_recipient_seat => null
    );

    return jsonb_build_object(
        'status', 'OK',
        'hand', v_prepared_hand,
        'redThreeMelds', v_red_three_melds,
        'team', v_team,
        'mortosLeft', jsonb_array_length(v_mortos),
        'deckSize', jsonb_array_length(v_deck)
    );
end;
$$;

revoke all on function public.online_take_morto(uuid, integer, boolean) from public;
grant execute on function public.online_take_morto(uuid, integer, boolean) to authenticated;
