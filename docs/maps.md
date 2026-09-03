# Mapas do Bike GPS

## Arquitetura

O Android usa o Mapbox Maps SDK 11.29.1 com o artefato NDK 27, compatível com páginas de memória de 16 KB. O `MapView` recebe o contexto visual da Activity e limita o teto a 60 FPS. O resultado efetivo depende da GPU, da temperatura do aparelho, da complexidade das camadas e da rede; 60 FPS é a meta configurada, não uma garantia para todo dispositivo.

Os estilos alternáveis são:

- Claro: `mapbox://styles/mapbox/light-v11`;
- Escuro: `mapbox://styles/mapbox/dark-v11`;
- Satélite híbrido: `mapbox://styles/mapbox/satellite-streets-v12`.

Esses estilos usam a cartografia Mapbox Streets, cuja base combina OpenStreetMap e outras fontes. A camada viária inclui ruas, calçadas, ciclovias, caminhos, trilhas e dados de superfície quando disponíveis no OSM; parques e POIs também provêm das camadas de uso do solo e pontos de interesse. O aplicativo não baixa nem replica diretamente o banco planetário do OSM.

As atualizações cartográficas são servidas pelo Mapbox conforme o provedor processa sua réplica do OSM e outras fontes. O SDK respeita expiração e revalidação dos recursos de rede. Portanto, novas edições do OSM aparecem depois de incorporadas pelo pipeline do Mapbox; o aplicativo não pode prometer uma latência exata.

## Token Mapbox

Crie um token público para Android no painel da sua conta Mapbox. Não coloque um token secreto `sk.` no aplicativo nem no repositório. Para desenvolvimento local, prefira `~/.gradle/gradle.properties`, fora do projeto:

```properties
MAPBOX_ACCESS_TOKEN=<TOKEN_PUBLICO_PK>
```

Também é possível iniciar o APK sem token e tocar na área do mapa para salvar um token público somente naquele aparelho. Essa alternativa existe para evitar crash de inicialização e facilitar diagnóstico; uma distribuição pronta deve injetar o token no build.

No GitHub, abra **Settings → Secrets and variables → Actions → Secrets**, crie `MAPBOX_ACCESS_TOKEN` e execute novamente o workflow **Android APK**. Apesar de o token `pk.` ser público por definição, mantê-lo na configuração do build evita registrar a credencial no código-fonte. Restrinja o token aos escopos e aplicativos necessários no painel Mapbox.

## Cache local

O SDK armazena automaticamente no cache em disco os tiles, estilos, fontes, sprites e demais recursos visualizados. Reabrir uma região já visitada reduz requisições e acelera a primeira pintura. Esse é um cache com descarte LRU e não equivale a uma região offline permanente. Downloads offline explícitos devem ter uma interface de consentimento, progresso, limite de armazenamento e atualização antes de serem habilitados em produção.

## POIs e clustering

O controlador cria uma fonte GeoJSON com clustering no próprio motor. Os clusters são recalculados conforme zoom e pan, evitando uma View Android para cada marcador. Os POIs locais de demonstração podem ser substituídos por dados vindos do backend sem alterar as camadas.

## Heatmap MVT

A camada de calor é opcional e aceita uma fonte Mapbox Vector Tile com pontos amostrados das atividades. Configure no build:

```properties
BIKEGPS_ACTIVITY_TILES_URL=https://tiles.seudominio.example/activities/{z}/{x}/{y}.mvt
BIKEGPS_ACTIVITY_TILES_LAYER=activity
```

Também é aceito um tileset `mapbox://conta.tileset`. O source-layer padrão é `activity`. A URL HTTPS não deve carregar segredo permanente em query string; use um endpoint público apropriado, um tileset Mapbox autorizado pelo token público ou uma estratégia de credencial curta no backend. O cliente evita consultar uma atualização do mesmo tile em intervalos menores que 15 minutos; expiração e disponibilidade de conteúdo novo continuam sendo responsabilidade do servidor. O gradiente vai de azul-claro a laranja e vermelho.

O servidor MVT não faz parte do APK: ele precisa gerar tiles protobuf com geometria `Point` no source-layer configurado. Dados privados do Strava não devem ser publicados em heatmap sem consentimento e anonimização ou agregação adequados.
