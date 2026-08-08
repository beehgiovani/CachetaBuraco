# Roadmap do produto - Carteado BR

Este documento concentra o caminho do aplicativo inteiro. O detalhamento do
backend fica em `online-roadmap.md` e a monetizacao fica em
`monetization-plan.md`.

## 1. Regras e robustez da partida

- [x] Manter Cacheta, Buraco e Tranca centralizados no `GameRulesEngine`.
- [x] Usar 9 cartas na Cacheta e 11 cartas no Buraco/Tranca.
- [x] Separar transporte local, maquina e online pelo mesmo contrato de mesa.
- [x] Fechar a matriz automatizada de regras por modo: compra, lixo, meld,
  descarte, morto, fim de monte, batida, nova rodada e pontuacao. Gaps reais
  fechados com teste ao longo de varias sessoes: batida do Buraco (so
  Cacheta/Tranca tinham), reciclagem do monte da Cacheta ("fim de monte" sem
  morto), Cacheta com monte E lixo reciclado esgotados ao mesmo tempo,
  Cacheta em dupla contando a mesma baixa em dobro no placar (bug real
  corrigido), batida direta local no Buraco com canastra limpa/suja, 3
  vermelho da Tranca em 4 jogadores passando pelo parceiro (mesa da equipe
  certa, nunca a do assento isolado) e placar por time (nao por assento)
  preservado em "proxima rodada" no modo dupla. 86 testes cobrem
  `MatchViewModelStartGameTest.kt` sozinho. "Exaustivo" no sentido absoluto
  nunca fica 100% provado, mas todo gap concreto identificado foi fechado.
- [x] Cobrir tentativas adulteradas: carta inexistente, carta repetida, assento
  falso, acao fora da vez, retry duplicado e resumo de rodada forjado.
  Coberto no `MatchViewModelStartGameTest.kt` para o host local/maquina (o
  lado Supabase ja tinha isso via migrations 0012-0019).
- [ ] Homologar partidas completas de cada modo em dois aparelhos fisicos.
  Testado fisico + emulador (Buraco, rodada completa online): descoberta de
  sala, entrada e jogo funcionaram apos corrigir o engine Ktor (ver secao 6).
  Falta repetir com dois aparelhos fisicos e cobrir Cacheta/Tranca e 4p.

## 2. Maquina e dificuldades

- [x] Manter o bot no mesmo fluxo de mensagens usado por um oponente real.
- [x] Separar decisoes do bot em `BotDecisionEngine` com testes unitarios.
- [x] Calibrar Facil, Normal e Dificil com comportamentos realmente distintos,
  sem fazer o nivel facil entregar a partida. Pesos e limiares diferentes por
  nivel em `BotDecisionEngine.kt` (compra do lixo, uso de curinga, risco do
  descarte); Facil erra por estrategia (aceita a 2a melhor jogada dentro de um
  limite), nunca por regra invalida.
- [x] Fazer o bot avaliar mao, lixo, jogos proprios, jogos adversarios, risco do
  descarte, morto, canastra e condicao de batida. Cobre todos esses sinais;
  morto fica restrito a seguranca (nao esvaziar a mao sem poder concluir o
  turno), sem estrategia de antecipar a compra do morto.
- [x] Criar cenarios fixos para medir a decisao esperada de cada dificuldade.
  18 testes em `BotDecisionEngineTest.kt` comparando Facil/Normal/Dificil lado
  a lado (compra do lixo, nao alimentar adversario, 3 preto defensivo,
  timing de curinga, qualidade da baixa).

## 3. Interface adaptativa da partida

- [ ] Garantir mao, monte, lixo, mortos e mesas visiveis em telas pequenas,
  grandes e com fonte do sistema ampliada.
- [ ] Redimensionar a matriz de jogos conforme espaco e quantidade; usar rolagem
  somente quando o tamanho minimo legivel nao couber.
- [ ] Manter lados, turnos, quantidade de cartas e placar visualmente claros.
- [ ] Respeitar barras do sistema ou usar modo imersivo sem esconder controles.
- [ ] Criar `@Preview` para telas e componentes visuais representativos, com
  estados de fonte grande, tela compacta, mesa cheia, vazio e erro.
- [ ] Validar previews e capturas em larguras/fontes variadas antes da entrega.

## 4. Animacao, som e identidade visual

- [ ] Refinar distribuicao inicial, compra, descarte, descida de jogo, encaixe,
  morto, troca de turno, fim de rodada e vitoria.
- [ ] Manter duracoes curtas e consistentes, sem atrasar a jogada ou deslocar a UI.
- [ ] Usar brilho, sombra e destaque somente para indicar estado ou acao valida.
- [ ] Revisar sons, vibracoes, splash, cartas, mesa, avatares, icone e assets da
  Play Store como um conjunto visual unico.
- [ ] Respeitar reducao de movimento, estado sem som e aparelhos mais lentos.

## 5. Criacao e entrada em salas

- [x] Mostrar regras e configuracoes antes de o convidado entrar na sala.
- [ ] Transformar a criacao em fluxo curto: modo, jogadores, regras e confirmacao.
- [ ] Exibir identidade visual propria para Cacheta, Buraco e Tranca.
- [ ] Publicar a sala para descoberta somente depois de a configuracao ser
  confirmada e persistida, tanto no Wi-Fi quanto no online.
- [ ] Mostrar progresso, sucesso, erro recuperavel e cancelamento sem duplicar sala.
- [ ] Preservar configuracoes escolhidas durante rotacao, reconexao e retorno de tela.

## 6. Online e antitrapaca

- [x] Criar salas, presenca, eventos Realtime, reconexao e ranking no Supabase.
- [x] Corrigir o engine HTTP do Supabase (`ktor-client-android` nao suporta
  WebSocket, entao o Realtime nunca conectava e a descoberta de sala online
  travava para sempre). Trocado para `ktor-client-okhttp` em
  `app/build.gradle.kts`; validado com fisico+emulador.
- [x] Deduplicar eventos e validar identidade, direcao e turno no banco.
- [x] Validar no servidor posse das cartas, lixo, morto e vitoria dos assentos clientes.
- [x] Identificar rodadas no app e aguardar confirmacao antes de uma nova distribuicao.
- [x] Aplicar e homologar no remoto a protecao de eventos atrasados da migration `0017`.
- [ ] Mover baralho, mao do host e transicoes completas para autoridade server-side antes do competitivo.
  Distribuicao inicial (embaralhar, mao de cada assento, vira, mortos, lixo de
  abertura) ja e server-side (migrations `0020`-`0023` + `MatchViewModel`
  ligado). Detalhe completo em `online-roadmap.md`. Falta homologar num
  aparelho de verdade e mover as transicoes seguintes (compra durante a
  rodada, nova rodada completa) -- essas continuam no mesmo nivel de
  confianca no host que ja tinham antes.
- [ ] Manter cartas privadas fora do estado publico e apagar payload privado apos
  o resultado confirmado.
- [x] Tratar falhas esperadas com resultados explicitos; nao usar `try/catch`
  generico para esconder erro de regra ou aceitar jogada duvidosa. Auditado
  `OnlineNetworkRepository.kt` e `data/online/*`: toda falha (rede, rejeicao
  do servidor, payload invalido) vira "nao entregue"/`ConnectionStatus.ERROR`,
  nunca sucesso silencioso. A regra em si e validada nas RPCs (`0014`-`0019`).
  Melhoria possivel, nao bloqueante: distinguir rejeicao de regra (mostrar
  "jogada invalida") de falha de rede (mostrar "sessao caiu") na UI.
- [ ] Adicionar telemetria de falhas sem registrar mao, token ou dado privado.
  Escrita e validada localmente: migration `0024_client_failure_telemetry.sql`
  cria `client_failure_events` + RPC `report_client_failure`, com categoria
  fechada (lista fixa, nao texto livre) pra nunca correr risco de vazar
  mensagem de excecao crua. Ainda NAO aplicada em producao -- CLI do Supabase
  caiu pro projeto errado de novo, aguardando novo login. App ainda nao chama
  essa RPC (falta ligar em `OnlineNetworkRepository.reportSessionFailure`).

## 7. Conta Google e perfil

- [x] Usar sessao anonima do Supabase durante o Beta.
- [ ] Configurar Google como provedor no Supabase Auth e vincular a identidade
  anonima existente para preservar perfil, XP e ranking.
- [ ] Configurar callback/deep link Android e testar cancelamento e troca de conta.
- [ ] Firebase Auth nao e obrigatorio: o Supabase Auth faz OAuth Google direto.

## 8. Qualidade e publicacao

- [x] Rotacionar a secret key exposta durante a configuracao inicial.
- [ ] Exigir testes unitarios para regras, bot, codecs, sala, ranking e ViewModels.
- [ ] Adicionar testes instrumentados para navegacao, fonte grande e partida basica.
- [ ] Rodar unit tests, lint e build debug/release antes de cada marco pratico.
- [ ] Manter comentarios curtos, naturais e voltados ao motivo da regra.
- [ ] Remover codigo, scripts e assets legados somente depois de provar que nao ha uso.
- [ ] Finalizar ficha da Play Store, privacidade, Data Safety, consentimento de
  anuncios e testes internos antes da publicacao.

## Ordem de execucao

1. Fechar seguranca estrutural e testes online em andamento.
2. Homologar regras e partidas completas por modo.
3. Calibrar os tres niveis do bot.
4. Homologar interface adaptativa e previews.
5. Polir animacoes, sons e assets sem alterar regras.
6. Simplificar criacao/entrada de salas.
7. Vincular conta Google pelo Supabase Auth.
8. Instrumentacao, testes fisicos e publicacao interna.
