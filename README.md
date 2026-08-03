# CachetaBuraco

Aplicativo Android em Kotlin/Jetpack Compose para jogar Cacheta, Buraco e Tranca.

## Status

Projeto em fase de testes práticos, com modo local, modo contra a máquina e base preparada para evoluir para online.

## Modos

- Cacheta: regras por sala, curinga pela vira e quantidade configurável de cartas.
- Buraco: 11 cartas, mortos, canastra e pontuação.
- Tranca: 11 cartas, mortos, 3 vermelho, 3 preto no lixo e regras próprias do modo.
- Contra a máquina: adversário local usando o mesmo fluxo de mensagens da partida.
- Rede local: salas na mesma rede Wi-Fi.

## Estrutura

- `domain/models`: modelos puros do jogo.
- `domain/usecases/GameRulesEngine.kt`: regras de Cacheta, Buraco e Tranca.
- `domain/repositories/LocalNetworkRepository.kt`: contrato único de comunicação da mesa.
- `data/network`: implementações de transporte, hoje Wi-Fi local e máquina.
- `presentation/match`: tela e ViewModel da partida.
- `presentation/lobby`: criação/entrada em salas e configuração das regras.
- `store-assets`: assets prontos para Play Store.

## Online depois

A base foi preparada para adicionar online sem duplicar regra. O caminho esperado é criar uma implementação `OnlineNetworkRepository` seguindo o contrato atual de `LocalNetworkRepository`, mantendo `MatchViewModel`, `MatchScreen`, `MatchConfig` e `GameRulesEngine`.

## Build e testes

Configuração atual do Android:

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
