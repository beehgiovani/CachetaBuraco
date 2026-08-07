-- O host continua sendo a autoridade completa da partida (mao de cada
-- assento, baralho, ordem exata). Esta migracao adiciona uma segunda camada,
-- no banco, que barra jogadas estruturalmente impossiveis mesmo que venham
-- de um cliente adulterado: um MELD que nao forma trinca/sequencia/canastra
-- valida para a sala, ou um DRAW_DISCARD contra uma regra de bloqueio do
-- lixo (3 preto na Tranca, curinga em qualquer modo). Isso replica
-- GameRulesEngine.kt (Kotlin) em plpgsql, sem reconstruir a mao privada de
-- ninguem no servidor -- por isso nao cobre "essa carta pertence a esse
-- jogador?" nem "a mao ficou vazia?". Esse e um proximo passo maior,
-- registrado no roadmap.

-- ─── Helpers de carta ────────────────────────────────────────────────────
-- Formato do id: "{RANK}_{SUIT}_{DECKCOLOR}" para carta normal,
-- "JOKER_{DECKCOLOR}_{SUIT}" para curinga (ver Card.kt).

create or replace function private.cbr_is_joker(p_card text)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select coalesce(left(p_card, 6) = 'JOKER_', false);
$$;

create or replace function private.cbr_is_valid_card_id(p_card text)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select coalesce(
        p_card ~ '^(ACE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|JACK|QUEEN|KING)_(HEARTS|DIAMONDS|CLUBS|SPADES)_(RED|BLACK)$'
        or p_card ~ '^JOKER_(RED|BLACK)_(SPADES|HEARTS)$',
        false
    );
$$;

create or replace function private.cbr_card_rank(p_card text)
returns text
language sql
immutable
set search_path = ''
as $$
    select case
        when private.cbr_is_joker(p_card) then null
        else split_part(p_card, '_', 1)
    end;
$$;

create or replace function private.cbr_card_suit(p_card text)
returns text
language sql
immutable
set search_path = ''
as $$
    select case
        when private.cbr_is_joker(p_card) then split_part(p_card, '_', 3)
        else split_part(p_card, '_', 2)
    end;
$$;

create or replace function private.cbr_rank_value(p_rank text)
returns integer
language sql
immutable
set search_path = ''
as $$
    select case p_rank
        when 'ACE' then 1
        when 'TWO' then 2
        when 'THREE' then 3
        when 'FOUR' then 4
        when 'FIVE' then 5
        when 'SIX' then 6
        when 'SEVEN' then 7
        when 'EIGHT' then 8
        when 'NINE' then 9
        when 'TEN' then 10
        when 'JACK' then 11
        when 'QUEEN' then 12
        when 'KING' then 13
        else null
    end;
$$;

create or replace function private.cbr_rank_name(p_value integer)
returns text
language sql
immutable
set search_path = ''
as $$
    select case p_value
        when 1 then 'ACE'
        when 2 then 'TWO'
        when 3 then 'THREE'
        when 4 then 'FOUR'
        when 5 then 'FIVE'
        when 6 then 'SIX'
        when 7 then 'SEVEN'
        when 8 then 'EIGHT'
        when 9 then 'NINE'
        when 10 then 'TEN'
        when 11 then 'JACK'
        when 12 then 'QUEEN'
        when 13 then 'KING'
        else null
    end;
$$;

-- Espelha GameRulesEngine.isWildcard / getMeldWildcards.
create or replace function private.cbr_is_wildcard(
    p_card text,
    p_game_type text,
    p_allow_wildcards boolean,
    p_cacheta_wild_rank text,
    p_cacheta_wild_suit text
)
returns boolean
language sql
immutable
set search_path = ''
as $$
    select case
        when p_game_type = 'CACHETA' then
            case
                when not coalesce(p_allow_wildcards, true) then false
                when private.cbr_is_joker(p_card) then true
                when p_cacheta_wild_rank is not null
                    and private.cbr_card_rank(p_card) = p_cacheta_wild_rank
                    and private.cbr_card_suit(p_card) = p_cacheta_wild_suit then true
                else false
            end
        else
            -- BURACO / TRANCA: o 2 e sempre curinga; o Joker so se a sala permitir.
            private.cbr_card_rank(p_card) = 'TWO'
            or (coalesce(p_allow_wildcards, true) and private.cbr_is_joker(p_card))
    end;
$$;

-- ─── Compra do lixo (GameRulesEngine.canDrawFromDiscard) ─────────────────

create or replace function private.cbr_can_draw_from_discard(
    p_top_discard text,
    p_game_type text,
    p_allow_draw_from_discard boolean
)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
begin
    if p_top_discard is null or p_top_discard = '' then
        return jsonb_build_object('allowed', false, 'reason', 'Lixo vazio');
    end if;
    if not coalesce(p_allow_draw_from_discard, true) then
        return jsonb_build_object('allowed', false, 'reason', 'Compra do lixo desabilitada nesta sala');
    end if;

    if p_game_type = 'CACHETA' then
        return jsonb_build_object('allowed', true);
    elsif p_game_type = 'BURACO' then
        if private.cbr_is_joker(p_top_discard) then
            return jsonb_build_object('allowed', false, 'reason', 'Nao e possivel comprar Curinga do lixo');
        end if;
        return jsonb_build_object('allowed', true);
    elsif p_game_type = 'TRANCA' then
        if private.cbr_card_rank(p_top_discard) = 'THREE'
           and private.cbr_card_suit(p_top_discard) in ('SPADES', 'CLUBS') then
            return jsonb_build_object('allowed', false, 'reason', 'Lixo trancado pelo 3 preto');
        elsif private.cbr_is_joker(p_top_discard) then
            return jsonb_build_object('allowed', false, 'reason', 'Nao e possivel comprar Curinga do lixo');
        end if;
        return jsonb_build_object('allowed', true);
    end if;

    return jsonb_build_object('allowed', true);
end;
$$;

-- ─── Validacao de jogo baixado (GameRulesEngine.validateMeld e afins) ────

create or replace function private.cbr_can_build_sequence(p_sorted_ranks integer[], p_wildcard_count integer)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_needed integer := 0;
    v_gap integer;
    v_len integer := coalesce(array_length(p_sorted_ranks, 1), 0);
begin
    for i in 1 .. (v_len - 1) loop
        v_gap := p_sorted_ranks[i + 1] - p_sorted_ranks[i] - 1;
        if v_gap < 0 then
            return false;
        end if;
        v_needed := v_needed + v_gap;
    end loop;

    return v_needed <= coalesce(p_wildcard_count, 0);
end;
$$;

create or replace function private.cbr_check_trinca(p_normal text[], p_wildcards text[], p_exact_size integer)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_normal_count integer := coalesce(array_length(p_normal, 1), 0);
    v_wild_count integer := coalesce(array_length(p_wildcards, 1), 0);
    v_distinct_ranks integer;
    v_total integer;
begin
    if v_normal_count = 0 then
        return jsonb_build_object('valid', false);
    end if;

    select count(distinct private.cbr_card_rank(c)) into v_distinct_ranks
    from unnest(p_normal) as c;

    if v_distinct_ranks <> 1 then
        return jsonb_build_object('valid', false);
    end if;

    v_total := v_normal_count + v_wild_count;
    if p_exact_size is not null and v_total <> p_exact_size then
        return jsonb_build_object('valid', false, 'reason', format('Trinca precisa ter exatamente %s cartas', p_exact_size));
    end if;

    if v_total >= 3 then
        return jsonb_build_object('valid', true, 'meldType', 'TRINCA');
    end if;

    return jsonb_build_object('valid', false, 'reason', 'Trinca invalida');
end;
$$;

create or replace function private.cbr_check_sequencia(p_normal text[], p_wildcards text[])
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_normal_count integer := coalesce(array_length(p_normal, 1), 0);
    v_wild_count integer := coalesce(array_length(p_wildcards, 1), 0);
    v_distinct_suits integer;
    v_total integer;
    v_low_ranks integer[];
    v_high_ranks integer[];
begin
    if v_normal_count = 0 and v_wild_count > 0 then
        return jsonb_build_object('valid', false);
    end if;

    select count(distinct private.cbr_card_suit(c)) into v_distinct_suits
    from unnest(p_normal) as c;

    if v_distinct_suits > 1 then
        return jsonb_build_object('valid', false, 'reason', 'Sequencia precisa ser do mesmo naipe');
    end if;

    v_total := v_normal_count + v_wild_count;
    if v_total < 3 then
        return jsonb_build_object('valid', false);
    end if;

    select array_agg(private.cbr_rank_value(private.cbr_card_rank(c)) order by private.cbr_rank_value(private.cbr_card_rank(c)))
    into v_low_ranks
    from unnest(p_normal) as c;

    if private.cbr_can_build_sequence(v_low_ranks, v_wild_count) then
        return jsonb_build_object('valid', true, 'meldType', 'SEQUENCIA');
    end if;

    select array_agg(
        (case when private.cbr_card_rank(c) = 'ACE' then 14 else private.cbr_rank_value(private.cbr_card_rank(c)) end)
        order by (case when private.cbr_card_rank(c) = 'ACE' then 14 else private.cbr_rank_value(private.cbr_card_rank(c)) end)
    )
    into v_high_ranks
    from unnest(p_normal) as c;

    if private.cbr_can_build_sequence(v_high_ranks, v_wild_count) then
        return jsonb_build_object('valid', true, 'meldType', 'SEQUENCIA');
    end if;

    return jsonb_build_object('valid', false, 'reason', 'Curingas insuficientes para a sequencia');
end;
$$;

create or replace function private.cbr_classify_canastra(p_base jsonb, p_normal text[], p_wildcards text[])
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_total integer := coalesce(array_length(p_normal, 1), 0) + coalesce(array_length(p_wildcards, 1), 0);
begin
    if v_total < 7 then
        return p_base;
    end if;

    if coalesce(array_length(p_wildcards, 1), 0) = 0 then
        return jsonb_build_object('valid', true, 'meldType', 'CANASTRA_LIMPA');
    end if;

    return jsonb_build_object('valid', true, 'meldType', 'CANASTRA_SUJA');
end;
$$;

create or replace function private.cbr_validate_meld(
    p_cards jsonb,
    p_game_type text,
    p_allow_wildcards boolean,
    p_allow_charutos boolean,
    p_cacheta_turn_card text
)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_cards text[];
    v_normal text[] := '{}';
    v_wildcards text[] := '{}';
    v_card text;
    v_wild_rank text;
    v_wild_suit text;
    v_trinca jsonb;
    v_seq jsonb;
    v_distinct_suits integer;
    v_normal_count integer;
begin
    select array_agg(value) into v_cards
    from jsonb_array_elements_text(coalesce(p_cards, '[]'::jsonb));

    if coalesce(array_length(v_cards, 1), 0) < 3 then
        return jsonb_build_object('valid', false, 'reason', 'Minimo 3 cartas');
    end if;

    if exists (
        select 1
        from unnest(v_cards) as card
        where not private.cbr_is_valid_card_id(card)
    ) then
        return jsonb_build_object('valid', false, 'reason', 'Jogo contem carta desconhecida');
    end if;

    if (select count(distinct card) from unnest(v_cards) as card)
       <> coalesce(array_length(v_cards, 1), 0) then
        return jsonb_build_object('valid', false, 'reason', 'Jogo repete a mesma carta fisica');
    end if;

    if p_game_type = 'CACHETA'
       and p_cacheta_turn_card is not null
       and p_cacheta_turn_card <> ''
       and not private.cbr_is_joker(p_cacheta_turn_card) then
        v_wild_rank := private.cbr_rank_name(
            ((private.cbr_rank_value(private.cbr_card_rank(p_cacheta_turn_card))) % 13) + 1
        );
        v_wild_suit := private.cbr_card_suit(p_cacheta_turn_card);
    end if;

    foreach v_card in array v_cards loop
        if private.cbr_is_wildcard(v_card, p_game_type, p_allow_wildcards, v_wild_rank, v_wild_suit) then
            v_wildcards := v_wildcards || v_card;
        else
            v_normal := v_normal || v_card;
        end if;
    end loop;

    if p_game_type = 'CACHETA' then
        if coalesce(array_length(v_wildcards, 1), 0) > 1 then
            return jsonb_build_object('valid', false, 'reason', 'Na Cacheta, cada jogo aceita apenas um coringa');
        end if;

        v_trinca := private.cbr_check_trinca(v_normal, v_wildcards, 3);
        if (v_trinca ->> 'valid')::boolean then
            v_normal_count := coalesce(array_length(v_normal, 1), 0);
            select count(distinct private.cbr_card_suit(c)) into v_distinct_suits from unnest(v_normal) as c;
            if v_distinct_suits = v_normal_count then
                return v_trinca;
            end if;
        end if;

        v_seq := private.cbr_check_sequencia(v_normal, v_wildcards);
        if (v_seq ->> 'valid')::boolean then
            return v_seq;
        end if;

        return jsonb_build_object('valid', false, 'reason', 'Precisa ser Trinca de naipes diferentes ou Sequencia do mesmo naipe');

    elsif p_game_type = 'BURACO' then
        if coalesce(array_length(v_wildcards, 1), 0) > 1 then
            return jsonb_build_object('valid', false, 'reason', 'Cada jogo aceita apenas um curinga');
        end if;

        v_seq := private.cbr_check_sequencia(v_normal, v_wildcards);
        if (v_seq ->> 'valid')::boolean then
            return private.cbr_classify_canastra(v_seq, v_normal, v_wildcards);
        end if;

        if coalesce(p_allow_charutos, true) then
            v_trinca := private.cbr_check_trinca(v_normal, v_wildcards, null);
            if (v_trinca ->> 'valid')::boolean then
                return private.cbr_classify_canastra(v_trinca, v_normal, v_wildcards);
            end if;
        end if;

        return jsonb_build_object(
            'valid', false,
            'reason', case when coalesce(p_allow_charutos, true)
                then 'Precisa ser Sequencia ou Charuto valido'
                else 'Nesta sala de Buraco so valem sequencias do mesmo naipe'
            end
        );

    elsif p_game_type = 'TRANCA' then
        if coalesce(array_length(v_wildcards, 1), 0) > 1 then
            return jsonb_build_object('valid', false, 'reason', 'Cada jogo aceita apenas um curinga');
        end if;

        if exists (select 1 from unnest(v_normal) as c where private.cbr_card_rank(c) = 'THREE') then
            return jsonb_build_object('valid', false, 'reason', 'Na Tranca, 3 nao entra em jogo comum');
        end if;

        v_seq := private.cbr_check_sequencia(v_normal, v_wildcards);
        if (v_seq ->> 'valid')::boolean then
            return private.cbr_classify_canastra(v_seq, v_normal, v_wildcards);
        end if;

        if coalesce(p_allow_charutos, true) then
            v_trinca := private.cbr_check_trinca(v_normal, v_wildcards, null);
            if (v_trinca ->> 'valid')::boolean then
                return private.cbr_classify_canastra(v_trinca, v_normal, v_wildcards);
            end if;
        end if;

        return jsonb_build_object(
            'valid', false,
            'reason', case when coalesce(p_allow_charutos, true)
                then 'Precisa ser Sequencia ou Charuto valido'
                else 'Nesta sala de Tranca so valem sequencias do mesmo naipe'
            end
        );
    end if;

    return jsonb_build_object('valid', false, 'reason', 'Tipo de jogo desconhecido');
end;
$$;

-- ─── Liga a validacao estrutural ao gatilho de turno ativo (migration 0012) ──
-- Mesma funcao, mesmo trigger; apenas acrescento os dois novos checks.

create or replace function private.enforce_active_turn_for_match_event()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_public_payload jsonb;
    v_active_seat integer;
    v_new_inner jsonb;
    v_game_type text;
    v_room_config jsonb;
    v_allow_wildcards boolean;
    v_allow_charutos boolean;
    v_allow_draw_from_discard boolean;
    v_meld_check jsonb;
    v_discard_pile jsonb;
    v_top_discard text;
    v_draw_check jsonb;
    v_cacheta_turn_card text;
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

    -- A partir daqui: checagem estrutural adicional. Falha ao ler contexto
    -- (sala, config) nao trava a sala -- essa camada e reforco, o host
    -- continua validando tudo com o estado privado completo.
    begin
        select room.game_type, room.config
        into v_game_type, v_room_config
        from public.match_rooms room
        where room.id = new.room_id;

        if v_game_type is null then
            return new;
        end if;

        v_allow_wildcards := coalesce((v_room_config ->> 'allowWildcards')::boolean, true);
        v_allow_charutos := coalesce((v_room_config ->> 'allowCharutos')::boolean, true);
        v_allow_draw_from_discard := coalesce((v_room_config ->> 'allowDrawFromDiscard')::boolean, true);

        if new.event_type = 'MELD' then
            v_new_inner := (new.payload ->> 'payload')::jsonb;

            -- Espelha o bypass do 3 vermelho automatico da Tranca no host:
            -- uma unica carta 3 de Copas/Ouros nao passa pela validacao de jogo.
            if v_game_type = 'TRANCA'
               and jsonb_array_length(coalesce(v_new_inner -> 'cards', '[]'::jsonb)) = 1
               and private.cbr_card_rank(v_new_inner -> 'cards' ->> 0) = 'THREE'
               and private.cbr_card_suit(v_new_inner -> 'cards' ->> 0) in ('HEARTS', 'DIAMONDS') then
                return new;
            end if;

            v_cacheta_turn_card := nullif(v_public_payload ->> 'turnCard', '');
            v_meld_check := private.cbr_validate_meld(
                v_new_inner -> 'cards',
                v_game_type,
                v_allow_wildcards,
                v_allow_charutos,
                v_cacheta_turn_card
            );

            if not coalesce((v_meld_check ->> 'valid')::boolean, false) then
                raise exception 'INVALID_MELD_SHAPE' using errcode = 'P0001',
                    detail = coalesce(v_meld_check ->> 'reason', 'jogo nao forma combinacao valida');
            end if;

        elsif new.event_type = 'DRAW_DISCARD' then
            v_discard_pile := v_public_payload -> 'discardPile';
            if v_discard_pile is not null and jsonb_typeof(v_discard_pile) = 'array'
               and jsonb_array_length(v_discard_pile) > 0 then
                v_top_discard := v_discard_pile ->> (jsonb_array_length(v_discard_pile) - 1);
                if new.payload ->> 'payload' <> v_top_discard then
                    raise exception 'DISCARD_TOP_MISMATCH' using errcode = 'P0001';
                end if;
                v_draw_check := private.cbr_can_draw_from_discard(v_top_discard, v_game_type, v_allow_draw_from_discard);
                if not coalesce((v_draw_check ->> 'allowed')::boolean, true) then
                    raise exception 'DISCARD_DRAW_BLOCKED' using errcode = 'P0001',
                        detail = coalesce(v_draw_check ->> 'reason', 'compra do lixo bloqueada');
                end if;
            end if;
        end if;
    exception
        when sqlstate 'P0001' then
            raise;
        when others then
            return new;
    end;

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
