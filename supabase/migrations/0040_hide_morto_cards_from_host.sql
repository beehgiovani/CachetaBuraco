-- O conteudo dos mortos pertence ao servidor ate o momento da retirada.
-- Manter essas cartas no retorno inicial permitia que um host modificado
-- conhecesse duas pilhas que ainda deveriam estar fechadas.

alter function public.start_online_round(uuid) set schema private;
alter function private.start_online_round(uuid)
rename to cbr_start_online_round_with_private_material;

revoke all on function private.cbr_start_online_round_with_private_material(uuid)
from public, anon, authenticated;

create or replace function public.start_online_round(p_room_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_result jsonb;
begin
    v_result := private.cbr_start_online_round_with_private_material(p_room_id);

    -- A mao do host continua privada para ele, mas os mortos permanecem apenas
    -- no estado interno usado pelas RPCs de compra e retirada.
    return v_result - 'mortos';
end;
$$;

revoke all on function public.start_online_round(uuid) from public;
grant execute on function public.start_online_round(uuid) to authenticated;

comment on function public.start_online_round(uuid) is
'Distribui a rodada no servidor sem revelar ao host as cartas guardadas nos mortos.';
