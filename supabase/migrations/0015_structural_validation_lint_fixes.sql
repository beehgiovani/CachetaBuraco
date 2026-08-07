-- Estes helpers dependem apenas dos argumentos, mas a montagem de jsonb usa
-- rotinas classificadas como STABLE pelo PostgreSQL. Ajusto a volatilidade e
-- deixo os arrays vazios tipados para o lint refletir o comportamento real.

alter function private.cbr_can_draw_from_discard(text, text, boolean) stable;
alter function private.cbr_check_trinca(text[], text[], integer) stable;
alter function private.cbr_check_sequencia(text[], text[]) stable;
alter function private.cbr_classify_canastra(jsonb, text[], text[]) stable;

create or replace function private.cbr_validate_meld(
    p_cards jsonb,
    p_game_type text,
    p_allow_wildcards boolean,
    p_allow_charutos boolean,
    p_cacheta_turn_card text
)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
    v_cards text[];
    v_normal text[] := array[]::text[];
    v_wildcards text[] := array[]::text[];
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
            select count(distinct private.cbr_card_suit(c))
            into v_distinct_suits
            from unnest(v_normal) as c;
            if v_distinct_suits = v_normal_count then
                return v_trinca;
            end if;
        end if;

        v_seq := private.cbr_check_sequencia(v_normal, v_wildcards);
        if (v_seq ->> 'valid')::boolean then
            return v_seq;
        end if;

        return jsonb_build_object(
            'valid', false,
            'reason', 'Precisa ser Trinca de naipes diferentes ou Sequencia do mesmo naipe'
        );

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

        if exists (
            select 1
            from unnest(v_normal) as c
            where private.cbr_card_rank(c) = 'THREE'
        ) then
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
