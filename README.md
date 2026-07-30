# CachetaBuraco

Aplicativo Android em Kotlin/Jetpack Compose para jogar Cacheta, Buraco e Tranca.

## Status

Projeto em fase de testes praticos, com modo local, modo contra a maquina e base preparada para evoluir para online.

## Modos

- Cacheta: regras por sala, curinga pela vira e quantidade configuravel de cartas.
- Buraco: 11 cartas, mortos, canastra e pontuacao.
- Tranca: 11 cartas, mortos, 3 vermelho, 3 preto no lixo e regras proprias do modo.
- Contra a maquina: IA local usando o mesmo fluxo de mensagens da partida.
- Rede local: salas na mesma rede Wi-Fi.

## Estrutura

- `domain/models`: modelos puros do jogo.
- `domain/usecases/GameRulesEngine.kt`: regras de Cacheta, Buraco e Tranca.
- `domain/repositories/LocalNetworkRepository.kt`: contrato unico de comunicacao da mesa.
- `data/network`: implementacoes de transporte, hoje Wi-Fi local e maquina.
- `presentation/match`: tela e ViewModel da partida.
- `presentation/lobby`: criacao/entrada em salas e configuracao das regras.
- `store-assets`: assets prontos para Play Store.

## Online depois

A base foi preparada para adicionar online sem duplicar regra. O caminho esperado e criar uma implementacao `OnlineNetworkRepository` seguindo o contrato atual de `LocalNetworkRepository`, mantendo `MatchViewModel`, `MatchScreen`, `MatchConfig` e `GameRulesEngine`.

## Build e testes

```powershell
.\gradlew.bat :app:compileDebugKotlin --warning-mode all --console=plain
.\gradlew.bat :app:testDebugUnitTest --warning-mode all --console=plain
.\gradlew.bat :app:assembleDebug --warning-mode all --console=plain
```

## Git

O repositorio deve versionar codigo, testes, recursos Android e assets finais da loja. Planejamentos locais, scripts temporarios, relatorios, builds, arquivos compactados e configuracoes da maquina ficam ignorados pelo `.gitignore`.
