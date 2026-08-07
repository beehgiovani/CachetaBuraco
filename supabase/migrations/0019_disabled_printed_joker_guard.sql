-- Impede que um Joker adulterado seja tratado como carta natural quando a
-- configuracao da sala desativa curingas. O 2 continua sendo curinga normal.

create or replace function private.cbr_room_allows_printed_jokers(p_config jsonb)
returns boolean
language plpgsql
stable
set search_path = ''
as $$
declare
    v_named_value text;
    v_serialized_value text;
begin
    v_named_value := p_config ->> 'allowWildcards';
    if v_named_value in ('true', 'false') then
        return v_named_value::boolean;
    end if;

    -- O terceiro campo do CSV representa allowWildcards em todas as versoes.
    v_serialized_value := split_part(coalesce(p_config ->> 'serialized', ''), ',', 3);
    if v_serialized_value in ('true', 'false') then
        return v_serialized_value::boolean;
    end if;

    return true;
end;
$$;

revoke all on function private.cbr_room_allows_printed_jokers(jsonb) from public;

create or replace function private.enforce_disabled_printed_joker()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_room_config jsonb;
    v_inner jsonb;
    v_cards jsonb;
begin
    if new.event_type <> 'MELD' then
        return new;
    end if;

    select room.config
    into v_room_config
    from public.match_rooms room
    where room.id = new.room_id;

    if private.cbr_room_allows_printed_jokers(v_room_config) then
        return new;
    end if;

    begin
        v_inner := (new.payload ->> 'payload')::jsonb;
    exception
        when others then
            return new;
    end;

    v_cards := v_inner -> 'cards';
    if coalesce(jsonb_typeof(v_cards), '') <> 'array' then
        return new;
    end if;

    if exists (
        select 1
        from jsonb_array_elements_text(v_cards) as item(card)
        where private.cbr_is_joker(item.card)
    ) then
        raise exception 'PRINTED_JOKER_DISABLED' using errcode = 'P0001';
    end if;

    return new;
end;
$$;

revoke all on function private.enforce_disabled_printed_joker() from public;

drop trigger if exists enforce_disabled_printed_joker on public.match_events;
create trigger enforce_disabled_printed_joker
before insert on public.match_events
for each row
execute function private.enforce_disabled_printed_joker();
