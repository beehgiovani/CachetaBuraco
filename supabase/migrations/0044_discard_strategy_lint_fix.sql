-- Mantem bancos que ja receberam a primeira versao da 0043 com a mesma
-- classificacao corrigida no arquivo original.
alter function private.cbr_can_justify_discard_draw(
    text, jsonb, jsonb, text, boolean, boolean, text
) stable;
