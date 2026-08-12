begin;

do $$
declare
    v_result jsonb;
begin
    -- Trinca de 3 continua valendo.
    v_result := private.cbr_validate_meld(
        '["NINE_HEARTS_RED","NINE_CLUBS_BLACK","NINE_SPADES_BLACK"]'::jsonb,
        'CACHETA', true, true, null
    );
    if not (v_result ->> 'valid')::boolean then
        raise exception 'TRINCA_3_SHOULD_BE_VALID: %', v_result;
    end if;

    -- Sequencia de 3 continua valendo.
    v_result := private.cbr_validate_meld(
        '["FOUR_HEARTS_RED","FIVE_HEARTS_RED","SIX_HEARTS_RED"]'::jsonb,
        'CACHETA', true, true, null
    );
    if not (v_result ->> 'valid')::boolean then
        raise exception 'SEQUENCIA_3_SHOULD_BE_VALID: %', v_result;
    end if;

    -- Sequencia de 4 tem que ser recusada (bug reportado: nao tinha maximo).
    v_result := private.cbr_validate_meld(
        '["FOUR_HEARTS_RED","FIVE_HEARTS_RED","SIX_HEARTS_RED","SEVEN_HEARTS_RED"]'::jsonb,
        'CACHETA', true, true, null
    );
    if (v_result ->> 'valid')::boolean then
        raise exception 'SEQUENCIA_4_SHOULD_BE_REJECTED: %', v_result;
    end if;

    -- "Encaixar" carta em jogo ja baixado = revalidar jogo+carta como um novo
    -- meld de 4, tem que ser recusado pelo mesmo motivo.
    v_result := private.cbr_validate_meld(
        '["NINE_HEARTS_RED","NINE_CLUBS_BLACK","NINE_SPADES_BLACK","NINE_DIAMONDS_RED"]'::jsonb,
        'CACHETA', true, true, null
    );
    if (v_result ->> 'valid')::boolean then
        raise exception 'APPEND_INTO_TRINCA_SHOULD_BE_REJECTED: %', v_result;
    end if;

    -- Buraco nao pode ter sido afetado -- sequencia longa (canastra) continua ok.
    v_result := private.cbr_validate_meld(
        '["FOUR_HEARTS_RED","FIVE_HEARTS_RED","SIX_HEARTS_RED","SEVEN_HEARTS_RED","EIGHT_HEARTS_RED"]'::jsonb,
        'BURACO', true, true, null
    );
    if not (v_result ->> 'valid')::boolean then
        raise exception 'BURACO_LONG_SEQUENCE_SHOULD_STAY_VALID: %', v_result;
    end if;

    raise notice 'SMOKE 0052 OK';
end $$;

rollback;
