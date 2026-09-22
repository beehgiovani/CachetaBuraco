# Status e próximos passos — Carteado BR / Cacheta-Buraco

> Auditoria de 22/09/2026. Esta é uma fotografia baseada em arquivos, Git, artefatos e endpoints observáveis. Nenhum build completo foi executado nesta classificação.

## Classificação

- **Estado:** MVP concretizado, com Android e web; online ainda beta
- **Confiança:** alta
- **Natureza:** jogo de cartas multiplataforma

## Evidências observadas

- O Android possui repositório limpo, APK e AAB de release e assets de loja.
- O web possui repositório limpo e `https://carteadobr.web.app` respondeu HTTP 200.
- O README Android classifica explicitamente o modo online como beta.

## Diagnóstico franco

É um dos projetos mais concretos. O risco está menos em construir telas e mais em validar regras, sincronização e operação real das partidas.

## Upgrades previstos

### P0 — preservar e tornar retomável

- Criar um documento comum na raiz ligando Android, web, backend/Supabase e regras do jogo.
- Executar uma matriz manual para Cacheta, Buraco e Tranca: local, bot e online.
- Revisar RLS, migrations e segredos antes de qualquer ampliação pública.

### P1 — estabilizar

- Cobrir invariantes de compra, descarte, morto, batida, reconexão e abandono.
- Unificar protocolo e versionamento das partidas entre Android e web.
- Registrar crashes e erros sem coletar dados excessivos.

### P2 — evoluir

- Preparar política de privacidade, ficha da loja e trilha de lançamento controlado.
- Só depois adicionar ranking, campeonato e monetização.

## Critério para considerar retomado

O projeto será considerado retomado quando as regras críticas passarem em testes e partidas reais entre Android e web, com versão publicada e observabilidade mínima.

## Prompt de retomada para o Codex

> Retome o projeto **Carteado BR / Cacheta-Buraco** nesta pasta. Leia este arquivo e o README, inspecione o Git e preserve todo trabalho local. Comece somente pelo P0, valide com evidências e não implemente P1/P2 antes de apresentar o diagnóstico atualizado.

