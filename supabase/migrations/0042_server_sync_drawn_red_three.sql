-- Quando um 3 vermelho saía do monte para um convidado, os aparelhos o
-- mostravam corretamente, mas o ledger privado da equipe continuava sem essa
-- baixa. Em uma reconexao ele podia desaparecer. O servidor passa a registrar
-- e publicar a carta no mesmo commit da compra.

create or replace function private.cbr_record_drawn_red_three(
    p_room_id uuid,
    p_seat integer,
    p_card text,
    p_draw_result jsonb
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room public.match_rooms%rowtype;
    v_team integer;
    v_team_melds jsonb;
    v_public_state jsonb;
    v_round_id text;
    v_message_id text := gen_random_uuid()::text;
begin
    select room.*
    into v_room
    from public.match_rooms room
    where room.id = p_room_id;

    if v_room.game_type <> 'TRANCA'
       or not coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true)
       or private.cbr_card_rank(p_card) <> 'THREE'
       or private.cbr_card_suit(p_card) not in ('HEARTS', 'DIAMONDS') then
        return;
    end if;

    select player.team
    into v_team
    from public.room_players player
    where player.room_id = p_room_id
      and player.seat = p_seat;

    if v_team is null then
        raise exception 'PRIVATE_PLAYER_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;

    select coalesce(state.melds, '[]'::jsonb)
    into v_team_melds
    from private.match_team_state state
    where state.room_id = p_room_id
      and state.team = v_team
    for update;

    v_team_melds := coalesce(v_team_melds, '[]'::jsonb);
    if not exists (
        select 1
        from jsonb_array_elements(v_team_melds) meld(value)
        cross join lateral jsonb_array_elements_text(meld.value) card(value)
        where card.value = p_card
    ) then
        v_team_melds := v_team_melds || jsonb_build_array(jsonb_build_array(p_card));
    end if;

    insert into private.match_team_state(
        room_id, team, melds, melds_initialized, updated_at
    ) values (
        p_room_id, v_team, v_team_melds, true, now()
    )
    on conflict (room_id, team) do update
    set melds = excluded.melds,
        melds_initialized = true,
        updated_at = now();

    v_public_state := private.cbr_latest_public_state(p_room_id);
    select event.payload ->> 'roundId'
    into v_round_id
    from public.match_events event
    where event.room_id = p_room_id
      and event.event_type = 'PUBLIC_STATE'
    order by event.id desc
    limit 1;

    perform private.cbr_publish_server_event(
        p_room_id => p_room_id,
        p_actor_id => v_room.host_id,
        p_event_type => 'PUBLIC_STATE',
        p_payload => jsonb_build_object(
            'type', 'PUBLIC_STATE',
            'senderId', v_room.host_id::text,
            'roundId', coalesce(v_round_id, ''),
            'messageId', v_message_id,
            'payload', jsonb_build_object(
                'v', 1,
                'activeSeat', p_seat,
                'deckSize', coalesce((p_draw_result ->> 'deckSize')::integer, 0),
                'discardCount', jsonb_array_length(coalesce(p_draw_result -> 'discardPile', '[]'::jsonb)),
                'discardPile', coalesce(p_draw_result -> 'discardPile', '[]'::jsonb),
                'turnCard', coalesce(v_public_state ->> 'turnCard', ''),
                'mortosLeft', coalesce((p_draw_result ->> 'mortosLeft')::integer, 0),
                'handCounts', coalesce(v_public_state -> 'handCounts', '[]'::jsonb),
                'team0Melds', case when v_team = 0
                    then v_team_melds
                    else coalesce(v_public_state -> 'team0Melds', '[]'::jsonb)
                end,
                'team1Melds', case when v_team = 1
                    then v_team_melds
                    else coalesce(v_public_state -> 'team1Melds', '[]'::jsonb)
                end
            )::text
        ),
        p_recipient_seat => null
    );
end;
$$;

revoke all on function private.cbr_record_drawn_red_three(uuid, integer, text, jsonb)
from public, anon, authenticated;

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
    v_drawn_seat integer;
    v_result jsonb;
    v_card text;
    v_auto_red_three boolean;
begin
    select room.*
    into v_room
    from public.match_rooms room
    where room.id = p_room_id
    for update;

    if not found then
        raise exception 'ROOM_NOT_FOUND' using errcode = 'P0001';
    end if;

    select state.drawn_seat
    into v_drawn_seat
    from private.match_deck_state state
    where state.room_id = p_room_id
    for update;

    if not found then
        raise exception 'DECK_STATE_NOT_FOUND' using errcode = 'P0001';
    end if;
    if v_drawn_seat is not null then
        raise exception 'DRAW_ALREADY_COMPLETED' using errcode = 'P0001';
    end if;

    v_result := private.cbr_online_draw_deck_card_without_turn_guard(p_room_id, p_seat);
    if coalesce(v_result ->> 'status', '') <> 'OK' then
        return v_result;
    end if;

    v_card := nullif(v_result ->> 'card', '');
    v_auto_red_three := v_room.game_type = 'TRANCA'
        and coalesce((v_room.config ->> 'autoMeldTrancaRedThrees')::boolean, true)
        and private.cbr_card_rank(v_card) = 'THREE'
        and private.cbr_card_suit(v_card) in ('HEARTS', 'DIAMONDS');

    if v_auto_red_three then
        perform private.cbr_record_drawn_red_three(
            p_room_id,
            p_seat,
            v_card,
            v_result
        );
    else
        update private.match_deck_state
        set drawn_seat = p_seat,
            updated_at = now()
        where room_id = p_room_id;
    end if;

    return v_result;
end;
$$;

revoke all on function public.online_draw_deck_card(uuid, integer)
from public, anon, authenticated;

comment on function private.cbr_record_drawn_red_three(uuid, integer, text, jsonb) is
'Registra no ledger e no estado publico o 3 vermelho automatico retirado do monte.';
