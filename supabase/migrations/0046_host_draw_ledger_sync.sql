-- A RPC de compra devolve a carta do host direto na resposta e, por isso, nao
-- cria SERVE_CARD para o proprio assento 0. Agora que o host tem ledger, aplico
-- exatamente essa carta no estado privado antes de devolver a resposta.

alter function public.online_draw_deck_card(uuid, integer) set schema private;
alter function private.online_draw_deck_card(uuid, integer)
rename to cbr_online_draw_deck_card_with_red_three_sync;

revoke all on function private.cbr_online_draw_deck_card_with_red_three_sync(
    uuid, integer
) from public, anon, authenticated;

create or replace function public.online_draw_deck_card(
    p_room_id uuid,
    p_seat integer
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room public.match_rooms%rowtype;
    v_result jsonb;
    v_card text;
    v_hand jsonb;
    v_initialized boolean;
    v_auto_red_three boolean;
begin
    v_result := private.cbr_online_draw_deck_card_with_red_three_sync(
        p_room_id,
        p_seat
    );
    if coalesce(v_result ->> 'status', '') <> 'OK' or p_seat <> 0 then
        return v_result;
    end if;

    select room.* into v_room
    from public.match_rooms room
    where room.id = p_room_id;
    v_card := nullif(v_result ->> 'card', '');
    if not private.cbr_is_valid_card_id(v_card) then
        raise exception 'INVALID_SERVED_CARD' using errcode = 'P0001';
    end if;

    v_auto_red_three := v_room.game_type = 'TRANCA'
        and coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true)
        and private.cbr_card_rank(v_card) = 'THREE'
        and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS');
    if v_auto_red_three then
        return v_result;
    end if;

    select state.hand, state.initialized
    into v_hand, v_initialized
    from private.match_seat_state state
    where state.room_id = p_room_id and state.seat = 0
    for update;
    if not coalesce(v_initialized, false) then
        raise exception 'PRIVATE_HOST_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_hand @> jsonb_build_array(v_card)
       or private.cbr_cards_overlap_other_hands(
           p_room_id,
           0,
           jsonb_build_array(v_card)
       ) then
        raise exception 'CARD_STATE_CONFLICT' using errcode = 'P0001';
    end if;

    update private.match_seat_state
    set hand = hand || jsonb_build_array(v_card),
        updated_at = now()
    where room_id = p_room_id and seat = 0;
    return v_result;
end;
$$;

revoke all on function public.online_draw_deck_card(uuid, integer)
from public, anon, authenticated;

comment on function public.online_draw_deck_card(uuid, integer) is
'Compra uma carta no servidor e sincroniza diretamente o ledger quando o assento comprador e o host.';
