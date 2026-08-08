-- Medalhas de gamificacao (Fase 5): vitorias totais, sequencia de vitorias,
-- vitorias por modo e partidas jogadas. Persistidas no servidor (com data de
-- conquista) em vez de recalculadas no cliente, para nunca "regredir" uma
-- medalha ja ganha e permitir avisar "medalha nova" so uma vez no futuro.
--
-- complete_match atualiza player_stats com um UPDATE set-based
-- (0005_reusable_room_match_results.sql), entao a concessao de medalha entra
-- por um trigger separado em vez de mexer nessa funcao ja testada em
-- producao -- mesmo padrao ja usado pelos triggers da 0006/0009.

create table if not exists public.player_medals (
    profile_id uuid not null references public.profiles(id) on delete cascade,
    medal_code text not null,
    earned_at timestamptz not null default now(),
    primary key (profile_id, medal_code)
);

alter table public.player_medals enable row level security;

grant select on public.player_medals to authenticated;

create policy "player_medals_select_public"
on public.player_medals for select
to authenticated
using (true);

create or replace function private.grant_player_medals()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if new.total_wins >= 10 and old.total_wins < 10 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'WINS_10') on conflict do nothing;
    end if;
    if new.total_wins >= 50 and old.total_wins < 50 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'WINS_50') on conflict do nothing;
    end if;
    if new.total_wins >= 150 and old.total_wins < 150 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'WINS_150') on conflict do nothing;
    end if;

    if new.best_streak >= 3 and old.best_streak < 3 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'STREAK_3') on conflict do nothing;
    end if;
    if new.best_streak >= 5 and old.best_streak < 5 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'STREAK_5') on conflict do nothing;
    end if;
    if new.best_streak >= 10 and old.best_streak < 10 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'STREAK_10') on conflict do nothing;
    end if;

    if new.cacheta_wins >= 10 and old.cacheta_wins < 10 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'CACHETA_WINS_10') on conflict do nothing;
    end if;
    if new.cacheta_wins >= 25 and old.cacheta_wins < 25 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'CACHETA_WINS_25') on conflict do nothing;
    end if;
    if new.cacheta_wins >= 50 and old.cacheta_wins < 50 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'CACHETA_WINS_50') on conflict do nothing;
    end if;

    if new.buraco_wins >= 10 and old.buraco_wins < 10 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'BURACO_WINS_10') on conflict do nothing;
    end if;
    if new.buraco_wins >= 25 and old.buraco_wins < 25 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'BURACO_WINS_25') on conflict do nothing;
    end if;
    if new.buraco_wins >= 50 and old.buraco_wins < 50 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'BURACO_WINS_50') on conflict do nothing;
    end if;

    if new.tranca_wins >= 10 and old.tranca_wins < 10 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'TRANCA_WINS_10') on conflict do nothing;
    end if;
    if new.tranca_wins >= 25 and old.tranca_wins < 25 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'TRANCA_WINS_25') on conflict do nothing;
    end if;
    if new.tranca_wins >= 50 and old.tranca_wins < 50 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'TRANCA_WINS_50') on conflict do nothing;
    end if;

    if new.total_matches >= 25 and old.total_matches < 25 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'MATCHES_25') on conflict do nothing;
    end if;
    if new.total_matches >= 100 and old.total_matches < 100 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'MATCHES_100') on conflict do nothing;
    end if;
    if new.total_matches >= 300 and old.total_matches < 300 then
        insert into public.player_medals(profile_id, medal_code) values (new.profile_id, 'MATCHES_300') on conflict do nothing;
    end if;

    return new;
end;
$$;

drop trigger if exists player_stats_grant_medals on public.player_stats;
create trigger player_stats_grant_medals
after update on public.player_stats
for each row execute function private.grant_player_medals();

-- Backfill: contas que ja passaram de algum limiar antes desta migration
-- existir nunca disparariam o trigger sozinhas (ele so reage a updates
-- futuros), entao concedemos aqui de uma vez so com o estado atual.
insert into public.player_medals (profile_id, medal_code)
select profile_id, medal_code
from public.player_stats stats
cross join (values
    ('total_wins', 10, 'WINS_10'),
    ('total_wins', 50, 'WINS_50'),
    ('total_wins', 150, 'WINS_150'),
    ('best_streak', 3, 'STREAK_3'),
    ('best_streak', 5, 'STREAK_5'),
    ('best_streak', 10, 'STREAK_10'),
    ('cacheta_wins', 10, 'CACHETA_WINS_10'),
    ('cacheta_wins', 25, 'CACHETA_WINS_25'),
    ('cacheta_wins', 50, 'CACHETA_WINS_50'),
    ('buraco_wins', 10, 'BURACO_WINS_10'),
    ('buraco_wins', 25, 'BURACO_WINS_25'),
    ('buraco_wins', 50, 'BURACO_WINS_50'),
    ('tranca_wins', 10, 'TRANCA_WINS_10'),
    ('tranca_wins', 25, 'TRANCA_WINS_25'),
    ('tranca_wins', 50, 'TRANCA_WINS_50'),
    ('total_matches', 25, 'MATCHES_25'),
    ('total_matches', 100, 'MATCHES_100'),
    ('total_matches', 300, 'MATCHES_300')
) as threshold(column_name, min_value, medal_code)
where
    case threshold.column_name
        when 'total_wins' then stats.total_wins
        when 'best_streak' then stats.best_streak
        when 'cacheta_wins' then stats.cacheta_wins
        when 'buraco_wins' then stats.buraco_wins
        when 'tranca_wins' then stats.tranca_wins
        when 'total_matches' then stats.total_matches
    end >= threshold.min_value
on conflict do nothing;
