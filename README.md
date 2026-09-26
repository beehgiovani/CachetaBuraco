# Carteado BR

Aplicativo Android de jogos de cartas em Kotlin e Jetpack Compose. O projeto reúne Cacheta, Buraco e Tranca em um mesmo motor de regras e reutiliza um contrato de mesa entre partidas contra a máquina, salas na rede local e o modo online.

> **Estado atual:** MVP Android funcional. Os modos local e contra a máquina estão implementados; as salas Wi-Fi estão disponíveis; o modo online continua beta. Publicação em loja e homologação ampla em aparelhos reais ainda são pendências.

## Escopo

- partidas de Cacheta, Buraco e Tranca;
- jogo local e adversário controlado por regras próprias;
- descoberta de salas por NSD e transporte TCP na mesma rede;
- salas online, presença, eventos, perfis e ranking com Supabase;
- proteção do estado privado das mãos e validações server-side no modo online;
- login anônimo com opção de vincular uma conta Google;
- preparação de assets, consentimento e espaços de anúncio para uma futura distribuição.

O repositório demonstra engenharia de regras, estado reativo e comunicação multiplayer. Ele não comprova, sozinho, estabilidade de rede em todos os aparelhos nem prontidão para publicação nas lojas.

## Situação por frente

| Frente | Situação verificável no código |
| --- | --- |
| Partida local | Implementada |
| Partida contra a máquina | Implementada e coberta por testes unitários |
| Salas Wi-Fi | Implementadas com NSD e sockets TCP |
| Modo online | Beta, com Supabase Auth, PostgREST e Realtime |
| Testes em dois aparelhos físicos | Ainda incompletos para todas as modalidades |
| Publicação em loja | Não concluída |

## Arquitetura

```text
app/src/main/java/com/brunogiovani/cachetaburaco/
├── domain/
│   ├── models/          cartas, partidas, ranking e campeonatos
│   ├── repositories/    contratos de transporte e serviços online
│   └── usecases/        motor de regras e decisões do bot
├── data/
│   ├── network/         implementações local, Wi-Fi e online
│   └── online/          salas, identidade, perfis, ranking e Realtime
└── presentation/        login, lobby, mesa, chat, ranking e ViewModels

supabase/
├── migrations/          evolução versionada do backend online
└── tests/               smokes SQL das regras críticas
```

O `GameRulesEngine` concentra as regras das modalidades. `LocalNetworkRepository` define o contrato usado pelas implementações de bot, Wi-Fi e online, reduzindo diferenças entre a interface e o transporte.

## Stack

- Kotlin, Jetpack Compose e Material 3;
- Coroutines, Flow e Kotlin Serialization;
- Ktor/OkHttp, NSD e sockets TCP;
- Supabase Auth, PostgREST, Realtime e Storage;
- Credential Manager para vinculação de conta Google;
- Google Mobile Ads e User Messaging Platform;
- JUnit e testes instrumentados Android.

## Requisitos

- Android Studio compatível com Android Gradle Plugin 9.3;
- JDK 21;
- Android SDK 37 e Build Tools 36.1;
- emulador ou dispositivo Android 8.0 (API 26) ou superior.

## Configuração segura

A URL e a *publishable key* do Supabase presentes no cliente são identificadores públicos de aplicativo. Elas não substituem segurança no backend: as tabelas e RPCs precisam continuar protegidas por autenticação, RLS e validação de identidade.

Nunca coloque no aplicativo ou no Git:

- `SUPABASE_SECRET_KEY`, senha do Postgres ou connection string;
- client secret do OAuth;
- senhas de assinatura, keystores ou credenciais de CI;
- dados reais de jogadores usados em diagnóstico.

Para assinar uma release local, crie `keystore.properties` fora do versionamento. Sem esse arquivo, o build de release permanece sem assinatura e os builds de desenvolvimento continuam disponíveis. O modelo de configuração do backend fica em [`supabase/.env.example`](supabase/.env.example).

## Build e testes

No Windows:

```powershell
.\gradlew.bat :app:compileDebugKotlin --warning-mode all --console=plain
.\gradlew.bat :app:testDebugUnitTest --warning-mode all --console=plain
.\gradlew.bat :app:lintDebug --warning-mode all --console=plain
.\gradlew.bat :app:assembleDebug --warning-mode all --console=plain
```

Em Linux ou macOS, substitua `.\gradlew.bat` por `./gradlew`.

Os testes instrumentados exigem um emulador ou aparelho conectado:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

As migrações e os smokes SQL exigem um ambiente Supabase próprio. Antes de qualquer operação remota, confira o projeto selecionado e siga [`supabase/README.md`](supabase/README.md). Não aplique migrações em um projeto que não tenha sido explicitamente conferido.

## Limites conhecidos

- O modo online ainda é beta; reconexão, latência e partidas completas precisam de uma matriz maior de testes reais.
- Testes automatizados reduzem regressões, mas não provam equilíbrio das regras ou compatibilidade com todos os fabricantes.
- Assets de loja, política de privacidade, consentimento de anúncios e ficha pública precisam de revisão antes de um lançamento.
- Recursos competitivos dependem das regras e migrações do backend estarem alinhadas à versão do app.

## Roadmap e documentação

- [Status e próximos passos](STATUS_E_PROXIMOS_PASSOS.md)
- [Roadmap do produto](docs/product-roadmap.md)
- [Roadmap e decisões do modo online](docs/online-roadmap.md)
- [Termos de uso](docs/termos-de-uso.md)

O roadmap separa o que já possui evidência técnica do que ainda depende de teste manual, infraestrutura ou decisão de produto.
