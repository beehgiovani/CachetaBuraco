-- Chat geral (fora de sala) era Realtime Broadcast puro, sem tabela nem
-- historico -- decisao original pra nao criar mais uma superficie de
-- moderacao/RLS. O usuario pediu (2026-08-09) pra quem abre o chat ver pelo
-- menos as ultimas mensagens, pra ter contexto do assunto em vez de cair
-- numa tela vazia. Troco Broadcast por tabela + Realtime Postgres Changes,
-- exatamente o mesmo padrao ja usado em room_chat_messages (migration
-- 0032) -- so que sem sender_seat/room_id (mensagem publica geral, nao
-- amarrada a nenhuma sala) e com retencao curta via trigger (chat geral
-- nao tem "fim de partida" que dispare limpeza, entao precisa de um jeito
-- proprio de nao crescer pra sempre).

create table if not exists public.global_chat_messages (
    id bigserial primary key,
    sender_id uuid references public.profiles(id) on delete set null,
    sender_name text not null check (char_length(trim(sender_name)) between 1 and 40),
    body text not null check (char_length(trim(body)) between 1 and 300),
    created_at timestamptz not null default now()
);

create index if not exists idx_global_chat_messages_created_at
on public.global_chat_messages (created_at desc);

alter table public.global_chat_messages enable row level security;

grant select, insert on public.global_chat_messages to authenticated;

-- Mensagem publica geral: qualquer autenticado le tudo (sem filtro de sala).
create policy "global_chat_messages_select_authenticated"
on public.global_chat_messages for select
to authenticated
using (true);

create policy "global_chat_messages_insert_self"
on public.global_chat_messages for insert
to authenticated
with check (sender_id = (select auth.uid()));

alter publication supabase_realtime add table public.global_chat_messages;

-- sender_name vem do cliente no insert, mas a RLS acima so confere
-- sender_id -- sem isso, qualquer um autenticado podia se apresentar com o
-- apelido de outro jogador no chat publico. Sobrescreve sempre com o
-- nickname real do perfil autenticado, ignorando o que o cliente mandou.
create or replace function private.enforce_global_chat_sender_name()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    select coalesce(p.nickname, 'Jogador')
    into new.sender_name
    from public.profiles p
    where p.id = new.sender_id;

    if new.sender_name is null then
        new.sender_name := 'Jogador';
    end if;

    return new;
end;
$$;

create trigger trg_enforce_global_chat_sender_name
before insert on public.global_chat_messages
for each row
execute function private.enforce_global_chat_sender_name();

-- Sem "fim de partida" pra disparar limpeza feito o chat de sala -- mantem
-- só as ultimas 200 linhas (bem acima do que a UI mostra) pra nao crescer
-- pra sempre. Roda a cada insert; custo e barato (so conta linhas e apaga
-- as excedentes quando passa do limite).
--
-- Cuidado com o operador aqui: "recent" sempre inclui TODAS as linhas
-- quando a tabela tem 200 ou menos (limit 200 nao corta nada), entao
-- min(id) desse conjunto vira o id mais antigo da tabela inteira -- se o
-- delete usasse "<=" em vez de "<", a primeira mensagem apagava a si mesma
-- assim que era inserida (achado revisando antes de aplicar em producao:
-- min(id) no primeiro insert e o id da propria linha recem-criada).
create or replace function private.trim_global_chat_messages()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from public.global_chat_messages
    where id < (
        select min(id) from (
            select id from public.global_chat_messages
            order by id desc
            limit 200
        ) as recent
    );
    return null;
end;
$$;

create trigger trg_trim_global_chat_messages
after insert on public.global_chat_messages
for each statement
execute function private.trim_global_chat_messages();
