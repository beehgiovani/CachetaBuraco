# Play Store assets

Generated assets for the first practical test/listing pass.

Required preview assets currently covered:

- `icon-512.png`: 512 x 512 PNG with alpha, for the Play Store app icon.
- `feature-graphic-1024x500.png`: 1024 x 500 PNG without alpha, for the Play Store feature graphic.
- `screenshots/*.png`: four 1920 x 1080 landscape PNG screenshots/mock captures for the game listing, including victory/ranking.

Asset generator:

- `../tools/generate_assets.py`: regenerates runtime app assets, launcher icons, Play Store icon, feature graphic, and screenshot mock captures from deterministic local drawing code.
- `../tools/generate_store_assets.py`: legacy store-only generator kept for reference.

Suggested listing copy:

- App name: `Cacheta & Buraco`
- Short description: `Jogue Cacheta, Buraco e Tranca em partidas de cartas locais.`
- Full description draft:

```text
Cacheta & Buraco e um jogo de cartas em desenvolvimento para partidas locais em modo paisagem.

Crie uma sala na rede local, escolha entre Cacheta, Buraco ou Tranca, defina partidas para 2 ou 4 jogadores e teste a experiencia de mesa com compra, descarte e jogos baixados.

Esta primeira versao prioriza testes praticos da interface, fluxo de sala, ranking local, animacao de vitoria e base de regras.
```

Before production release:

- Replace generated screenshot/mock captures with screenshots captured from a real device or emulator build.
- Configure Play App Signing and a private upload keystore before generating the final release bundle.
- Complete content rating, data safety, target audience, privacy policy if required, and closed/internal testing tracks in Play Console.
