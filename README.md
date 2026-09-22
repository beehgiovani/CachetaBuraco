# Carteado BR

Aplicativo Android em Kotlin e Jetpack Compose para partidas de Cacheta, Buraco e Tranca.

## Estado do projeto

- Partida local e contra a máquina: implementadas.
- Salas na rede Wi-Fi: implementadas com descoberta NSD e transporte TCP.
- Modo online: beta, com Supabase Auth/PostgREST/Realtime.
- Homologação ampla com múltiplos aparelhos e publicação em loja: pendentes.

## O que o projeto demonstra

- Motor de regras separado da interface.
- Um contrato de comunicação reutilizado pelo bot, Wi-Fi e online.
- Separação entre estado público da mesa e cartas privadas.
- Host autoritativo, eventos idempotentes e proteção contra eventos de rodadas antigas.
- Testes unitários, testes instrumentados e preparação de assets de loja.

## Stack confirmada

- Kotlin, Jetpack Compose e Material 3.
- Coroutines, Flow e Kotlin Serialization.
- Supabase Auth, PostgREST, Realtime e Storage.
- Ktor/OkHttp, NSD e sockets TCP.
- JUnit e testes de interface Android.

## Estrutura

- `domain/models`: modelos do jogo.
- `domain/usecases/GameRulesEngine.kt`: regras das modalidades.
- `domain/repositories/LocalNetworkRepository.kt`: contrato de transporte.
- `data/network` e `data/online`: implementações local, Wi-Fi e online.
- `presentation`: lobby, partida e ViewModels.
- `supabase`: migrações do modo online.

## Build e testes

```powershell
.\gradlew.bat :app:compileDebugKotlin --warning-mode all --console=plain
.\gradlew.bat :app:testDebugUnitTest --warning-mode all --console=plain
.\gradlew.bat :app:assembleDebug --warning-mode all --console=plain
```

## Limites

O modo online continua identificado como beta. Testes automatizados reduzem regressões, mas não provam estabilidade de rede, reconexão e experiência de jogo em todos os aparelhos.
