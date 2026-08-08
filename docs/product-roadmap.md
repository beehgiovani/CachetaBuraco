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

- [x] Garantir mao, monte, lixo, mortos e mesas visiveis em telas pequenas,
  grandes e com fonte do sistema ampliada. Auditoria confirmou que
  `DrawPilesPanel` ja le `fontScale` pra trocar pro layout compacto de
  "cartas prioritarias" e nao precisou de mudanca de logica.
- [x] Redimensionar a matriz de jogos conforme espaco e quantidade; usar rolagem
  somente quando o tamanho minimo legivel nao couber. `MeldArea` ja calculava
  colunas/largura pela quantidade de jogos e espaco disponivel; confirmado
  por auditoria, sem mudanca de logica.
- [x] Manter lados, turnos, quantidade de cartas e placar visualmente claros.
  Auditado `TopBar`: fase do turno com texto+cor dedicados ("Compre uma
  carta"/"Baixe ou descarte"/"Turno do oponente"), placar ao vivo (times ou
  jogador x oponente conforme o modo) e contagem de cartas do oponente ja
  exibidos. Sem gap concreto encontrado; "visualmente claro" e subjetivo, mas
  os dados existem e sao legiveis.
- [x] Respeitar barras do sistema ou usar modo imersivo sem esconder controles.
  `MainActivity` ja usa `WindowInsetsControllerCompat` com
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` e re-esconde as barras em
  `onWindowFocusChanged` (padrao recomendado pra elas nao ficarem "presas"
  visiveis apos um swipe). `MatchScreen`/`LobbyScreen` ja aplicam
  `windowInsetsPadding` nos controles proximos as bordas. Sem gap encontrado.
- [x] Criar `@Preview` para telas e componentes visuais representativos, com
  estados de fonte grande, tela compacta, mesa cheia, vazio e erro. Adicionado
  em `MatchScreen.kt`: mesa vazia, mesa cheia (2 tamanhos), erro, retrato
  compacto, fonte grande (`fontScale=2.0`), alem de previews isolados de
  `MeldArea` sozinha (cheia com canastra, vazia em caixa pequena).
- [ ] Validar previews e capturas em larguras/fontes variadas antes da entrega.
  Os previews existem agora; falta a revisao visual manual no Android Studio.

## 4. Animacao, som e identidade visual

- [x] Refinar distribuicao inicial, compra, descarte, descida de jogo, encaixe,
  morto, troca de turno, fim de rodada e vitoria.
- [x] Manter duracoes curtas e consistentes, sem atrasar a jogada ou deslocar a UI.
  8 pontos de brilho/pulso que tinham ciclos diferentes (900/1100/1200ms:
  monte, lixo, vira, canastra, dialogos de inspecao, aviso de morto) agora
  usam a mesma duracao via `MenuMotion.pulse()`/`quick()`/`standard()`.
  Tambem: transicao de cor na troca de turno na barra superior, e a animacao
  de distribuicao inicial com o delay externo/interno sincronizados.
- [x] Usar brilho, sombra e destaque somente para indicar estado ou acao valida.
  Confirmado ao unificar os pontos de pulso -- todos ja eram estado-dependentes,
  nenhum decorativo gratuito.
- [x] Respeitar reducao de movimento e aparelhos mais lentos (item que estava
  faltando explicitamente no texto original desta secao, mas e parte do
  mesmo trabalho): `rememberReducedMotionEnabled()` le
  `Settings.Global.ANIMATOR_DURATION_SCALE` e congela/pula confete de
  vitoria, brilho de jogo formado, aceno da carta selecionada e a animacao
  de distribuicao quando o sistema pede menos movimento.
- [ ] Revisar sons, vibracoes, splash, cartas, mesa, avatares, icone e assets da
  Play Store como um conjunto visual unico. Nao mexido -- precisa de assets
  de audio/imagem que so o dono do projeto pode fornecer.
- [x] Respeitar estado sem som do aparelho (silencioso/vibrar) nos efeitos
  sonoros. `MatchFeedback.play()` agora le `AudioManager.ringerMode`: modo
  silencioso corta som e vibracao, modo vibrar corta so o som e mantem a
  vibracao (preferencia explicita de feedback tatil do usuario). Extraido em
  `shouldPlaySound`/`shouldVibrate` (testaveis sem depender de hardware real)
  com 3 testes cobrindo normal/vibrar/silencioso em `MatchFeedbackTest.kt`.

## 5. Criacao e entrada em salas

- [x] Mostrar regras e configuracoes antes de o convidado entrar na sala.
- [x] Transformar a criacao em fluxo curto: modo, jogadores, regras e
  confirmacao. Ja organizado em passos numerados (1-4) no `HostPanel`; auditado,
  sem gap real encontrado.
- [x] Exibir identidade visual propria para Cacheta, Buraco e Tranca. A "capa
  da sala" (`RuleSummaryText`) agora usa cor propria por jogo (Cacheta =
  dourado, Buraco = verde, Tranca = vermelho) em vez da mesma cor pros tres.
- [x] Publicar a sala para descoberta somente depois de a configuracao ser
  confirmada e persistida, tanto no Wi-Fi quanto no online. Auditado
  `publishOrStart()`: `publishedConfig` (o que efetivamente aparece como sala
  publicada) so e setado depois que `connectionStatus` confirma
  `ROOM_READY`/`CONNECTED` -- ja funcionava, sem gap real.
- [x] Mostrar progresso, sucesso, erro recuperavel e cancelamento sem duplicar
  sala. `isPublishing`/`publicationError` ja cobrem isso e erro chama
  `stopHosting()` antes de permitir nova tentativa; auditado, sem gap real.
- [x] Preservar configuracoes escolhidas durante rotacao, reconexao e retorno
  de tela. Rotacao nao e um risco de verdade (Activity travada em
  `landscape`), mas "retorno de tela" era um bug real: a navegacao troca de
  tela com um `when` manual em `MainActivity`, entao sair do lobby e voltar
  recriava o composable do zero e um "Voltar" sem querer jogava fora toda
  regra escolhida. Trocado `remember` por `rememberSaveable` em toda a
  selecao de regras do `LobbyScreen`.

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
- [x] Mover baralho, mao do host e transicoes completas para autoridade server-side antes do competitivo.
  Distribuicao inicial (embaralhar, mao de cada assento, vira, mortos, lixo de
  abertura) e server-side desde as migrations `0020`-`0023`. Migration `0025`
  (fase 3a) fechou o proximo pedaco: compra do monte principal durante a
  rodada, reciclagem do lixo (Cacheta) e morto virando novo monte
  (Buraco/Tranca), decididos pela RPC `online_draw_deck_card`. `start_online_round`
  tambem parou de devolver o baralho inteiro pro host na distribuicao -- sem
  isso a RPC de compra seria simbolica, ja que o host teria conhecimento
  total do monte desde o inicio da rodada (gap real encontrado so ao revisar
  o design). Migration `0026` (fase 3b) fechou o ultimo gap conhecido: o
  pedido explicito de time pegar o morto inteiro como mao
  (`REQ_PICK_MORTO`/`SERVE_MORTO`) agora e decidido pela RPC
  `online_take_morto`, reaproveitando a mesma tabela `private.match_deck_state`
  da 0025 -- os dois mecanismos disputam a mesma coluna `mortos`, travada com
  "for update" contra qualquer corrida entre eles. Dois bugs reais de ordem
  entre a RPC e o trigger existente (`0016`) encontrados testando local antes
  de aplicar em producao (a RPC atualizava mao/`picked_morto` antes de
  publicar o evento, e o proprio trigger, que checa os mesmos campos pra
  recusar pedido duplicado, via o valor ja novo e recusava a primeira
  tentativa). Detalhe completo em `online-roadmap.md`.
  Falta so homologar em aparelho de verdade -- a autoridade do servidor em si
  esta completa pra toda a rodada (distribuicao, compra, morto).
- [x] Manter cartas privadas fora do estado publico e apagar payload privado apos
  o resultado confirmado. Migration `0011_private_event_redaction.sql`: apos
  `complete_match`, eventos privados preservam so tipo/messageId/marca de
  redacao. Smoke test remoto ja confirmou o marcador antes do resultado e a
  remocao depois dele (checkbox estava desatualizado, o trabalho ja existia).
- [x] Tratar falhas esperadas com resultados explicitos; nao usar `try/catch`
  generico para esconder erro de regra ou aceitar jogada duvidosa. Auditado
  `OnlineNetworkRepository.kt` e `data/online/*`: toda falha (rede, rejeicao
  do servidor, payload invalido) vira "nao entregue"/`ConnectionStatus.ERROR`,
  nunca sucesso silencioso. A regra em si e validada nas RPCs (`0014`-`0019`).
  Melhoria antes marcada como "possivel, nao bloqueante" -- feita: `publish()`
  e `publishConfirmed()` capturavam qualquer excecao no mesmo catch generico,
  entao uma jogada recusada pela validacao estrutural do servidor (RPC/trigger,
  ex.: `CARD_NOT_IN_HAND`) virava `ConnectionStatus.ERROR` identico a uma
  queda de rede de verdade -- isso ficou mais visivel depois do item abaixo
  (antes o erro era so ignorado; agora abria o dialogo de reconexao pra uma
  jogada simplesmente invalida). `publishConfirmed()` tambem tentava de novo
  ate `CONFIRMED_SEND_ATTEMPTS` vezes uma jogada que ia falhar identico todas
  as vezes. `SupabaseOnlineRoomDataSource` agora traduz
  `PostgrestRestException` com codigo `P0001` (sempre um `raise exception`
  nosso) pra um tipo proprio (`OnlineRuleRejectedException`, sem depender do
  supabase-kt em `OnlineNetworkRepository`); um canal novo (`actionRejections`)
  leva isso ate o `MatchViewModel` sem tocar em `connectionStatus`, e o retry
  para na primeira tentativa quando a causa e regra, nao rede.
- [x] Corrigir gap real encontrado nesta auditoria: `ConnectionStatus.ERROR`
  (falha de registro NSD/socket no Wi-Fi local, falha de sessao/heartbeat/
  publicacao no online) nao tinha nenhum tratamento em `MatchScreen.kt` --
  a mesa congelava em silencio, sem dialogo, mensagem ou botao de saida, no
  meio de uma partida em andamento. Agora reaproveita o mesmo dialogo de
  desconexao (Wi-Fi ja tinha isso para OPPONENT_DISCONNECTED/HOST_DISCONNECTED)
  com mensagem propria ("A conexao com a sala falhou").
- [x] Adicionar telemetria de falhas sem registrar mao, token ou dado privado.
  Migration `0024_client_failure_telemetry.sql` (aplicada em producao,
  validada antes localmente contra Postgres real) cria `client_failure_events`
  + RPC `report_client_failure`, com categoria fechada (lista fixa, nao texto
  livre) pra nunca correr risco de vazar mensagem de excecao crua -- o banco
  tambem rejeita qualquer categoria fora da lista. `OnlineNetworkRepository`
  reporta em 4 pontos reais: erro de sessao (stream de eventos/presenca),
  heartbeat falhando, publicacao de evento falhando e pedido de distribuicao
  ao servidor falhando. Testado com dublê de teste confirmando a categoria
  certa em cada caso.

## 7. Conta Google e perfil

- [x] Usar sessao anonima do Supabase durante o Beta.
- [x] Configurar Google como provedor no Supabase Auth e vincular a identidade
  anonima existente para preservar perfil, XP e ranking. Provedor Google
  habilitado no Supabase Auth (OAuth Client Web no Google Cloud Console) e
  "manual linking" habilitado. `GoogleAccountLinker` usa Credential Manager
  nativo (sem navegador) pra pegar o ID token e chama
  `auth.linkIdentityWithIdToken()`, que atualiza a MESMA identidade anonima
  em vez de criar usuario novo -- API confirmada lendo o sources jar do
  supabase-kt 3.7.0 direto. Botao "Vincular conta Google" na
  `OnlineProfileScreen`. Falta confirmacao de teste real em aparelho fisico.
- [ ] Testar cancelamento e troca de conta em aparelho fisico. Como o fluxo
  escolhido foi nativo (Credential Manager), nao existe callback/deep link
  pra configurar -- esse item do enunciado original nao se aplica. O que
  falta e so a confirmacao manual: cancelar o seletor de conta (deve ficar
  em silencio, sem mensagem de erro) e vincular com uma conta diferente da
  que ja esta logada no Google no aparelho.
- [x] Firebase Auth nao e obrigatorio: o Supabase Auth faz OAuth Google direto.
  Confirmado -- implementado 100% via supabase-kt + Credential Manager, sem
  nenhuma dependencia do Firebase.

## 8. Qualidade e publicacao

- [x] Rotacionar a secret key exposta durante a configuracao inicial.
- [x] Exigir testes unitarios para regras, bot, codecs, sala, ranking e ViewModels.
  Cobertura confirmada: `GameRulesEngineTest`, `BotDecisionEngineTest`,
  `OnlineEventCodecTest`, `OnlineRoomCodeTest`/`OnlineRoomConfigJsonTest`,
  `OnlineRankingTest`/`OnlineRankingScreenLogicTest`,
  `MatchViewModelStartGameTest`/`MatchScreenLogicTest`, entre outras.
- [x] Adicionar testes instrumentados para navegacao, fonte grande e partida basica.
  Navegacao ja tinha `LoginFlowNavigationTest`/`MainMenuNavigationTest`.
  Adicionado `BotMatchNavigationTest` (menu -> lobby -> partida contra a
  maquina carrega a mesa -> sair -> volta ao menu, unico modo que nao
  depende de rede/Supabase) e `LargeFontScaleNavigationTest` (nova
  `FontScaleRule` muda `font_scale` do sistema pra 2.0x via shell antes da
  Activity abrir e devolve ao normal depois, mesmo se o teste falhar).
  Achado e corrigido no processo: rodar as 4 classes de teste juntas
  expunha uma flakiness real de ordem (`LoginFlowNavigationTest` falhava
  so quando rodava depois de outra classe, mas passava sozinho). Causa
  raiz: `FakeAuthRepository` e um singleton de processo, e classes de
  teste instrumentado compartilham o mesmo processo do app -- seu
  `loadSavedProfile()` so definia `currentPlayer` quando havia perfil
  salvo, nunca zerava quando nao havia, entao um perfil deixado por uma
  classe anterior sobrevivia mesmo depois do `SharedPreferences` ser
  limpo pela proxima. Corrigido para sempre refletir o estado persistido
  (inofensivo num app real, que so chama `init()` uma vez por processo).
  As 4 classes confirmadas passando juntas em aparelho fisico e emulador.
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
