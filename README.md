# Carteado BR

Aplicativo Android em Kotlin/Jetpack Compose para jogar Cacheta, Buraco e Tranca.

## Status

Projeto em fase de testes praticos, com modo local, modo contra a maquina e modo online Beta conectado ao Supabase.

## Modos

- Cacheta: 9 cartas, regras por sala e curinga definido pela vira.
- Buraco: 11 cartas, mortos, canastra e pontuacao.
- Tranca: 11 cartas, mortos, 3 vermelho, 3 preto no lixo e regras proprias do modo.
- Contra a maquina: adversario local usando o mesmo fluxo de mensagens da partida.
- Rede local: salas na mesma rede Wi-Fi.
- Online Beta: salas por codigo, presenca, eventos em tempo real, reconexao e ranking global.

## Estrutura

- `domain/models`: modelos puros do jogo.
- `domain/usecases/GameRulesEngine.kt`: regras de Cacheta, Buraco e Tranca.
- `domain/repositories/LocalNetworkRepository.kt`: contrato unico de comunicacao da mesa.
- `data/network`: implementacoes de transporte para Wi-Fi local, maquina e online.
- `data/online`: autenticacao anonima e acesso seguro ao Supabase.
- `presentation/match`: tela e ViewModel da partida.
- `presentation/lobby`: criacao/entrada em salas e configuracao das regras.
- `store-assets`: assets prontos para Play Store.
- `docs`: roadmap geral do produto, online, monetizacao e proximos upgrades.
- `supabase`: migrations versionadas da base online.

## Online Beta

A implementacao `OnlineNetworkRepository` usa o mesmo contrato de `LocalNetworkRepository`, mantendo `MatchViewModel`, `MatchScreen`, `MatchConfig` e `GameRulesEngine` compartilhados entre os transportes. A identidade autenticada e usada na partida online; assentos, presenca, entrega idempotente, isolamento de eventos privados e encerramento de sessao possuem cobertura automatizada. O resultado final e enviado pelo host com chave idempotente e validado contra o evento persistido antes de atualizar as estatisticas.

O teste pratico com dois aparelhos ainda faz parte da homologacao. As migracoes `0001` a `0017` estao aplicadas no projeto remoto e o schema passa pelo lint da CLI. A RPC de eventos rejeita tipos desconhecidos, separa mensagens do host e dos clientes e valida identidade, turno, rodada, formato dos jogos e posse das cartas dos assentos clientes. Eventos atrasados de uma rodada anterior nao podem alterar a rodada atual. A secret key usada na configuracao inicial ja foi rotacionada; o Android contem somente URL publica e publishable key.

## Build e testes

Configuracao atual do Android:

- `compileSdk`: 37
- `targetSdk`: 37
- `minSdk`: 26
- Java/Kotlin JVM target: 21
- Android Gradle Plugin: 9.2.1
- Kotlin Compose plugin: 2.4.10
- Gradle Wrapper: 9.6.1

```powershell
.\gradlew.bat :app:compileDebugKotlin --warning-mode all --console=plain
.\gradlew.bat :app:testDebugUnitTest --warning-mode all --console=plain
.\gradlew.bat :app:assembleDebug --warning-mode all --console=plain
```
