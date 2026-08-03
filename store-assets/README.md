# Assets da Play Store

Assets gerados para a primeira rodada de testes práticos e preparação da listagem.

Arquivos principais já cobertos:

- `icon-512.png`: PNG 512 x 512 com alpha, usado como ícone da Play Store.
- `feature-graphic-1024x500.png`: PNG 1024 x 500 sem alpha, usado como gráfico de destaque.
- `screenshots/*.png`: quatro imagens 1920 x 1080 em paisagem para a listagem, incluindo mesa, vitória e ranking.

Geradores:

- `../tools/generate_assets.py`: regenera assets do app, ícones, gráfico de destaque e imagens de divulgação.
- `../tools/generate_store_assets.py`: gerador antigo, mantido apenas como referência.

Texto sugerido para a listagem:

- App name: `Cacheta & Buraco`
- Short description: `Jogue Cacheta, Buraco e Tranca em partidas de cartas locais.`
- Full description draft:

```text
Cacheta & Buraco é um jogo de cartas em desenvolvimento para partidas locais em modo paisagem.

Crie uma sala na rede local, escolha entre Cacheta, Buraco ou Tranca, defina partidas para 2 ou 4 jogadores e teste a experiência de mesa com compra, descarte e jogos baixados.

Esta primeira versão prioriza testes práticos da interface, fluxo de sala, ranking local, animação de vitória e base de regras.
```

Antes de publicar em produção:

- Trocar capturas simuladas por screenshots de um aparelho ou emulador real.
- Configurar Play App Signing e uma chave privada de upload antes do bundle final.
- Preencher classificação indicativa, segurança de dados, público-alvo, política de privacidade se for exigida, e trilhas de teste no Play Console.
