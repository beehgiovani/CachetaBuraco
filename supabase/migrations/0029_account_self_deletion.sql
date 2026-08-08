-- Exclusao de conta pelo proprio usuario (LGPD, direito ao esquecimento).
-- Apago direto de auth.users -- profiles.id ja referencia auth.users(id)
-- on delete cascade, e todas as tabelas que dependem de profiles (player_stats,
-- match_rooms.host_id, room_players, match_result_players, player_medals,
-- avatar_photo_reports) tambem sao cascade; as poucas que preservam
-- historico de outros jogadores (match_events.actor_id,
-- match_results.winner_profile_id, client_failure_events.profile_id) usam
-- set null em vez de cascade, entao a exclusao nunca deixa FK orfa nem
-- apaga resultado de partida de quem continuar jogando.

create or replace function public.delete_own_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from auth.users where id = auth.uid();
end;
$$;

revoke all on function public.delete_own_account() from public;
grant execute on function public.delete_own_account() to authenticated;
