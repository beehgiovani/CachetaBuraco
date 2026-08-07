-- As cartas privadas ficam disponiveis enquanto a partida precisa reconectar.
-- Depois do resultado confirmado, mantenho apenas o envelope necessario para
-- auditoria e retiro maos, cartas servidas e estados privados do historico.

create or replace function private.redact_completed_match_private_events()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_result_event_id bigint;
begin
    select event.id
    into v_result_event_id
    from public.match_events event
    where event.room_id = new.room_id
      and event.message_id = new.result_key
      and event.event_type = 'ROUND_SUMMARY';

    if v_result_event_id is null then
        return new;
    end if;

    update public.match_events event
    set payload = jsonb_build_object(
        'messageId', event.message_id,
        'type', event.event_type,
        'redacted', true
    )
    where event.room_id = new.room_id
      and event.id <= v_result_event_id
      and event.event_type in (
          'GAME_START',
          'SERVE_CARD',
          'SERVE_MORTO',
          'RECONNECT_STATE'
      )
      and event.payload ->> 'redacted' is distinct from 'true';

    return new;
end;
$$;

revoke all on function private.redact_completed_match_private_events() from public;

drop trigger if exists redact_completed_match_private_events
on public.match_results;

create trigger redact_completed_match_private_events
after insert on public.match_results
for each row
execute function private.redact_completed_match_private_events();

-- Aplica a mesma regra aos resultados gravados antes desta migracao sem tocar
-- nos eventos de uma partida nova que possa estar usando a mesma sala.
update public.match_events event
set payload = jsonb_build_object(
    'messageId', event.message_id,
    'type', event.event_type,
    'redacted', true
)
where event.event_type in (
    'GAME_START',
    'SERVE_CARD',
    'SERVE_MORTO',
    'RECONNECT_STATE'
)
and event.payload ->> 'redacted' is distinct from 'true'
and exists (
    select 1
    from public.match_results result
    join public.match_events summary
      on summary.room_id = result.room_id
     and summary.message_id = result.result_key
     and summary.event_type = 'ROUND_SUMMARY'
    where result.room_id = event.room_id
      and event.id <= summary.id
);
