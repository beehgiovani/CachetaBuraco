-- Mais uma coisa que eu ia esquecer antes de ligar o app: os jogos de 3
-- vermelho ja pre-formados na Tranca (v_team_melds) eu so publicava dentro do
-- PUBLIC_STATE -- mas o host nunca ve a propria transmissao de volta
-- (handleNetworkMessage descarta de cara qualquer evento com senderId igual
-- ao proprio, e quem chama essa RPC e o host). Mesmo caso do `hands` que ja
-- corrigi na 0021: preciso devolver os jogos da equipe tambem no retorno que
-- so o host recebe, senao ele fica sem saber quais 3 vermelho ja foram
-- baixados na propria mesa nem na do adversario.

create or replace function public.start_online_round(p_room_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_seat integer;
    v_max_players integer;
    v_cards_per_player integer;
    v_auto_meld_red_threes boolean;
    v_cacheta_starts_with_discard boolean;
    v_deck text[];
    v_deal text[];
    v_card text;
    v_hands jsonb := '{}'::jsonb;
    -- 1-indexado (Postgres array literal comeca em 1): posicao 1 = time 0, posicao 2 = time 1.
    v_team_melds jsonb[] := array['[]'::jsonb, '[]'::jsonb];
    v_seat_red_threes text[];
    v_team integer;
    v_turn_card text := '';
    v_first_discard text := '';
    v_postponed text[];
    v_morto_0 text[];
    v_morto_1 text[];
    v_mortos_left integer := 0;
    v_round_id uuid := gen_random_uuid();
    v_hand_counts jsonb;
    v_message_id text;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    select r.* into v_room
    from public.match_rooms r
    where r.id = p_room_id
      and r.status in ('waiting', 'playing')
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    if v_room.host_id <> v_user_id then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;

    select rp.seat into v_seat
    from public.room_players rp
    where rp.room_id = p_room_id
      and rp.profile_id = v_user_id;

    if v_seat is null or v_seat <> 0 then
        raise exception 'HOST_REQUIRED' using errcode = 'P0001';
    end if;

    v_max_players := v_room.max_players;
    v_cards_per_player := coalesce(
        (v_room.config ->> 'cardsPerPlayer')::integer,
        case when v_room.game_type = 'CACHETA' then 9 else 11 end
    );
    v_auto_meld_red_threes := coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true);
    v_cacheta_starts_with_discard := coalesce((v_room.config ->> 'cachetaStartsWithDiscard')::boolean, false);

    v_deck := private.cbr_shuffled_deck();

    -- Distribuo a mao de cada assento. Na Tranca, com o 3 vermelho automatico
    -- ligado, tiro cada 3 vermelho sorteado da mao, reponho do monte e baixo
    -- ele sozinho na mesa da EQUIPE (nao do assento) -- mesma regra que ja
    -- tinha em GameRulesEngine.handleThreeReds, so que aqui compartilho entre
    -- os assentos do mesmo time conforme vou processando a distribuicao.
    for seat_idx in 0 .. v_max_players - 1 loop
        v_deal := array[]::text[];
        v_seat_red_threes := array[]::text[];

        while array_length(v_deal, 1) is null or array_length(v_deal, 1) < v_cards_per_player loop
            if array_length(v_deck, 1) is null or array_length(v_deck, 1) = 0 then
                raise exception 'DECK_EXHAUSTED_DURING_DEAL' using errcode = 'P0001';
            end if;
            v_card := v_deck[array_length(v_deck, 1)];
            v_deck := v_deck[1 : array_length(v_deck, 1) - 1];

            if v_room.game_type = 'TRANCA' and v_auto_meld_red_threes
               and private.cbr_is_red_three(v_card) then
                v_seat_red_threes := v_seat_red_threes || v_card;
            else
                v_deal := v_deal || v_card;
            end if;
        end loop;

        v_hands := v_hands || jsonb_build_object(seat_idx::text, to_jsonb(v_deal));

        if array_length(v_seat_red_threes, 1) > 0 then
            v_team := (seat_idx % 2) + 1;
            for i in 1 .. array_length(v_seat_red_threes, 1) loop
                v_team_melds[v_team] := v_team_melds[v_team] || jsonb_build_array(jsonb_build_array(v_seat_red_threes[i]));
            end loop;
        end if;
    end loop;

    -- Deixo os mortos intactos com onze cartas fisicas cada; o 3 vermelho so
    -- sai quando um time realmente pega esse morto, e esse fluxo (SERVE_MORTO)
    -- eu nao mexi aqui, continua igual no app.
    if v_room.game_type <> 'CACHETA' then
        v_morto_0 := v_deck[array_length(v_deck, 1) - 10 : array_length(v_deck, 1)];
        v_deck := v_deck[1 : array_length(v_deck, 1) - 11];
        v_morto_1 := v_deck[array_length(v_deck, 1) - 10 : array_length(v_deck, 1)];
        v_deck := v_deck[1 : array_length(v_deck, 1) - 11];
        v_mortos_left := 2;
    end if;

    -- Vira (Cacheta) e lixo de abertura.
    if v_room.game_type = 'CACHETA' then
        v_card := v_deck[array_length(v_deck, 1)];
        v_deck := v_deck[1 : array_length(v_deck, 1) - 1];
        v_turn_card := v_card;
        if v_cacheta_starts_with_discard then
            v_first_discard := v_turn_card;
        end if;
    elsif v_room.game_type = 'BURACO' then
        v_card := v_deck[array_length(v_deck, 1)];
        v_deck := v_deck[1 : array_length(v_deck, 1) - 1];
        v_first_discard := v_card;
    else
        -- Tranca: o 3 vermelho nunca abre o lixo. Separa, vira outra carta e
        -- devolve os 3 vermelhos pro meio do monte embaralhando de novo, sem
        -- perder carta -- mesma logica de drawOpeningDiscard() no Kotlin.
        v_postponed := array[]::text[];
        while v_first_discard = '' and (array_length(v_deck, 1) is not null and array_length(v_deck, 1) > 0) loop
            v_card := v_deck[array_length(v_deck, 1)];
            v_deck := v_deck[1 : array_length(v_deck, 1) - 1];
            if private.cbr_is_red_three(v_card) then
                v_postponed := v_postponed || v_card;
            else
                v_first_discard := v_card;
            end if;
        end loop;
        if array_length(v_postponed, 1) > 0 then
            select array_agg(x order by random()) into v_deck
            from unnest(v_deck || v_postponed) as x;
        end if;
    end if;

    -- Publico a mao privada de cada assento REMOTO (1..N-1) -- mesmo evento
    -- GAME_START que eu ja mandava manualmente do host pros outros assentos,
    -- so que agora com o conteudo decidido aqui dentro. O proprio host
    -- (assento 0) nunca recebe esse evento de si mesmo (o append_match_event
    -- exige p_recipient_seat <> 0 pra tipo privado do host); devolvo a mao do
    -- host no retorno da funcao, do jeito que o Kotlin ja usava myCards direto
    -- no estado local sem passar por mensagem de rede.
    for seat_idx in 1 .. v_max_players - 1 loop
        -- Preciso gerar o messageId uma vez so e reusar nos dois lugares: o
        -- append_match_event exige que o campo dentro do envelope bata
        -- exatamente com o parametro p_message_id (EVENT_ENVELOPE_MISMATCH se
        -- eu gerar dois uuid aleatorios diferentes, um pra cada lado).
        v_message_id := gen_random_uuid()::text;
        perform public.append_match_event(
            p_room_id => p_room_id,
            p_message_id => v_message_id,
            p_event_type => 'GAME_START',
            p_payload => jsonb_build_object(
                'type', 'GAME_START',
                'senderId', v_user_id::text,
                'roundId', v_round_id::text,
                'messageId', v_message_id,
                'payload', jsonb_build_object(
                    'v', 1,
                    'roundId', v_round_id::text,
                    'config', v_room.config ->> 'serialized',
                    'hand', v_hands -> seat_idx::text,
                    'seat', seat_idx,
                    'activeSeat', 1,
                    'discard', v_first_discard,
                    'turnCard', v_turn_card,
                    'deckSize', array_length(v_deck, 1),
                    'mortosLeft', v_mortos_left
                )::text
            ),
            p_recipient_seat => seat_idx
        );
    end loop;

    select jsonb_agg(v_cards_per_player) into v_hand_counts
    from generate_series(1, greatest(v_max_players, 2));

    -- Fotografia publica identica a que o host ja publicava logo depois do
    -- GAME_START privado -- mesmos campos de buildPublicTableStatePayload(). No
    -- Kotlin, o proprio host carimba o roundId ativo em qualquer evento mandado
    -- depois do GAME_START via prepareOutgoingMessage(); aqui eu tenho que
    -- carimbar isso a mao, senao o guard de round (0017) recusa com
    -- ROUND_ID_REQUIRED assim que o GAME_START ja tiver criado o estado da
    -- rodada.
    v_message_id := gen_random_uuid()::text;
    perform public.append_match_event(
        p_room_id => p_room_id,
        p_message_id => v_message_id,
        p_event_type => 'PUBLIC_STATE',
        p_payload => jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_user_id::text,
            'roundId', v_round_id::text,
            'messageId', v_message_id,
            'payload', jsonb_build_object(
                'v', 1,
                'activeSeat', 1,
                'deckSize', array_length(v_deck, 1),
                'discardCount', case when v_first_discard = '' then 0 else 1 end,
                'discardPile', case when v_first_discard = '' then '[]'::jsonb else jsonb_build_array(v_first_discard) end,
                'turnCard', v_turn_card,
                'mortosLeft', v_mortos_left,
                'handCounts', v_hand_counts,
                'team0Melds', v_team_melds[1],
                'team1Melds', v_team_melds[2]
            )::text
        )
    );

    -- Devolvo pro host a mao de TODOS os assentos, nao so a dele -- percebi
    -- isso a tempo (antes de ligar o app nisso): o host mantem uma copia local
    -- de toda mao alheia (remoteHandsBySeat) pra validar descarte/baixa/vitoria
    -- de cada cliente depois, e essa copia so existe hoje porque foi o proprio
    -- host quem gerou tudo. Se eu mandasse so a mao dele, essa bookkeeping
    -- ficava vazia pros outros assentos e a validacao de jogada quebrava.
    -- O evento GAME_START continua indo pros assentos 1..N-1 do jeito de
    -- sempre (e a parte que já era "de rede"); isto aqui e so o espelho local
    -- que o host sempre teve. Pelo mesmo motivo, devolvo tambem os jogos de 3
    -- vermelho de cada equipe (team0Melds/team1Melds) -- sem isso o host nunca
    -- saberia quais 3 vermelho ja baixaram na propria mesa ou na do
    -- adversario, ja que ele nunca recebe a propria transmissao de volta.
    --
    -- Tambem devolvo o `deck` (as cartas que sobraram) pro host continuar
    -- puxando carta localmente durante a rodada, exatamente como sempre fez --
    -- mover CADA compra pro servidor fica pra depois (fase 3), fora do escopo
    -- de hoje que e so a embaralhada e a distribuicao inicial.
    --
    -- Os mortos tambem voltam aqui, e nao ficam guardados em nenhuma tabela:
    -- o app do host continua sendo quem serve o morto quando um time pede
    -- (SERVE_MORTO, fluxo que esta fase 1 nao mexe), entao ele precisa das
    -- 22 cartas fisicas do mesmo jeito que precisava quando as gerava sozinho
    -- -- so que agora vem do servidor em vez de vir do proprio embaralhamento
    -- local.
    return jsonb_build_object(
        'v', 1,
        'roundId', v_round_id::text,
        'config', v_room.config ->> 'serialized',
        'hand', v_hands -> '0',
        'hands', v_hands,
        'seat', 0,
        'activeSeat', 1,
        'discard', v_first_discard,
        'turnCard', v_turn_card,
        'deck', to_jsonb(v_deck),
        'deckSize', array_length(v_deck, 1),
        'mortosLeft', v_mortos_left,
        'mortos', case
            when v_room.game_type = 'CACHETA' then '[]'::jsonb
            else jsonb_build_array(to_jsonb(v_morto_0), to_jsonb(v_morto_1))
        end,
        'team0Melds', v_team_melds[1],
        'team1Melds', v_team_melds[2]
    );
end;
$$;
