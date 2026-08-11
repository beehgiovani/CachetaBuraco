-- A retirada do morto segue a mesma assimetria da compra: convidados recebem
-- SERVE_MORTO, enquanto o host recebe a mao preparada diretamente na resposta.
-- Sincronizo essa resposta no ledger dentro da mesma transacao.

alter function public.online_take_morto(uuid, integer, boolean) set schema private;
alter function private.online_take_morto(uuid, integer, boolean)
rename to cbr_online_take_morto_without_host_ledger;

revoke all on function private.cbr_online_take_morto_without_host_ledger(
    uuid, integer, boolean
) from public, anon, authenticated;

create or replace function public.online_take_morto(
    p_room_id uuid,
    p_seat integer,
    p_indirect boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_result jsonb;
    v_hand jsonb;
    v_current_hand jsonb;
    v_pending jsonb;
    v_required_card text;
    v_initialized boolean;
begin
    v_result := private.cbr_online_take_morto_without_host_ledger(
        p_room_id,
        p_seat,
        p_indirect
    );
    if coalesce(v_result ->> 'status', '') <> 'OK' or p_seat <> 0 then
        return v_result;
    end if;

    v_hand := v_result -> 'hand';
    if not private.cbr_is_valid_card_array(v_hand, 11, 11) then
        raise exception 'INVALID_MORTO_HAND' using errcode = 'P0001';
    end if;

    select state.hand, state.pending_discard_cards,
           state.required_discard_card, state.initialized
    into v_current_hand, v_pending, v_required_card, v_initialized
    from private.match_seat_state state
    where state.room_id = p_room_id and state.seat = 0
    for update;
    if not coalesce(v_initialized, false) then
        raise exception 'PRIVATE_HOST_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;
    if jsonb_array_length(v_current_hand) <> 0
       or jsonb_array_length(v_pending) <> 0
       or v_required_card is not null then
        raise exception 'MORTO_REQUIRES_EMPTY_HAND' using errcode = 'P0001';
    end if;
    if private.cbr_cards_overlap_other_hands(p_room_id, 0, v_hand) then
        raise exception 'CARD_ASSIGNED_TO_MULTIPLE_PLAYERS' using errcode = 'P0001';
    end if;

    update private.match_seat_state
    set hand = v_hand,
        required_discard_card = null,
        pending_discard_cards = '[]'::jsonb,
        updated_at = now()
    where room_id = p_room_id and seat = 0;
    return v_result;
end;
$$;

revoke all on function public.online_take_morto(uuid, integer, boolean)
from public, anon, authenticated;

comment on function public.online_take_morto(uuid, integer, boolean) is
'Retira o morto no servidor e sincroniza diretamente o ledger quando o assento e o host.';
