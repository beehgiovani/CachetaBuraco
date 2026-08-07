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

As migracoes `0001` a `0016` estao aplicadas no projeto remoto. Para uma nova
migracao, simular antes e conferir se somente o arquivo esperado aparece:

```powershell
npx supabase db push --linked --dry-run --profile carteado-br-online
npx supabase db push --linked --profile carteado-br-online
npx supabase db lint --linked --level warning --fail-on error --profile carteado-br-online
npx supabase migration list --linked --profile carteado-br-online
```

## Responsabilidades

- Supabase guarda perfis, estatisticas, ranking, salas, presenca e eventos.
- A `0009` congela os participantes de cada resultado e calcula rankings semanal e mensal no fuso de Sao Paulo.
- A `0010` aceita somente avatares internos conhecidos e atualiza apenas o perfil autenticado.
- A `0011` redige maos e cartas privadas assim que o resultado da partida e confirmado.
- A `0012` rejeita acoes criticas de um cliente quando outro assento esta na vez.
- A `0013` deriva remetente e assento da sessao autenticada e valida payloads criticos.
- A `0014` valida formato dos jogos, IDs das cartas e compra do topo publico do lixo.
- A `0015` corrige tipagem/volatilidade desses helpers e mantem o lint sem avisos.
- A `0016` guarda maos de clientes no schema privado e valida posse, lixo, morto e vitoria.
- A RPC de eventos aplica tipo, papel e destinatario antes de persistir a mensagem.
- Android mantem interface, fluxo local e validacao imediata.
- O host e a autoridade da primeira versao online.
- Antes da versao competitiva, o servidor ainda deve assumir o baralho e a mao do host por completo.

A secret key compartilhada durante a configuracao inicial ja foi rotacionada.
A autenticacao anonima fica habilitada durante o Beta e depois pode ser
vinculada a Google ou e-mail sem trocar a identidade.
