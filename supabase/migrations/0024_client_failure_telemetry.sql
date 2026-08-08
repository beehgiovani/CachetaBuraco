-- Telemetria de falhas do cliente, sem guardar mao, token ou qualquer dado
-- privado da partida. Uso isso pra enxergar com que frequencia o transporte
-- online falha (sessao caiu, heartbeat parou, evento nao foi publicado) sem
-- precisar pedir print de log pro jogador.
--
-- Deixei a categoria como uma lista fechada de valores (nao texto livre)
-- exatamente pra nao correr o risco de alguem um dia passar uma mensagem de
-- excecao crua aqui dentro e vazar algo sensivel sem querer -- se um valor
-- novo de categoria for precisar, isso e uma decisao consciente de mexer
-- nesta migration, nao um acidente de string livre.

create table if not exists public.client_failure_events (
    id bigint generated always as identity primary key,
    profile_id uuid references public.profiles(id) on delete set null,
    room_id uuid references public.match_rooms(id) on delete set null,
    category text not null check (category in (
        'ONLINE_SESSION_ERROR',
        'ONLINE_HEARTBEAT_FAILED',
        'ONLINE_PUBLISH_FAILED',
        'ONLINE_DEAL_REQUEST_FAILED'
    )),
    app_version text,
    created_at timestamptz not null default now()
);

alter table public.client_failure_events enable row level security;

-- So escrita: o app nunca precisa ler telemetria de volta, so registrar.
create policy "client_failure_events_insert_self"
on public.client_failure_events for insert
to authenticated
with check (profile_id = auth.uid() or profile_id is null);

create index if not exists idx_client_failure_events_category_created
on public.client_failure_events (category, created_at desc);

create or replace function public.report_client_failure(
    p_category text,
    p_room_id uuid default null,
    p_app_version text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = 'P0001';
    end if;

    insert into public.client_failure_events(profile_id, room_id, category, app_version)
    values (v_user_id, p_room_id, p_category, nullif(trim(coalesce(p_app_version, '')), ''));

    return true;
end;
$$;

revoke all on function public.report_client_failure(text, uuid, text) from public;
grant execute on function public.report_client_failure(text, uuid, text) to authenticated;
