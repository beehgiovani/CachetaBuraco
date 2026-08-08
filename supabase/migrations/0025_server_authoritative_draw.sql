-- Fase 3 da autoridade do servidor: so a distribuicao inicial (0020-0023) era
-- server-side. Toda compra durante a rodada (monte principal, reciclagem do
-- lixo na Cacheta, morto virando novo monte no Buraco/Tranca) continuava
-- inteiramente decidida pelo host em memoria -- um host adulterado podia se
-- servir de qualquer carta querendo, sem nenhuma validacao do banco.
--
-- Este arquivo guarda o baralho remanescente e os mortos reais numa tabela
-- privada, alimentada pelo proprio start_online_round, e adiciona a RPC
-- online_draw_deck_card que passa a decidir qual carta sai do monte.
--
-- O pedido explicito de um time pegar o morto inteiro como mao (REQ_PICK_MORTO)
-- continua no host por enquanto (fica pra uma proxima migration), mas o host
-- precisa espelhar localmente todo consumo de morto feito por esta RPC pra
-- reciclagem -- senao os dois mecanismos podem tentar usar o mesmo morto.

-- Nova categoria fechada de telemetria pra falha ao pedir a compra
-- server-side (mesmo espirito da 0024: lista fixa, nunca texto livre).
alter table public.client_failure_events drop constraint if exists client_failure_events_category_check;
alter table public.client_failure_events add constraint client_failure_events_category_check check (category in (
    'ONLINE_SESSION_ERROR',
    'ONLINE_HEARTBEAT_FAILED',
    'ONLINE_PUBLISH_FAILED',
    'ONLINE_DEAL_REQUEST_FAILED',
    'ONLINE_DRAW_REQUEST_FAILED'
));

create table if not exists private.match_deck_state (
    room_id uuid primary key references public.match_rooms(id) on delete cascade,
    deck jsonb not null default '[]'::jsonb,
    mortos jsonb not null default '[]'::jsonb,
    updated_at timestamptz not null default now()
);

revoke all on table private.match_deck_state from public, anon, authenticated;

-- start_online_round ja calculava v_deck/v_morto_0/v_morto_1; a unica
-- mudanca aqui e persistir essas mesmas cartas nesta tabela antes de
-- devolver o resultado pro host, pra online_draw_deck_card ter uma fonte
-- real (nao so a copia que so o host recebe) pra continuar puxando carta.
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
    v_mortos_json jsonb;
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

    if v_room.game_type <> 'CACHETA' then
        v_morto_0 := v_deck[array_length(v_deck, 1) - 10 : array_length(v_deck, 1)];
        v_deck := v_deck[1 : array_length(v_deck, 1) - 11];
        v_morto_1 := v_deck[array_length(v_deck, 1) - 10 : array_length(v_deck, 1)];
        v_deck := v_deck[1 : array_length(v_deck, 1) - 11];
        v_mortos_left := 2;
    end if;

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

    for seat_idx in 1 .. v_max_players - 1 loop
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

    v_mortos_json := case
        when v_room.game_type = 'CACHETA' then '[]'::jsonb
        else jsonb_build_array(to_jsonb(v_morto_0), to_jsonb(v_morto_1))
    end;

    -- Novo nesta migration: o baralho remanescente e os mortos reais tambem
    -- ficam guardados aqui, pra online_draw_deck_card poder continuar
    -- decidindo carta por carta durante a rodada em vez de so na distribuicao
    -- inicial. NEXT_ROUND chama esta funcao de novo a cada rodada nova, entao
    -- este upsert sempre comeca do zero (nunca herda lixo de rodada anterior).
    insert into private.match_deck_state(room_id, deck, mortos, updated_at)
    values (p_room_id, to_jsonb(v_deck), v_mortos_json, now())
    on conflict (room_id) do update
    set deck = excluded.deck,
        mortos = excluded.mortos,
        updated_at = now();

    -- Ate a 0023 eu devolvia o `deck` inteiro pro host aqui, pra ele continuar
    -- puxando carta local (fase 3 ficava so pra depois). Agora que
    -- online_draw_deck_card decide cada compra, devolver o baralho inteiro
    -- pro host de novo destruiria a propria protecao desta migration -- o host
    -- nao precisaria nem tentar burlar a RPC, so ler a mao toda do proprio
    -- retorno da distribuicao pra saber a ordem exata de todas as proximas
    -- compras da rodada inteira. Por isso esse campo sai daqui.
    --
    -- `mortos` continua voltando por enquanto: o pedido explicito de time
    -- pegar o morto inteiro (REQ_PICK_MORTO) ainda e decidido e servido pelo
    -- host localmente, sem mudanca nesta fase -- fechar esse gap especifico
    -- fica pra uma proxima migration.
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
        'deckSize', array_length(v_deck, 1),
        'mortosLeft', v_mortos_left,
        'mortos', v_mortos_json,
        'team0Melds', v_team_melds[1],
        'team1Melds', v_team_melds[2]
    );
end;
$$;

-- Insere um evento em nome do host sem exigir que o CHAMADOR seja o host --
-- online_draw_deck_card e chamada pelo host em nome de qualquer assento que
-- esteja comprando, mas append_match_event (0013) exige que quem chama seja
-- realmente o assento 0 pra publicar tipos privados do host (SERVE_CARD) ou
-- publicos do host (PUBLIC_STATE). Aqui quem decide o conteudo e o proprio
-- banco (a carta sorteada), entao o "remetente" e sempre o host da sala,
-- independente de quem disparou a compra.
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
end;
$$;

revoke all on function private.cbr_publish_server_event(uuid, uuid, text, jsonb, integer) from public;

-- Compra do monte principal, com reciclagem do lixo (Cacheta) e morto
-- virando novo monte (Buraco/Tranca) quando o monte esgota. So o host chama
-- esta funcao (mesmo modelo de confianca que start_online_round ja tinha
-- pra turno/vez de jogar), mas quem decide QUAL carta sai e o banco, nao o
-- processo do host -- o host nunca ve a ordem do baralho, so recebe uma
-- carta de cada vez.
create or replace function public.online_draw_deck_card(p_room_id uuid, p_seat integer)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_room public.match_rooms%rowtype;
    v_public_state jsonb;
    v_active_seat integer;
    v_deck jsonb;
    v_mortos jsonb;
    v_morto_consumed boolean := false;
    v_discard_pile jsonb;
    v_new_discard jsonb;
    v_card text;
    v_message_id text;
    v_round_id text;
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

    if p_seat not between 0 and v_room.max_players - 1 then
        raise exception 'INVALID_SEAT' using errcode = 'P0001';
    end if;

    v_public_state := private.cbr_latest_public_state(p_room_id);
    v_active_seat := coalesce((v_public_state ->> 'activeSeat')::integer, -1);
    if v_active_seat <> p_seat then
        raise exception 'NOT_ACTIVE_SEAT' using errcode = 'P0001';
    end if;

    select deck, mortos into v_deck, v_mortos
    from private.match_deck_state
    where room_id = p_room_id
    for update;

    if v_deck is null then
        raise exception 'DECK_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;

    if jsonb_array_length(v_deck) = 0 then
        if v_room.game_type = 'CACHETA' then
            v_discard_pile := coalesce(v_public_state -> 'discardPile', '[]'::jsonb);
            if jsonb_array_length(v_discard_pile) < 2 then
                return jsonb_build_object('status', 'EXHAUSTED');
            end if;
            v_new_discard := jsonb_build_array(v_discard_pile -> (jsonb_array_length(v_discard_pile) - 1));
            select coalesce(jsonb_agg(to_jsonb(pile.card) order by random()), '[]'::jsonb)
            into v_deck
            from jsonb_array_elements_text(v_discard_pile) with ordinality as pile(card, ordinal)
            where pile.ordinal < jsonb_array_length(v_discard_pile);
        elsif jsonb_array_length(v_mortos) > 0 then
            select coalesce(jsonb_agg(to_jsonb(card.value) order by random()), '[]'::jsonb)
            into v_deck
            from jsonb_array_elements_text(v_mortos -> 0) as card(value);
            v_mortos := v_mortos - 0;
            v_morto_consumed := true;
        else
            return jsonb_build_object('status', 'EXHAUSTED');
        end if;
    end if;

    v_card := v_deck ->> (jsonb_array_length(v_deck) - 1);
    v_deck := v_deck - (jsonb_array_length(v_deck) - 1);

    update private.match_deck_state
    set deck = v_deck,
        mortos = v_mortos,
        updated_at = now()
    where room_id = p_room_id;

    -- cbr_latest_public_state devolve so o "payload" interno do evento
    -- (achei isso a tempo testando local): roundId mora no envelope de FORA,
    -- nao dentro desse objeto. Sem isso o guard de rodada (0017) recusa com
    -- ROUND_ID_REQUIRED em qualquer evento que eu publique aqui.
    select event.payload ->> 'roundId'
    into v_round_id
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;
    v_round_id := coalesce(v_round_id, '');

    if p_seat <> 0 then
        v_message_id := gen_random_uuid()::text;
        perform private.cbr_publish_server_event(
            p_room_id => p_room_id,
            p_actor_id => v_user_id,
            p_event_type => 'SERVE_CARD',
            p_payload => jsonb_build_object(
                'type', 'SERVE_CARD',
                'senderId', v_user_id::text,
                'roundId', v_round_id,
                'messageId', v_message_id,
                'payload', v_card
            ),
            p_recipient_seat => p_seat
        );
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
                'discardCount', case when v_new_discard is null
                    then coalesce(jsonb_array_length(v_public_state -> 'discardPile'), 0)
                    else 1 end,
                'discardPile', coalesce(v_new_discard, coalesce(v_public_state -> 'discardPile', '[]'::jsonb)),
                'turnCard', coalesce(v_public_state ->> 'turnCard', ''),
                'mortosLeft', jsonb_array_length(v_mortos),
                'handCounts', coalesce(v_public_state -> 'handCounts', '[]'::jsonb),
                'team0Melds', coalesce(v_public_state -> 'team0Melds', '[]'::jsonb),
                'team1Melds', coalesce(v_public_state -> 'team1Melds', '[]'::jsonb)
            )::text
        ),
        p_recipient_seat => null
    );

    return jsonb_build_object(
        'status', 'OK',
        'card', v_card,
        'deckSize', jsonb_array_length(v_deck),
        'mortosLeft', jsonb_array_length(v_mortos),
        'mortoConsumedForRefill', v_morto_consumed,
        'discardPile', coalesce(v_new_discard, coalesce(v_public_state -> 'discardPile', '[]'::jsonb))
    );
end;
$$;

revoke all on function public.online_draw_deck_card(uuid, integer) from public;
grant execute on function public.online_draw_deck_card(uuid, integer) to authenticated;
