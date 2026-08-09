# Supabase - Carteado BR

Base online separada do motor local do jogo.

## Projeto

- Project ref: `yvpbegrdepevppglbcbm`
- URL publica: `https://yvpbegrdepevppglbcbm.supabase.co`
- Perfil da CLI: `carteado-br-online`
- O Android usa somente URL publica e publishable key.
- Secret key e senha do Postgres nunca entram no app ou no Git.

## Conferencia antes de alterar o banco

Como a mesma maquina acessa mais de uma conta Supabase, qualquer comando de
escrita deve confirmar primeiro o perfil e o projeto linkado:

```powershell
Get-Content supabase/.temp/project-ref
npx supabase projects list --profile carteado-br-online --output json
```

Os dois resultados precisam apontar para `yvpbegrdepevppglbcbm`.

## Migracoes

As migracoes `0001` a `0035` estao aplicadas no projeto remoto (ver
`docs/online-roadmap.md` pra decisao de design por fase). Para uma nova
migracao, simular antes e conferir se somente o arquivo esperado aparece:

```powershell
npx supabase db push --linked --dry-run --profile carteado-br-online
npx supabase db push --linked --profile carteado-br-online
npx supabase db lint --linked --level warning --fail-on error --profile carteado-br-online
npx supabase migration list --linked --profile carteado-br-online
```

## Responsabilidades

- Supabase guarda perfis, estatisticas, ranking, salas, presenca e eventos.
- `0001`-`0016`: fundacao online -- salas, RLS, entrega confiavel, ranking
  global/por periodo, redacao de mao/carta privada, guarda de turno ativo,
  identidade autenticada nos eventos, validacao estrutural de jogos/descarte.
- `0017`-`0026`: servidor vira autoridade da partida -- guarda de identidade
  de rodada, baralho/mao/morto sorteados e servidos pelo servidor
  (`start_online_round`, `online_draw_deck_card`, `online_take_morto`), nao
  mais pelo host sozinho.
- `0027`-`0030`: gamificacao sem fichas -- medalhas (`player_medals`, trigger
  em `player_stats`), foto de avatar real (upload + moderacao basica).
- `0031`-`0033`: sala privada com senha, chat de sala (apagado ao encerrar a
  partida), historico de ranking por periodo (semanal/mensal, sem tabela de
  reset -- calculado ao vivo por `period_offset`).
- `0034`-`0035`: campeonatos por pontos -- inscricao por codigo (mesmo padrao
  de sala privada, sem lista publica), vinculo de sala por `room_code`
  (`link_room_to_championship`), classificacao e historico calculados ao
  vivo (sem tabela de leaderboard persistida).
- A RPC de eventos aplica tipo, papel e destinatario antes de persistir a mensagem.
- Android mantem interface, fluxo local e validacao imediata.
- O servidor e a autoridade da partida online desde a Fase de compra/deal
  server-side (`0020`-`0026`) -- o host so manda no fluxo Wi-Fi local.

A secret key compartilhada durante a configuracao inicial ja foi rotacionada.
A autenticacao anonima fica habilitada durante o Beta e depois pode ser
vinculada a Google ou e-mail sem trocar a identidade.
