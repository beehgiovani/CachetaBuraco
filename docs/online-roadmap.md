# Roadmap online - Carteado BR

Este roadmap deixa o caminho do online separado do jogo local. A regra continua no `GameRulesEngine`, a tela continua em `MatchScreen` e cada transporte novo precisa respeitar o contrato de mesa para nao misturar responsabilidades.

O escopo completo de regras, bot, interface, animacoes, salas, identidade e
publicacao esta em `product-roadmap.md`.

## Checklist de seguranca

- [x] Rotacionar a `SUPABASE_SECRET_KEY` que foi colada no chat antes de qualquer publicacao.
- [x] Nunca colocar `SUPABASE_SECRET_KEY`, senha do Postgres ou connection string dentro do app Android.
- [x] Usar no Android apenas `SUPABASE_URL` e `SUPABASE_PUBLISHABLE_KEY`.
- [x] Manter RLS ligada em todas as tabelas publicas nas migracoes.
- [x] Criar politicas por usuario autenticado antes de expor escrita real.
- [x] Registrar eventos de partida com `message_id` unico e ACK idempotente para evitar jogada duplicada.
- [x] Restringir mensagens privadas ao assento destinatario.
- [x] Restringir tipos e direcao dos eventos entre host e clientes no banco.
- [x] Validar no host (autoridade principal da partida).
- [x] Rejeitar estruturalmente MELD invalido e DRAW_DISCARD bloqueado pela config da sala (migration `0014`).
- [x] Validar posse da carta, mao vazia, lixo, morto e vitoria dos assentos clientes no servidor (migration `0016`).
- [x] Aplicar a migration `0017` para rejeitar eventos atrasados de outra rodada no projeto remoto correto.
- [ ] Tornar o servidor autoridade integral do baralho e tambem da mao do host antes de um modo competitivo.

## Fase 1 - Base online sem mudar o jogo local

- [x] Criar projeto Supabase e confirmar regiao.
- [x] Rodar `supabase login` no terminal correto.
- [x] Rodar `supabase link --project-ref yvpbegrdepevppglbcbm`.
- [x] Aplicar as migracoes `0001`, `0002`, `0003` e `0004` no projeto remoto.
- [x] Aplicar a migracao corretiva `0005` no projeto remoto antes do teste pratico online.
- [x] Criar wrapper de configuracao online no app, sem secret key.
- [x] Criar `OnlineNetworkRepository` implementando `LocalNetworkRepository`.
- [x] Criar salas, entrada atomica, presenca e eventos Realtime na camada de dados.
- [x] Deduplicar eventos no banco, no transporte e no ViewModel.
- [x] Reenviar entregas privadas com o mesmo `messageId` e restaurar carta/morto quando falharem.
- [x] Renovar presenca a cada 10 segundos e expirar conexoes abandonadas depois de 30 segundos.
- [x] Manter `LocalNetworkRepositoryImpl` para Wi-Fi local e `SoloBotNetworkRepository` para maquina.
- [x] Habilitar autenticacao anonima no painel para o primeiro teste pratico.
- [x] Ligar os fluxos de criar e encontrar sala online na UI com identificacao Beta.
- [ ] Validar os dois fluxos em aparelhos fisicos depois da aplicacao remota das migracoes.

## Fase 2 - Perfil e ranking global

- [x] Usar o `profile_id` autenticado como identidade da partida online.
- [x] Encerrar a sessao anonima ao sair para nao reaproveitar o perfil anterior.
- [x] Salvar apelido, estatisticas e data da ultima partida.
- [x] Permitir escolher e sincronizar avatar interno do perfil.
- [x] Criar ranking global por vitorias.
- [x] Criar ranking semanal e mensal.
- [x] Mostrar ranking online em tela propria, fora da partida.
- [ ] Sincronizar ranking local somente quando usuario optar/estiver logado.

## Fase 3 - Salas online

- [x] Criar sala com codigo curto.
- [x] Entrar em sala por codigo.
- [x] Exibir regras da sala antes de entrar.
- [x] Usar presence/realtime para status dos jogadores.
- [x] Persistir estado publico da mesa como evento do host.
- [x] Persistir eventos de jogada com ordem e deduplicacao.
- [x] Reconectar integrante no mesmo assento sem vazar mao privada.

## Fase 4 - Antitrapaca e consistencia

- [x] Host continua sendo autoridade na primeira versao online.
- [x] Validar estruturalmente MELD e DRAW_DISCARD no banco pela migration `0014`.
- [x] Servidor valida posse da carta, mao vazia, lixo, morto e vitoria dos assentos clientes com estado privado por assento.
- [ ] Servidor passa a controlar baralho, mao do host e todas as transicoes sem depender da autoridade do aparelho host.
- [x] Servidor confirma repeticao identica e rejeita colisao diferente pelo `message_id`.
- [x] Host rejeita evento fora do turno antes de alterar a mesa canonica.
- [x] Banco rejeita compra, baixa e descarte enviados por um assento fora do turno publico.
- [x] Banco rejeita evento desconhecido, destinatario incorreto e mensagem exclusiva do papel oposto.
- [x] App identifica cada rodada, preserva o token nos retries e so redistribui depois do ACK de `NEXT_ROUND`.
- [x] Banco valida o `roundId` e impede resultado antigo de limpar a mao da rodada atual (migration `0017`).
- [x] Servidor salva resumo final da partida com breakdown e atualiza estatisticas de forma idempotente.
- [x] Logs de auditoria removem cartas privadas depois do resultado confirmado.

## Fase 5 - Gamificacao sem fichas

- [x] XP por partidas finalizadas.
- [ ] Medalhas por vitorias, sequencias e campeonatos.
- [ ] Temporadas semanais/mensais.
- [x] Estatisticas por modo: Cacheta, Buraco e Tranca.
- [ ] Badges visuais no perfil.
- [ ] Evitar fichas, aposta, moeda ou mecanica que pareca jogo de azar com valor real.

## Fase 6 - Campeonatos

- [ ] Criar campeonato simples por pontos.
- [ ] Inscricao por sala/codigo.
- [ ] Tabela de classificacao.
- [ ] Historico de partidas do campeonato.
- [ ] Temporadas com reset programado do ranking.

## Ordem recomendada

1. Ranking global.
2. Salas online por codigo.
3. Realtime/reconexao.
4. Validacao server-side.
5. Campeonatos.

## Estado do teste remoto

As migracoes `0001` a `0017` foram sincronizadas no projeto remoto e o lint do banco nao
encontrou erros. A `0005` permite varias partidas na mesma sala, valida o resumo persistido e
impede pontuacao duplicada. A `0006` registra a ultima partida e disponibiliza o ranking global
autenticado, limitado e ordenado no servidor. A `0007` impede que perfis sem partida ocupem uma
posicao. A autenticacao anonima esta ativa. O smoke test remoto criou perfil e sala, renovou
a presenca, confirmou o mesmo evento duas vezes sem duplica-lo e encerrou a sala.
Um segundo smoke test usou host, convidado e observador independentes: a sala foi
descoberta, o convidado ocupou o assento 1, reconectou no mesmo assento e recebeu
o evento privado; o observador recebeu zero eventos. O proximo marco pratico e
validar host e convidado em dois aparelhos fisicos. A CLI deve usar o perfil
`carteado-br-online`, autenticado na conta que possui o projeto `yvpbegrdepevppglbcbm`. O perfil
ativo foi conferido com `supabase projects list` antes do push da `0005`.

A migracao `0009_period_rankings.sql` congela os participantes de cada resultado e
disponibiliza os rankings semanal e mensal. O dry-run mostrou somente a `0009`, o historico
local/remoto ficou alinhado, o lint remoto nao encontrou erros e o smoke test autenticado
validou `Geral`, `Semana` e `Mes`. Periodo invalido e acesso sem sessao foram recusados.

A migracao `0010_profile_avatars.sql` limita o perfil aos seis avatares internos do aplicativo
e disponibiliza uma RPC que altera somente o perfil autenticado. O dry-run mostrou apenas a
`0010`, o historico local/remoto ficou alinhado e o lint nao encontrou erros. O smoke test
salvou `builtin:sapphire`; URL externa e chamada sem usuario foram recusadas com HTTP 400.

A migracao `0011_private_event_redaction.sql` conserva cartas privadas somente durante a
partida, quando ainda podem ser necessarias para reconexao. Depois de `complete_match`, os
eventos privados preservam apenas tipo, `messageId` e a marca de redacao. O smoke test remoto
confirmou o marcador antes do resultado e comprovou sua remocao depois dele, sem perder o
envelope de auditoria.

A migracao `0012_active_turn_event_guard.sql` consulta o ultimo `PUBLIC_STATE` e rejeita
compra do monte, compra do lixo, baixa e descarte enviados fora do assento ativo. Um retry
identico continua chegando ao controle de idempotencia mesmo depois da troca de turno. O
smoke test remoto aceitou os dois assentos nas suas respectivas vezes e recusou a tentativa
fora do turno com `OUT_OF_TURN_EVENT`.

A migracao `0013_authenticated_event_identity.sql` troca qualquer `senderId` informado pelo
aparelho pelo usuario autenticado do Supabase e valida o assento declarado nos payloads
criticos. O smoke test confirmou remetente/assento canonicos e retry idempotente; tentativas
com assento divergente ou payload malformado receberam HTTP 400.

A migracao `0014_meld_and_discard_structural_validation.sql` porta `GameRulesEngine.kt`
(trinca/sequencia/canastra e bloqueio do lixo) para plpgsql e liga essa checagem ao mesmo
gatilho da `0012`/`0013`: um `MELD` que nao forma combinacao valida para a config da sala e
um `DRAW_DISCARD` contra o bloqueio do 3 preto (Tranca) ou curinga sao rejeitados com
`INVALID_MELD_SHAPE`/`DISCARD_DRAW_BLOCKED` antes de entrar no historico. A `0014` cuida da
forma publica; a reconstrucao privada das maos foi acrescentada depois, na `0016`. O app
tambem passou a mandar `allowWildcards`,
`allowCharutos` e `allowDrawFromDiscard` como campos estruturados dentro de `config`
(alem do `serialized` de sempre) para o banco ler sem depender do parser posicional do CSV.
A `0016` tambem recebe `requireCleanCanastraToWin` e `cardsPerPlayer`; salas antigas usam
os valores oficiais de compatibilidade quando esses campos nao existem.
O perfil e o projeto foram conferidos novamente antes do push. O smoke test remoto aceitou
jogos validos dos tres modos, retry idempotente e o 3 vermelho automatico da Tranca. Tambem
recusou combinacao invalida, carta desconhecida, carta fisica repetida, charuto desativado,
trinca de quatro cartas na Cacheta, compra com 3 preto no topo, tentativa de comprar outro
topo, assento divergente e payload malformado.

A migracao `0015_structural_validation_lint_fixes.sql` ajusta volatilidade e tipagem dos
helpers da `0014`. Depois dela, o lint remoto ficou sem avisos e um smoke de regressao
confirmou jogo valido, rejeicao de jogo invalido e bloqueio do lixo da Tranca.

A migracao `0016_client_private_hand_ledger.sql` mantem, no schema `private`, a mao dos
assentos clientes e a mesa por equipe. O aplicativo nao recebe acesso a essas tabelas. O
gatilho confere posse antes de baixar ou descartar, obriga o uso do topo comprado do lixo,
so libera as cartas restantes depois da baixa, impede morto com mao cheia ou duplicado e
valida mao vazia, morto e canastra antes de aceitar `WIN_ROUND`. `GAME_START` exige 9 cartas
na Cacheta e 11 no Buraco/Tranca; `RECONNECT_STATE` corrige o ledger com a mao canonica do
host. O envio Android tambem foi serializado para um pedido de morto nunca ultrapassar o
`MELD` que esvaziou a mao. O smoke remoto cobriu cada recusa e aceitou o fluxo valido ate a
vitoria com morto e canastra limpa. O host continua sendo a autoridade do baralho e do seu
proprio assento, portanto a autoridade 100% server-side permanece como etapa competitiva.

A migracao `0017_round_identity_guard.sql` foi aplicada no projeto
`yvpbegrdepevppglbcbm`. O app cria um `roundId` novo em cada distribuicao, carimba as
mensagens online, ignora eventos atrasados e espera o ACK de `NEXT_ROUND` antes de distribuir
novamente. O banco separa rodada ativa, intervalo e partida encerrada, e impede que um
resultado persistido com atraso apague a mao de uma rodada mais nova. O smoke autenticado
confirmou o inicio de duas rodadas, o cancelamento entre elas, a rejeicao de eventos sem
`roundId` ou com token antigo e a idempotencia de retries. O teste especifico de resultado
antigo fica para a homologacao controlada, pois a RPC publica grava uma partida e alteraria o
ranking real. A validacao final em dois aparelhos fisicos continua pendente.
